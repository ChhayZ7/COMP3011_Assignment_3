package comp3011.assignment1.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import comp3011.assignment1.error.ShutdownInProgressException;

/**
 * Exercises the race condition in {@link ServerLifecycleService#requestShutdown()} directly,
 * under genuine concurrent pressure rather than by inspecting the code.
 *
 * The class under test is instantiated with {@code performExit()} overridden to a no-op. Without
 * that seam, a passing call to {@code requestShutdown()} here would schedule a real
 * {@code System.exit} roughly 500ms later — silently killing the JVM running this very test suite,
 * well after the assertion had already passed. See the Javadoc on {@code performExit} for the full
 * reasoning.
 *
 * This is a plain unit test, not a {@code @SpringBootTest}: the class under test only needs an
 * {@code ApplicationContext} reference to pass to {@code SpringApplication.exit} inside the
 * overridden method, which is never invoked, so {@code null} is safe here.
 */

public class ServerLifecycleServiceConcurrencyTest {
	private static final int CONCURRENT_REQUESTS = 50;
	
	@Test
	@DisplayName("under 50 simultaneous shutdown requests, exactly one succeeds and the rest are rejected")
	void exactlyOneConcurrentShutdownRequestSucceeds() throws InterruptedException {
	    ServerLifecycleService lifecycleService = new ServerLifecycleService(null) {
	        @Override
	        protected void performExit() {
	            // Intentionally does nothing: see the class Javadoc for why a real exit here
	            // would be unsafe inside a test JVM.
	            }
	        };
	 
	        CountDownLatch readyLatch = new CountDownLatch(CONCURRENT_REQUESTS);
	        CountDownLatch startLatch = new CountDownLatch(1);
	        List<Boolean> accepted = new CopyOnWriteArrayList<>();
	        List<Boolean> rejected = new CopyOnWriteArrayList<>();
	 
	        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	        try {
	            IntStream.range(0, CONCURRENT_REQUESTS).forEach(i -> executor.submit(() -> {
	                readyLatch.countDown();
	                try {
	                // Every thread waits at this gate so requestShutdown() is called by all of
	                // them as close to simultaneously as the JVM can arrange, rather than in a
	                // staggered sequence that would never actually exercise the race.
	                    startLatch.await();
	                    lifecycleService.requestShutdown();
	                    accepted.add(true);
	                } catch (ShutdownInProgressException expected) {
	                    rejected.add(true);
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
	 
	    // The behaviour this test exists to catch: a naive check-then-set (rather than
	    // compareAndSet) would let more than one thread observe "not yet requested" and both
	    // succeed. Asserting the exact count, not just "at least one", is what would actually
	    // fail if that regression were reintroduced.
	    assertThat(accepted).hasSize(1);
	    assertThat(rejected).hasSize(CONCURRENT_REQUESTS - 1);
	    assertThat(lifecycleService.isShutdownRequested()).isTrue();
	}
}
