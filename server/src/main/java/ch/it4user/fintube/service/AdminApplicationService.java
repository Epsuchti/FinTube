package ch.it4user.fintube.service;

import ch.it4user.fintube.api.contract.model.AdminUser;
import ch.it4user.fintube.api.contract.model.CacheEntry;
import ch.it4user.fintube.api.contract.model.Job;
import ch.it4user.fintube.api.contract.model.OperationResult;
import ch.it4user.fintube.api.contract.model.Role;
import ch.it4user.fintube.api.contract.model.UpdateRoleRequest;
import ch.it4user.fintube.core.AuditLogger;
import ch.it4user.fintube.core.Database;
import ch.it4user.fintube.core.SettingsPolicy;
import ch.it4user.fintube.integration.JellyfinSyncService;
import ch.it4user.fintube.media.BackgroundFillService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

@Service
public class AdminApplicationService {
    private final Database db;
    private final AuthorizationService authorization;
    private final BackgroundFillService filler;
    private final JellyfinSyncService jellyfin;
    private final AuditLogger audit;

    public AdminApplicationService(Database db, AuthorizationService authorization, BackgroundFillService filler, JellyfinSyncService jellyfin, AuditLogger audit) {
        this.db = db;
        this.authorization = authorization;
        this.filler = filler;
        this.jellyfin = jellyfin;
        this.audit = audit;
    }

    public Map<String, String> settings(HttpServletRequest request) {
        return database(() -> {
            authorization.requireAdmin(request);
            return db.settings(true);
        });
    }

    public void updateSettings(HttpServletRequest request, Map<String, String> values) {
        database(() -> {
            AuthService.Principal admin = authorization.requireAdmin(request);
            try {
                SettingsPolicy.validate(values);
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
            }
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (entry.getValue() == null || entry.getValue().isBlank() || SettingsPolicy.MASK.equals(entry.getValue())) continue;
                db.saveSetting(entry.getKey(), entry.getValue(), SettingsPolicy.isSecret(entry.getKey()));
            }
            audit.event("ADMIN_SETTING_CHANGED", Map.of("userId", admin.id(), "settingCount", values.size()));
            return null;
        });
    }

    public List<AdminUser> users(HttpServletRequest request) {
        return database(() -> {
            authorization.requireAdmin(request);
            return rows("SELECT id,username,email,role,filesystem_slug,created_at FROM users ORDER BY username").stream().map(this::user).toList();
        });
    }

    public void updateUserRole(HttpServletRequest request, long id, UpdateRoleRequest requestBody) {
        database(() -> {
            AuthService.Principal admin = authorization.requireAdmin(request);
            String role = requestBody.getRole() == null ? null : requestBody.getRole().getValue();
            if (!java.util.Set.of("USER", "ADMIN").contains(role)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            try (Connection connection = db.open(); PreparedStatement statement = connection.prepareStatement("UPDATE users SET role=?,updated_at=? WHERE id=?")) {
                statement.setString(1, role);
                statement.setString(2, Database.now());
                statement.setLong(3, id);
                if (statement.executeUpdate() == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            audit.event("ADMIN_USER_ROLE_CHANGED", Map.of("adminId", admin.id(), "targetUserId", id, "role", role));
            return null;
        });
    }

    public List<CacheEntry> cache(HttpServletRequest request) {
        return database(() -> {
            authorization.requireAdmin(request);
            return rows("SELECT video_id,format_key,status,last_accessed_at,active_readers,active_writers FROM cache_entries ORDER BY last_accessed_at").stream().map(this::cacheEntry).toList();
        });
    }

    public void deleteCache(HttpServletRequest request, String video) {
        database(() -> {
            AuthService.Principal admin = authorization.requireAdmin(request);
            try (Connection connection = db.open(); PreparedStatement query = connection.prepareStatement("SELECT path FROM cached_fragments WHERE video_id=?")) {
                query.setString(1, video);
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) Files.deleteIfExists(Path.of(result.getString(1)));
                }
                try (PreparedStatement fragments = connection.prepareStatement("DELETE FROM cached_fragments WHERE video_id=?")) {
                    fragments.setString(1, video);
                    fragments.executeUpdate();
                }
                try (PreparedStatement entry = connection.prepareStatement("DELETE FROM cache_entries WHERE video_id=? AND active_readers=0 AND active_writers=0")) {
                    entry.setString(1, video);
                    entry.executeUpdate();
                }
            }
            audit.event("CACHE_ENTRY_DELETED", Map.of("adminId", admin.id(), "videoId", video));
            return null;
        });
    }

    public List<Job> jobs(HttpServletRequest request) {
        return database(() -> {
            authorization.requireAdmin(request);
            return rows("SELECT id,video_id,status,priority,created_at,error FROM jobs ORDER BY created_at DESC").stream().map(this::job).toList();
        });
    }

    public void cancelJob(HttpServletRequest request, String id) {
        database(() -> {
            AuthService.Principal admin = authorization.requireAdmin(request);
            if (!filler.cancel(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            audit.event("BACKGROUND_JOB_CANCELLED", Map.of("adminId", admin.id(), "jobId", id));
            return null;
        });
    }

    public Map<String, Object> jellyfinStatus(HttpServletRequest request) {
        return database(() -> {
            AuthService.Principal admin = authorization.requireAdmin(request);
            Map<String, Object> result = jellyfin.adminStatus();
            audit.event("JELLYFIN_STATUS_CHECKED", Map.of("adminId", admin.id(), "reachable", result.getOrDefault("reachable", false)));
            return result;
        });
    }

    public Map<String, Object> validateJellyfin(HttpServletRequest request) {
        return database(() -> {
            AuthService.Principal admin = authorization.requireAdmin(request);
            Map<String, Object> result = jellyfin.adminStatus();
            audit.event("JELLYFIN_VALIDATED", Map.of("adminId", admin.id(), "reachable", result.getOrDefault("reachable", false)));
            return result;
        });
    }

    public OperationResult refreshJellyfin(HttpServletRequest request) {
        return database(() -> {
            AuthService.Principal admin = authorization.requireAdmin(request);
            Map<String, Object> result = jellyfin.refreshNow();
            audit.event("JELLYFIN_REFRESH_REQUESTED", Map.of("adminId", admin.id(), "success", result.getOrDefault("success", false)));
            return new OperationResult().success(booleanValue(result.get("success"))).message(text(result, "message"));
        });
    }

    private AdminUser user(Map<String, Object> value) {
        return new AdminUser(number(value, "id"), text(value, "username"), Role.fromValue(text(value, "role")), text(value, "filesystem_slug"), text(value, "created_at")).email(text(value, "email"));
    }

    private CacheEntry cacheEntry(Map<String, Object> value) {
        return new CacheEntry(text(value, "video_id"), text(value, "status"))
                .formatKey(text(value, "format_key"))
                .lastAccessedAt(text(value, "last_accessed_at"))
                .activeReaders(integer(value, "active_readers"))
                .activeWriters(integer(value, "active_writers"));
    }

    private Job job(Map<String, Object> value) {
        return new Job(text(value, "id"), text(value, "video_id"), text(value, "status"), integer(value, "priority"), text(value, "created_at"))
                .error(text(value, "error"));
    }

    private List<Map<String, Object>> rows(String sql, Object... args) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection connection = db.open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            try (ResultSet rows = statement.executeQuery()) {
                var metadata = rows.getMetaData();
                while (rows.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) row.put(metadata.getColumnLabel(i).toLowerCase(Locale.ROOT), rows.getObject(i));
                    result.add(row);
                }
            }
        }
        return result;
    }

    private <T> T database(Callable<T> operation) {
        try {
            return operation.call();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "administration operation could not be completed", e);
        }
    }

    private static long number(Map<String, Object> value, String key) { Object item = value.get(key); return item instanceof Number n ? n.longValue() : Long.parseLong(item.toString()); }
    private static Integer integer(Map<String, Object> value, String key) { Object item = value.get(key); return item instanceof Number n ? n.intValue() : item == null ? null : Integer.valueOf(item.toString()); }
    private static String text(Map<String, Object> value, String key) { Object item = value.get(key); return item == null ? null : item.toString(); }
    private static Boolean booleanValue(Object value) { return value instanceof Boolean b ? b : value == null ? null : Boolean.parseBoolean(value.toString()); }
}
