package comp3011.assignment1.service;

import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Service;

import comp3011.assignment1.model.GlobalStatsResponse;

/**
 * Global, process-wide token usage counters for the Cloud STT service.
 *
 * This is the one piece of genuinely shared mutable state in the application, written
 * concurrently by every in-flight transcription. A plain {@code long += n} here would lose
 * updates under concurrent load because it is a read-modify-write, not an atomic operation.
 *
 * {@link LongAdder} is used in preference to {@code AtomicLong}: both are correct, but
 * LongAdder spreads contention across internal cells and so degrades far less when many
 * threads increment simultaneously. Reads are rare (only the stats endpoint), writes are
 * frequent, which is exactly the trade-off LongAdder is designed for.
 *
 * Counters are per-process and reset on restart, as the specification requires.
 */
@Service
public class StatsService {
	private final LongAdder inputTokens = new LongAdder();
	private final LongAdder outputTokens = new LongAdder();
	
	/*
	 * Records the token usage of one completed STT call.
	 * 
	 * @param input input tokens billed
	 * @param output output tokens generated
	 */
	public void recordUsage(long input, long output) {
		inputTokens.add(input);
		outputTokens.add(output);
	}
	
	/*
	 * Takes a point-in-time snapshot of the counters.
	 * 
	 * The two sums are read independently, so a snapshot taken during heavy traffic
	 * may reflect slightly different instants for each counter. That is acceptable
	 * for a monitoring endpoint and is preferable to locking the write path to make
	 * reads atomic.
	 */
	public GlobalStatsResponse snapshot() {
		return new GlobalStatsResponse(inputTokens.sum(), outputTokens.sum());
	}
}
