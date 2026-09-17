package ch.it4user.fintube.persistence.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/** Composite key used by the cached_fragments table. */
@Embeddable
public class CachedFragmentId implements Serializable {
    @Column(name = "video_id", length = 255, nullable = false)
    private String videoId;

    @Column(name = "format_key", length = 255, nullable = false)
    private String formatKey;

    @Column(name = "fragment_id", length = 255, nullable = false)
    private String fragmentId;

    protected CachedFragmentId() {
    }

    public CachedFragmentId(String videoId, String formatKey, String fragmentId) {
        this.videoId = videoId;
        this.formatKey = formatKey;
        this.fragmentId = fragmentId;
    }

    public String getVideoId() {
        return videoId;
    }

    public String getFormatKey() {
        return formatKey;
    }

    public String getFragmentId() {
        return fragmentId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CachedFragmentId that)) return false;
        return Objects.equals(videoId, that.videoId)
                && Objects.equals(formatKey, that.formatKey)
                && Objects.equals(fragmentId, that.fragmentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(videoId, formatKey, fragmentId);
    }
}
