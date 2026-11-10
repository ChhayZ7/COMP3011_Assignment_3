package comp3011.assignment1.service;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import comp3011.assignment1.model.UptimeResponse;
import comp3011.assignment1.error.ShutdownInProgressException;

/*
 * Server lifecycle state: when the process started, and whether a graceful shutdown has been accepted.
 */
@Service
public class ServerLifecycleService {
	private static final Logger log = LoggerFactory.getLogger(ServerLifecycleService.class);
	
//	Delay before tearing down the context, so the 202 response reaches the client first.
	private static final Duration SHUTDOWN_DELAY = Duration.ofMillis(500);
	
	private final Instant utcServerStart;
	private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
	private final ApplicationContext applicationContext;
	
	public ServerLifecycleService(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
		// The JVM's own start time is used rather than Instant.now() at bean construction, so the 
		// reported value is the true process start and does not drift with Spring startup cost.
		this.utcServerStart = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());
	}
	
	// Builds the uptime payload, computing elapsed seconds at nanosecond resolution. 
	public UptimeResponse currentUptime() {
		Instant now = Instant.now();
		double seconds = Duration.between(utcServerStart, now).toNanos() / 1_000_000_000.0;
		return new UptimeResponse(
				DateTimeFormatter.ISO_INSTANT.format(utcServerStart),
				DateTimeFormatter.ISO_INSTANT.format(now),
				seconds);
	}
	/**
	 * Accepts a graceful shutdown request.
	 *
	 * {@code compareAndSet} makes the accept/reject decision atomic: if two shutdown requests
	 * arrive simultaneously, exactly one wins and the other is rejected with 409. A
	 * check-then-set pair would let both requests observe {@code false} and both succeed.
	 *
	 * The context is closed on a separate non-daemon platform thread after a short delay.
	 * Closing it inline would tear down the container while the servlet was still writing the
	 * response, so the caller would see a dropped connection instead of the 202 the
	 * specification requires.
	 *
	 * @throws ShutdownInProgressException if a shutdown has already been accepted
	 */
	public void requestShutdown() {
		if (!shutdownRequested.compareAndSet(false, true)) {
			throw new ShutdownInProgressException();
		}
		log.info("Graceful shutdown accepted; closing application context in {}ms", SHUTDOWN_DELAY.toMillis());
		Thread closer = new Thread(this::closeAfterDelay, "graceful-shutdown");
		closer.setDaemon(false);
		closer.start();	
	}
	
	// Exposed for tests that need to assert the rejection path without killing the JVM
	public boolean isShutdownRequested() {
		return shutdownRequested.get();
	}
	
	private void closeAfterDelay() {
		try {
			Thread.sleep(SHUTDOWN_DELAY.toMillis());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}
		performExit();
	}
	
    /**
     * Closes the Spring context and terminates the JVM.
     *
     * Deliberately factored out as its own method, overridable in package-private test
     * subclasses. A test that exercised the real shutdown path unmodified would schedule a
     * genuine {@code System.exit} roughly {@link #SHUTDOWN_DELAY} after the call returns —
     * silently killing the JVM running the test suite itself, well after the assertions had
     * already passed. Overriding this one method lets a test verify the compare-and-set race
     * behaviour in {@link #requestShutdown()} without ever reaching a real process exit.
     */
	protected void performExit() {
		// SpringApplication.exit closes the context, which lets Tomcat drain in-flight requests
		// according to server.shutdown=graceful before the JVM terminates.
		int exitCode = SpringApplication.exit(applicationContext, () -> 0);
		System.exit(exitCode);
	}
}
