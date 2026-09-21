package ch.it4user.fintube.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import ch.it4user.fintube.persistence.entities.VideoEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    void rejectsIncrementalShortsWhenShortImportIsDisabled() throws Exception {
        assertThat(YouTubeSyncService.acceptedIncrementalVideo(
                JSON.readTree("{\"contentDetails\":{\"duration\":\"PT2M3S\"},\"snippet\":{}}"), 1, 0, 1))
                .isFalse();
    }

    @Test
    void allowsIncrementalShortsWhenShortImportIsEnabled() throws Exception {
        assertThat(YouTubeSyncService.acceptedIncrementalVideo(
                JSON.readTree("{\"contentDetails\":{\"duration\":\"PT2M3S\"},\"snippet\":{}}"), 1, 1, 1))
                .isTrue();
    }

    @Test
    void retainsNewestVideoInEachConfiguredCategory() {
        VideoEntity newestVideo = video("video-new", "2026-09-20T00:00:00Z", 0, 0);
        VideoEntity oldVideo = video("video-old", "2026-09-19T00:00:00Z", 0, 0);
        VideoEntity newestShort = video("short-new", "2026-09-18T00:00:00Z", 1, 0);
        VideoEntity oldShort = video("short-old", "2026-09-17T00:00:00Z", 1, 0);
        VideoEntity newestLive = video("live-new", "2026-09-16T00:00:00Z", 0, 1);
        VideoEntity oldLive = video("live-old", "2026-09-15T00:00:00Z", 0, 1);

        assertThat(YouTubeSyncService.retainedVideoIds(
                List.of(oldLive, newestShort, oldVideo, newestLive, oldShort, newestVideo), 1, 1, 1))
                .containsExactlyInAnyOrder("video-new", "short-new", "live-new");
    }

    @Test
    void initialDiscoveryKeepsPagingUntilRegularVideoLimitIsFilled() throws Exception {
        int[] counts = new int[3];
        int[] limits = {20, 0, 0};
        var regular = JSON.readTree("{\"contentDetails\":{\"duration\":\"PT20M\"},\"snippet\":{}}");
        var shortVideo = JSON.readTree("{\"contentDetails\":{\"duration\":\"PT1M\"},\"snippet\":{}}");

        for (int i = 0; i < 14; i++) assertThat(YouTubeSyncService.acceptInitialVideo(regular, counts, limits)).isTrue();
        for (int i = 0; i < 6; i++) assertThat(YouTubeSyncService.acceptInitialVideo(shortVideo, counts, limits)).isFalse();
        assertThat(YouTubeSyncService.limitsFilled(counts, limits)).isFalse();

        for (int i = 0; i < 6; i++) assertThat(YouTubeSyncService.acceptInitialVideo(regular, counts, limits)).isTrue();
        assertThat(YouTubeSyncService.limitsFilled(counts, limits)).isTrue();
        assertThat(counts).containsExactly(20, 0, 0);
    }

    private static VideoEntity video(String id, String published, int isShort, int isLiveStream) {
        return new VideoEntity(id, "channel", id, "", published, 60, isShort, isLiveStream,
                "", "AVAILABLE", published);
    }
}
