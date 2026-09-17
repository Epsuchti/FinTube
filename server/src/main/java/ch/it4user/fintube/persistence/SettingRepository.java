package ch.it4user.fintube.persistence;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingRepository extends JpaRepository<SettingEntity, String> {
  List<SettingEntity> findAllByOrderByKeyAsc();
  Optional<SettingEntity> findByKey(String key);
}
