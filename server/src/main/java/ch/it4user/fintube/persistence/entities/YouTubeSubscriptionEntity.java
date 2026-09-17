package ch.it4user.fintube.persistence.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** User subscription to one canonical YouTube channel. */
@Entity
@Table(name = "youtube_subscriptions",
    uniqueConstraints = @UniqueConstraint(name = "uq_subscription", columnNames = {"user_id", "channel_id"}))
public class YouTubeSubscriptionEntity {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @Column(name = "user_id", nullable = false) private Long userId;
  @Column(name = "channel_id", nullable = false, length = 255) private String channelId;
  @Column(nullable = false) private int enabled;
  @Column(name = "created_at", nullable = false, length = 64) private String createdAt;
  @Column(name = "last_checked_at", length = 64) private String lastCheckedAt;
  @Column(name = "last_successful_sync_at", length = 64) private String lastSuccessfulSyncAt;
  protected YouTubeSubscriptionEntity() { }
  public YouTubeSubscriptionEntity(Long userId, String channelId, int enabled, String createdAt) {
    this.userId = userId; this.channelId = channelId; this.enabled = enabled; this.createdAt = createdAt;
  }
  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public String getChannelId() { return channelId; }
  public int getEnabled() { return enabled; }
  public String getCreatedAt() { return createdAt; }
  public String getLastCheckedAt() { return lastCheckedAt; }
  public String getLastSuccessfulSyncAt() { return lastSuccessfulSyncAt; }
  public void setEnabled(int value) { enabled = value; }
  public void setLastCheckedAt(String value) { lastCheckedAt = value; }
  public void setLastSuccessfulSyncAt(String value) { lastSuccessfulSyncAt = value; }
}
