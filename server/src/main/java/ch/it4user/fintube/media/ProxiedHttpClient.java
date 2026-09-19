package ch.it4user.fintube.media;

import ch.it4user.fintube.core.SettingsService;
import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ProxiedHttpClient {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);

    private final SettingsService settings;
    private final HttpClient direct;
    private volatile ProxyClient proxyClient;

    private record ProxyClient(String url, String username, String password, HttpClient client) {}

    @Autowired
    public ProxiedHttpClient(SettingsService settings) {
        this(settings, HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    ProxiedHttpClient(SettingsService settings, HttpClient direct) {
        this.settings = settings;
        this.direct = direct;
    }

    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        return client().send(request, handler);
    }

    public String proxyArgument() {
        Map<String, String> values = settings.values(false);
        if (values == null) values = Map.of();
        return proxyArgument(values);
    }

    public String proxyArgument(Map<String, String> values) {
        if (values == null) values = Map.of();
        String base = values.getOrDefault("proxy_url", "").trim();
        if (base.isBlank()) return "";
        String username = values.getOrDefault("proxy_username", "");
        String password = values.getOrDefault("proxy_password", "");
        if (username.isBlank() && password.isBlank()) return base;
        URI proxy = URI.create(base);
        String authority = percentEncode(username) + ":" + percentEncode(password) + "@" + proxy.getRawAuthority();
        StringBuilder result = new StringBuilder(proxy.getScheme()).append("://").append(authority);
        if (proxy.getRawPath() != null) result.append(proxy.getRawPath());
        if (proxy.getRawQuery() != null) result.append('?').append(proxy.getRawQuery());
        if (proxy.getRawFragment() != null) result.append('#').append(proxy.getRawFragment());
        return result.toString();
    }

    private HttpClient client() {
        Map<String, String> values = settings.values(false);
        if (values == null) values = Map.of();
        String url = values.getOrDefault("proxy_url", "").trim();
        if (url.isBlank()) return direct;

        String username = values.getOrDefault("proxy_username", "");
        String password = values.getOrDefault("proxy_password", "");
        ProxyClient current = proxyClient;
        if (current != null && current.url().equals(url)
                && current.username().equals(username) && current.password().equals(password)) {
            return current.client();
        }
        synchronized (this) {
            current = proxyClient;
            if (current != null && current.url().equals(url)
                    && current.username().equals(username) && current.password().equals(password)) {
                return current.client();
            }

            URI proxy = URI.create(url);
            if (!"http".equalsIgnoreCase(proxy.getScheme())
                    && !"https".equalsIgnoreCase(proxy.getScheme())) {
                throw new IllegalArgumentException("proxy_url must use http or https");
            }
            if (proxy.getHost() == null) throw new IllegalArgumentException("proxy_url must include a host");
            int port = proxy.getPort() < 0
                    ? ("https".equalsIgnoreCase(proxy.getScheme()) ? 443 : 80)
                    : proxy.getPort();
            var builder = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .proxy(ProxySelector.of(InetSocketAddress.createUnresolved(proxy.getHost(), port)));
            if (!username.isBlank() || !password.isBlank()) {
                builder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        if (getRequestorType() != RequestorType.PROXY) return null;
                        return new PasswordAuthentication(username, password.toCharArray());
                    }
                });
            }
            HttpClient configured = builder.build();
            proxyClient = new ProxyClient(url, username, password, configured);
            return configured;
        }
    }

    private static String percentEncode(String value) {
        StringBuilder result = new StringBuilder();
        for (byte valueByte : value.getBytes(StandardCharsets.UTF_8)) {
            int character = valueByte & 0xff;
            if ((character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9') || character == '-'
                    || character == '_' || character == '.' || character == '~') {
                result.append((char) character);
            } else {
                result.append('%').append(String.format(Locale.ROOT, "%02X", character));
            }
        }
        return result.toString();
    }
}
