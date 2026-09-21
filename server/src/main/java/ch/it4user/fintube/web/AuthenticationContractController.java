package ch.it4user.fintube.web;

import ch.it4user.fintube.service.AuthenticationApplicationService;
import ch.it4user.fintube.service.SessionCookieService;

import ch.it4user.fintube.api.contract.AuthenticationApi;
import ch.it4user.fintube.api.contract.model.AuthResult;
import ch.it4user.fintube.api.contract.model.LoginRequest;
import ch.it4user.fintube.api.contract.model.PasswordResetConfirmRequest;
import ch.it4user.fintube.api.contract.model.PasswordResetRequest;
import ch.it4user.fintube.api.contract.model.RegisterRequest;
import ch.it4user.fintube.api.contract.model.Role;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api")
public class AuthenticationContractController implements AuthenticationApi {
    private final AuthenticationApplicationService authentication;
    private final SessionCookieService cookies;
    private final HttpServletRequest requestContext;
    private final HttpServletResponse response;

    public AuthenticationContractController(AuthenticationApplicationService authentication, SessionCookieService cookies, HttpServletRequest requestContext, HttpServletResponse response) {
        this.authentication = authentication;
        this.cookies = cookies;
        this.requestContext = requestContext;
        this.response = response;
    }

    @Override
    public ResponseEntity<AuthResult> login(LoginRequest request) {
        AuthenticationApplicationService.Result result = authentication.login(request);
        cookies.set(response, result.sessionToken());
        return ResponseEntity.ok(new AuthResult().role(result.role()).username(result.username()));
    }

    @Override
    public ResponseEntity<Void> logout() {
        authentication.logout(requestContext);
        cookies.clear(response);
        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<Void> requestPasswordReset(PasswordResetRequest request) {
        authentication.requestPasswordReset(request);
        return ResponseEntity.accepted().build();
    }

    @Override
    public ResponseEntity<Void> confirmPasswordReset(PasswordResetConfirmRequest request) {
        authentication.confirmPasswordReset(request);
        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<AuthResult> register(RegisterRequest request) {
        AuthenticationApplicationService.Result result = authentication.register(request);
        cookies.set(response, result.sessionToken());
        return ResponseEntity.ok(new AuthResult().role(result.role()).username(result.username()));
    }

}
