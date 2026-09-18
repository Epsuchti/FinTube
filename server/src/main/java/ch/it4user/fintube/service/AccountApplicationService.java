package ch.it4user.fintube.service;

import ch.it4user.fintube.api.contract.model.ChangePasswordRequest;
import ch.it4user.fintube.api.contract.model.Profile;
import ch.it4user.fintube.api.contract.model.Role;
import ch.it4user.fintube.core.AuditLogger;
import ch.it4user.fintube.core.ApplicationClock;
import ch.it4user.fintube.persistence.entities.UserEntity;
import ch.it4user.fintube.persistence.repositories.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;

@Service
public class AccountApplicationService {
    private final UserRepository users;
    private final AuthService auth;
    private final AuthorizationService authorization;
    private final AuditLogger audit;

    public AccountApplicationService(UserRepository users, AuthService auth, AuthorizationService authorization, AuditLogger audit) {
        this.users = users;
        this.auth = auth;
        this.authorization = authorization;
        this.audit = audit;
    }

    public Profile currentUser(HttpServletRequest request) {
        AuthService.Principal principal = authorization.requireUser(request);
        return new Profile(principal.id(), principal.username(), Role.fromValue(principal.role()), principal.slug());
    }

    @Transactional
    public void changePassword(HttpServletRequest request, ChangePasswordRequest password) {
        AuthService.Principal principal = authorization.requireUser(request);
        if (password.getNewPassword() == null || password.getNewPassword().length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "password must be at least 6 characters");
        }
        UserEntity user = users.findById(principal.id()).orElse(null);
        if (user == null || !auth.matches(password.getOldPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        user.setPasswordHash(auth.hash(password.getNewPassword()));
        user.setUpdatedAt(ApplicationClock.now());
        users.save(user);
        audit.event("PASSWORD_CHANGED", java.util.Map.of("userId", principal.id()));
    }
}
