package ch.it4user.fintube.service;

import ch.it4user.fintube.core.SettingsService;
import ch.it4user.fintube.media.BackgroundFillService;
import ch.it4user.fintube.media.FragmentManager;
import ch.it4user.fintube.media.MediaSourceService;
import ch.it4user.fintube.persistence.repositories.UserVideoRepository;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlaybackServiceTest {
    @Test
    void disabledPlaybackBackgroundFillLeavesFragmentsOnDemand() throws Exception {
        UserVideoRepository userVideos = mock(UserVideoRepository.class);
        MediaSourceService sources = mock(MediaSourceService.class);
        BackgroundFillService filler = mock(BackgroundFillService.class);
        SettingsService settings = mock(SettingsService.class);
        when(userVideos.existsByIdVideoIdAndPlaybackToken("video", "token")).thenReturn(true);
        when(sources.source("video")).thenReturn(source());
        when(settings.value("background_fill_on_playback")).thenReturn("false");

        PlaybackService playback = new PlaybackService(userVideos, mock(AuthService.class), sources,
                mock(FragmentManager.class), filler, settings);

        playback.manifest("video", "token");

        verifyNoInteractions(filler);
    }

    @Test
    void enabledPlaybackBackgroundFillQueuesTheRemainingFragments() throws Exception {
        UserVideoRepository userVideos = mock(UserVideoRepository.class);
        MediaSourceService sources = mock(MediaSourceService.class);
        BackgroundFillService filler = mock(BackgroundFillService.class);
        SettingsService settings = mock(SettingsService.class);
        when(userVideos.existsByIdVideoIdAndPlaybackToken("video", "token")).thenReturn(true);
        when(sources.source("video")).thenReturn(source());
        when(settings.value("background_fill_on_playback")).thenReturn("true");

        PlaybackService playback = new PlaybackService(userVideos, mock(AuthService.class), sources,
                mock(FragmentManager.class), filler, settings);

        playback.manifest("video", "token");

        verify(filler).enqueue("video", "playback_manifest");
    }

    @Test
    void missingPlaybackBackgroundFillSettingDefaultsToDisabled() throws Exception {
        UserVideoRepository userVideos = mock(UserVideoRepository.class);
        MediaSourceService sources = mock(MediaSourceService.class);
        BackgroundFillService filler = mock(BackgroundFillService.class);
        SettingsService settings = mock(SettingsService.class);
        when(userVideos.existsByIdVideoIdAndPlaybackToken("video", "token")).thenReturn(true);
        when(sources.source("video")).thenReturn(source());

        PlaybackService playback = new PlaybackService(userVideos, mock(AuthService.class), sources,
                mock(FragmentManager.class), filler, settings);

        playback.manifest("video", "token");

        verifyNoInteractions(filler);
    }

    private static MediaSourceService.Source source() {
        return new MediaSourceService.Source("format",
                List.of(new MediaSourceService.Fragment("fragment", URI.create("https://example.test/fragment"), 4)), 4);
    }
}
