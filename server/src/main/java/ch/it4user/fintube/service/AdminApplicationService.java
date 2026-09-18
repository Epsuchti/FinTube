package ch.it4user.fintube.service;

import ch.it4user.fintube.api.contract.model.AdminUser;
import ch.it4user.fintube.api.contract.model.CacheEntry;
import ch.it4user.fintube.api.contract.model.Job;
import ch.it4user.fintube.api.contract.model.OperationResult;
import ch.it4user.fintube.api.contract.model.Role;
import ch.it4user.fintube.api.contract.model.UpdateRoleRequest;
import ch.it4user.fintube.core.AuditLogger;
import ch.it4user.fintube.core.ApplicationClock;
import ch.it4user.fintube.core.SettingsPolicy;
import ch.it4user.fintube.core.SettingsService;
import ch.it4user.fintube.integration.JellyfinSyncService;
import ch.it4user.fintube.media.BackgroundFillService;
import ch.it4user.fintube.persistence.entities.CacheEntryEntity;
import ch.it4user.fintube.persistence.repositories.CacheEntryRepository;
import ch.it4user.fintube.persistence.repositories.CachedFragmentRepository;
import ch.it4user.fintube.persistence.entities.JobEntity;
import ch.it4user.fintube.persistence.repositories.JobRepository;
import ch.it4user.fintube.persistence.entities.UserEntity;
import ch.it4user.fintube.persistence.repositories.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.springframework.data.domain.Sort;

@Service
public class AdminApplicationService {
    private final SettingsService settings;
    private final UserRepository users;
    private final CacheEntryRepository cacheEntries;
    private final CachedFragmentRepository cachedFragments;
    private final JobRepository jobs;
    private final AuthorizationService authorization;
    private final BackgroundFillService filler;
    private final JellyfinSyncService jellyfin;
    private final AuditLogger audit;

    public AdminApplicationService(SettingsService settings, UserRepository users,
                                   CacheEntryRepository cacheEntries,
                                   CachedFragmentRepository cachedFragments,
                                   JobRepository jobs,
                                   AuthorizationService authorization,
                                   BackgroundFillService filler,
                                   JellyfinSyncService jellyfin,
                                   AuditLogger audit) {
        this.settings = settings;
        this.users = users;
        this.cacheEntries = cacheEntries;
        this.cachedFragments = cachedFragments;
        this.jobs = jobs;
        this.authorization = authorization;
        this.filler = filler;
        this.jellyfin = jellyfin;
        this.audit = audit;
    }

    public Map<String, String> settings(HttpServletRequest request) {
        return database(() -> {
            authorization.requireAdmin(request);
            Map<String, String> result = new LinkedHashMap<>(settings.values(true));
            result.remove("youtube_api_key");
            return result;
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
                settings.save(entry.getKey(), entry.getValue(), SettingsPolicy.isSecret(entry.getKey()));
            }
            audit.event("ADMIN_SETTING_CHANGED", Map.of("userId", admin.id(), "settingCount", values.size()));
            return null;
        });
    }

    public List<AdminUser> users(HttpServletRequest request) {
        return database(() -> {
            authorization.requireAdmin(request);
            return users.findAll(Sort.by(Sort.Direction.ASC, "username")).stream().map(AdminApplicationService::user).toList();
        });
    }

    public void updateUserRole(HttpServletRequest request, long id, UpdateRoleRequest requestBody) {
        database(() -> {
            AuthService.Principal admin = authorization.requireAdmin(request);
            String role = requestBody.getRole() == null ? null : requestBody.getRole().getValue();
            if (!java.util.Set.of("USER", "ADMIN").contains(role)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            UserEntity user = users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            user.setRole(role);
            user.setUpdatedAt(ApplicationClock.now());
            users.save(user);
            audit.event("ADMIN_USER_ROLE_CHANGED", Map.of("adminId", admin.id(), "targetUserId", id, "role", role));
            return null;
        });
    }

    public List<CacheEntry> cache(HttpServletRequest request) {
        return database(() -> {
            authorization.requireAdmin(request);
            return cacheEntries.findAll(Sort.by(Sort.Direction.ASC, "lastAccessedAt")).stream().map(AdminApplicationService::cacheEntry).toList();
        });
    }

    public void deleteCache(HttpServletRequest request, String video) {
        database(() -> {
            AuthService.Principal admin = authorization.requireAdmin(request);
            for (var fragment : cachedFragments.findByVideoId(video)) {
                Files.deleteIfExists(Path.of(fragment.getPath()));
            }
            cachedFragments.deleteByVideoId(video);
            cacheEntries.deleteInactiveByVideoId(video);
            audit.event("CACHE_ENTRY_DELETED", Map.of("adminId", admin.id(), "videoId", video));
            return null;
        });
    }

    public List<Job> jobs(HttpServletRequest request) {
        return database(() -> {
            authorization.requireAdmin(request);
            return jobs.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream().map(AdminApplicationService::job).toList();
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

    private <T> T database(Callable<T> operation) {
        try {
            return operation.call();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "administration operation could not be completed", e);
        }
    }

    private static AdminUser user(UserEntity value) {
        return new AdminUser(value.getId(), value.getUsername(), Role.fromValue(value.getRole()),
                value.getFilesystemSlug(), null).email(value.getEmail());
    }

    private static CacheEntry cacheEntry(CacheEntryEntity value) {
        return new CacheEntry(value.getVideoId(), value.getStatus())
                .formatKey(value.getFormatKey())
                .lastAccessedAt(value.getLastAccessedAt())
                .activeReaders(value.getActiveReaders())
                .activeWriters(value.getActiveWriters());
    }

    private static Job job(JobEntity value) {
        return new Job(value.getId(), value.getVideoId(), value.getStatus(), value.getPriority(), value.getCreatedAt())
                .error(value.getError());
    }

    private static String text(Map<String, Object> value, String key) { Object item = value.get(key); return item == null ? null : item.toString(); }
    private static Boolean booleanValue(Object value) { return value instanceof Boolean b ? b : value == null ? null : Boolean.parseBoolean(value.toString()); }
}
