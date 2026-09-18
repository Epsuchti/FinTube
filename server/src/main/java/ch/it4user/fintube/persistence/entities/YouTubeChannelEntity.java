package ch.it4user.fintube.persistence.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/** Canonical YouTube channel and synchronization cursor. */
@Entity
@Table(name = "youtube_channels")
public class YouTubeChannelEntity {
  @Id @Column(name = "channel_id", length = 255) private String channelId;
  @Column(nullable = false, length = 512) private String name;
  @Column(length = 4096) private String url;
  @Column(name = "thumbnail_url", length = 4096) private String thumbnailUrl;
  @Column(name = "updated_at", nullable = false, length = 64) private String updatedAt;
  @Column(name = "last_sync_at", length = 64) private String lastSyncAt;
  @Column(name = "last_successful_sync_at", length = 64) private String lastSuccessfulSyncAt;
  @Column(name = "last_sync_published_at", length = 64) private String lastSyncPublishedAt;
  @Column(name = "initial_import_count") private Integer initialImportCount;
  @Column(name = "download_count") private Integer downloadCount;
  @Lob @Column(name = "sync_error") private String syncError;
  protected YouTubeChannelEntity() { }
  public YouTubeChannelEntity(String channelId, String name, String url, String updatedAt) {
    this.channelId = channelId; this.name = name; this.url = url; this.updatedAt = updatedAt;
  }
  public String getChannelId() { return channelId; }
  public String getName() { return name; }
  public String getUrl() { return url; }
  public String getThumbnailUrl() { return thumbnailUrl; }
  public String getUpdatedAt() { return updatedAt; }
  public String getLastSyncAt() { return lastSyncAt; }
  public String getLastSuccessfulSyncAt() { return lastSuccessfulSyncAt; }
  public String getLastSyncPublishedAt() { return lastSyncPublishedAt; }
  public Integer getInitialImportCount() { return initialImportCount; }
  public Integer getDownloadCount() { return downloadCount; }
  public String getSyncError() { return syncError; }
  public void setName(String name) { this.name = name; }
  public void setUrl(String url) { this.url = url; }
  public void setThumbnailUrl(String value) { thumbnailUrl = value; }
  public void setUpdatedAt(String value) { updatedAt = value; }
  public void setLastSyncAt(String value) { lastSyncAt = value; }
  public void setLastSuccessfulSyncAt(String value) { lastSuccessfulSyncAt = value; }
  public void setLastSyncPublishedAt(String value) { lastSyncPublishedAt = value; }
  public void setInitialImportCount(Integer value) { initialImportCount = value; }
  public void setDownloadCount(Integer value) { downloadCount = value; }
  public void setSyncError(String value) { syncError = value; }
}
