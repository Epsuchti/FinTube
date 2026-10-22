package ch.it4user.fintube.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 503 with Retry-After for media that is expected to become playable later. */
public class MediaNotReadyResponseException extends ResponseStatusException {
    private final long retryAfterSeconds;

    public MediaNotReadyResponseException(String reason, long retryAfterSeconds) {
        super(HttpStatus.SERVICE_UNAVAILABLE, reason);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        return headers;
    }
}
