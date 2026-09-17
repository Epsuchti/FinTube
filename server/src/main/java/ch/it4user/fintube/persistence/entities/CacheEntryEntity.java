package ch.it4user.fintube.persistence.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One aggregate cache entry for a video and its currently selected format. */
@Entity
@Table(name = "cache_entries")
public class CacheEntryEntity {
    @Id
    @Column(name = "video_id", length = 255, nullable = false)
    private String videoId;

    @Column(name = "format_key", length = 255)
    private String formatKey;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "last_accessed_at", nullable = false, length = 64)
    private String lastAccessedAt;

    @Column(name = "active_readers", nullable = false)
    private int activeReaders;

    @Column(name = "active_writers", nullable = false)
    private int activeWriters;

    @Column(name = "completed_at", length = 64)
    private String completedAt;

    protected CacheEntryEntity() {
    }

    public CacheEntryEntity(String videoId, String formatKey, String status, String lastAccessedAt,
                            int activeReaders, int activeWriters, String completedAt) {
        this.videoId = videoId;
        this.formatKey = formatKey;
        this.status = status;
        this.lastAccessedAt = lastAccessedAt;
        this.activeReaders = activeReaders;
        this.activeWriters = activeWriters;
        this.completedAt = completedAt;
    }

    public String getVideoId() {
        return videoId;
    }

    public String getFormatKey() {
        return formatKey;
    }

    public void setFormatKey(String formatKey) {
        this.formatKey = formatKey;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLastAccessedAt() {
        return lastAccessedAt;
    }

    public void setLastAccessedAt(String lastAccessedAt) {
        this.lastAccessedAt = lastAccessedAt;
    }

    public int getActiveReaders() {
        return activeReaders;
    }

    public void setActiveReaders(int activeReaders) {
        this.activeReaders = activeReaders;
    }

    public int getActiveWriters() {
        return activeWriters;
    }

    public void setActiveWriters(int activeWriters) {
        this.activeWriters = activeWriters;
    }

    public String getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(String completedAt) {
        this.completedAt = completedAt;
    }
}
