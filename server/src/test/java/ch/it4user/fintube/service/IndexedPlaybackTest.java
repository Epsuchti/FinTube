package ch.it4user.fintube.service;

import ch.it4user.fintube.media.AdaptiveIndex;
import ch.it4user.fintube.media.MediaSourceService;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IndexedPlaybackTest {
    @Test
    void masterListsH264FirstAndVp9SecondSharingOneAacRendition() {
        var segments = List.of(new AdaptiveIndex.Segment(100, 200, 0, 5), new AdaptiveIndex.Segment(200, 300, 5, 5));
        var h264 = new MediaSourceService.IndexedTrack("v0", "137", URI.create("https://example.test/137"), "mp4", "h264",
                "avc1.640028", false, 1920, 1080, 30, 5_000_000, "SDR", 100, segments);
        var vp9 = new MediaSourceService.IndexedTrack("v1", "313", URI.create("https://example.test/313"), "webm", "vp9",
                "vp09.00.50.08", false, 3840, 2160, 30, 23_000_000, "SDR", 100, segments);
        var aac = new MediaSourceService.IndexedTrack("a", "140", URI.create("https://example.test/140"), "mp4", "aac",
                "mp4a.40.2", true, 0, 0, 0, 160_000, "SDR", 100, segments);
        var source = new MediaSourceService.Source("313+140~137-idxv1", "313", "140", List.of(), 10, 5, "vp9", "aac",
                "webm", null, false, 3840, 2160, 30, 23_160_000, "vp09.00.50.08,mp4a.40.2", null, "policy",
                List.of(h264, vp9, aac));

        String master = PlaybackService.indexedMaster("video", "token", source);

        assertTrue(master.contains("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aac\""));
        assertTrue(master.contains("/a/index.m3u8?token=token"));
        int h264At = master.indexOf("CODECS=\"avc1.640028,mp4a.40.2\"");
        int vp9At = master.indexOf("CODECS=\"vp09.00.50.08,mp4a.40.2\"");
        assertTrue(h264At > 0 && vp9At > h264At, "the H.264 variant comes first");
        assertTrue(master.contains("RESOLUTION=3840x2160"));
        assertTrue(master.contains("/v1/index.m3u8?token=token"));
        assertEquals(2, master.split("AUDIO=\"aac\"", -1).length - 1);
    }
}
