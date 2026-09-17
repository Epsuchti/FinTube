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

/** Small explicit SQLite store: all user-owned rows are always queried with user_id. */
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
    Files.createDirectories(usersRoot); Files.createDirectories(cacheRoot); url="jdbc:sqlite:"+root.resolve("fintube.db");
    settingsKey = loadSettingsKey();
    try (Connection c=open(); Statement s=c.createStatement()) { s.executeUpdate("PRAGMA foreign_keys=ON");
      s.executeUpdate("CREATE TABLE IF NOT EXISTS users(id INTEGER PRIMARY KEY,username TEXT NOT NULL UNIQUE,email TEXT UNIQUE,password_hash TEXT NOT NULL,role TEXT NOT NULL,filesystem_slug TEXT NOT NULL UNIQUE,created_at TEXT NOT NULL,updated_at TEXT NOT NULL)");
      s.executeUpdate("CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY,user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,expires_at TEXT NOT NULL)");
      s.executeUpdate("CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY,value TEXT NOT NULL,secret INTEGER NOT NULL DEFAULT 0,updated_at TEXT NOT NULL)");
      s.executeUpdate("CREATE TABLE IF NOT EXISTS youtube_channels(channel_id TEXT PRIMARY KEY,name TEXT NOT NULL,url TEXT,thumbnail_url TEXT,updated_at TEXT NOT NULL,last_sync_at TEXT,last_successful_sync_at TEXT,last_sync_published_at TEXT,sync_error TEXT)");
      s.executeUpdate("CREATE TABLE IF NOT EXISTS youtube_subscriptions(id INTEGER PRIMARY KEY,user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,channel_id TEXT NOT NULL REFERENCES youtube_channels(channel_id),enabled INTEGER NOT NULL DEFAULT 1,created_at TEXT NOT NULL,last_checked_at TEXT,last_successful_sync_at TEXT,UNIQUE(user_id,channel_id))");
      s.executeUpdate("CREATE TABLE IF NOT EXISTS videos(video_id TEXT PRIMARY KEY,channel_id TEXT NOT NULL REFERENCES youtube_channels(channel_id),title TEXT NOT NULL,description TEXT,published_at TEXT,duration_seconds INTEGER NOT NULL,is_short INTEGER NOT NULL DEFAULT 0,thumbnail_url TEXT,availability TEXT NOT NULL DEFAULT 'AVAILABLE',metadata_updated_at TEXT NOT NULL)");
      s.executeUpdate("CREATE TABLE IF NOT EXISTS user_videos(user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,video_id TEXT NOT NULL REFERENCES videos(video_id) ON DELETE CASCADE,library_path TEXT NOT NULL,playback_token TEXT NOT NULL UNIQUE,created_at TEXT NOT NULL,PRIMARY KEY(user_id,video_id))");
      s.executeUpdate("CREATE TABLE IF NOT EXISTS cache_entries(video_id TEXT PRIMARY KEY,format_key TEXT,status TEXT NOT NULL DEFAULT 'PARTIAL',last_accessed_at TEXT NOT NULL,active_readers INTEGER NOT NULL DEFAULT 0,active_writers INTEGER NOT NULL DEFAULT 0,completed_at TEXT)");
      s.executeUpdate("CREATE TABLE IF NOT EXISTS cached_fragments(video_id TEXT NOT NULL,format_key TEXT NOT NULL,fragment_id TEXT NOT NULL,path TEXT NOT NULL,size_bytes INTEGER NOT NULL,completed INTEGER NOT NULL DEFAULT 1,last_accessed_at TEXT NOT NULL,PRIMARY KEY(video_id,format_key,fragment_id))");
      s.executeUpdate("CREATE TABLE IF NOT EXISTS media_sources(video_id TEXT PRIMARY KEY,format_key TEXT NOT NULL,source_json TEXT NOT NULL,duration_seconds INTEGER NOT NULL DEFAULT 0,expires_at TEXT,updated_at TEXT NOT NULL)");
      s.executeUpdate("CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY,video_id TEXT NOT NULL,status TEXT NOT NULL,priority INTEGER NOT NULL,created_at TEXT NOT NULL,error TEXT,type TEXT NOT NULL DEFAULT 'BACKGROUND_FILL',requested_fragment TEXT,completed_fragments INTEGER NOT NULL DEFAULT 0,total_fragments INTEGER NOT NULL DEFAULT 0,cancel_requested INTEGER NOT NULL DEFAULT 0,started_at TEXT,updated_at TEXT,completed_at TEXT)");
      // Keep databases created by earlier application versions usable. SQLite has no
      // IF NOT EXISTS form for ADD COLUMN, so duplicate-column errors are expected.
      for(String alter: List.of(
          "ALTER TABLE youtube_channels ADD COLUMN last_sync_at TEXT",
          "ALTER TABLE youtube_channels ADD COLUMN last_successful_sync_at TEXT",
          "ALTER TABLE youtube_channels ADD COLUMN last_sync_published_at TEXT",
          "ALTER TABLE youtube_channels ADD COLUMN sync_error TEXT",
          "ALTER TABLE jobs ADD COLUMN type TEXT NOT NULL DEFAULT 'BACKGROUND_FILL'",
          "ALTER TABLE jobs ADD COLUMN requested_fragment TEXT",
          "ALTER TABLE jobs ADD COLUMN completed_fragments INTEGER NOT NULL DEFAULT 0",
          "ALTER TABLE jobs ADD COLUMN total_fragments INTEGER NOT NULL DEFAULT 0",
          "ALTER TABLE jobs ADD COLUMN cancel_requested INTEGER NOT NULL DEFAULT 0",
          "ALTER TABLE jobs ADD COLUMN started_at TEXT",
          "ALTER TABLE jobs ADD COLUMN updated_at TEXT",
          "ALTER TABLE jobs ADD COLUMN completed_at TEXT")) {
        try { s.executeUpdate(alter); } catch(SQLException ignored) { }
      }
      for(String[] d: List.of(new String[]{"stream_quality","720"},new String[]{"cache_retention_days","30"},new String[]{"cache_min_free_gb","20"},new String[]{"background_download_max_mbps","75"},new String[]{"cache_cleanup_interval_minutes","360"},new String[]{"initial_channel_import_count","20"},new String[]{"subscription_sync_minutes","60"},new String[]{"public_base_url","http://localhost:8080"},new String[]{"preferred_video_codecs","av1,vp9,h264"},new String[]{"preferred_audio_codecs","opus,aac"},new String[]{"yt_dlp_path","yt-dlp"},new String[]{"ffmpeg_path","ffmpeg"},new String[]{"jellyfin_enabled","true"},new String[]{"jellyfin_auto_refresh","true"},new String[]{"jellyfin_runtime_sync","true"},new String[]{"jellyfin_request_timeout_seconds","10"})) {
        try(PreparedStatement p=c.prepareStatement("INSERT OR IGNORE INTO settings(key,value,updated_at) VALUES(?,?,datetime('now'))")){p.setString(1,d[0]);p.setString(2,d[1]);p.executeUpdate();}
      }
      migratePlaintextSecrets(c);
    }
  }
  public Connection open() throws SQLException {
    Connection c = DriverManager.getConnection(url);
    try (Statement s = c.createStatement()) { s.execute("PRAGMA busy_timeout=5000"); } catch (SQLException ignored) { }
    return c;
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
        "INSERT INTO settings(key,value,secret,updated_at) VALUES(?,?,?,?) "+
            "ON CONFLICT(key) DO UPDATE SET value=excluded.value,secret=excluded.secret,updated_at=excluded.updated_at")) {
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

  private void migratePlaintextSecrets(Connection c) throws Exception {
    List<String[]> pending = new ArrayList<>();
    try (PreparedStatement q=c.prepareStatement("SELECT key,value FROM settings WHERE secret=1 OR key IN ('youtube_api_key','jellyfin_api_key','proxy_password','cookie_file')"); ResultSet r=q.executeQuery()) {
      while(r.next()) if (!r.getString(2).startsWith(ENCRYPTED_PREFIX)) pending.add(new String[]{r.getString(1),r.getString(2)});
    }
    for (String[] entry : pending) try (PreparedStatement u=c.prepareStatement("UPDATE settings SET value=?,updated_at=? WHERE key=?")) {
      u.setString(1,encrypt(entry[1])); u.setString(2,now()); u.setString(3,entry[0]); u.executeUpdate();
    }
    try (PreparedStatement u=c.prepareStatement("UPDATE settings SET secret=1 WHERE key IN ('youtube_api_key','jellyfin_api_key','proxy_password','cookie_file')")) { u.executeUpdate(); }
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
