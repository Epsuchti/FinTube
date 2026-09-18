package ch.it4user.fintube.core;

import ch.it4user.fintube.persistence.entities.SettingEntity;
import ch.it4user.fintube.persistence.repositories.SettingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DatabaseSettingsCryptoTest {
  private static final Path DATA_DIR = dataDirectory();

  @Autowired SettingsService settings;
  @Autowired SettingRepository settingRepository;
  @Autowired ApplicationPaths paths;

  @DynamicPropertySource
  static void dataProperties(DynamicPropertyRegistry registry) {
    registry.add("fintube.data-dir", DATA_DIR::toString);
  }

  @Test
  void secretsAreEncryptedAndReadableAcrossDatabaseRecreation() throws Exception {
    settings.save("jellyfin_api_key", "super-secret-api-key", true);

    SettingEntity stored = settingRepository.findById("jellyfin_api_key").orElseThrow();
    assertThat(stored.getValue()).doesNotContain("super-secret-api-key").startsWith("enc:v1:");
    assertThat(stored.getSecret()).isEqualTo(1);
    assertThat(settings.values(false).get("jellyfin_api_key")).isEqualTo("super-secret-api-key");
    assertThat(settings.values(true).get("jellyfin_api_key")).isEqualTo(SettingsPolicy.MASK);

    SettingsService second = new SettingsService(settingRepository, paths, "");
    second.initialize();
    assertThat(second.values(false).get("jellyfin_api_key")).isEqualTo("super-secret-api-key");
    assertThat(Files.getPosixFilePermissions(DATA_DIR.resolve("settings.key")))
        .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
  }

  private static Path dataDirectory() {
    try { return Files.createTempDirectory("fintube-settings-test-"); }
    catch (Exception e) { throw new ExceptionInInitializerError(e); }
  }
}
