package com.webox.menu;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class StockStreamIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper mapper;

    @Test
    void connectedClientReceivesLiveStockChangedEvent() throws Exception {
        ResponseEntity<Map> registration = http.postForEntity("/api/v1/auth/register", Map.of(
                "email", "stock-stream-" + UUID.randomUUID() + "@example.test", "password", "Password123", "displayName", "Stock Stream"), Map.class);
        String token = registration.getBody().get("accessToken").toString();
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(900);
        URI uri = URI.create(http.getRootUri() + "/api/v1/app/menu/stock-stream?date=" + date);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token).GET().build();
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<Stream<String>> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .get(5, TimeUnit.SECONDS);
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try { response.body().forEach(lines::offer); }
            catch (RuntimeException ignored) { /* Closing the SSE body interrupts the reader. */ }
        }, "stock-sse-test-reader");
        reader.setDaemon(true);
        reader.start();
        try {
            boolean resyncSent = false;
            boolean resyncFrameEnded = false;
            for (int i = 0; i < 8; i++) {
                String line = lines.poll(3, TimeUnit.SECONDS);
                if (line == null) break;
                if (line.contains("resync.required")) resyncSent = true;
                if (resyncSent && line.isEmpty()) { resyncFrameEnded = true; break; }
            }
            assertThat(resyncSent && resyncFrameEnded).isTrue();
            String event = mapper.writeValueAsString(Map.of("date", date.toString(), "menuItemId", 41,
                    "remainingStock", 7, "stockVersion", 3));
            String observed = "";
            for (int attempt = 0; attempt < 10 && !observed.contains("stock.changed"); attempt++) {
                redis.convertAndSend("webox:stock:" + date, event);
                for (int i = 0; i < 8; i++) {
                    String line = lines.poll(250, TimeUnit.MILLISECONDS);
                    if (line == null) break;
                    observed += line + "\n";
                    if (line.contains("stock.changed")) {
                        String data = lines.poll(2, TimeUnit.SECONDS);
                        if (data != null) observed += data;
                        break;
                    }
                }
            }
            assertThat(observed).contains("stock.changed", "remainingStock", "7", "stockVersion", "3");
        } finally {
            response.body().close();
            reader.interrupt();
        }
    }
}
