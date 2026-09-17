package ch.it4user.fintube.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Pattern;

/** Small structured audit logger with a deliberately narrow, secret-safe API. */
@Component
public final class AuditLogger {
    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9_.:/,@+%?&=\\[\\]{}()'\\-]{0,160}");
    private static final Pattern SENSITIVE_KEY = Pattern.compile(".*(password|secret|token|cookie|api[_-]?key|authorization|credential|source[_-]?(url|uri)|(^|_)(url|uri)$).*", Pattern.CASE_INSENSITIVE);
    private static final Logger LOG = LoggerFactory.getLogger("fintube.audit");

    public void event(String name, Map<String, ?> fields) {
        StringBuilder message = new StringBuilder("event=").append(safe(name));
        fields.forEach((key, value) -> {
            String normalizedKey = safe(key);
            String normalizedValue = value == null ? "null" : safe(String.valueOf(value));
            // Field names are treated as untrusted too.  Redact credentials and
            // source URLs before they reach the logging backend; values are never
            // interpolated into an exception or a URL by this class.
            if (sensitive(key) || looksLikeUrl(value)) normalizedValue = "[REDACTED]";
            message.append(' ').append(normalizedKey).append('=').append(normalizedValue);
        });
        LOG.info(message.toString());
    }

    private static String safe(String value) {
        if (value == null || !SAFE_VALUE.matcher(value).matches()) return "[REDACTED]";
        return value;
    }

    private static boolean sensitive(String key) {
        if (key == null) return true;
        String normalized = key.replaceAll("([a-z])([A-Z])", "$1_$2");
        return SENSITIVE_KEY.matcher(normalized).matches();
    }

    private static boolean looksLikeUrl(Object value) {
        if (!(value instanceof String text)) return false;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("socks5://");
    }
}
