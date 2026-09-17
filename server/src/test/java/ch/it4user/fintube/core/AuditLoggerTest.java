package ch.it4user.fintube.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class AuditLoggerTest {
    @Test
    void redactsCredentialsAndSourceUrlsWhileKeepingSafeEventFields(CapturedOutput output) {
        new AuditLogger().event("PLAY_REQUEST", Map.of(
                "userId", 42,
                "videoId", "VIDEO123",
                "apiKey", "youtube-secret",
                "sourceUrl", "https://googlevideo.example/video?signature=private"));

        assertThat(output.toString()).contains("event=PLAY_REQUEST")
                .contains("userId=42")
                .contains("videoId=VIDEO123")
                .doesNotContain("youtube-secret")
                .doesNotContain("googlevideo.example")
                .contains("apiKey=[REDACTED]")
                .contains("sourceUrl=[REDACTED]");
    }
}
