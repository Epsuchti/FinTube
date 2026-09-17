package ch.it4user.fintube.persistence.repositories;

import ch.it4user.fintube.persistence.entities.*;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface JobRepository extends JpaRepository<JobEntity, String> {
    @Transactional(readOnly = true)
    List<JobEntity> findByTypeAndStatusIn(String type, Collection<String> statuses);

    @Modifying
    @Transactional
    @Query("""
            update JobEntity job
               set job.status = :status,
                   job.error = :error,
                   job.completedFragments = :completedFragments,
                   job.totalFragments = :totalFragments,
                   job.updatedAt = :now,
                   job.startedAt = coalesce(job.startedAt, :now),
                   job.completedAt = :completedAt
             where job.id = :id
            """)
    int updateProgress(@Param("id") String id,
                       @Param("status") String status,
                       @Param("error") String error,
                       @Param("completedFragments") int completedFragments,
                       @Param("totalFragments") int totalFragments,
                       @Param("now") String now,
                       @Param("completedAt") String completedAt);

    @Modifying
    @Transactional
    @Query("""
            update JobEntity job
               set job.cancelRequested = 1,
                   job.status = 'CANCELLED',
                   job.updatedAt = :now,
                   job.completedAt = :now
             where job.id = :id
               and job.type = 'BACKGROUND_FILL'
               and job.status in ('QUEUED', 'RUNNING')
            """)
    int cancelBackgroundFill(@Param("id") String id, @Param("now") String now);
}
