package ch.it4user.fintube.persistence.repositories;

import ch.it4user.fintube.persistence.entities.*;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserVideoRepository extends JpaRepository<UserVideoEntity, UserVideoId> {
  List<UserVideoEntity> findByIdUserIdAndIdVideoIdIn(Long userId, Collection<String> videoIds);

  long deleteByIdUserIdAndIdVideoIdIn(Long userId, Collection<String> videoIds);

  Optional<UserVideoEntity> findByIdUserIdAndIdVideoId(Long userId, String videoId);

  boolean existsByIdVideoIdAndPlaybackToken(String videoId, String playbackToken);
}
