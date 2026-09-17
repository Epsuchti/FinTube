package ch.it4user.fintube.persistence.entities;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Per-user library metadata and playback credential for a canonical video. */
@Entity
@Table(name = "user_videos")
public class UserVideoEntity {
  @EmbeddedId
  private UserVideoId id;

  @Column(name = "library_path", nullable = false, length = 4096)
  private String libraryPath;

  @Column(name = "playback_token", nullable = false, unique = true, length = 255)
  private String playbackToken;

  @Column(name = "created_at", nullable = false, length = 64)
  private String createdAt;

  protected UserVideoEntity() {
  }

  public UserVideoEntity(UserVideoId id, String libraryPath, String playbackToken, String createdAt) {
    this.id = id;
    this.libraryPath = libraryPath;
    this.playbackToken = playbackToken;
    this.createdAt = createdAt;
  }

  public UserVideoEntity(Long userId, String videoId, String libraryPath,
                         String playbackToken, String createdAt) {
    this(new UserVideoId(userId, videoId), libraryPath, playbackToken, createdAt);
  }

  public UserVideoId getId() {
    return id;
  }

  public String getLibraryPath() {
    return libraryPath;
  }

  public String getPlaybackToken() {
    return playbackToken;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public void setLibraryPath(String libraryPath) {
    this.libraryPath = libraryPath;
  }
}
