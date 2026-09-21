package ch.it4user.fintube.service;

import ch.it4user.fintube.api.contract.model.LoginRequest;
import ch.it4user.fintube.api.contract.model.PasswordResetConfirmRequest;
import ch.it4user.fintube.api.contract.model.PasswordResetRequest;
import ch.it4user.fintube.api.contract.model.RegisterRequest;
import ch.it4user.fintube.api.contract.model.Role;
import ch.it4user.fintube.core.AuditLogger;
import ch.it4user.fintube.core.ApplicationClock;
import ch.it4user.fintube.core.ApplicationPaths;
import ch.it4user.fintube.persistence.entities.UserEntity;
import ch.it4user.fintube.persistence.entities.PasswordResetEntity;
import ch.it4user.fintube.persistence.repositories.PasswordResetRepository;
import ch.it4user.fintube.persistence.repositories.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Service
public class AuthenticationApplicationService {
    private final ApplicationPaths paths;
    private final UserRepository users;
    private final AuthService auth;
    private final AuditLogger audit;
    private final PasswordResetRepository passwordResets;
    private final SecureRandom random = new SecureRandom();
    private static final Duration RESET_LIFETIME = Duration.ofMinutes(15);
    private static final Duration RESET_REQUEST_INTERVAL = Duration.ofMinutes(1);

    public AuthenticationApplicationService(ApplicationPaths paths, UserRepository users, AuthService auth, AuditLogger audit, PasswordResetRepository passwordResets) {
        this.paths = paths;
        this.users = users;
        this.auth = auth;
        this.audit = audit;
        this.passwordResets = passwordResets;
    }

    @Transactional
    public Result register(RegisterRequest request) {
        String username = required(request.getUsername(), "username");
        String password = required(request.getPassword(), "password");
        if (!username.matches("[A-Za-z0-9_.-]{3,48}") || password.length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username or password does not meet policy");
        }
        String slug = auth.slug(username);
        try {
            String now = ApplicationClock.now();
            UserEntity user = users.save(new UserEntity(username, request.getEmail(), auth.hash(password),
                    "USER", slug, now, now));
            Files.createDirectories(paths.usersRoot.resolve(slug));
            long id = user.getId();
            audit.event("USER_REGISTERED", java.util.Map.of("userId", id));
            return new Result(Role.USER, username, auth.login(id));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "username already exists");
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "user library could not be created", e);
        }
    }

    public Result login(LoginRequest request) {
        String username = required(request.getUsername(), "username");
        String password = required(request.getPassword(), "password");
        UserEntity user = users.findByUsername(username).orElse(null);
        if (user == null || !auth.matches(password, user.getPasswordHash())) {
            audit.event("USER_LOGIN_FAILED", java.util.Map.of("username", username));
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
        }
        long userId = user.getId();
        audit.event("USER_LOGIN", java.util.Map.of("userId", userId, "role", user.getRole()));
        return new Result(Role.fromValue(user.getRole()), null, auth.login(userId));
    }

    public void logout(HttpServletRequest request) {
        AuthService.Principal principal = requireCurrent(request);
        auth.logout(request);
        audit.event("USER_LOGOUT", java.util.Map.of("userId", principal.id()));
    }

    /** Prints a short-lived recovery code only to the server console; responses never reveal whether a user exists. */
    @Transactional
    public void requestPasswordReset(PasswordResetRequest request) {
        String username = request == null ? "" : request.getUsername();
        if (username == null || username.isBlank()) return;
        UserEntity user = users.findByUsername(username.trim()).orElse(null);
        if (user == null) return;
        Instant now = Instant.now();
        PasswordResetEntity current = passwordResets.findById(user.getId()).orElse(null);
        if (current != null && Instant.parse(current.getRequestedAt()).plus(RESET_REQUEST_INTERVAL).isAfter(now)) {
            audit.event("PASSWORD_RESET_REQUEST_RATE_LIMITED", java.util.Map.of("userId", user.getId()));
            return;
        }
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        passwordResets.save(new PasswordResetEntity(user.getId(), auth.hash(code), now.plus(RESET_LIFETIME).toString(), now.toString()));
        audit.event("PASSWORD_RESET_REQUESTED", java.util.Map.of("userId", user.getId()));
        org.slf4j.LoggerFactory.getLogger(AuthenticationApplicationService.class).warn(
                "PASSWORD RESET CODE username={} code={} expiresAt={}. Enter this code only in the FinTube reset-password form.",
                user.getUsername(), code, now.plus(RESET_LIFETIME));
    }

    @Transactional
    public void confirmPasswordReset(PasswordResetConfirmRequest request) {
        if (request == null || request.getUsername() == null || request.getCode() == null || request.getNewPassword() == null
                || request.getNewPassword().length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid reset request");
        }
        UserEntity user = users.findByUsername(request.getUsername().trim()).orElse(null);
        PasswordResetEntity reset = user == null ? null : passwordResets.findById(user.getId()).orElse(null);
        if (reset == null || Instant.parse(reset.getExpiresAt()).isBefore(Instant.now()) || !auth.matches(request.getCode(), reset.getCodeHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid or expired reset code");
        }
        user.setPasswordHash(auth.hash(request.getNewPassword()));
        user.setUpdatedAt(ApplicationClock.now());
        passwordResets.delete(reset);
        auth.logoutEverywhere(user.getId());
        audit.event("PASSWORD_RESET_COMPLETED", java.util.Map.of("userId", user.getId()));
    }

    private AuthService.Principal requireCurrent(HttpServletRequest request) {
        AuthService.Principal principal = auth.current(request);
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return principal;
    }

    private static String required(String value, String key) {
        if (value == null || value.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " is required");
        return value;
    }

    public record Result(Role role, String username, String sessionToken) {}
}
