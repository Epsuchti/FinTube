package ch.it4user.fintube.media;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MediaNotReadyExceptionTest {
  @Test
  void recognisesUpcomingLiveEvent() {
    assertTrue(MediaNotReadyException.matches(
        "ERROR: [youtube] perVLHPvHqg: This live event will begin in a few moments."));
    assertTrue(MediaNotReadyException.matches("ERROR: [youtube] x: Premieres in 2 hours"));
  }

  @Test
  void otherFailuresAreNotTreatedAsNotReady() {
    assertFalse(MediaNotReadyException.matches("ERROR: [youtube] x: Video unavailable"));
    assertFalse(MediaNotReadyException.matches(""));
    assertFalse(MediaNotReadyException.matches(null));
  }
}
