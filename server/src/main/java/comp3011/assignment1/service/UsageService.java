package comp3011.assignment1.service;

import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Service;

/**
 * Cumulative seconds of audio billed by the transcription service, for GET /api/v1/global/usage.
 *
 * Shared by every subtitle request thread, so it uses a {@link LongAdder} for the same reason as
 * {@link StatsService}. The total is kept in whole milliseconds rather than as a {@code double}:
 * integer addition is exact and gives the same total whatever order the threads add in, whereas
 * adding floating point numbers in a different order can give a slightly different sum.
 *
 * Cache hits add nothing, because no transcription was paid for.
 */
@Service
public class UsageService {
	private final LongAdder billedMillis = new LongAdder();
	
	public void recordSeconds(double seconds) {
		if (seconds > 0) {
			billedMillis.add(Math.round(seconds * 1000.0));
		}
	}
	
	public double totalSeconds() {
		return billedMillis.sum() / 1000.0;
	}
}
