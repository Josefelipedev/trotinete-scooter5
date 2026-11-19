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
 *  - the link itself: a new watcher is a new connection, so a scooter found locked by the app on it
 *    was left behind on an earlier one, and the signal climbing from where it reappeared is the walk back;
 *  - whether the phone is unlocked: in your hand, with no steps, you are standing there using it;
 *    and for the strict unlock mode.
 *
 * The "away" level starts from the hand-tuned one and can only be lowered (lock farther away),
 * never raised, by what the app learns:
 *  - while riding, the phone is by definition next to the scooter, so the median signal seen then
 *    is this phone's "on the scooter" level for this pocket; a weak one (phone in a bag) lowers the
 *    level to [BASELINE_LOCK_OFFSET_DB] below it;
 *  - a lock the owner undoes by hand straight away, without having moved off, was made with them
 *    beside the scooter; the level drops to [BESIDE_MARGIN_DB] below the signal it was made at.
 * Raising it from a strong riding level is what used to lock the scooter with its owner next to it:
 * on the scooter the phone reads about -56 dBm, standing beside it easily -70 to -80.
 *
 * Safety is not decided here: the service re-checks on the scooter that it is stopped before any
 * lock order goes out. This class only says "the owner seems to have left".
 */
class ProximityWatcher(private val settings: () -> Settings) {

    data class Settings(
        val autoLock: Boolean,
        val autoUnlock: Boolean,
        /** Hand-tuned "away" level; a ceiling the learnt adjustments can only go below. */
        val manualThresholdDbm: Int,
        /** Let the app lower the level from what it learns (riding level, locks undone by hand). */
        val adaptiveThreshold: Boolean,
        /** Median signal next to the scooter learnt on previous rides, null if none yet. */
        val learnedBaselineDbm: Int?,
        /** Weakest signal the owner proved to be beside the scooter at, by undoing a lock; null if never. */
        val besideFloorDbm: Int?,
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
        /**
         * The owner unlocked by hand a lock this watcher had just made, and the signal never got
         * weaker in between: they were beside the scooter at [rssiAtLock]. [besideFloorDbm] is the
         * new weakest "beside it" level, for the service to keep.
         */
        data class LockUndone(val rssiAtLock: Int, val besideFloorDbm: Int) : Decision
    }

    companion object {
        const val UNLOCK_MARGIN_DB = 4
        /** Beside a parked scooter the phone easily reads 20 dB less than on it: body in the way, other side, other height. */
        const val BASELINE_LOCK_OFFSET_DB = 20
        const val BASELINE_UNLOCK_OFFSET_DB = 4
        const val SUDDEN_DROP_DB = 12
        /** Readings failing mean the link is dying only when the signal was already this close to the lock level. */
        const val FAILING_MARGIN_DB = 8
        const val STEPS_TO_LOCK = 10
        /** Phone in hand with at most this many recent steps: the owner is standing there using it. */
        const val STILL_MAX_STEPS = 3
        const val STEPS_TO_UNLOCK = 4
        const val RISE_TO_UNLOCK_DB = 6
        /** Signal this far above the unlock level means "standing beside it", enough even without having gone far. */
        const val VERY_NEAR_DB = 6
        const val STRONG_FOR_MS = 2_000L
        const val GRACE_AFTER_UNLOCK_MS = 20_000L
        /** Unlocking by hand means the owner is right there: no auto-lock for this long. */
        const val GRACE_AFTER_HAND_UNLOCK_MS = 60_000L
        const val LOCK_RETRY_MS = 4_000L
        const val TREND_WINDOW_MS = 6_000L
        const val STEP_WINDOW_MS = 20_000L
        /** How long after the link comes up its weakest reading still counts as "where you came from". */
        const val ARRIVAL_WINDOW_MS = 120_000L
        /** A lock undone by hand within this time, with the signal never weaker by more than [UNDO_TOLERANCE_DB], was a mistake. */
        const val UNDO_WINDOW_MS = 180_000L
        const val UNDO_TOLERANCE_DB = 4
        /** How far below a proven "beside it" signal the lock level goes. */
        const val BESIDE_MARGIN_DB = 6
        /** Learning never pushes the level below this: lower, the link may die before the lock order goes out. */
        const val LOWEST_LEARNT_LOCK_DBM = -88
        /** An unlock we sent shows up as "unlocked" within this time; later ones are someone's hand. */
        private const val OWN_UNLOCK_ECHO_MS = 10_000L
        const val MIN_RIDE_SAMPLES = 10
        const val MAX_RIDE_SAMPLES = 40
        /** A learnt baseline outside this is nonsense (phone left at home, RSSI quirk) and is ignored. */
        val BASELINE_RANGE = -85..-50

        /** The "away" level in use: the manual one, lowered (never raised) by what was learnt when [adaptive]. */
        fun lockThreshold(manual: Int, adaptive: Boolean, baseline: Int?, besideFloor: Int?): Int {
            if (!adaptive) return manual
            val learnt = listOfNotNull(baseline?.minus(BASELINE_LOCK_OFFSET_DB), besideFloor?.minus(BESIDE_MARGIN_DB))
                .minOrNull() ?: return manual
            return minOf(manual, learnt.coerceAtLeast(LOWEST_LEARNT_LOCK_DBM))
        }
    }

    /** A lock this watcher decided, followed to tell a mistake (undone by hand right away) from a real one. */
    private class OurLock(val at: Long, val rssi: Int?) {
        var seenLocked = false
        var weakestSince = rssi ?: Int.MAX_VALUE
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
     * fresh connection, so the owner was away. Without this, a lock triggered by steps could be
     * undone two seconds later by the same steps.
     */
    private var wentAway = true
    /** Readings since the last lock; until 3, the median still holds readings from before it. */
    private var readingsSinceLock = Int.MAX_VALUE
    private var settingsLoaded = false
    /** First reading of this watcher, i.e. when this link came up, and the weakest signal seen since. */
    private var firstSampleAt: Long? = null
    private var weakestSinceStart = Int.MAX_VALUE
    /** This watcher locked the scooter itself: a lock it now sees is not one left from an earlier absence. */
    private var lockedOnThisLink = false
    private var ourLock: OurLock? = null
    private var lastLocked: Boolean? = null
    private var unlockSentAt: Long? = null
    private var floorInSettings: Int? = null

    /** Smoothed signal of the latest sample, for the screen. */
    var smoothedRssi: Int? = null
        private set

    /** Baseline learnt so far (from settings, then refined after each ride). The service persists it. */
    var learnedBaseline: Int? = null
        private set

    /** Weakest proven "beside it" signal so far (from settings, then lowered by each undone lock). */
    var besideFloor: Int? = null
        private set

    /** The lock level in use right now, for the screen. */
    var lockThresholdDbm: Int = 0
        private set

    var unlockThresholdDbm: Int = 0
        private set

    fun onSample(s: Sample): Decision {
        val cfg = settings()
        if (!settingsLoaded) {
            learnedBaseline = cfg.learnedBaselineDbm
            settingsLoaded = true
        }
        // Follow a change made outside (the owner pressing "forget"); otherwise keep what was learnt here.
        if (cfg.besideFloorDbm != floorInSettings) {
            floorInSettings = cfg.besideFloorDbm
            besideFloor = cfg.besideFloorDbm
        }
        val startedAt = firstSampleAt ?: s.now.also { firstSampleAt = it }
        learn(s)
        lockThresholdDbm = lockThreshold(cfg.manualThresholdDbm, cfg.adaptiveThreshold, learnedBaseline, besideFloor)
        val baseline = learnedBaseline?.takeIf { cfg.adaptiveThreshold }
        unlockThresholdDbm = maxOf(
            baseline?.minus(BASELINE_UNLOCK_OFFSET_DB) ?: (cfg.manualThresholdDbm + UNLOCK_MARGIN_DB),
            lockThresholdDbm + UNLOCK_MARGIN_DB,
        )
        val lockAt = lockThresholdDbm
        val undone = onLockChange(s)

        val canLock = cfg.autoLock && s.locked == false && s.stopped &&
            s.now >= noLockBefore && s.now - lastLockAttempt >= LOCK_RETRY_MS

        val r = s.rssi
        if (r == null) {
            // Readings failing with the signal already weak: the link is dying, last chance to lock.
            failures++
            if (undone != null) return undone
            if (canLock && failures >= 2 && (smoothedRssi ?: 0) < lockAt + FAILING_MARGIN_DB) return lock("readings failing", s)
            return Decision.None
        }
        failures = 0
        window.addLast(r)
        if (window.size > 5) window.removeFirst()
        // Median, not mean: one wild sample (hand over the phone, a reflection) cannot cross a threshold alone.
        val smoothed = window.takeLast(3).sorted().let { it[it.size / 2] }
        smoothedRssi = smoothed
        weakestSinceStart = minOf(weakestSinceStart, smoothed)
        trend.addLast(s.now to smoothed)
        while (trend.isNotEmpty() && s.now - trend.first().first > TREND_WINDOW_MS) trend.removeFirst()
        if (readingsSinceLock < Int.MAX_VALUE) readingsSinceLock++
        if (s.locked == true && smoothed < lockAt && readingsSinceLock >= 3) wentAway = true
        ourLock?.let { it.weakestSince = minOf(it.weakestSince, smoothed) }
        if (undone != null) return undone

        if (canLock) {
            // In your hand and not walking: you are standing there looking at it. Only steps (walking
            // off with the phone in hand) or the link dying count then; a weak or dipping signal alone does not.
            val standingWithPhone = s.phoneUnlocked && s.recentSteps <= STILL_MAX_STEPS
            // A drop confirmed by the median (two low readings in a row) and already below the lock level.
            val suddenDrop = trend.maxOf { it.second } - smoothed >= SUDDEN_DROP_DB && smoothed < lockAt
            val walkingAway = s.recentSteps >= STEPS_TO_LOCK && smoothed < lockAt
            if (suddenDrop && !standingWithPhone) return lock("sudden drop", s)
            if (walkingAway) return lock("walking away, ${s.recentSteps} steps", s)
            if (smoothed < lockAt && !standingWithPhone) {
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
        // Locked on an earlier link means the owner went out of reach; this link is new. The climb from
        // the signal the scooter reappeared with up to the unlock level is the walk back itself, even
        // when the phone slept through most of it and the 6 s trend window only caught the flat end.
        val cameBack = !lockedOnThisLink && s.now - startedAt <= ARRIVAL_WINDOW_MS &&
            smoothed - weakestSinceStart >= RISE_TO_UNLOCK_DB
        val reason = when {
            cfg.unlockRequiresPhoneUnlocked -> if (s.phoneUnlocked) "phone unlocked" else return Decision.None
            rising -> "signal rising"
            cameBack -> "came back into range, ${smoothed - weakestSinceStart} dB up"
            s.recentSteps >= STEPS_TO_UNLOCK -> "walking back, ${s.recentSteps} steps"
            else -> return Decision.None
        }
        strongSince = null
        wentAway = false
        ourLock = null
        unlockSentAt = s.now
        noLockBefore = s.now + GRACE_AFTER_UNLOCK_MS
        return Decision.Unlock(reason)
    }

    /**
     * Follows the lock state. An unlock this watcher did not order is someone's hand, and the owner
     * is right there: hold off the auto-lock for a while. If it undid a lock we had just made without
     * the signal ever getting weaker, that lock was a mistake, and its signal is a "beside it" level.
     */
    private fun onLockChange(s: Sample): Decision? {
        ourLock?.let { if (s.now - it.at > UNDO_WINDOW_MS) ourLock = null }
        ourLock?.let { if (s.locked == true && s.lockedByUs) it.seenLocked = true }
        val wasLocked = lastLocked
        if (s.locked != null) lastLocked = s.locked
        if (wasLocked != true || s.locked != false) return null
        val ours = unlockSentAt?.let { s.now - it <= OWN_UNLOCK_ECHO_MS } == true
        unlockSentAt = null
        if (ours) return null
        noLockBefore = maxOf(noLockBefore, s.now + GRACE_AFTER_HAND_UNLOCK_MS)
        val mistake = ourLock?.takeIf { it.seenLocked }
        ourLock = null
        val rssi = mistake?.rssi ?: return null
        if (mistake.weakestSince < rssi - UNDO_TOLERANCE_DB) return null // went farther first: a real lock
        val floor = minOf(besideFloor ?: rssi, rssi)
        besideFloor = floor
        return Decision.LockUndone(rssi, floor)
    }

    private fun lock(reason: String, s: Sample): Decision {
        lastLockAttempt = s.now
        weakSince = null
        wentAway = false
        readingsSinceLock = 0
        lockedOnThisLink = true
        ourLock = OurLock(s.now, smoothedRssi)
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

    /** Steps may arrive out of order (a batch the sensor held while the phone slept), so prune them all, not just the head. */
    @Synchronized
    fun countSince(now: Long, windowMs: Long): Int {
        steps.removeAll { now - it > windowMs }
        return steps.size
    }
}
