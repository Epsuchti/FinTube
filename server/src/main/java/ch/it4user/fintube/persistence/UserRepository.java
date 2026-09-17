package ch.it4user.fintube.persistence;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
  Optional<UserEntity> findByUsername(String username);
  Optional<UserEntity> findByEmail(String email);
  boolean existsByUsername(String username);
  boolean existsByFilesystemSlug(String filesystemSlug);
  List<UserEntity> findAllByRoleOrderByUsernameAsc(String role);
}
