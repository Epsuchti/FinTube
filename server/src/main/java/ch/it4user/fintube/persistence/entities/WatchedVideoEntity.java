package ch.it4user.fintube.persistence.entities;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/** Prevents a watched per-user library item from being recreated by later channel syncs. */
@Entity
@Table(name = "watched_videos")
public class WatchedVideoEntity {
  @EmbeddedId
  private UserVideoId id;

  @Column(name = "watched_at", nullable = false, length = 64)
  private String watchedAt;

  @Column(name = "youtube_marked_at", length = 64)
  private String youtubeMarkedAt;

  @Lob
  @Column(name = "youtube_mark_error")
  private String youtubeMarkError;

  protected WatchedVideoEntity() { }

  public WatchedVideoEntity(UserVideoId id, String watchedAt) {
    this.id = id;
    this.watchedAt = watchedAt;
  }

  public UserVideoId getId() { return id; }
  public String getWatchedAt() { return watchedAt; }
  public String getYoutubeMarkedAt() { return youtubeMarkedAt; }
  public String getYoutubeMarkError() { return youtubeMarkError; }

  public void setYoutubeMarkedAt(String value) { youtubeMarkedAt = value; }
  public void setYoutubeMarkError(String value) { youtubeMarkError = value; }
}
