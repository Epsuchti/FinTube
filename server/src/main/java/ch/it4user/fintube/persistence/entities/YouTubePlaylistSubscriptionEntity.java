package ch.it4user.fintube.persistence.entities;

import jakarta.persistence.*;

/** A user's independently synchronized YouTube playlist. */
@Entity
@Table(name = "youtube_playlist_subscriptions", uniqueConstraints =
    @UniqueConstraint(name = "uq_playlist_subscription", columnNames = {"user_id", "playlist_id"}))
public class YouTubePlaylistSubscriptionEntity {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @Column(name = "user_id", nullable = false) private Long userId;
  @Column(name = "playlist_id", nullable = false, length = 255) private String playlistId;
  @Column(nullable = false, length = 512) private String name;
  @Column(length = 4096) private String url;
  @Column(nullable = false) private int enabled;
  @Column(name = "created_at", nullable = false, length = 64) private String createdAt;
  @Column(name = "last_checked_at", length = 64) private String lastCheckedAt;
  @Column(name = "last_successful_sync_at", length = 64) private String lastSuccessfulSyncAt;
  protected YouTubePlaylistSubscriptionEntity() { }
  public YouTubePlaylistSubscriptionEntity(Long userId, String playlistId, String name, String url, int enabled, String createdAt) {
    this.userId = userId; this.playlistId = playlistId; this.name = name; this.url = url; this.enabled = enabled; this.createdAt = createdAt;
  }
  public Long getId() { return id; } public Long getUserId() { return userId; } public String getPlaylistId() { return playlistId; }
  public String getName() { return name; } public String getUrl() { return url; } public int getEnabled() { return enabled; }
  public String getLastCheckedAt() { return lastCheckedAt; } public String getLastSuccessfulSyncAt() { return lastSuccessfulSyncAt; }
  public void setName(String value) { name = value; } public void setUrl(String value) { url = value; }
  public void setEnabled(int value) { enabled = value; } public void setLastCheckedAt(String value) { lastCheckedAt = value; }
  public void setLastSuccessfulSyncAt(String value) { lastSuccessfulSyncAt = value; }
}
