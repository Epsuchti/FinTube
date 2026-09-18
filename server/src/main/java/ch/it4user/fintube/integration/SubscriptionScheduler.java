package ch.it4user.fintube.integration;

import ch.it4user.fintube.core.SettingsService;
import ch.it4user.fintube.persistence.repositories.YouTubeSubscriptionRepository;
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
  private final YouTubeSyncService sync;
  private final UserYouTubeApiKeyService youtubeApiKeys;

  SubscriptionScheduler(SettingsService settings, YouTubeSubscriptionRepository subscriptions,
                        YouTubeSyncService sync, UserYouTubeApiKeyService youtubeApiKeys) {
    this.settings = settings;
    this.subscriptions = subscriptions;
    this.sync = sync;
    this.youtubeApiKeys = youtubeApiKeys;
  }

  @Scheduled(fixedDelayString = "PT15M")
  void syncDue() {
    int minutes = syncIntervalMinutes();
    String cutoff = Instant.now().minusSeconds(minutes * 60L).toString();
    try {
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
