package ch.it4user.fintube.core;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import ch.it4user.fintube.persistence.entities.SettingEntity;
import ch.it4user.fintube.persistence.repositories.SettingRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns administrator settings and the at-rest encryption of secret values.
 * Persistence access is deliberately kept behind the Spring Data repository.
 */
@Service
public class SettingsService {
    private static final String ENCRYPTED_PREFIX = "enc:v1:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SettingRepository settings;
    private final ApplicationPaths paths;
    private final String configuredSettingsKey;
    private byte[] settingsKey;

    public SettingsService(
            SettingRepository settings,
            ApplicationPaths paths,
            @Value("${fintube.settings-key:}") String configuredSettingsKey) {
        this.settings = settings;
        this.paths = paths;
        this.configuredSettingsKey = configuredSettingsKey == null ? "" : configuredSettingsKey;
    }

    @PostConstruct
    void initialize() throws Exception {
        settingsKey = loadSettingsKey();
    }

    /** Return settings for trusted backend consumers, masking secrets when requested. */
    @Transactional(readOnly = true)
    public Map<String, String> values(boolean mask) {
        Map<String, String> result = new LinkedHashMap<>();
        for (SettingEntity setting : settings.findAllByOrderByKeyAsc()) {
            boolean secret = setting.getSecret() != 0 || SettingsPolicy.isSecret(setting.getKey());
            if (mask && secret) {
                result.put(setting.getKey(), SettingsPolicy.MASK);
            } else {
                result.put(setting.getKey(), secret ? reveal(setting.getValue()) : setting.getValue());
            }
        }
        return result;
    }

    public String value(String key) {
        return values(false).get(key);
    }

    /** Persist one administrator-controlled setting, encrypting secrets first. */
    @Transactional
    public void save(String key, String value, boolean secret) {
        SettingEntity setting = settings.findByKey(key)
                .orElseGet(() -> new SettingEntity(key, value, secret ? 1 : 0, ApplicationClock.now()));
        setting.setValue(secret ? protect(value) : value);
        setting.setSecret(secret ? 1 : 0);
        setting.setUpdatedAt(ApplicationClock.now());
        settings.save(setting);
    }

    private byte[] loadSettingsKey() throws Exception {
        String configured = configuredSettingsKey.trim();
        if (!configured.isBlank()) {
            // A configured passphrase is never stored. Derive a fixed AES-256 key
            // from it so deployments can rotate keys deliberately through config.
            return MessageDigest.getInstance("SHA-256")
                    .digest(configured.getBytes(StandardCharsets.UTF_8));
        }

        Path keyFile = paths.root.resolve("settings.key");
        if (Files.exists(keyFile)) return readKey(keyFile);

        byte[] generated = new byte[32];
        RANDOM.nextBytes(generated);
        try {
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(generated) + "\n",
                    StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException ignored) {
            return readKey(keyFile);
        }
        try {
            Files.setPosixFilePermissions(keyFile,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows and other filesystems may not expose POSIX permissions.
        }
        return generated;
    }

    private static byte[] readKey(Path keyFile) throws IOException {
        byte[] existing = Base64.getDecoder()
                .decode(Files.readString(keyFile, StandardCharsets.US_ASCII).trim());
        if (existing.length != 32) throw new IllegalStateException("invalid settings key file");
        return existing;
    }

    public String protect(String plain) {
        if (plain == null) return null;
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(settingsKey, "AES"),
                    new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(encrypted, 0, combined, nonce.length, encrypted.length);
            return ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("could not encrypt setting", e);
        }
    }

    public String reveal(String stored) {
        if (stored == null || !stored.startsWith(ENCRYPTED_PREFIX)) return stored;
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(ENCRYPTED_PREFIX.length()));
            if (combined.length < 13) throw new IllegalArgumentException("short ciphertext");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(settingsKey, "AES"),
                    new GCMParameterSpec(128, Arrays.copyOf(combined, 12)));
            return new String(cipher.doFinal(Arrays.copyOfRange(combined, 12, combined.length)),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("could not decrypt setting", e);
        }
    }
}
