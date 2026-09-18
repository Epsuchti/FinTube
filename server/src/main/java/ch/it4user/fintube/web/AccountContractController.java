package ch.it4user.fintube.web;

import ch.it4user.fintube.service.AccountApplicationService;

import ch.it4user.fintube.api.contract.AccountApi;
import ch.it4user.fintube.api.contract.model.ChangePasswordRequest;
import ch.it4user.fintube.api.contract.model.Profile;
import ch.it4user.fintube.api.contract.model.Role;
import ch.it4user.fintube.api.contract.model.UpdateYouTubeApiKeyRequest;
import ch.it4user.fintube.api.contract.model.YouTubeApiKeyStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api")
public class AccountContractController implements AccountApi {
    private final AccountApplicationService account;
    private final HttpServletRequest requestContext;

    public AccountContractController(AccountApplicationService account, HttpServletRequest requestContext) {
        this.account = account;
        this.requestContext = requestContext;
    }

    @Override
    public ResponseEntity<Void> changePassword(ChangePasswordRequest request) {
        account.changePassword(requestContext, request);
        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<Profile> getCurrentUser() {
        return ResponseEntity.ok(account.currentUser(requestContext));
    }

    @Override
    public ResponseEntity<YouTubeApiKeyStatus> getYouTubeApiKeyStatus() {
        return ResponseEntity.ok(account.youtubeApiKeyStatus(requestContext));
    }

    @Override
    public ResponseEntity<YouTubeApiKeyStatus> updateYouTubeApiKey(UpdateYouTubeApiKeyRequest request) {
        return ResponseEntity.ok(account.updateYouTubeApiKey(requestContext, request));
    }
}
