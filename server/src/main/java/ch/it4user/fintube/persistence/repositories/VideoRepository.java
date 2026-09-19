package ch.it4user.fintube.persistence.repositories;

import ch.it4user.fintube.persistence.entities.*;

import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VideoRepository extends JpaRepository<VideoEntity, String> {
  interface CacheMetadataView {
    String getVideoId();
    String getVideoTitle();
    String getChannelName();
  }

  List<VideoEntity> findByChannelIdOrderByPublishedAtDesc(String channelId);

  List<VideoEntity> findByChannelIdAndAvailabilityOrderByPublishedAtDesc(
      String channelId, String availability, Pageable pageable);

  List<VideoEntity> findByChannelIdAndAvailabilityAndIsShortAndIsLiveStreamOrderByPublishedAtDesc(
      String channelId, String availability, int isShort, int isLiveStream, Pageable pageable);

  @Query(value = """
      select v.video_id as videoId, v.title as videoTitle, c.name as channelName
        from videos v
        left join youtube_channels c on c.channel_id = v.channel_id
       where v.video_id in (:videoIds)
      """, nativeQuery = true)
  List<CacheMetadataView> findCacheMetadataByVideoIds(@Param("videoIds") Collection<String> videoIds);

  List<VideoEntity> findByChannelIdAndVideoIdIn(String channelId, Collection<String> videoIds);

  @Query("select v.videoId from VideoEntity v "
      + "where v.channelId = :channelId "
      + "and (v.metadataUpdatedAt is null or v.metadataUpdatedAt < :cutoff) "
      + "order by v.metadataUpdatedAt")
  List<String> findStaleVideoIds(@Param("channelId") String channelId,
                                @Param("cutoff") String cutoff, Pageable pageable);
}
