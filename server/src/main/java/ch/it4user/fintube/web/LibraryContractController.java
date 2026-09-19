package ch.it4user.fintube.web;

import ch.it4user.fintube.service.LibraryApplicationService;

import ch.it4user.fintube.api.contract.LibraryApi;
import ch.it4user.fintube.api.contract.model.AddSubscriptionRequest;
import ch.it4user.fintube.api.contract.model.ImportChannelsResult;
import ch.it4user.fintube.api.contract.model.ImportCookiesRequest;
import ch.it4user.fintube.api.contract.model.RefreshResult;
import ch.it4user.fintube.api.contract.model.ToggleSubscriptionRequest;
import ch.it4user.fintube.api.contract.model.VideoPage;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

import ch.it4user.fintube.api.contract.model.Subscription;
import java.util.List;

@RestController
@RequestMapping("/api")
public class LibraryContractController implements LibraryApi {
    private final LibraryApplicationService library;
    private final HttpServletRequest requestContext;

    public LibraryContractController(LibraryApplicationService library, HttpServletRequest requestContext) {
        this.library = library;
        this.requestContext = requestContext;
    }

    @Override
    public ResponseEntity<Void> addSubscription(AddSubscriptionRequest request) {
        library.addSubscription(requestContext, request);
        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<ImportChannelsResult> importSubscriptionsFromCookies(ImportCookiesRequest request) {
        return ResponseEntity.ok(library.importSubscriptionsFromCookies(requestContext, request));
    }

    @Override
    public ResponseEntity<List<Subscription>> listSubscriptions() {
        return ResponseEntity.ok(library.subscriptions(requestContext));
    }

    @Override
    public ResponseEntity<VideoPage> listVideos(Integer page, Integer pageSize, String search) {
        return ResponseEntity.ok(library.videos(requestContext, page, pageSize, search));
    }

    @Override
    public ResponseEntity<Resource> getVideoThumbnail(String video) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(library.thumbnail(requestContext, video));
    }

    @Override
    public ResponseEntity<RefreshResult> refreshSubscription(Long id) {
        return ResponseEntity.ok(library.refreshSubscription(requestContext, id));
    }

    @Override
    public ResponseEntity<RefreshResult> refreshAllSubscriptions() {
        return ResponseEntity.ok(library.refreshAllSubscriptions(requestContext));
    }

    @Override
    public ResponseEntity<Void> removeSubscription(Long id) {
        library.removeSubscription(requestContext, id);
        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<Void> updateSubscription(Long id, ToggleSubscriptionRequest request) {
        library.updateSubscription(requestContext, id, request);
        return ResponseEntity.ok().build();
    }

}
