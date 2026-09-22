package ch.it4user.fintube.integration;

import ch.it4user.fintube.core.SettingsService;
import ch.it4user.fintube.persistence.repositories.YouTubeSubscriptionRepository;
import ch.it4user.fintube.persistence.repositories.YouTubePlaylistSubscriptionRepository;
import ch.it4user.fintube.service.UserYouTubeApiKeyService;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** A failed channel is isolated and one API sync serves all its subscribers. */
@Component
public class SubscriptionScheduler {
  private static final Logger LOG = LoggerFactory.getLogger(SubscriptionScheduler.class);
  private final SettingsService settings;
  private final YouTubeSubscriptionRepository subscriptions;
  private final YouTubePlaylistSubscriptionRepository playlists;
  private final YouTubeSyncService sync;
  private final UserYouTubeApiKeyService youtubeApiKeys;
  private final JellyfinSyncService jellyfin;
  private final WatchedVideoSyncService watched;

  SubscriptionScheduler(SettingsService settings, YouTubeSubscriptionRepository subscriptions, YouTubePlaylistSubscriptionRepository playlists,
                        YouTubeSyncService sync, UserYouTubeApiKeyService youtubeApiKeys,
                        JellyfinSyncService jellyfin, WatchedVideoSyncService watched) {
    this.settings = settings;
    this.subscriptions = subscriptions;
    this.playlists = playlists;
    this.sync = sync;
    this.youtubeApiKeys = youtubeApiKeys;
    this.jellyfin = jellyfin;
    this.watched = watched;
  }

  @Scheduled(fixedDelayString = "PT15M")
  void syncDue() {
    int minutes = syncIntervalMinutes();
    String cutoff = Instant.now().minusSeconds(minutes * 60L).toString();
    try {
      try (JellyfinSyncService.RefreshBatch ignored = jellyfin.beginRefreshBatch()) {
        watched.reconcile();
        Set<String> syncedChannels = new HashSet<>();
        for (YouTubeSubscriptionRepository.DueSubscriptionView due : subscriptions.findDueSubscriptions(cutoff)) {
          String channel = due.getChannelId();
          if (syncedChannels.contains(channel)) continue;
          if (!youtubeApiKeys.configured(due.getUserId())) continue;
          try {
            sync.syncChannel(channel, due.getUserId());
            syncedChannels.add(channel);
          } catch (Exception e) {
            LOG.error("Scheduled subscription sync failed for channel={} userId={}", channel, due.getUserId(), e);
            // A channel failure is isolated; the scheduler must remain alive.
          }
        }
        for (var playlist : playlists.findDue(cutoff)) {
          if (!youtubeApiKeys.configured(playlist.getUserId())) continue;
          try {
            sync.syncPlaylist(playlist);
          } catch (Exception e) {
            LOG.error("Scheduled playlist sync failed for playlist={} userId={}", playlist.getPlaylistId(), playlist.getUserId(), e);
          }
        }
      }
    } catch (Exception e) {
      LOG.error("Scheduled subscription sync run failed", e);
      // A repository failure must not stop future scheduled runs.
    }
  }

  private int syncIntervalMinutes() {
    try {
      String raw = settings.value("subscription_sync_minutes");
      if (raw == null || raw.isBlank()) raw = "60";
      return Math.max(1, Integer.parseInt(raw));
    } catch (Exception ignored) {
      return 60;
    }
  }
}
