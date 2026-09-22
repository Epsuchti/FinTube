package ch.it4user.fintube.service;

import ch.it4user.fintube.core.ApplicationPaths;
import ch.it4user.fintube.core.SettingsService;
import ch.it4user.fintube.integration.JellyfinSyncService;
import ch.it4user.fintube.media.*;
import ch.it4user.fintube.persistence.repositories.UserVideoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlaybackServiceTest {
    @TempDir Path temp;
    final UserVideoRepository users = mock(UserVideoRepository.class);
    final MediaSourceService sources = mock(MediaSourceService.class);
    final BackgroundFillService filler = mock(BackgroundFillService.class);
    final SettingsService settings = mock(SettingsService.class);
    final SponsorBlockService sponsors = mock(SponsorBlockService.class);
    final SponsorRenditionService renditions = mock(SponsorRenditionService.class);

    PlaybackService playback(SponsorRenditionService service) throws Exception {
        when(users.existsByIdVideoIdAndPlaybackToken("video", "token")).thenReturn(true);
        when(sources.source("video")).thenReturn(source());
        return new PlaybackService(users, mock(AuthService.class), sources, mock(FragmentManager.class),
                filler, settings, sponsors, service, mock(JellyfinSyncService.class));
    }

    SponsorRenditionService originals() {
        ApplicationPaths paths = new ApplicationPaths();
        paths.root = temp;
        return new SponsorRenditionService(paths, settings, mock(FragmentManager.class), sources,
                mock(SponsorStreamCopy.class));
    }

    @Test void disabledPlaybackBackgroundFillLeavesFragmentsOnDemand() throws Exception {
        when(settings.value("background_fill_on_playback")).thenReturn("false");
        playback(originals()).manifest("video", "token");
        verifyNoInteractions(filler);
    }

    @Test void enabledPlaybackBackgroundFillQueuesTheRemainingFragments() throws Exception {
        when(settings.value("background_fill_on_playback")).thenReturn("true");
        playback(originals()).manifest("video", "token");
        verify(filler).enqueue("video", "playback_manifest");
    }

    @Test void missingPlaybackBackgroundFillSettingDefaultsToDisabled() throws Exception {
        playback(originals()).manifest("video", "token");
        verifyNoInteractions(filler);
    }

    @Test void originalVersionSurvivesSponsorLookupChangesAndServiceRestart() throws Exception {
        SponsorRenditionService service = originals();
        PlaybackService playback = playback(service);
        String master = playback.manifest("video", "token");
        assertTrue(master.contains("#EXT-X-STREAM-INF:"));
        assertFalse(master.contains("#EXTINF:"));
        String id = master.split("/rendition/")[1].split("/")[0];
        String before = playback.renditionManifest("video", id, "token");
        assertEquals(3, before.lines().filter(l -> l.startsWith("#EXTINF:")).count());
        assertFalse(before.contains("DISCONTINUITY"));
        when(sponsors.skipSegments("video")).thenReturn(List.of(new SponsorBlockService.Segment(4, 8)));
        String after = playback(originals()).renditionManifest("video", id, "token");
        assertEquals(before, after);
        verify(sponsors, times(1)).skipSegments("video");
    }

    private static MediaSourceService.Source source() {
        return new MediaSourceService.Source("format", List.of(
                new MediaSourceService.Fragment("before", URI.create("https://example.test/0"), null, 4, 0),
                new MediaSourceService.Fragment("sponsor", URI.create("https://example.test/1"), null, 4, 4),
                new MediaSourceService.Fragment("after", URI.create("https://example.test/2"), null, 4, 8)), 12);
    }
}
