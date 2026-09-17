package ch.it4user.fintube.web;

import ch.it4user.fintube.service.AdminApplicationService;

import ch.it4user.fintube.api.contract.AdminApi;
import ch.it4user.fintube.api.contract.model.AdminUser;
import ch.it4user.fintube.api.contract.model.CacheEntry;
import ch.it4user.fintube.api.contract.model.Job;
import ch.it4user.fintube.api.contract.model.OperationResult;
import ch.it4user.fintube.api.contract.model.UpdateRoleRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AdminContractController implements AdminApi {
    private final AdminApplicationService admin;
    private final HttpServletRequest requestContext;

    public AdminContractController(AdminApplicationService admin, HttpServletRequest requestContext) {
        this.admin = admin;
        this.requestContext = requestContext;
    }

    @Override
    public ResponseEntity<Void> cancelJob(String id) {
        admin.cancelJob(requestContext, id);
        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<Void> deleteCacheEntry(String video) {
        admin.deleteCache(requestContext, video);
        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<Map<String, Object>> getJellyfinStatus() {
        return ResponseEntity.ok(admin.jellyfinStatus(requestContext));
    }

    @Override
    public ResponseEntity<Map<String, String>> getSettings() {
        return ResponseEntity.ok(admin.settings(requestContext));
    }

    @Override
    public ResponseEntity<List<CacheEntry>> listCacheEntries() {
        return ResponseEntity.ok(admin.cache(requestContext));
    }

    @Override
    public ResponseEntity<List<Job>> listJobs() {
        return ResponseEntity.ok(admin.jobs(requestContext));
    }

    @Override
    public ResponseEntity<List<AdminUser>> listUsers() {
        return ResponseEntity.ok(admin.users(requestContext));
    }

    @Override
    public ResponseEntity<OperationResult> refreshJellyfin() {
        return ResponseEntity.ok(admin.refreshJellyfin(requestContext));
    }

    @Override
    public ResponseEntity<Void> updateSettings(Map<String, String> requestBody) {
        admin.updateSettings(requestContext, requestBody);
        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<Void> updateUserRole(Long id, UpdateRoleRequest request) {
        admin.updateUserRole(requestContext, id, request);
        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<Map<String, Object>> validateJellyfin() {
        return ResponseEntity.ok(admin.validateJellyfin(requestContext));
    }

}
