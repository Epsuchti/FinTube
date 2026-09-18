package ch.it4user.fintube.web;

import ch.it4user.fintube.service.PlaybackService;

import ch.it4user.fintube.api.playback.contract.PlaybackApi;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

/** HTTP adapter for the stable Jellyfin-facing playback contract. */
@RestController
public class PlaybackContractController implements PlaybackApi {
    private final PlaybackService playback;
    private final HttpServletRequest requestContext;

    public PlaybackContractController(PlaybackService playback, HttpServletRequest requestContext) {
        this.playback = playback;
        this.requestContext = requestContext;
    }

    @Override
    public ResponseEntity<Resource> getPlaybackFragment(String video, String fragment, String token) {
        PlaybackService.Fragment value = playback.fragment(video, fragment, token, requestContext);
        return ResponseEntity.ok().contentLength(value.contentLength()).contentType(value.contentType()).body((Resource) value.resource());
    }

    @Override
    public ResponseEntity<String> getPlaybackManifest(String video, String token) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"))
                .header("Cache-Control", "no-store")
                .body(playback.manifest(video, token, requestContext));
    }
}
