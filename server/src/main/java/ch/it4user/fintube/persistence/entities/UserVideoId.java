package ch.it4user.fintube.persistence.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/** Composite key for the per-user link to a canonical video. */
@Embeddable
public class UserVideoId implements Serializable {
  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "video_id", nullable = false, length = 255)
  private String videoId;

  protected UserVideoId() {
  }

  public UserVideoId(Long userId, String videoId) {
    this.userId = userId;
    this.videoId = videoId;
  }

  public Long getUserId() {
    return userId;
  }

  public String getVideoId() {
    return videoId;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof UserVideoId that)) return false;
    return Objects.equals(userId, that.userId) && Objects.equals(videoId, that.videoId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(userId, videoId);
  }
}
