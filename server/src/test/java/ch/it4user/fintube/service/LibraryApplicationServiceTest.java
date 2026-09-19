package ch.it4user.fintube.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryApplicationServiceTest {
    @Test
    void reportsUnauthenticatedYouTubeCookiesAsClientError() {
        ResponseStatusException failure = LibraryApplicationService.subscriptionFeedFailure(
                "ERROR: Login details are needed to download this content", null);

        assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(failure.getReason()).contains("cookies are invalid or expired");
    }

    @Test
    void reportsProxyFailuresAsGatewayError() {
        ResponseStatusException failure = LibraryApplicationService.subscriptionFeedFailure(
                "ERROR: Unable to connect through proxy", null);

        assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(failure.getReason()).contains("proxy and network settings");
    }

    @Test
    void reportsUnexpectedYtDlpFailuresWithoutExposingRawOutput() {
        ResponseStatusException failure = LibraryApplicationService.subscriptionFeedFailure(
                "ERROR: unexpected upstream detail", new IllegalStateException("unexpected upstream detail"));

        assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(failure.getReason()).contains("subscription import failed");
        assertThat(failure.getReason()).doesNotContain("unexpected upstream detail");
    }
}
