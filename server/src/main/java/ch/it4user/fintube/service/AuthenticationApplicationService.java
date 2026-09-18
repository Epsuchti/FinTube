package ch.it4user.fintube.service;

import ch.it4user.fintube.api.contract.model.LoginRequest;
import ch.it4user.fintube.api.contract.model.RegisterRequest;
import ch.it4user.fintube.api.contract.model.Role;
import ch.it4user.fintube.core.AuditLogger;
import ch.it4user.fintube.core.ApplicationClock;
import ch.it4user.fintube.core.ApplicationPaths;
import ch.it4user.fintube.persistence.entities.UserEntity;
import ch.it4user.fintube.persistence.repositories.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;

@Service
public class AuthenticationApplicationService {
    private final ApplicationPaths paths;
    private final UserRepository users;
    private final AuthService auth;
    private final AuditLogger audit;

    public AuthenticationApplicationService(ApplicationPaths paths, UserRepository users, AuthService auth, AuditLogger audit) {
        this.paths = paths;
        this.users = users;
        this.auth = auth;
        this.audit = audit;
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
