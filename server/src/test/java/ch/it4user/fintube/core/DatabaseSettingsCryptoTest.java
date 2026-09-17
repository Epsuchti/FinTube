package ch.it4user.fintube.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseSettingsCryptoTest {
  @TempDir Path temp;

  @Test
  void secretsAreEncryptedAndReadableAcrossDatabaseRecreation() throws Exception {
    Database first = database();
    first.saveSetting("youtube_api_key", "super-secret-api-key", true);

    try (var c = first.open(); var p = c.prepareStatement("SELECT value,secret FROM settings WHERE key='youtube_api_key'")) {
      var r = p.executeQuery();
      assertThat(r.next()).isTrue();
      assertThat(r.getString(1)).doesNotContain("super-secret-api-key").startsWith("enc:v1:");
      assertThat(r.getInt(2)).isEqualTo(1);
    }
    assertThat(first.settings(false).get("youtube_api_key")).isEqualTo("super-secret-api-key");
    assertThat(first.settings(true).get("youtube_api_key")).isEqualTo(SettingsPolicy.MASK);

    Database second = database();
    assertThat(second.settings(false).get("youtube_api_key")).isEqualTo("super-secret-api-key");
    assertThat(Files.getPosixFilePermissions(temp.resolve("settings.key")))
        .containsExactlyInAnyOrder(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
  }

  private Database database() throws Exception {
    Database db = new Database();
    Field dataDir = Database.class.getDeclaredField("dataDir");
    dataDir.setAccessible(true);
    dataDir.set(db, temp.toString());
    db.init();
    return db;
  }
}
