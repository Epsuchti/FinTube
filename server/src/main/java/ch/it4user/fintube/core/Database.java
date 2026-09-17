package ch.it4user.fintube.core;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/** Small explicit H2 store: Liquibase owns the schema; user-owned rows are always queried with user_id. */
@Component public class Database {
  @Value("${fintube.data-dir}") String dataDir;
  @Value("${fintube.settings-key:}") String configuredSettingsKey;
  public Path root, usersRoot, cacheRoot;
  private String url;
  private byte[] settingsKey;
  private static final String ENCRYPTED_PREFIX = "enc:v1:";
  private static final SecureRandom RANDOM = new SecureRandom();
  @PostConstruct public void init() throws Exception {
    root=Paths.get(dataDir).toAbsolutePath().normalize(); usersRoot=root.resolve("users"); cacheRoot=root.resolve("cache");
    Files.createDirectories(usersRoot); Files.createDirectories(cacheRoot); url="jdbc:h2:file:"+root.resolve("fintube-h2")+";AUTO_SERVER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=KEY,VALUE";
    settingsKey = loadSettingsKey();
    try (Connection c=open()) {
      liquibase.database.Database database=DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      new Liquibase("db/changelog/db.changelog-master.yaml",new ClassLoaderResourceAccessor(),database).update(new Contexts(),new LabelExpression());
    }
  }
  public Connection open() throws SQLException {
    return DriverManager.getConnection(url);
  }
  public static String now(){return java.time.Instant.now().toString();}

  /**
   * Return settings for trusted backend consumers. Secret values are decrypted
   * only when requested unmasked; admin/API responses use the masked path.
   */
  public Map<String,String> settings(boolean mask) throws SQLException {
    Map<String,String> m=new LinkedHashMap<>();
    try(Connection c=open(); PreparedStatement p=c.prepareStatement("SELECT key,value,secret FROM settings ORDER BY key"); ResultSet r=p.executeQuery()) {
      while(r.next()) {
        String key = r.getString(1);
        boolean secret = r.getInt(3)==1 || SettingsPolicy.isSecret(key);
        if (mask && secret) m.put(key, SettingsPolicy.MASK);
        else m.put(key, secret ? decrypt(r.getString(2)) : r.getString(2));
      }
    }
    return m;
  }

  /** Persist one administrator-controlled setting, encrypting secrets first. */
  public void saveSetting(String key, String value, boolean secret) throws SQLException {
    String stored = secret ? encrypt(value) : value;
    try(Connection c=open(); PreparedStatement p=c.prepareStatement(
        "MERGE INTO settings(key,value,secret,updated_at) KEY(key) VALUES(?,?,?,?)")) {
      p.setString(1,key); p.setString(2,stored); p.setInt(3,secret?1:0); p.setString(4,now()); p.executeUpdate();
    }
  }

  private byte[] loadSettingsKey() throws Exception {
    String configured = configuredSettingsKey == null ? "" : configuredSettingsKey.trim();
    if (!configured.isBlank()) {
      // A configured passphrase is never stored. Derive a fixed AES-256 key
      // from it so deployments can rotate keys deliberately through config.
      return MessageDigest.getInstance("SHA-256").digest(configured.getBytes(StandardCharsets.UTF_8));
    }
    Path keyFile = root.resolve("settings.key");
    if (Files.exists(keyFile)) {
      byte[] existing = Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.US_ASCII).trim());
      if (existing.length != 32) throw new IllegalStateException("invalid settings key file");
      return existing;
    }
    byte[] generated = new byte[32]; RANDOM.nextBytes(generated);
    try {
      Files.writeString(keyFile, Base64.getEncoder().encodeToString(generated)+"\n", StandardCharsets.US_ASCII,
          StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    } catch (FileAlreadyExistsException e) {
      byte[] existing = Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.US_ASCII).trim());
      if (existing.length != 32) throw new IllegalStateException("invalid settings key file");
      return existing;
    }
    try { Files.setPosixFilePermissions(keyFile, Set.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
    catch (UnsupportedOperationException | IOException ignored) { }
    return generated;
  }

  private String encrypt(String plain) throws SQLException {
    if (plain == null) return null;
    try {
      byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(settingsKey,"AES"), new GCMParameterSpec(128,nonce));
      byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
      byte[] combined = new byte[nonce.length + encrypted.length];
      System.arraycopy(nonce,0,combined,0,nonce.length); System.arraycopy(encrypted,0,combined,nonce.length,encrypted.length);
      return ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(combined);
    } catch (Exception e) { throw new SQLException("could not encrypt setting", e); }
  }

  private String decrypt(String stored) throws SQLException {
    if (stored == null || !stored.startsWith(ENCRYPTED_PREFIX)) return stored; // legacy row before migration
    try {
      byte[] combined = Base64.getDecoder().decode(stored.substring(ENCRYPTED_PREFIX.length()));
      if (combined.length < 13) throw new IllegalArgumentException("short ciphertext");
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(settingsKey,"AES"), new GCMParameterSpec(128,Arrays.copyOf(combined,12)));
      return new String(cipher.doFinal(Arrays.copyOfRange(combined,12,combined.length)),StandardCharsets.UTF_8);
    } catch (Exception e) { throw new SQLException("could not decrypt setting", e); }
  }
}
