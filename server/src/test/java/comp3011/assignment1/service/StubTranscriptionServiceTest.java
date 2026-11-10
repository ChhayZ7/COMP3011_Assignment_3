package comp3011.assignment1.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import comp3011.assignment1.model.TranscriptionResult;

/**
 * Regression tests for {@link StubTranscriptionService}.
 *
 * Two things are under test here. First, that profile-based wiring actually resolves: with the
 * {@code stub} profile active, an injection point declared as {@link TranscriptionService} must
 * receive the stub rather than a real Cloud provider. Second, that the stub's output is
 * deterministic, since every later controller and load test asserts against these values.
 *
 * The simulated latency is overridden to a token value so the suite stays fast. The delay
 * itself is exercised deliberately by the load tests, not here.
 */

@SpringBootTest
@ActiveProfiles("stub")
@TestPropertySource(properties = "stt.stub.latency-millis=1")
public class StubTranscriptionServiceTest {
	/**
     * Injected by interface, never by concrete type. Declaring the field as
     * {@code StubTranscriptionService} would make the test pass for the wrong reason: it would
     * prove the class exists, not that Spring selects it for callers that ask for the interface.
     */
	@Autowired
	private TranscriptionService transcriptionService;
	
	@Test
	@DisplayName("stub profile wires StubTranscriptionService in behind the interface")
	void stubProfileResolvesToStubImplementation() {
		assertThat(transcriptionService).isInstanceOf(StubTranscriptionService.class);
	}
	
	@Test
	@DisplayName("returned the fixed stub transcript regardless of input")
	void returnsFixedTranscript() {
		TranscriptionResult result = transcriptionService.transcribe(new byte[1024], "test.wav", "audio/wav");
		
		assertThat(result.text()).isEqualTo("This is a stubbed transcription result.");
	}
	
	@Test
	@DisplayName("reports one input token per whole kilobyte of audio")
	void inputTokensScaleWithPayloadSize() {
		TranscriptionResult oneKilobyte = transcriptionService.transcribe(new byte[1024], "small.wav", "audio/wav");
		TranscriptionResult fiveThousandBytes = transcriptionService.transcribe(new byte[5000], "larger.wav", "audio/wav");
		
		assertThat(oneKilobyte.inputTokens()).isEqualTo(1L);
		// 5000 / 1024 tuncates to 4 under integer division.
		assertThat(fiveThousandBytes.inputTokens()).isEqualTo(4L);
	}
	
	@Test
	@DisplayName("never reports zero input tokens, even for a payload under one kilobyte")
	void inputTokensAreFlooredAtOne() {
		TranscriptionResult tiny = transcriptionService.transcribe(new byte[10], "tiny.wav", "audio/wav");
		
		// Math.max(1, ...) guards this. Without the floor, sub-kilobyte uploads would advance the
		// global counters by nothing and the stats endpoint would under-report real traffic
		assertThat(tiny.inputTokens()).isEqualTo(1L);
	}
	
	@Test
	@DisplayName("reports a constant output token count")
	void outputTokensAreConstant() {
		TranscriptionResult result = transcriptionService.transcribe(new byte[2048], "test.wav", "audio/wav");
		
		assertThat(result.outputTokens()).isEqualTo(8L);
	}
}
