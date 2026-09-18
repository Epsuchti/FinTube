package ch.it4user.fintube.persistence.repositories;

import ch.it4user.fintube.persistence.entities.*;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Read-only projections for a user's library. Videos and user_videos remain
 * owned by the integration persistence layer; this repository is the query
 * boundary used by the application service.
 */
public interface LibraryVideoRepository extends Repository<VideoEntity, String> {
    interface VideoView {
        String getVideoId();
        String getTitle();
        String getDescription();
        String getPublishedAt();
        Integer getDurationSeconds();
        String getChannel();
        String getLibraryPath();
        String getCacheStatus();
        String getCacheLastAccessedAt();
        Long getCacheBytes();
        Integer getCachedFragments();
        Integer getDownloaded();
    }

    @Query(value = """
            SELECT v.video_id AS videoId,
                   v.title AS title,
                   CAST(v.description AS VARCHAR) AS description,
                   v.published_at AS publishedAt,
                   v.duration_seconds AS durationSeconds,
                   c.name AS channel,
                   uv.library_path AS libraryPath,
                   COALESCE(e.status, 'NOT_CACHED') AS cacheStatus,
                   e.last_accessed_at AS cacheLastAccessedAt,
                   COALESCE((SELECT SUM(f.size_bytes) FROM cached_fragments f
                              WHERE f.video_id = v.video_id), 0) AS cacheBytes,
                   COALESCE((SELECT COUNT(*) FROM cached_fragments f
                              WHERE f.video_id = v.video_id AND f.completed = 1), 0) AS cachedFragments,
                   CASE WHEN e.status = 'COMPLETE' THEN 1 ELSE 0 END AS downloaded
              FROM user_videos uv
              JOIN videos v ON v.video_id = uv.video_id
              JOIN youtube_channels c ON c.channel_id = v.channel_id
              LEFT JOIN cache_entries e ON e.video_id = v.video_id
             WHERE uv.user_id = :userId
             ORDER BY v.published_at DESC
            """, nativeQuery = true)
    List<VideoView> findForUser(@Param("userId") long userId);
}
