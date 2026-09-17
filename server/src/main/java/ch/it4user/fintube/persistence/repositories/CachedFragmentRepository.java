package ch.it4user.fintube.persistence.repositories;

import ch.it4user.fintube.persistence.entities.*;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CachedFragmentRepository extends JpaRepository<CachedFragmentEntity, CachedFragmentId> {
    @Transactional(readOnly = true)
    @Query("select fragment from CachedFragmentEntity fragment where fragment.id.videoId = :videoId")
    List<CachedFragmentEntity> findByVideoId(@Param("videoId") String videoId);

    List<CachedFragmentEntity> findByIdVideoId(String videoId);

    @Modifying
    @Transactional
    @Query("delete from CachedFragmentEntity fragment where fragment.id.videoId = :videoId")
    int deleteByVideoId(@Param("videoId") String videoId);

    @Modifying
    @Transactional
    @Query("""
            update CachedFragmentEntity fragment
               set fragment.lastAccessedAt = :lastAccessedAt
             where fragment.id.videoId = :videoId
               and fragment.id.formatKey = :formatKey
               and fragment.id.fragmentId = :fragmentId
            """)
    int touch(@Param("videoId") String videoId,
              @Param("formatKey") String formatKey,
              @Param("fragmentId") String fragmentId,
              @Param("lastAccessedAt") String lastAccessedAt);

    @Transactional(readOnly = true)
    @Query("""
            select count(fragment)
              from CachedFragmentEntity fragment
             where fragment.id.videoId = :videoId
               and fragment.id.formatKey = :formatKey
               and fragment.completed = 1
            """)
    long countCompleted(@Param("videoId") String videoId, @Param("formatKey") String formatKey);

    @Modifying
    @Transactional
    @Query("""
            delete from CachedFragmentEntity fragment
             where fragment.id.videoId = :videoId
               and fragment.id.formatKey = :formatKey
               and fragment.id.fragmentId = :fragmentId
            """)
    int deleteFragment(@Param("videoId") String videoId,
                       @Param("formatKey") String formatKey,
                       @Param("fragmentId") String fragmentId);

    /**
     * Return old, currently unleased fragments in eviction order. The lease
     * predicate belongs here so maintenance never has to build SQL.
     */
    @Transactional(readOnly = true)
    @Query(value = """
            select f.*
              from cached_fragments f
             where f.last_accessed_at < :cutoff
               and not exists (
                     select 1
                       from cache_entries e
                      where e.video_id = f.video_id
                        and (e.active_readers > 0 or e.active_writers > 0)
                   )
             order by f.last_accessed_at asc
            """, nativeQuery = true)
    List<CachedFragmentEntity> findEvictableBefore(@Param("cutoff") String cutoff);

    /** Return all currently unleased fragments in oldest-first order. */
    @Transactional(readOnly = true)
    @Query(value = """
            select f.*
              from cached_fragments f
             where not exists (
                     select 1
                       from cache_entries e
                      where e.video_id = f.video_id
                        and (e.active_readers > 0 or e.active_writers > 0)
                   )
             order by f.last_accessed_at asc
            """, nativeQuery = true)
    List<CachedFragmentEntity> findEvictableAll();

    /** Recheck leases under the process eviction lock before deleting a row. */
    @Modifying
    @Transactional
    @Query(value = """
            delete from cached_fragments
             where video_id = :videoId
               and format_key = :formatKey
               and fragment_id = :fragmentId
               and not exists (
                     select 1
                       from cache_entries e
                      where e.video_id = :videoId
                        and (e.active_readers > 0 or e.active_writers > 0)
                   )
            """, nativeQuery = true)
    int deleteIfInactive(@Param("videoId") String videoId,
                         @Param("formatKey") String formatKey,
                         @Param("fragmentId") String fragmentId);
}
