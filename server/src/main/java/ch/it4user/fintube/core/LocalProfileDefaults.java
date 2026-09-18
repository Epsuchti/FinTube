package ch.it4user.fintube.core;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
@DependsOn("liquibase")
class LocalProfileDefaults {
    private static final String STOCK_PUBLIC_BASE_URL = "http://localhost:8080";

    private final SettingsService settings;
    private final ApplicationPaths paths;
    private final String publicBaseUrl;

    LocalProfileDefaults(
            SettingsService settings,
            ApplicationPaths paths,
            @Value("${fintube.default-public-base-url}") String publicBaseUrl) {
        this.settings = settings;
        this.paths = paths;
        this.publicBaseUrl = publicBaseUrl;
    }

    @PostConstruct
    void apply() {
        if (STOCK_PUBLIC_BASE_URL.equals(settings.value("public_base_url"))) {
            settings.save("public_base_url", publicBaseUrl, false);
            rewriteStockPlaybackUrls();
        }
    }

    private void rewriteStockPlaybackUrls() {
        try (var files = Files.walk(paths.usersRoot)) {
            files.filter(path -> path.getFileName().toString().equals("video.strm"))
                    .forEach(this::rewriteStockPlaybackUrl);
        } catch (IOException e) {
            throw new IllegalStateException("could not update local playback URLs", e);
        }
    }

    private void rewriteStockPlaybackUrl(Path path) {
        try {
            String existing = Files.readString(path);
            if (existing.startsWith(STOCK_PUBLIC_BASE_URL + "/")) {
                Files.writeString(path, publicBaseUrl + existing.substring(STOCK_PUBLIC_BASE_URL.length()));
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not update local playback URL", e);
        }
    }
}
