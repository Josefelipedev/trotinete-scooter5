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
    fun walkingAwayWithStepsLocksBeforeTheSignalIsWeak() {
        feed(5) { sample(-66) }
        // -74 is above the -78 threshold: alone it would never lock, but with 12 steps it does
        val lock = feed(3) { sample(-74, steps = 12) }
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
        feed(5) { sample(-66) }
        assertTrue(feed(3) { sample(-74, steps = 12) } is Decision.Lock)
        // owner keeps walking just 5 m away: signal still decent, steps still recent. Must stay locked.
        assertEquals(Decision.None, feed(10) { sample(-72, locked = true, lockedByUs = true, steps = 12) })
        // ...until they really go away and come back
        feed(3) { sample(-84, locked = true, lockedByUs = true) }
        assertTrue(feed(6) { sample(-70, locked = true, lockedByUs = true, steps = 6) } is Decision.Unlock)
    }

    @Test
    fun standingRightBesideItUnlocksEvenWithoutHavingGoneFar() {
        feed(5) { sample(-66) }
        assertTrue(feed(3) { sample(-74, steps = 12) } is Decision.Lock)
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
        assertEquals(-80, watcher.lockThresholdDbm)
        assertEquals(-72, watcher.unlockThresholdDbm)
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
}
