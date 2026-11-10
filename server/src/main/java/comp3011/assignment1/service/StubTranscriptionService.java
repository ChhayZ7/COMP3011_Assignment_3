package comp3011.assignment1.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import comp3011.assignment1.model.TranscriptionResult;

/**
 * Offline stand-in for the Cloud STT provider, active under the {@code stub} profile.
 *
 * Two purposes. It lets the full request path be exercised locally and in regression tests
 * with no API key present and no network call, and it makes load testing possible without
 * incurring real API cost or hitting provider rate limits.
 *
 * An artificial delay is applied so that the stub still exhibits the blocking-on-IO
 * behaviour that the concurrency design has to cope with. Without it, load tests would pass
 * trivially and prove nothing about thread handling.
 */

@Service
@Profile("stub")
public class StubTranscriptionService implements TranscriptionService {
	private static final Logger log = LoggerFactory.getLogger(StubTranscriptionService.class);
	private final long simulatedLatencyMillis;
	
	public StubTranscriptionService(
			@org.springframework.beans.factory.annotation.Value("${stt.stub.latency-millis:250}")
			long simulatedLatencyMillis) {
		this.simulatedLatencyMillis = simulatedLatencyMillis;
	}
	
	@Override
	public TranscriptionResult transcribe(byte[] audio, String filename, String contentType) {
		log.debug("Stub transcription of {} byte ({})", audio.length, contentType);
		if (simulatedLatencyMillis > 0) {
			try {
				Thread.sleep(simulatedLatencyMillis);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new comp3011.assignment1.error.TranscriptionException("Transcription interrupted", e);
			}
		}
		// Token counts are derived from payload size purely so that repeated stud calls move the
		// global counters in a predictable, assertable way.
		long inputTokens = Math.max(1, audio.length / 1024);
		return new TranscriptionResult("This is a stubbed transcription result.", inputTokens, 8L);
	}
}
