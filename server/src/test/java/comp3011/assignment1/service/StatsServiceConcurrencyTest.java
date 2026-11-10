package comp3011.assignment1.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link StatsService}'s counters are race-safe under genuine concurrent writes, not just
 * correct when called from a single thread.
 *
 * A plain {@code long total; total += n;} looks atomic but is actually a read-modify-write
 * spanning three separate steps, so concurrent callers can silently lose updates. This test fires
 * many threads at the same counters simultaneously and asserts the exact expected sum; a
 * regression to a non-atomic counter would show up here as a total lower than expected, not as an
 * exception, which is why the assertion checks the precise value rather than just "greater than
 * zero".
 */
class StatsServiceConcurrencyTest {

    private static final int CONCURRENT_WRITERS = 200;
    private static final long INPUT_TOKENS_PER_CALL = 3;
    private static final long OUTPUT_TOKENS_PER_CALL = 1;

    @Test
    @DisplayName("200 concurrent recordUsage calls produce an exact total, with no lost updates")
    void concurrentUsageRecordingProducesExactTotal() throws InterruptedException {
        StatsService statsService = new StatsService();

        CountDownLatch readyLatch = new CountDownLatch(CONCURRENT_WRITERS);
        CountDownLatch startLatch = new CountDownLatch(1);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            IntStream.range(0, CONCURRENT_WRITERS).forEach(i -> executor.submit(() -> {
                readyLatch.countDown();
                try {
                    // All writers wait at this gate so the increments land as close to
                    // simultaneously as the JVM can arrange, rather than trickling in sequentially
                    // — a staggered sequence would never actually exercise the race.
                    startLatch.await();
                    statsService.recordUsage(INPUT_TOKENS_PER_CALL, OUTPUT_TOKENS_PER_CALL);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));

            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.countDown();
        } finally {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        var snapshot = statsService.snapshot();
        assertThat(snapshot.inputTokens()).isEqualTo(CONCURRENT_WRITERS * INPUT_TOKENS_PER_CALL);
        assertThat(snapshot.outputTokens()).isEqualTo(CONCURRENT_WRITERS * OUTPUT_TOKENS_PER_CALL);
    }
}