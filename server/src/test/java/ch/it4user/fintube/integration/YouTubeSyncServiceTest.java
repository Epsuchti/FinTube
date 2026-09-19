package ch.it4user.fintube.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class YouTubeSyncServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void recognizesShortsUpToThreeMinutesWithoutShortsHashtag() throws Exception {
        assertThat(YouTubeSyncService.isShort(JSON.readTree("{\"snippet\":{\"title\":\"Capri-Sun Kaubonbons\"}}"), 66))
                .isTrue();
    }

    @Test
    void keepsVideosOverThreeMinutesOutOfShortsCategory() throws Exception {
        assertThat(YouTubeSyncService.isShort(JSON.readTree("{\"snippet\":{\"title\":\"Long video\"}}"), 181))
                .isFalse();
    }
}
