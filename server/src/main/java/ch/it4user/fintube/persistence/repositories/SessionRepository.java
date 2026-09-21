package ch.it4user.fintube.persistence.repositories;

import ch.it4user.fintube.persistence.entities.*;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionRepository extends JpaRepository<SessionEntity, String> {
  Optional<SessionEntity> findByTokenAndExpiresAtAfter(String token, String now);
  void deleteByToken(String token);
  @Modifying
  @Query("delete from SessionEntity session where session.user.id = :userId")
  void deleteAllByUserId(@Param("userId") long userId);
}
