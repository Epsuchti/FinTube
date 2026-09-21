package ch.it4user.fintube.persistence.repositories;

import ch.it4user.fintube.persistence.entities.UserVideoId;
import ch.it4user.fintube.persistence.entities.WatchedVideoEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchedVideoRepository extends JpaRepository<WatchedVideoEntity, UserVideoId> {
  boolean existsByIdUserIdAndIdVideoId(Long userId, String videoId);

  List<WatchedVideoEntity> findByYoutubeMarkedAtIsNullOrderByWatchedAtAsc(Pageable pageable);

  List<WatchedVideoEntity> findByChannelIdAndIdUserIdIn(String channelId, Collection<Long> userIds);
}
