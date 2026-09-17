package ch.it4user.fintube.service;

import ch.it4user.fintube.api.contract.model.CreateAdminRequest;
import ch.it4user.fintube.api.contract.model.Role;
import ch.it4user.fintube.core.AuditLogger;
import ch.it4user.fintube.core.Bootstrap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SetupApplicationService {
    private final Bootstrap bootstrap;
    private final AuthService auth;
    private final AuditLogger audit;

    public SetupApplicationService(Bootstrap bootstrap, AuthService auth, AuditLogger audit) {
        this.bootstrap = bootstrap;
        this.auth = auth;
        this.audit = audit;
    }

    public boolean required() {
        return bootstrap.required();
    }

    public Result createAdmin(CreateAdminRequest request) {
        try {
            long id = bootstrap.createAdmin(request.getUsername(), request.getEmail(), request.getPassword(), request.getToken());
            String token = auth.login(id);
            audit.event("INITIAL_ADMIN_CREATED", java.util.Map.of("userId", id));
            return new Result(Role.ADMIN, token);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid setup token");
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "administrator could not be created", e);
        }
    }

    public record Result(Role role, String sessionToken) {}
}
