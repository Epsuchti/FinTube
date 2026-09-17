package ch.it4user.fintube.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Resolves the authenticated application principal for web-facing services. */
@Service
public class AuthorizationService {
    private final AuthService auth;

    public AuthorizationService(AuthService auth) {
        this.auth = auth;
    }

    public AuthService.Principal requireUser(HttpServletRequest request) {
        AuthService.Principal principal = auth.current(request);
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return principal;
    }

    public AuthService.Principal requireAdmin(HttpServletRequest request) {
        AuthService.Principal principal = requireUser(request);
        if (!principal.admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return principal;
    }
}
