package ch.it4user.fintube.web;

import ch.it4user.fintube.core.Database;
import ch.it4user.fintube.media.FragmentManager;
import ch.it4user.fintube.media.MediaSourceService;
import ch.it4user.fintube.media.BackgroundFillService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.sql.*;

/** Jellyfin sees a stable HLS timeline; only individual missing fragments contact the upstream source. */
@RestController public class PlaybackController {
  final Database db; final MediaSourceService sources; final FragmentManager fragments; final BackgroundFillService filler;
  PlaybackController(Database d, MediaSourceService s, FragmentManager f,BackgroundFillService b){db=d;sources=s;fragments=f;filler=b;}
  void valid(String video,String token)throws Exception { try(Connection c=db.open(); PreparedStatement p=c.prepareStatement("SELECT 1 FROM user_videos WHERE video_id=? AND playback_token=?")){p.setString(1,video);p.setString(2,token);if(!p.executeQuery().next())throw new ResponseStatusException(HttpStatus.NOT_FOUND);}}
  @GetMapping(value="/play/{video}",produces="application/vnd.apple.mpegurl") String manifest(@PathVariable String video,@RequestParam String token)throws Exception {
    valid(video,token); var source=sources.source(video); filler.enqueue(video);
    StringBuilder out=new StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-INDEPENDENT-SEGMENTS\n");
    out.append("#EXT-X-TARGETDURATION:").append(source.targetDuration()).append("\n#EXT-X-MEDIA-SEQUENCE:0\n");
    for(var f:source.fragments()) out.append("#EXTINF:").append(String.format(java.util.Locale.ROOT,"%.3f",f.seconds())).append(",\n/play/").append(video).append("/fragment/").append(f.id()).append("?token=").append(token).append("\n");
    return out.append("#EXT-X-ENDLIST\n").toString();
  }
  @GetMapping("/play/{video}/fragment/{fragment}") ResponseEntity<InputStreamResource> fragment(@PathVariable String video,@PathVariable String fragment,@RequestParam String token)throws Exception {
    valid(video,token); var source=sources.source(video); var f=source.fragments().stream().filter(x->x.id().equals(fragment)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    Path path; java.io.InputStream stream;
    try { path=fragments.get(video,source.format(),fragment,f.url(),f.audioUrl(),true); stream=fragments.open(video,source.format(),fragment,f.url(),f.audioUrl(),true); }
    catch(FragmentManager.ExpiredSourceException e){ source=sources.refresh(video); var fresh=source.fragments().stream().filter(x->x.id().equals(fragment)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_GATEWAY)); path=fragments.get(video,source.format(),fragment,fresh.url(),fresh.audioUrl(),true); stream=fragments.open(video,source.format(),fragment,fresh.url(),fresh.audioUrl(),true); }
    MediaType type = source.progressive()
        ? ("webm".equalsIgnoreCase(source.container()) ? MediaType.parseMediaType("video/webm") : MediaType.parseMediaType("video/mp4"))
        : MediaType.parseMediaType("video/mp2t");
    return ResponseEntity.ok().contentLength(Files.size(path)).contentType(type).body(new InputStreamResource(stream));
  }
}
