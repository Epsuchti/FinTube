package ch.it4user.fintube.core;

import java.time.Instant;

/** Provides the application timestamp format used by persisted records. */
public final class ApplicationClock {
    private ApplicationClock() {
    }

    public static String now() {
        return Instant.now().toString();
    }
}
