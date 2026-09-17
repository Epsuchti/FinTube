package ch.it4user.fintube.service;

import ch.it4user.fintube.api.contract.model.ChangePasswordRequest;
import ch.it4user.fintube.api.contract.model.Profile;
import ch.it4user.fintube.api.contract.model.Role;
import ch.it4user.fintube.core.AuditLogger;
import ch.it4user.fintube.core.Database;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

@Service
public class AccountApplicationService {
    private final Database db;
    private final AuthService auth;
    private final AuthorizationService authorization;
    private final AuditLogger audit;

    public AccountApplicationService(Database db, AuthService auth, AuthorizationService authorization, AuditLogger audit) {
        this.db = db;
        this.auth = auth;
        this.authorization = authorization;
        this.audit = audit;
    }

    public Profile currentUser(HttpServletRequest request) {
        AuthService.Principal principal = authorization.requireUser(request);
        return new Profile(principal.id(), principal.username(), Role.fromValue(principal.role()), principal.slug());
    }

    public void changePassword(HttpServletRequest request, ChangePasswordRequest password) {
        AuthService.Principal principal = authorization.requireUser(request);
        if (password.getNewPassword() == null || password.getNewPassword().length() < 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "password must be at least 12 characters");
        }
        try (Connection connection = db.open(); PreparedStatement find = connection.prepareStatement("SELECT password_hash FROM users WHERE id=?")) {
            find.setLong(1, principal.id());
            try (ResultSet result = find.executeQuery()) {
                if (!result.next() || !auth.matches(password.getOldPassword(), result.getString(1))) {
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
                }
            }
            try (PreparedStatement update = connection.prepareStatement("UPDATE users SET password_hash=?,updated_at=? WHERE id=?")) {
                update.setString(1, auth.hash(password.getNewPassword()));
                update.setString(2, Database.now());
                update.setLong(3, principal.id());
                update.executeUpdate();
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "password could not be changed", e);
        }
        audit.event("PASSWORD_CHANGED", java.util.Map.of("userId", principal.id()));
    }
}
