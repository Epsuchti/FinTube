package ch.it4user.fintube.web;

import ch.it4user.fintube.service.PlaybackService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class Fmp4PlaybackControllerTest {
    @Test
    void exposesTrackPlaylistAndSegments() throws Exception {
        PlaybackService playback = mock(PlaybackService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new Fmp4PlaybackController(playback)).build();
        String routeKey = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("628+234-fmp4v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(playback.fmp4TrackManifest("video", routeKey, "video", "token"))
                .thenReturn("#EXTM3U\n");
        when(playback.fmp4TrackFragment("video", routeKey, "video", 1, "token"))
                .thenReturn(new PlaybackService.Fragment(
                        new InputStreamResource(new ByteArrayInputStream(new byte[]{1, 2, 3})), 3,
                        MediaType.parseMediaType("video/mp4")));

        mvc.perform(get("/play/video/fmp4/" + routeKey + "/video/index.m3u8?token=token"))
                .andExpect(status().isOk()).andExpect(content().string("#EXTM3U\n"));
        mvc.perform(get("/play/video/fmp4/" + routeKey + "/video/1.m4s?token=token"))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[]{1, 2, 3}));
    }
}
