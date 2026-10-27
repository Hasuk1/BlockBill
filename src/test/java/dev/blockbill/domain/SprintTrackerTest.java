package dev.blockbill.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SprintTrackerTest {
    @Test
    void penaltyStartsAtTheOneHundredTwentiethContinuousTick() {
        SprintTracker tracker = new SprintTracker();
        sprintWithoutPenalty(tracker, 119);
        assertTrue(tracker.tick(true, true, false));
        assertEquals(160, tracker.cooldownTicks());
    }

    @Test
    void stoppedSprintDoesNotAccumulateAcrossRuns() {
        SprintTracker tracker = new SprintTracker();
        for (int run = 0; run < 10; run++) {
            sprintWithoutPenalty(tracker, 119);
            assertFalse(tracker.tick(true, false, false));
            assertEquals(0, tracker.continuousTicks());
        }
    }

    @Test
    void continuousSprintNeverPenalizesMoreOftenThanEveryOneHundredSixtyTicks() {
        SprintTracker tracker = new SprintTracker();
        sprintWithoutPenalty(tracker, 119);
        assertTrue(tracker.tick(true, true, false));
        for (int penalty = 0; penalty < 5; penalty++) {
            sprintWithoutPenalty(tracker, 159);
            assertTrue(tracker.tick(true, true, false));
        }
    }

    @Test
    void stoppingSprintDoesNotResetCooldown() {
        SprintTracker tracker = new SprintTracker();
        sprintWithoutPenalty(tracker, 119);
        assertTrue(tracker.tick(true, true, false));
        assertFalse(tracker.tick(true, false, false));
        sprintWithoutPenalty(tracker, 158);
        assertTrue(tracker.tick(true, true, false));
    }

    @Test
    void licenseResetsTheAccumulatedSprintAndPreventsPenalties() {
        SprintTracker tracker = new SprintTracker();
        sprintWithoutPenalty(tracker, 119);
        for (int tick = 0; tick < 500; tick++) {
            assertFalse(tracker.tick(true, true, true));
        }
        sprintWithoutPenalty(tracker, 119);
        assertTrue(tracker.tick(true, true, false));
    }

    @Test
    void creativeSpectatorAndAdventureDoNotAccumulateOrReceivePenalties() {
        SprintTracker tracker = new SprintTracker();
        sprintWithoutPenalty(tracker, 119);
        for (int tick = 0; tick < 500; tick++) {
            assertFalse(tracker.tick(false, true, false));
        }
        assertEquals(0, tracker.continuousTicks());
        assertEquals(0, tracker.cooldownTicks());
        sprintWithoutPenalty(tracker, 119);
        assertTrue(tracker.tick(true, true, false));
    }

    @Test
    void resetAfterRespawnStartsFreshTransientState() {
        SprintTracker tracker = new SprintTracker();
        sprintWithoutPenalty(tracker, 119);
        assertTrue(tracker.tick(true, true, false));
        tracker.reset();
        assertEquals(0, tracker.continuousTicks());
        assertEquals(0, tracker.cooldownTicks());
    }

    @Test
    void trackersAreIndependentForEachPlayer() {
        SprintTracker first = new SprintTracker();
        SprintTracker second = new SprintTracker();
        sprintWithoutPenalty(first, 119);
        assertTrue(first.tick(true, true, false));
        assertEquals(0, second.continuousTicks());
        assertEquals(0, second.cooldownTicks());
    }

    private static void sprintWithoutPenalty(SprintTracker tracker, int ticks) {
        for (int tick = 0; tick < ticks; tick++) {
            assertFalse(tracker.tick(true, true, false), "Unexpected penalty on tick " + (tick + 1));
        }
    }
}
