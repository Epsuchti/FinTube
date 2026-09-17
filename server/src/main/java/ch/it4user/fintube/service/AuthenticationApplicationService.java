package ch.it4user.fintube.service;

import ch.it4user.fintube.api.contract.model.LoginRequest;
import ch.it4user.fintube.api.contract.model.RegisterRequest;
import ch.it4user.fintube.api.contract.model.Role;
import ch.it4user.fintube.core.AuditLogger;
import ch.it4user.fintube.core.Database;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@Service
public class AuthenticationApplicationService {
    private final Database db;
    private final AuthService auth;
    private final AuditLogger audit;

    public AuthenticationApplicationService(Database db, AuthService auth, AuditLogger audit) {
        this.db = db;
        this.auth = auth;
        this.audit = audit;
    }

    public Result register(RegisterRequest request) {
        String username = required(request.getUsername(), "username");
        String password = required(request.getPassword(), "password");
        if (!username.matches("[A-Za-z0-9_.-]{3,48}") || password.length() < 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username or password does not meet policy");
        }
        String slug = auth.slug(username);
        try (Connection connection = db.open(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO users(username,email,password_hash,role,filesystem_slug,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                java.sql.Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, username);
            statement.setString(2, request.getEmail());
            statement.setString(3, auth.hash(password));
            statement.setString(4, "USER");
            statement.setString(5, slug);
            statement.setString(6, Database.now());
            statement.setString(7, Database.now());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("user ID was not returned");
                Files.createDirectories(db.usersRoot.resolve(slug));
                long id = keys.getLong(1);
                audit.event("USER_REGISTERED", java.util.Map.of("userId", id));
                return new Result(Role.USER, username, auth.login(id));
            }
        } catch (SQLException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "username already exists");
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "user library could not be created", e);
        }
    }

    public Result login(LoginRequest request) {
        String username = required(request.getUsername(), "username");
        String password = required(request.getPassword(), "password");
        try (Connection connection = db.open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT id,password_hash,role FROM users WHERE username=?")) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !auth.matches(password, result.getString(2))) {
                    audit.event("USER_LOGIN_FAILED", java.util.Map.of("username", username));
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
                }
                long userId = result.getLong(1);
                String role = result.getString(3);
                audit.event("USER_LOGIN", java.util.Map.of("userId", userId, "role", role));
                return new Result(Role.fromValue(role), null, auth.login(userId));
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (SQLException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "login could not be completed", e);
        }
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
