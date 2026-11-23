package com.eonmux.chatllm.lifecycle;

import com.eonmux.chatllm.config.AppConfig;
import io.vertx.mutiny.mysqlclient.MySQLPool;
import io.vertx.mutiny.redis.client.RedisAPI;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Application readiness probe published at {@code /health/ready}.
 *
 * <p>Gates readiness on the two dependencies the backend cannot serve
 * without: MySQL (a round-trip {@code SELECT 1}) and Redis ({@code PING}).
 * When an external, non-mock LLM server is configured it is also probed so a
 * load balancer stops routing traffic once the LLM provider is unreachable.</p>
 *
 * <p>The reactive {@link MySQLPool} / {@link RedisAPI} calls are awaited
 * synchronously via {@code .await().indefinitely()}. Quarkus invokes
 * SmallRye {@code @Readiness} checks off the request I/O thread for this
 * endpoint, so a short blocking await is acceptable here; an
 * {@code ifNoItem().after(...)} guard bounds each probe so a hung dependency
 * can never stall the endpoint.</p>
 *
 * <p>Local (llama.cpp) model servers are intentionally excluded from the
 * gate: they are owned by {@code ModelServerPool} and started lazily on the
 * first request, so gating readiness on them would report DOWN until the
 * first LLM request and flap during the multi-minute model-loading window.</p>
 */
@Slf4j
@Readiness
@ApplicationScoped
public class ReadinessHealthCheck implements HealthCheck {

    /** Hard timeout per dependency probe so /health/ready never hangs. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    private static final String LLM_SKIPPED = "skipped";
    private static final String LLM_UP = "up";
    private static final String LLM_DOWN = "down";

    @Inject
    MySQLPool mysqlPool;

    @Inject
    RedisAPI redis;

    @Inject
    AppConfig config;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @Override
    public HealthCheckResponse call() {
        boolean mysqlUp = probeMysql();
        boolean redisUp = probeRedis();

        // The LLM is only probed when a real external provider is configured.
        // Mock mode and the locally-managed llama.cpp server are skipped (see class javadoc).
        boolean llmChecked = config.isLlmEnabled()
                && !config.isLlmMockMode()
                && config.isLlmExternalServer();
        boolean llmUp = llmChecked ? probeExternalLlm() : true;
        String llmState = llmChecked ? (llmUp ? LLM_UP : LLM_DOWN) : LLM_SKIPPED;

        boolean up = mysqlUp && redisUp && llmUp;

        return HealthCheckResponse.builder()
                .name("chatllm-dependencies")
                .status(up)
                .withData("mysql", mysqlUp)
                .withData("redis", redisUp)
                .withData("llm", llmState)
                .build();
    }

    private boolean probeMysql() {
        try {
            mysqlPool.query("SELECT 1")
                    .execute()
                    .ifNoItem().after(PROBE_TIMEOUT).fail()
                    .await().indefinitely();
            return true;
        } catch (Exception e) {
            log.warn("Readiness probe: MySQL unreachable - {}", e.toString());
            return false;
        }
    }

    private boolean probeRedis() {
        try {
            redis.ping(List.of())
                    .ifNoItem().after(PROBE_TIMEOUT).fail()
                    .await().indefinitely();
            return true;
        } catch (Exception e) {
            log.warn("Readiness probe: Redis unreachable - {}", e.toString());
            return false;
        }
    }

    /**
     * Probes the configured external LLM server's {@code /health} endpoint
     * (the path llama.cpp / OpenAI-compatible servers expose).
     */
    private boolean probeExternalLlm() {
        String baseUrl = config.getLlmExternalBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        String trimmed = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        String url = trimmed + "/health";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(PROBE_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.discarding());
            boolean up = response.statusCode() >= 200 && response.statusCode() < 300;
            if (!up) {
                log.warn("Readiness probe: external LLM at {} returned HTTP {}", url, response.statusCode());
            }
            return up;
        } catch (Exception e) {
            log.warn("Readiness probe: external LLM at {} unreachable - {}", url, e.toString());
            return false;
        }
    }
}
