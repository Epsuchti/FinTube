package ch.it4user.fintube.persistence.repositories;

import ch.it4user.fintube.persistence.entities.*;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SetupStateRepository extends JpaRepository<SetupStateEntity, String> {
}
