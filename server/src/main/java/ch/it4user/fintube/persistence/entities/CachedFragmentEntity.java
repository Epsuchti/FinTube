package ch.it4user.fintube.persistence.entities;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** A published file for one logical media fragment. */
@Entity
@Table(name = "cached_fragments")
public class CachedFragmentEntity {
    @EmbeddedId
    private CachedFragmentId id;

    @Column(nullable = false, length = 4096)
    private String path;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private int completed;

    @Column(name = "last_accessed_at", nullable = false, length = 64)
    private String lastAccessedAt;

    protected CachedFragmentEntity() {
    }

    public CachedFragmentEntity(CachedFragmentId id, String path, long sizeBytes, int completed,
                                String lastAccessedAt) {
        this.id = id;
        this.path = path;
        this.sizeBytes = sizeBytes;
        this.completed = completed;
        this.lastAccessedAt = lastAccessedAt;
    }

    public CachedFragmentId getId() {
        return id;
    }

    public String getVideoId() {
        return id.getVideoId();
    }

    public String getFormatKey() {
        return id.getFormatKey();
    }

    public String getFragmentId() {
        return id.getFragmentId();
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public int getCompleted() {
        return completed;
    }

    public void setCompleted(int completed) {
        this.completed = completed;
    }

    public String getLastAccessedAt() {
        return lastAccessedAt;
    }

    public void setLastAccessedAt(String lastAccessedAt) {
        this.lastAccessedAt = lastAccessedAt;
    }
}
