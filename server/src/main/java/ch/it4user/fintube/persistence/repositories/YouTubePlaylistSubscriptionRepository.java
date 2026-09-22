package ch.it4user.fintube.persistence.repositories;

import ch.it4user.fintube.persistence.entities.YouTubePlaylistSubscriptionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface YouTubePlaylistSubscriptionRepository extends JpaRepository<YouTubePlaylistSubscriptionEntity, Long> {
  List<YouTubePlaylistSubscriptionEntity> findByUserIdOrderByName(Long userId);
  List<YouTubePlaylistSubscriptionEntity> findByUserIdAndEnabled(Long userId, int enabled);
  Optional<YouTubePlaylistSubscriptionEntity> findByIdAndUserId(Long id, Long userId);
  Optional<YouTubePlaylistSubscriptionEntity> findByUserIdAndPlaylistId(Long userId, String playlistId);
  @Query("select p from YouTubePlaylistSubscriptionEntity p where p.enabled = 1 and (p.lastCheckedAt is null or p.lastCheckedAt < :cutoff)")
  List<YouTubePlaylistSubscriptionEntity> findDue(@Param("cutoff") String cutoff);
}
