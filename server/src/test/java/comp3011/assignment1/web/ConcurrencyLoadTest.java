package comp3011.assignment1.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import comp3011.assignment1.model.TranscriptionResponse;

/**
 * Demonstrates the application handling more than 200 simultaneous blocking HTTP requests without
 * significant delay or failure — the non-functional target this project is built around.
 *
 * <p>{@code StubTranscriptionService} carries an artificial 200ms sleep per call specifically so
 * this test exercises genuine blocking-on-IO behaviour: without it, a load test would pass
 * trivially and prove nothing about how the server behaves while a real Cloud STT call is in
 * flight. With {@code spring.threads.virtual.enabled=true}, each request's servlet thread parks
 * during that sleep instead of occupying a platform thread, so 250 concurrent requests should
 * complete in roughly one request's latency, not 250 times that.
 *
 * <p>The client side matters as much as the server side here: requests are fired from a virtual
 * thread per task so the test itself does not become the bottleneck by sending them one at a time.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("stub")
@TestPropertySource(properties = "stt.stub.latency-millis=200")
class ConcurrencyLoadTest {

    private static final int CONCURRENT_REQUESTS = 250;
    private static final long STUB_LATENCY_MILLIS = 200;

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("250 concurrent transcription requests all succeed, well under sequential time")
    void handles250ConcurrentBlockingRequestsWithoutFailureOrSignificantDelay() throws InterruptedException {
        // The default RestTemplate backing TestRestTemplate uses java.net.HttpURLConnection,
        // which historically caps concurrent connections per host at around 5 — a limit from an
        // era before keep-alive connection pooling was standard. Two hundred and fifty "concurrent"
        // requests through that client would queue behind those few connections regardless of how
        // well the server itself handles concurrency, making this test measure the client's
        // bottleneck instead of the server's. JdkClientHttpRequestFactory backs the client with
        // java.net.http.HttpClient, which has no such cap.
        RestTemplateBuilder builder = new RestTemplateBuilder().requestFactory(JdkClientHttpRequestFactory::new);
        TestRestTemplate restTemplate = new TestRestTemplate(builder);
        String url = "http://localhost:" + port + "/api/v1/transcribe";

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        List<Callable<HttpStatusCode>> requests = IntStream.range(0, CONCURRENT_REQUESTS)
                .<Callable<HttpStatusCode>>mapToObj(i -> () -> sendOneRequest(restTemplate, url))
                .collect(Collectors.toList());

        long startNanos = System.nanoTime();
        List<Future<HttpStatusCode>> results;
        try {
            results = executor.invokeAll(requests, 60, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        long successCount = results.stream()
                .map(this::resolveQuietly)
                .filter(status -> status != null && status.value() == HttpStatus.OK.value())
                .count();

        assertThat(successCount)
                .as("all %d concurrent requests should succeed", CONCURRENT_REQUESTS)
                .isEqualTo(CONCURRENT_REQUESTS);

        // Run sequentially, 250 requests at 200ms each would take 50 seconds. A generous 10x
        // safety margin over one request's latency is used as the threshold rather than a tight
        // bound, since CI and shared machines vary — the point is distinguishing "concurrent" from
        // "sequential", not chasing a specific millisecond figure.
        long sequentialTimeMillis = CONCURRENT_REQUESTS * STUB_LATENCY_MILLIS;
        long concurrencyThresholdMillis = STUB_LATENCY_MILLIS * 10;
        assertThat(elapsedMillis)
                .as("elapsed time should reflect concurrent handling, not %dms of sequential processing",
                        sequentialTimeMillis)
                .isLessThan(concurrencyThresholdMillis);
    }

    private HttpStatusCode sendOneRequest(TestRestTemplate restTemplate, String url) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource resource = new ByteArrayResource(new byte[2048]) {
            @Override
            public String getFilename() {
                return "clip.wav";
            }
        };
        body.add("file", resource);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<TranscriptionResponse> response =
                restTemplate.postForEntity(url, new HttpEntity<>(body, headers), TranscriptionResponse.class);
        return response.getStatusCode();
    }

    private HttpStatusCode resolveQuietly(Future<HttpStatusCode> future) {
        try {
            return future.get();
        } catch (Exception e) {
            // A request that failed outright (timeout, connection reset, thread pool exhaustion)
            // is exactly the failure mode this test exists to catch. Treating it as a non-200
            // "result" rather than letting the test itself blow up keeps the success-count
            // assertion the single source of truth for pass/fail.
            return null;
        }
    }
}