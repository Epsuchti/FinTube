package ch.it4user.fintube.persistence.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/** Canonical YouTube metadata shared by all subscribers of a channel. */
@Entity
@Table(name = "videos")
public class VideoEntity {
  @Id
  @Column(name = "video_id", length = 255)
  private String videoId;

  @Column(name = "channel_id", nullable = false, length = 255)
  private String channelId;

  @Column(nullable = false, length = 1024)
  private String title;

  @Lob
  @Column(name = "description")
  private String description;

  @Column(name = "published_at", length = 64)
  private String publishedAt;

  @Column(name = "duration_seconds", nullable = false)
  private int durationSeconds;

  // Liquibase stores this legacy flag as an integer rather than a SQL boolean.
  @Column(name = "is_short", nullable = false)
  private int isShort;

  @Column(name = "is_live_stream", nullable = false)
  private int isLiveStream;

  @Column(name = "thumbnail_url", length = 4096)
  private String thumbnailUrl;

  @Column(nullable = false, length = 32)
  private String availability;

  @Column(name = "metadata_updated_at", nullable = false, length = 64)
  private String metadataUpdatedAt;

  protected VideoEntity() {
  }

  public VideoEntity(String videoId, String channelId, String title, String description,
                     String publishedAt, int durationSeconds, int isShort, String thumbnailUrl,
                     String availability, String metadataUpdatedAt) {
    this(videoId, channelId, title, description, publishedAt, durationSeconds, isShort, 0,
        thumbnailUrl, availability, metadataUpdatedAt);
  }

  public VideoEntity(String videoId, String channelId, String title, String description,
                     String publishedAt, int durationSeconds, int isShort, int isLiveStream,
                     String thumbnailUrl, String availability, String metadataUpdatedAt) {
    this.videoId = videoId;
    this.channelId = channelId;
    this.title = title;
    this.description = description;
    this.publishedAt = publishedAt;
    this.durationSeconds = durationSeconds;
    this.isShort = isShort;
    this.isLiveStream = isLiveStream;
    this.thumbnailUrl = thumbnailUrl;
    this.availability = availability;
    this.metadataUpdatedAt = metadataUpdatedAt;
  }

  public String getVideoId() {
    return videoId;
  }

  public String getChannelId() {
    return channelId;
  }

  public String getTitle() {
    return title;
  }

  public String getDescription() {
    return description;
  }

  public String getPublishedAt() {
    return publishedAt;
  }

  public int getDurationSeconds() {
    return durationSeconds;
  }

  public int getIsShort() {
    return isShort;
  }

  public int getIsLiveStream() {
    return isLiveStream;
  }

  public String getThumbnailUrl() {
    return thumbnailUrl;
  }

  public String getAvailability() {
    return availability;
  }

  public String getMetadataUpdatedAt() {
    return metadataUpdatedAt;
  }

  public void setChannelId(String channelId) {
    this.channelId = channelId;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public void setPublishedAt(String publishedAt) {
    this.publishedAt = publishedAt;
  }

  public void setDurationSeconds(int durationSeconds) {
    this.durationSeconds = durationSeconds;
  }

  public void setIsShort(int isShort) {
    this.isShort = isShort;
  }

  public void setIsLiveStream(int isLiveStream) {
    this.isLiveStream = isLiveStream;
  }

  public void setThumbnailUrl(String thumbnailUrl) {
    this.thumbnailUrl = thumbnailUrl;
  }

  public void setAvailability(String availability) {
    this.availability = availability;
  }

  public void setMetadataUpdatedAt(String metadataUpdatedAt) {
    this.metadataUpdatedAt = metadataUpdatedAt;
  }
}
