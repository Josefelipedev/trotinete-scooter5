package dev.josefelipe.trotinete.servico

import dev.josefelipe.trotinete.servico.ProximityWatcher.Decision
import dev.josefelipe.trotinete.servico.ProximityWatcher.Sample
import dev.josefelipe.trotinete.servico.ProximityWatcher.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityWatcherTest {

    private var settings = Settings(
        autoLock = true,
        autoUnlock = true,
        manualThresholdDbm = -78,
        adaptiveThreshold = false,
        learnedBaselineDbm = null,
        besideFloorDbm = null,
        weakSeconds = 6,
        unlockRequiresPhoneUnlocked = false,
    )
    private val watcher = ProximityWatcher { settings }
    private var now = 1_000_000L

    private fun sample(
        rssi: Int?,
        locked: Boolean? = false,
        lockedByUs: Boolean = false,
        stopped: Boolean = true,
        riding: Boolean = false,
        charging: Boolean = false,
        steps: Int = 0,
        phoneUnlocked: Boolean = false,
    ): Decision {
        now += 800
        return watcher.onSample(Sample(now, rssi, locked, lockedByUs, stopped, riding, charging, steps, phoneUnlocked))
    }

    /** Feeds [n] identical samples and returns the first non-None decision, or None. */
    private fun feed(n: Int, build: () -> Decision): Decision {
        repeat(n) { build().let { if (it != Decision.None) return it } }
        return Decision.None
    }

    @Test
    fun strongSignalNextToTheScooterNeverLocks() {
        assertEquals(Decision.None, feed(30) { sample(-65) })
    }

    @Test
    fun weakSignalForTheConfiguredSecondsLocks() {
        val d = feed(5) { sample(-85) } // 4 s: not yet
        assertEquals(Decision.None, d)
        val lock = feed(5) { sample(-85) } // past 6 s
        assertTrue(lock is Decision.Lock && lock.reason.startsWith("weak"))
    }

    @Test
    fun oneWildReadingDoesNotLock() {
        feed(5) { sample(-66) }
        // a single -95 among -66 readings: the median stays at -66, nothing happens
        assertEquals(Decision.None, sample(-95))
        assertEquals(-66, watcher.smoothedRssi)
        assertEquals(Decision.None, feed(10) { sample(-66) })
    }

    @Test
    fun walkingAwayWithStepsLocksWithoutWaiting() {
        feed(5) { sample(-66) }
        // walking around beside it: steps, but the signal is still above the -78 threshold
        assertEquals(Decision.None, feed(10) { sample(-74, steps = 12) })
        // once below it (a gentle decline, not a sudden drop), the steps say "leaving": no need to wait the 6 s of weak signal
        val lock = feed(2) { sample(-80, steps = 12) }
        assertTrue(lock is Decision.Lock && "walking away" in lock.reason)
    }

    @Test
    fun stepsAloneWithStrongSignalDoNotLock() {
        feed(5) { sample(-66) }
        assertEquals(Decision.None, feed(10) { sample(-66, steps = 30) })
    }

    @Test
    fun suddenDropLocksAtOnce() {
        feed(5) { sample(-66) }
        feed(1) { sample(-66) }
        assertTrue(sample(-82) is Decision.None) // median still -66
        assertTrue(sample(-82) is Decision.Lock) // median -82, drop of 16 from the peak
    }

    @Test
    fun failingReadingsWithWeakSignalLock() {
        feed(5) { sample(-80) }
        assertEquals(Decision.None, sample(null))
        assertTrue(sample(null) is Decision.Lock)
    }

    @Test
    fun failingReadingsWithStrongSignalDoNotLock() {
        feed(5) { sample(-62) }
        assertEquals(Decision.None, feed(5) { sample(null) })
    }

    @Test
    fun neverLocksWhenNotStopped() {
        assertEquals(Decision.None, feed(20) { sample(-90, stopped = false) })
    }

    @Test
    fun lockWhileChargingIsQuiet() {
        val lock = feed(12) { sample(-85, charging = true) }
        assertTrue(lock is Decision.Lock && lock.quiet)
    }

    @Test
    fun noUnlockWhileCharging() {
        feed(3) { sample(-85, locked = true, lockedByUs = true) }
        assertEquals(Decision.None, feed(10) { sample(-62, locked = true, lockedByUs = true, charging = true, steps = 10) })
    }

    @Test
    fun comingBackWithRisingSignalUnlocks() {
        // a new watcher = a new connection: the owner was away. Signal rises as they walk back.
        var rssi = -88
        val d = feed(12) { rssi += 3; sample(rssi.coerceAtMost(-66), locked = true, lockedByUs = true) }
        assertTrue(d is Decision.Unlock && (d as Decision.Unlock).reason == "signal rising")
    }

    /** The phone slept: no readings, and no steps reported, for [ms]. */
    private fun sleep(ms: Long) {
        now += ms
    }

    @Test
    fun comingBackWhileThePhoneSleptUnlocksOnTheWholeClimb() {
        // the link comes up at the edge of range, then the phone sleeps through the walk back and only
        // sees the flat end: no rise inside the 6 s window, no steps, but -88 -> -66 is the walk itself
        feed(2) { sample(-88, locked = true, lockedByUs = true) }
        sleep(40_000)
        assertEquals(Decision.None, feed(2) { sample(-66, locked = true, lockedByUs = true) })
        sleep(7_000) // past the 6 s trend window: the old rule sees a flat -66
        val d = feed(3) { sample(-66, locked = true, lockedByUs = true) }
        assertTrue(d is Decision.Unlock && "came back into range" in (d as Decision.Unlock).reason)
    }

    @Test
    fun theClimbSinceTheLinkCameUpOnlyCountsForTwoMinutes() {
        feed(2) { sample(-88, locked = true, lockedByUs = true) }
        sleep(ProximityWatcher.ARRIVAL_WINDOW_MS + 10_000)
        feed(2) { sample(-66, locked = true, lockedByUs = true) }
        sleep(7_000)
        assertEquals(Decision.None, feed(10) { sample(-66, locked = true, lockedByUs = true) })
    }

    @Test
    fun aLockMadeOnThisLinkIsNotUndoneAsAComeBack() {
        // locked here; the owner never left the link, so its weakest reading is not "where they came
        // from": a slow climb (never 6 dB inside 6 s) without steps must not unlock
        feed(5) { sample(-66) }
        assertTrue(feed(10) { sample(-85) } is Decision.Lock)
        feed(3) { sample(-85, locked = true, lockedByUs = true) }
        for (level in listOf(-80, -75)) {
            sleep(10_000)
            feed(3) { sample(level, locked = true, lockedByUs = true) }
        }
        sleep(10_000)
        assertEquals(Decision.None, feed(10) { sample(-71, locked = true, lockedByUs = true) })
    }

    @Test
    fun strongButSteadySignalWithoutStepsDoesNotUnlock() {
        // "scooter outside the shop window": good signal, nobody walking towards it
        assertEquals(Decision.None, feed(15) { sample(-70, locked = true, lockedByUs = true) })
    }

    @Test
    fun strongSteadySignalWithStepsUnlocks() {
        val d = feed(6) { sample(-70, locked = true, lockedByUs = true, steps = 6) }
        assertTrue(d is Decision.Unlock && "walking back" in (d as Decision.Unlock).reason)
    }

    @Test
    fun neverUnlocksALockMadeByHand() {
        assertEquals(Decision.None, feed(15) { sample(-62, locked = true, lockedByUs = false, steps = 10) })
    }

    @Test
    fun unlockNeedsTheSignalAboveThresholdPlusMargin() {
        // threshold -78, margin 4: -76 is not enough, -73 is
        assertEquals(Decision.None, feed(10) { sample(-76, locked = true, lockedByUs = true, steps = 10) })
        assertTrue(feed(5) { sample(-73, locked = true, lockedByUs = true, steps = 10) } is Decision.Unlock)
    }

    @Test
    fun strictModeNeedsThePhoneUnlocked() {
        settings = settings.copy(unlockRequiresPhoneUnlocked = true)
        assertEquals(Decision.None, feed(10) { sample(-66, locked = true, lockedByUs = true, steps = 10) })
        val d = feed(5) { sample(-66, locked = true, lockedByUs = true, steps = 10, phoneUnlocked = true) }
        assertTrue(d is Decision.Unlock && (d as Decision.Unlock).reason == "phone unlocked")
    }

    @Test
    fun afterAnUnlockThereIsAGracePeriodBeforeLockingAgain() {
        feed(6) { sample(-70, locked = true, lockedByUs = true, steps = 6) } // unlocks
        // signal immediately weak again: no lock for 20 s
        assertEquals(Decision.None, feed(20) { sample(-85) }) // 16 s
        assertTrue(feed(15) { sample(-85) } is Decision.Lock)
    }

    @Test
    fun aLockTriggeredByStepsIsNotUndoneByTheSameSteps() {
        feed(5) { sample(-74) }
        assertTrue(feed(3) { sample(-80, steps = 12) } is Decision.Lock)
        // the signal bounces back a little while the owner keeps walking, steps still recent: the
        // readings from before the lock still in the median must not count as "went away". Stays locked.
        assertEquals(Decision.None, feed(10) { sample(-72, locked = true, lockedByUs = true, steps = 12) })
        // ...until they really go away and come back
        feed(3) { sample(-84, locked = true, lockedByUs = true) }
        assertTrue(feed(6) { sample(-70, locked = true, lockedByUs = true, steps = 6) } is Decision.Unlock)
    }

    @Test
    fun standingRightBesideItUnlocksEvenWithoutHavingGoneFar() {
        feed(5) { sample(-74) }
        assertTrue(feed(3) { sample(-80, steps = 12) } is Decision.Lock)
        // -66 is unlock level (-74) + 6 or better: the owner is clearly beside it again
        assertTrue(feed(6) { sample(-66, locked = true, lockedByUs = true, steps = 6) } is Decision.Unlock)
    }

    @Test
    fun learnsTheBaselineFromARideAndDerivesThresholds() {
        settings = settings.copy(adaptiveThreshold = true)
        sample(-68)
        assertNull(watcher.learnedBaseline)
        assertEquals(-78, watcher.lockThresholdDbm) // manual until something is learnt
        feed(12) { sample(-68, stopped = false, riding = true) }
        sample(-68) // ride over
        assertEquals(-68, watcher.learnedBaseline)
        // a weak riding level (phone in a bag) lowers the -78 manual level to 20 dB below it
        assertEquals(-88, watcher.lockThresholdDbm)
        assertEquals(-72, watcher.unlockThresholdDbm)
    }

    @Test
    fun aStrongRidingLevelNeverRaisesTheManualThreshold() {
        // the real case: -56 dBm on the scooter used to mean "lock below -68", and beside a parked
        // scooter the phone reads -70 to -80. The hand-tuned -81 stays in charge.
        settings = settings.copy(adaptiveThreshold = true, learnedBaselineDbm = -56, manualThresholdDbm = -81)
        sample(-70)
        assertEquals(-81, watcher.lockThresholdDbm)
        assertEquals(-60, watcher.unlockThresholdDbm)
    }

    @Test
    fun learningNeverPushesTheThresholdPastTheLinkLimit() {
        assertEquals(-88, ProximityWatcher.lockThreshold(manual = -78, adaptive = true, baseline = -80, besideFloor = null))
        assertEquals(-88, ProximityWatcher.lockThreshold(manual = -78, adaptive = true, baseline = null, besideFloor = -86))
        // a manual level below the limit is the owner's choice and stays
        assertEquals(-92, ProximityWatcher.lockThreshold(manual = -92, adaptive = true, baseline = -60, besideFloor = null))
        assertEquals(-78, ProximityWatcher.lockThreshold(manual = -78, adaptive = false, baseline = -80, besideFloor = -86))
    }

    @Test
    fun walkingAroundBesideTheParkedScooterWithThePhoneDoesNotLock() {
        // 17 Nov 2026: app open, scooter beside the owner, and it locked within 30 s
        settings = settings.copy(adaptiveThreshold = true, learnedBaselineDbm = -56, manualThresholdDbm = -81, weakSeconds = 5)
        var i = 0
        val levels = listOf(-66, -71, -74, -69, -78, -72, -80, -70)
        assertEquals(Decision.None, feed(60) { sample(levels[i++ % levels.size], steps = 15, phoneUnlocked = true) })
    }

    @Test
    fun standingStillWithThePhoneInHandDoesNotLockOnAWeakSignal() {
        // looking at the app at the edge of the garage: weak, but nobody is walking away
        assertEquals(Decision.None, feed(20) { sample(-85, phoneUnlocked = true) })
        // phone back in the pocket: the weak signal counts again
        val lock = feed(10) { sample(-85) }
        assertTrue(lock is Decision.Lock && lock.reason.startsWith("weak"))
    }

    @Test
    fun aLockUndoneByHandBesideTheScooterLowersTheThreshold() {
        settings = settings.copy(adaptiveThreshold = true)
        assertTrue(feed(10) { sample(-82) } is Decision.Lock)
        feed(5) { sample(-82, locked = true, lockedByUs = true) }
        // the owner taps "Unlock": they were right there
        val d = sample(-81, locked = false)
        assertEquals(Decision.LockUndone(rssiAtLock = -82, besideFloorDbm = -82), d)
        assertEquals(-82, watcher.besideFloor)
        sample(-82)
        assertEquals(-88, watcher.lockThresholdDbm)
        // and the same signal no longer locks, even after the grace period
        sleep(ProximityWatcher.GRACE_AFTER_HAND_UNLOCK_MS)
        assertEquals(Decision.None, feed(20) { sample(-82) })
    }

    @Test
    fun forgettingTheBesideLevelInSettingsTakesEffectAtOnce() {
        settings = settings.copy(adaptiveThreshold = true, besideFloorDbm = -82)
        sample(-70)
        assertEquals(-88, watcher.lockThresholdDbm)
        settings = settings.copy(besideFloorDbm = null)
        sample(-70)
        assertEquals(-78, watcher.lockThresholdDbm)
    }

    @Test
    fun aLockUndoneAfterGoingFartherTeachesNothing() {
        settings = settings.copy(adaptiveThreshold = true)
        assertTrue(feed(10) { sample(-82) } is Decision.Lock)
        feed(5) { sample(-90, locked = true, lockedByUs = true) } // kept walking: it was a real lock
        feed(5) { sample(-75, locked = true, lockedByUs = true) }
        assertEquals(Decision.None, sample(-75, locked = false))
        assertNull(watcher.besideFloor)
    }

    @Test
    fun aLockUndoneLongAfterTeachesNothing() {
        settings = settings.copy(adaptiveThreshold = true)
        assertTrue(feed(10) { sample(-82) } is Decision.Lock)
        feed(5) { sample(-82, locked = true, lockedByUs = true) }
        sleep(ProximityWatcher.UNDO_WINDOW_MS)
        assertEquals(Decision.None, sample(-82, locked = false))
        assertNull(watcher.besideFloor)
    }

    @Test
    fun aHandUnlockHoldsTheAutoLockOff() {
        // locked by hand, then unlocked by hand from the window: the owner is right there
        feed(3) { sample(-85, locked = true) }
        assertEquals(Decision.None, sample(-85, locked = false))
        assertEquals(Decision.None, feed(70) { sample(-85) }) // 56 s
        assertTrue(feed(15) { sample(-85) } is Decision.Lock)
    }

    @Test
    fun aRideTooShortTeachesNothing() {
        settings = settings.copy(adaptiveThreshold = true)
        feed(5) { sample(-68, stopped = false, riding = true) }
        sample(-68)
        assertNull(watcher.learnedBaseline)
    }

    @Test
    fun aSecondRideBlendsWithTheFirst() {
        settings = settings.copy(adaptiveThreshold = true, learnedBaselineDbm = -70)
        feed(12) { sample(-62, stopped = false, riding = true) }
        sample(-62)
        assertEquals(-66, watcher.learnedBaseline)
    }

    @Test
    fun adaptiveOffKeepsTheManualThresholdEvenWithABaseline() {
        settings = settings.copy(adaptiveThreshold = false, learnedBaselineDbm = -60)
        sample(-66)
        assertEquals(-78, watcher.lockThresholdDbm)
        assertEquals(-74, watcher.unlockThresholdDbm)
    }

    @Test
    fun stepWindowCountsOnlyRecentSteps() {
        val w = StepWindow()
        w.step(1_000)
        w.step(5_000)
        w.step(21_000)
        assertEquals(2, w.countSince(now = 25_000, windowMs = 20_000))
        assertEquals(1, w.countSince(now = 30_000, windowMs = 10_000))
        assertFalse(w.countSince(now = 60_000, windowMs = 20_000) > 0)
    }

    @Test
    fun stepWindowDropsOldStepsThatArrivedLate() {
        val w = StepWindow()
        w.step(50_000)
        w.step(10_000) // held by the sensor while the phone slept, delivered after a newer one
        assertEquals(1, w.countSince(now = 55_000, windowMs = 20_000))
    }
}
