package ch.it4user.fintube.persistence.repositories;

import ch.it4user.fintube.persistence.entities.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CacheEntryRepository extends JpaRepository<CacheEntryEntity, String> {
    @Modifying
    @Transactional
    @Query("delete from CacheEntryEntity entry where entry.videoId = :videoId and entry.activeReaders = 0 and entry.activeWriters = 0")
    int deleteInactiveByVideoId(@Param("videoId") String videoId);

    @Modifying
    @Transactional
    @Query("update CacheEntryEntity entry set entry.lastAccessedAt = :lastAccessedAt where entry.videoId = :videoId")
    int touch(@Param("videoId") String videoId, @Param("lastAccessedAt") String lastAccessedAt);

    @Modifying
    @Transactional
    @Query("""
            update CacheEntryEntity entry
               set entry.status = 'COMPLETE',
                   entry.completedAt = :completedAt,
                   entry.lastAccessedAt = :lastAccessedAt
             where entry.videoId = :videoId
            """)
    int markComplete(@Param("videoId") String videoId,
                     @Param("completedAt") String completedAt,
                     @Param("lastAccessedAt") String lastAccessedAt);
}
