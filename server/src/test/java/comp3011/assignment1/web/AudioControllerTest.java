package comp3011.assignment1.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import comp3011.assignment1.model.ErrorResponse;
import comp3011.assignment1.model.GlobalStatsResponse;
import comp3011.assignment1.model.TranscriptionResponse;

/**
 * Regression tests for {@link AudioController}, driven over real HTTP against the {@code stub}
 * profile.
 *
 * Running through {@code TestRestTemplate} against a real embedded server — rather than calling
 * the controller method directly — is deliberate: it exercises multipart parsing, content
 * negotiation, and {@link comp3011.assignment1.error.GlobalExceptionHandler} exactly as a real
 * client would trigger them, at the cost of a slower test than a unit test would be. No API key,
 * network access, or spend is involved: {@code StubTranscriptionService} answers every call.
 *
 * Token counts are asserted as deltas (after minus before), not absolute values. The stats
 * counters are process-wide singletons shared across every test method in this class, so an
 * absolute assertion would be order-dependent and would break the moment a new test method is
 * added anywhere in the class.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("stub")
class AudioControllerTest {

    @LocalServerPort
    private int port;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    @Test
    void uploadReturnsStubTextAndAdvancesGlobalStats() {
        GlobalStatsResponse before = fetchStats();

        MultiValueMap<String, Object> body = multipartBodyWithFile(1024);
        ResponseEntity<TranscriptionResponse> response = restTemplate.postForEntity(
                transcribeUrl(), new HttpEntity<>(body, multipartHeaders()), TranscriptionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().text()).isEqualTo("This is a stubbed transcription result.");

        GlobalStatsResponse after = fetchStats();
        // StubTranscriptionService reports one input token per whole kilobyte and a constant 8
        // output tokens; a 1024-byte upload should advance the counters by exactly 1 and 8.
        assertThat(after.inputTokens()).isEqualTo(before.inputTokens() + 1);
        assertThat(after.outputTokens()).isEqualTo(before.outputTokens() + 8);
    }

    @Test
    void emptyFileIsRejectedWithBadRequestAndTheYamlErrorShape() {
        MultiValueMap<String, Object> body = multipartBodyWithFile(0);

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                transcribeUrl(), new HttpEntity<>(body, multipartHeaders()), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("No audio data was supplied.");
        assertThat(response.getBody().path()).isEqualTo("/api/v1/transcribe");
    }

    @Test
    void missingFilePartIsRejectedWithBadRequest() {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("notfile", "irrelevant");

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                transcribeUrl(), new HttpEntity<>(body, multipartHeaders()), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void requestThatIsNotMultipartAtAllIsRejectedWithBadRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                transcribeUrl(), new HttpEntity<>("{}", headers), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private GlobalStatsResponse fetchStats() {
        ResponseEntity<GlobalStatsResponse> response =
                restTemplate.getForEntity(baseUrl() + "/api/v1/global/stats", GlobalStatsResponse.class);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private MultiValueMap<String, Object> multipartBodyWithFile(int sizeInBytes) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource resource = new ByteArrayResource(new byte[sizeInBytes]) {
            @Override
            public String getFilename() {
                return "clip.wav";
            }
        };
        body.add("file", resource);
        return body;
    }

    private HttpHeaders multipartHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return headers;
    }

    private String transcribeUrl() {
        return baseUrl() + "/api/v1/transcribe";
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }
}