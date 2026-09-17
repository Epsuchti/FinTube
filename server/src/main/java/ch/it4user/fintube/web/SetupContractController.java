package ch.it4user.fintube.web;

import ch.it4user.fintube.service.SessionCookieService;
import ch.it4user.fintube.service.SetupApplicationService;

import ch.it4user.fintube.api.contract.SetupApi;
import ch.it4user.fintube.api.contract.model.AuthResult;
import ch.it4user.fintube.api.contract.model.CreateAdminRequest;
import ch.it4user.fintube.api.contract.model.SetupStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api")
public class SetupContractController implements SetupApi {
    private final SetupApplicationService setup;
    private final SessionCookieService cookies;
    private final HttpServletResponse response;

    public SetupContractController(SetupApplicationService setup, SessionCookieService cookies, HttpServletResponse response) {
        this.setup = setup;
        this.cookies = cookies;
        this.response = response;
    }

    @Override
    public ResponseEntity<AuthResult> createInitialAdmin(CreateAdminRequest request) {
        SetupApplicationService.Result result = setup.createAdmin(request);
        cookies.set(response, result.sessionToken());
        return ResponseEntity.ok(new AuthResult().role(result.role()));
    }

    @Override
    public ResponseEntity<SetupStatus> getSetupStatus() {
        return ResponseEntity.ok(new SetupStatus(setup.required()));
    }

}
