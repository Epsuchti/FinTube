package ch.it4user.fintube.persistence.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/** Persisted yt-dlp source selection and its segment timeline. */
@Entity
@Table(name = "media_sources")
public class MediaSourceEntity {
    @Id
    @Column(name = "video_id", length = 255, nullable = false)
    private String videoId;

    @Column(name = "format_key", length = 255, nullable = false)
    private String formatKey;

    @Lob
    @Column(name = "source_json", nullable = false)
    private String sourceJson;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    @Column(name = "expires_at", length = 64)
    private String expiresAt;

    @Column(name = "updated_at", nullable = false, length = 64)
    private String updatedAt;

    protected MediaSourceEntity() {
    }

    public MediaSourceEntity(String videoId, String formatKey, String sourceJson, int durationSeconds,
                             String expiresAt, String updatedAt) {
        this.videoId = videoId;
        this.formatKey = formatKey;
        this.sourceJson = sourceJson;
        this.durationSeconds = durationSeconds;
        this.expiresAt = expiresAt;
        this.updatedAt = updatedAt;
    }

    public String getVideoId() {
        return videoId;
    }

    public String getFormatKey() {
        return formatKey;
    }

    public String getSourceJson() {
        return sourceJson;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setFormatKey(String formatKey) {
        this.formatKey = formatKey;
    }

    public void setSourceJson(String sourceJson) {
        this.sourceJson = sourceJson;
    }

    public void setDurationSeconds(int durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public void setExpiresAt(String expiresAt) {
        this.expiresAt = expiresAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
