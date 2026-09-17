package ch.it4user.fintube.web;

import ch.it4user.fintube.service.PlaybackService;

import ch.it4user.fintube.api.playback.contract.PlaybackApi;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** HTTP adapter for the stable Jellyfin-facing playback contract. */
@RestController
public class PlaybackContractController implements PlaybackApi {
    private final PlaybackService playback;

    public PlaybackContractController(PlaybackService playback) {
        this.playback = playback;
    }

    @Override
    public ResponseEntity<Resource> getPlaybackFragment(String video, String fragment, String token) {
        PlaybackService.Fragment value = playback.fragment(video, fragment, token);
        return ResponseEntity.ok().contentLength(value.contentLength()).contentType(value.contentType()).body((Resource) value.resource());
    }

    @Override
    public ResponseEntity<String> getPlaybackManifest(String video, String token) {
        return ResponseEntity.ok(playback.manifest(video, token));
    }
}
