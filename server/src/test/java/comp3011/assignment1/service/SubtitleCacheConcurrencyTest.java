package comp3011.assignment1.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import comp3011.assignment1.model.SubtitleCue;

/**
 * Proves that {@link SubtitleCache} makes exactly one paid call per clip, even when many players
 * ask for the same clip at the same moment.
 */
class SubtitleCacheConcurrencyTest {
    private static final List<SubtitleCue> CUES = List.of(new SubtitleCue(0, 1, List.of("hi")));

    @Test
    @DisplayName("100 simultaneous requests for one clip cause exactly one transcription")
    void singleFlight() throws Exception {
        SubtitleCache cache = new SubtitleCache(100);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<SubtitleCache.Lookup>> results = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return cache.getOrCompute("clip", () -> {
                        calls.incrementAndGet();
                        sleep(200); // A slow upstream call, so the other requests pile up behind it.
                        return CUES;
                    });
                }));
            }
            start.countDown();

            int cachedCount = 0;
            for (Future<SubtitleCache.Lookup> result : results) {
                SubtitleCache.Lookup lookup = result.get();
                assertThat(lookup.cues()).isEqualTo(CUES);
                if (lookup.cached()) {
                    cachedCount++;
                }
            }
            assertThat(calls.get()).isEqualTo(1);
            assertThat(cachedCount).isEqualTo(99);
        }
    }

    @Test
    @DisplayName("a failed transcription is not cached, so the next request tries again")
    void failuresAreRetried() {
        SubtitleCache cache = new SubtitleCache(100);

        assertThatThrownBy(() -> cache.getOrCompute("clip", () -> {
            throw new IllegalStateException("network down");
        })).isInstanceOf(IllegalStateException.class);

        SubtitleCache.Lookup retry = cache.getOrCompute("clip", () -> CUES);
        assertThat(retry.cached()).isFalse();
        assertThat(retry.cues()).isEqualTo(CUES);
    }

    @Test
    @DisplayName("the oldest clips are dropped once the cache is full")
    void evictsOldest() {
        SubtitleCache cache = new SubtitleCache(2);
        cache.getOrCompute("a", () -> CUES);
        cache.getOrCompute("b", () -> CUES);
        cache.getOrCompute("c", () -> CUES);

        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.getOrCompute("a", () -> CUES).cached()).isFalse();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
