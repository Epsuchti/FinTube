package ch.it4user.fintube.media;

import ch.it4user.fintube.core.SettingsService;
import ch.it4user.fintube.integration.JellyfinSyncService;
import ch.it4user.fintube.persistence.repositories.UserVideoRepository;
import ch.it4user.fintube.service.AuthService;
import ch.it4user.fintube.service.PlaybackService;
import ch.it4user.fintube.service.SponsorBlockService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Fmp4PlaybackTest {
    @TempDir Path temp;

    @Test
    void publishesSeparateVideoAndAudioWithoutRemuxing() throws Exception {
        var users = mock(UserVideoRepository.class);
        var sources = mock(MediaSourceService.class);
        var fragments = mock(FragmentManager.class);
        var filler = mock(BackgroundFillService.class);
        var renditions = mock(SponsorRenditionService.class);
        when(users.existsByIdVideoIdAndPlaybackToken("video", "token")).thenReturn(true);
        var source = new MediaSourceService.Source("628+234-fmp4v1", "628", "234", List.of(
                new MediaSourceService.Fragment("v0", URI.create("https://example.test/v0"), URI.create("https://example.test/a0"), 6, 0),
                new MediaSourceService.Fragment("v1", URI.create("https://example.test/v1"), URI.create("https://example.test/a1"), 6, 6)),
                12, 6, "vp9", "aac", "mp4", null, false,
                3840, 2160, 60, 30_000_000, "vp09.00.51.08,mp4a.40.2",
                URI.create("https://example.test/init"), "2160|h264,vp9,av1|aac,opus||");
        when(sources.source("video")).thenReturn(source);
        PlaybackService playback = new PlaybackService(users, mock(AuthService.class), sources, fragments,
                filler, mock(SettingsService.class), mock(SponsorBlockService.class), renditions,
                mock(JellyfinSyncService.class));

        String master = playback.manifest("video", "token");
        String routeKey = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(source.format().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(master.contains("RESOLUTION=3840x2160"));
        assertTrue(master.contains("CODECS=\"vp09.00.51.08,mp4a.40.2\""));
        assertTrue(master.contains("/fmp4/" + routeKey + "/video/index.m3u8"));
        assertTrue(master.contains("#EXT-X-MEDIA:TYPE=AUDIO"));
        String video = playback.fmp4TrackManifest("video", routeKey, "video", "token");
        assertTrue(video.contains("#EXT-X-MAP:"));
        assertTrue(video.contains("/video/1.m4s"));
        String audio = playback.fmp4TrackManifest("video", routeKey, "audio", "token");
        assertFalse(audio.contains("#EXT-X-MAP:"));
        assertTrue(audio.contains("/audio/1.aac"));
        verifyNoInteractions(renditions, filler);

        Path file = temp.resolve("segment");
        Files.writeString(file, "segment");
        when(fragments.get(eq("video"), eq(source.format() + "-video"), eq("video1"), eq(URI.create("https://example.test/v1")), eq(true))).thenReturn(file);
        when(fragments.open(eq("video"), eq(source.format() + "-video"), eq("video1"), eq(URI.create("https://example.test/v1")), eq(true)))
                .thenReturn(Files.newInputStream(file));
        try (var stream = playback.fmp4TrackFragment("video", routeKey, "video", 1, "token").resource().getInputStream()) {
            assertEquals("segment", new String(stream.readAllBytes()));
        }
        verify(fragments, never()).get(anyString(), anyString(), anyString(), any(), any(), anyDouble(), anyBoolean());
    }
}
