package ch.it4user.fintube.persistence.repositories;

import ch.it4user.fintube.persistence.entities.PasswordResetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordResetRepository extends JpaRepository<PasswordResetEntity, Long> {}
