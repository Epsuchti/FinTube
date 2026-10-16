package ch.it4user.fintube.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SponsorBlockNotifierTest {
    @Test
    void messageDescribesWhatWasRemoved() {
        assertEquals("2 sponsor segments removed (1:23 min skipped).", SponsorBlockNotifier.message(2, 2, 83.4));
        assertEquals("1 sponsor segment removed (12 s skipped).", SponsorBlockNotifier.message(1, 1, 12));
        assertEquals("2 of 3 sponsor segments removed (1:10 min skipped).", SponsorBlockNotifier.message(3, 2, 70));
        assertEquals("1 sponsor segment found in this video, but it could not be removed without re-encoding.",
                SponsorBlockNotifier.message(1, 0, 0));
    }
}
