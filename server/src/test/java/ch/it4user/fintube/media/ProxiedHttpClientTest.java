package ch.it4user.fintube.media;

import ch.it4user.fintube.core.SettingsService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProxiedHttpClientTest {
    @Test
    void sendsExternalRequestsThroughConfiguredProxy() throws Exception {
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> requestTarget = new AtomicReference<>();
        proxy.createContext("/", exchange -> {
            requestTarget.set(exchange.getRequestURI().toString());
            byte[] body = "proxied".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        proxy.start();
        try {
            SettingsService settings = mock(SettingsService.class);
            when(settings.values(false)).thenReturn(Map.of(
                    "proxy_url", "http://127.0.0.1:" + proxy.getAddress().getPort()));
            ProxiedHttpClient client = new ProxiedHttpClient(settings, HttpClient.newHttpClient());

            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://example.test/resource")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals("proxied", response.body());
            assertTrue(requestTarget.get().contains("example.test/resource"));
        } finally {
            proxy.stop(0);
        }
    }
}
