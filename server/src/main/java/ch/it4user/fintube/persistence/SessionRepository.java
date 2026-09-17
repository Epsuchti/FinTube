package ch.it4user.fintube.persistence;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionRepository extends JpaRepository<SessionEntity, String> {
  Optional<SessionEntity> findByTokenAndExpiresAtAfter(String token, String now);
  void deleteByToken(String token);
}
