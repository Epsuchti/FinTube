package ch.it4user.fintube.integration;

import ch.it4user.fintube.core.SettingsService;
import ch.it4user.fintube.persistence.repositories.YouTubeSubscriptionRepository;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** A failed channel is isolated and one API sync serves all its subscribers. */
@Component
public class SubscriptionScheduler {
  private final SettingsService settings;
  private final YouTubeSubscriptionRepository subscriptions;
  private final YouTubeSyncService sync;

  SubscriptionScheduler(SettingsService settings, YouTubeSubscriptionRepository subscriptions,
                        YouTubeSyncService sync) {
    this.settings = settings;
    this.subscriptions = subscriptions;
    this.sync = sync;
  }

  @Scheduled(fixedDelayString = "PT15M")
  void syncDue() {
    int minutes = syncIntervalMinutes();
    String cutoff = Instant.now().minusSeconds(minutes * 60L).toString();
    try {
      for (String channel : subscriptions.findDueChannelIds(cutoff)) {
        try {
          sync.syncChannel(channel);
        } catch (Exception ignored) {
          // A channel failure is isolated; the scheduler must remain alive.
        }
      }
    } catch (Exception ignored) {
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
