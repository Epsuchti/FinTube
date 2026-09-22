package ch.it4user.fintube.web;

import ch.it4user.fintube.service.PlaybackService;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Separate source tracks preserve YouTube's fMP4 video without an MPEG-TS remux. */
@RestController
public class Fmp4PlaybackController {
    private final PlaybackService playback;

    public Fmp4PlaybackController(PlaybackService playback) { this.playback = playback; }

    @GetMapping("/play/{video}/fmp4/{format}/{track}/index.m3u8")
    public ResponseEntity<String> manifest(@PathVariable String video, @PathVariable String format, @PathVariable String track,
                                           @RequestParam String token) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"))
                .header("Cache-Control", "private, no-store")
                .body(playback.fmp4TrackManifest(video, format, track, token));
    }

    @GetMapping("/play/{video}/fmp4/{format}/video/init.mp4")
    public ResponseEntity<Resource> init(@PathVariable String video, @PathVariable String format, @RequestParam String token) {
        return fragment(video, format, "video", null, token);
    }

    @GetMapping("/play/{video}/fmp4/{format}/video/{index}.m4s")
    public ResponseEntity<Resource> videoSegment(@PathVariable String video, @PathVariable String format, @PathVariable Integer index,
                                                  @RequestParam String token) {
        return fragment(video, format, "video", index, token);
    }

    @GetMapping("/play/{video}/fmp4/{format}/audio/{index}.aac")
    public ResponseEntity<Resource> audioSegment(@PathVariable String video, @PathVariable String format, @PathVariable Integer index,
                                                  @RequestParam String token) {
        return fragment(video, format, "audio", index, token);
    }

    private ResponseEntity<Resource> fragment(String video, String format, String track, Integer index, String token) {
        PlaybackService.Fragment value = playback.fmp4TrackFragment(video, format, track, index, token);
        return ResponseEntity.ok().contentLength(value.contentLength()).contentType(value.contentType())
                .body(value.resource());
    }
}
