package ch.it4user.fintube.persistence.repositories;

import ch.it4user.fintube.persistence.entities.*;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface YouTubeSubscriptionRepository extends JpaRepository<YouTubeSubscriptionEntity, Long> {
  interface UserSubscriptionView {
    Long getId(); String getChannelId(); String getName(); String getUrl(); Integer getEnabled(); Integer getInitialImportCount(); Integer getShortImportCount(); Integer getLiveStreamImportCount(); Integer getDownloadCount();
    String getLastCheckedAt(); String getLastSuccessfulSyncAt();
  }
  interface DueSubscriptionView {
    String getChannelId(); Long getUserId();
  }
  @Query(value = """
      SELECT s.id AS id, c.channel_id AS channelId, c.name AS name, c.url AS url,
             s.enabled AS enabled, s.last_checked_at AS lastCheckedAt,
             s.last_successful_sync_at AS lastSuccessfulSyncAt,
             c.initial_import_count AS initialImportCount,
             c.short_import_count AS shortImportCount,
             c.live_stream_import_count AS liveStreamImportCount,
             c.download_count AS downloadCount
        FROM youtube_subscriptions s JOIN youtube_channels c ON c.channel_id = s.channel_id
       WHERE s.user_id = :userId ORDER BY c.name
      """, nativeQuery = true)
  List<UserSubscriptionView> findForUser(@Param("userId") long userId);
  List<YouTubeSubscriptionEntity> findByChannelIdAndEnabled(String channelId, int enabled);
  Optional<YouTubeSubscriptionEntity> findByIdAndUserId(Long id, Long userId);
  Optional<YouTubeSubscriptionEntity> findByUserIdAndChannelId(Long userId, String channelId);
  Optional<YouTubeSubscriptionEntity> findByUserIdAndChannelIdAndEnabled(Long userId, String channelId, int enabled);
  @Query("select s.channelId as channelId, s.userId as userId from YouTubeSubscriptionEntity s, YouTubeChannelEntity c "
      + "where s.channelId = c.channelId and s.enabled = 1 and (c.lastSyncAt is null or c.lastSyncAt < :cutoff) "
      + "order by s.channelId, s.userId")
  List<DueSubscriptionView> findDueSubscriptions(@Param("cutoff") String cutoff);
}
