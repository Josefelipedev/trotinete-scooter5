package dev.josefelipe.trotinete.servico

/**
 * Decides when to lock the scooter because its owner walked away, and when to unlock it because
 * they came back. Pure logic with no Android in it: the service feeds it one [Sample] every ~0.8 s
 * and acts on the returned [Decision], so every rule here can be unit-tested with a scripted
 * sequence of samples.
 *
 * Evidence used:
 *  - the Bluetooth signal (RSSI), smoothed with a median of the last 3 readings;
 *  - its trend: a sudden drop when leaving, a rise when coming back;
 *  - the phone's step detector: steps while the scooter is parked mean you are walking away;
 *  - whether the phone is unlocked, for the strict unlock mode.
 *
 * The threshold can be learnt instead of hand-tuned: while riding, the phone is by definition next
 * to the scooter, so the median signal seen then is this phone's "next to it" level for this pocket.
 * "Away" is [BASELINE_LOCK_OFFSET_DB] below that and "back" [BASELINE_UNLOCK_OFFSET_DB] below.
 *
 * Safety is not decided here: the service re-checks on the scooter that it is stopped before any
 * lock order goes out. This class only says "the owner seems to have left".
 */
class ProximityWatcher(private val settings: () -> Settings) {

    data class Settings(
        val autoLock: Boolean,
        val autoUnlock: Boolean,
        /** Hand-tuned "away" level, used when [adaptiveThreshold] is off or nothing has been learnt yet. */
        val manualThresholdDbm: Int,
        val adaptiveThreshold: Boolean,
        /** Median signal next to the scooter learnt on previous rides, null if none yet. */
        val learnedBaselineDbm: Int?,
        /** Seconds of weak signal before a slow walk-away locks. */
        val weakSeconds: Int,
        /** Strict unlock: the phone must be unlocked and in use, not just nearby in a pocket. */
        val unlockRequiresPhoneUnlocked: Boolean,
    )

    data class Sample(
        val now: Long,
        /** Raw RSSI in dBm, or null when the reading failed or timed out. */
        val rssi: Int?,
        val locked: Boolean?,
        /** The current lock was made by the app (persisted flag), so it may be undone by the app. */
        val lockedByUs: Boolean,
        /** Scooter reports not moving and drawing little power. */
        val stopped: Boolean,
        /** Scooter reports moving or drawing riding power: the phone is certainly next to it. */
        val riding: Boolean,
        val charging: Boolean,
        /** Steps detected by the phone in the last [STEP_WINDOW_MS]. */
        val recentSteps: Int,
        /** Screen on and keyguard dismissed. */
        val phoneUnlocked: Boolean,
    )

    sealed interface Decision {
        data object None : Decision
        /** [quiet]: lock without telling the user (the scooter is charging, presumably at home). */
        data class Lock(val reason: String, val quiet: Boolean) : Decision
        data class Unlock(val reason: String) : Decision
    }

    companion object {
        const val UNLOCK_MARGIN_DB = 4
        const val BASELINE_LOCK_OFFSET_DB = 12
        const val BASELINE_UNLOCK_OFFSET_DB = 4
        const val SUDDEN_DROP_DB = 12
        const val STEPS_TO_LOCK = 10
        const val STEPS_TO_UNLOCK = 4
        const val RISE_TO_UNLOCK_DB = 6
        /** Signal this far above the unlock level means "standing beside it", enough even without having gone far. */
        const val VERY_NEAR_DB = 6
        const val STRONG_FOR_MS = 2_000L
        const val GRACE_AFTER_UNLOCK_MS = 20_000L
        const val LOCK_RETRY_MS = 4_000L
        const val TREND_WINDOW_MS = 6_000L
        const val STEP_WINDOW_MS = 20_000L
        const val MIN_RIDE_SAMPLES = 10
        const val MAX_RIDE_SAMPLES = 40
        /** A learnt baseline outside this is nonsense (phone left at home, RSSI quirk) and is ignored. */
        val BASELINE_RANGE = -85..-50
    }

    private val window = ArrayDeque<Int>()                 // last raw readings, up to 5
    private val trend = ArrayDeque<Pair<Long, Int>>()      // (time, smoothed) over TREND_WINDOW_MS
    private val rideSamples = ArrayDeque<Int>()
    private var wasRiding = false
    private var weakSince: Long? = null
    private var strongSince: Long? = null
    private var failures = 0
    private var lastLockAttempt = 0L
    private var noLockBefore = 0L
    /**
     * Since the last lock, has the signal actually gone below the lock level? A fresh watcher means a
     * fresh connection, so the owner was away. Without this, a lock triggered by steps at a still
     * decent signal could be undone two seconds later by the same steps.
     */
    private var wentAway = true
    private var baselineLoaded = false

    /** Smoothed signal of the latest sample, for the screen. */
    var smoothedRssi: Int? = null
        private set

    /** Baseline learnt so far (from settings, then refined after each ride). The service persists it. */
    var learnedBaseline: Int? = null
        private set

    /** The lock level in use right now (manual or learnt), for the screen. */
    var lockThresholdDbm: Int = 0
        private set

    var unlockThresholdDbm: Int = 0
        private set

    fun onSample(s: Sample): Decision {
        val cfg = settings()
        if (!baselineLoaded) {
            learnedBaseline = cfg.learnedBaselineDbm
            baselineLoaded = true
        }
        learn(s)
        val baseline = learnedBaseline?.takeIf { cfg.adaptiveThreshold }
        lockThresholdDbm = baseline?.minus(BASELINE_LOCK_OFFSET_DB) ?: cfg.manualThresholdDbm
        unlockThresholdDbm = baseline?.minus(BASELINE_UNLOCK_OFFSET_DB) ?: (cfg.manualThresholdDbm + UNLOCK_MARGIN_DB)
        val lockAt = lockThresholdDbm

        val canLock = cfg.autoLock && s.locked == false && s.stopped &&
            s.now >= noLockBefore && s.now - lastLockAttempt >= LOCK_RETRY_MS

        val r = s.rssi
        if (r == null) {
            // Readings failing with the signal already weak: the link is dying, last chance to lock.
            failures++
            if (canLock && failures >= 2 && (smoothedRssi ?: 0) < lockAt + 12) return lock("readings failing", s)
            return Decision.None
        }
        failures = 0
        window.addLast(r)
        if (window.size > 5) window.removeFirst()
        // Median, not mean: one wild sample (hand over the phone, a reflection) cannot cross a threshold alone.
        val smoothed = window.takeLast(3).sorted().let { it[it.size / 2] }
        smoothedRssi = smoothed
        trend.addLast(s.now to smoothed)
        while (trend.isNotEmpty() && s.now - trend.first().first > TREND_WINDOW_MS) trend.removeFirst()
        if (s.locked == true && smoothed < lockAt) wentAway = true

        if (canLock) {
            val suddenDrop = window.size >= 2 && window.max() - r >= SUDDEN_DROP_DB && smoothed < lockAt + 8
            val walkingAway = s.recentSteps >= STEPS_TO_LOCK && smoothed < lockAt + 6
            if (suddenDrop) return lock("sudden drop", s)
            if (walkingAway) return lock("walking away, ${s.recentSteps} steps", s)
            if (smoothed < lockAt) {
                val since = weakSince ?: s.now.also { weakSince = it }
                if (s.now - since >= cfg.weakSeconds * 1_000L) return lock("weak for ${cfg.weakSeconds} s", s)
            } else {
                weakSince = null
            }
        } else {
            weakSince = null
        }

        val mayUnlock = cfg.autoUnlock && s.locked == true && s.lockedByUs && !s.charging &&
            smoothed > unlockThresholdDbm && (wentAway || smoothed >= unlockThresholdDbm + VERY_NEAR_DB)
        if (!mayUnlock) {
            strongSince = null
            return Decision.None
        }
        val since = strongSince ?: s.now.also { strongSince = it }
        if (s.now - since < STRONG_FOR_MS) return Decision.None
        // Proof that someone actually came back, not just that the signal is good: a strong signal
        // alone would unlock a scooter parked outside a shop window while you are inside.
        val rising = smoothed - (trend.minOfOrNull { it.second } ?: smoothed) >= RISE_TO_UNLOCK_DB
        val approaching = s.recentSteps >= STEPS_TO_UNLOCK || rising
        val reason = when {
            cfg.unlockRequiresPhoneUnlocked -> if (s.phoneUnlocked) "phone unlocked" else return Decision.None
            rising -> "signal rising"
            approaching -> "walking back, ${s.recentSteps} steps"
            else -> return Decision.None
        }
        strongSince = null
        wentAway = false
        noLockBefore = s.now + GRACE_AFTER_UNLOCK_MS
        return Decision.Unlock(reason)
    }

    private fun lock(reason: String, s: Sample): Decision {
        lastLockAttempt = s.now
        weakSince = null
        wentAway = false
        return Decision.Lock(reason, quiet = s.charging)
    }

    /** While riding, every reading is a "next to the scooter" reading; at the end of the ride their median becomes the baseline. */
    private fun learn(s: Sample) {
        if (s.riding && s.rssi != null) {
            rideSamples.addLast(s.rssi)
            if (rideSamples.size > MAX_RIDE_SAMPLES) rideSamples.removeFirst()
        }
        if (wasRiding && !s.riding) {
            if (rideSamples.size >= MIN_RIDE_SAMPLES) {
                val median = rideSamples.sorted()[rideSamples.size / 2]
                // Blend with what was learnt before so one odd ride (phone in a bag) does not swing it.
                if (median in BASELINE_RANGE) learnedBaseline = learnedBaseline?.let { (it + median) / 2 } ?: median
            }
            rideSamples.clear()
        }
        wasRiding = s.riding
    }
}

/** Timestamps of recent steps from the phone's step detector; [countSince] says how many fell in a window. */
class StepWindow {
    private val steps = ArrayDeque<Long>()

    @Synchronized
    fun step(now: Long) {
        steps.addLast(now)
        if (steps.size > 500) steps.removeFirst()
    }

    @Synchronized
    fun countSince(now: Long, windowMs: Long): Int {
        while (steps.isNotEmpty() && now - steps.first() > windowMs) steps.removeFirst()
        return steps.size
    }
}
