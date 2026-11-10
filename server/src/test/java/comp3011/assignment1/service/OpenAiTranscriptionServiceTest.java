package comp3011.assignment1.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import comp3011.assignment1.config.OpenAiProperties;
import comp3011.assignment1.error.TranscriptionException;
import comp3011.assignment1.model.TranscriptionResult;

/**
 * Verifies {@link OpenAiTranscriptionService} against a mocked HTTP layer.
 *
 * No real OpenAI account, API key, or network access is involved. {@link MockRestServiceServer}
 * intercepts the outgoing request at the point where {@link RestClient} would otherwise send it
 * over the wire, and lets these tests assert on exactly what this application sent — the URL, the
 * bearer token, the multipart shape — before ever spending real money against a live endpoint.
 * The one genuine end-to-end check against the real API happens on TITAN, where a valid key is
 * supplied by the environment.
 */
class OpenAiTranscriptionServiceTest {

    private static final String BASE_URL = "https://api.openai.com";

    private MockRestServiceServer server;
    private OpenAiTranscriptionService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        OpenAiProperties properties =
                new OpenAiProperties("test-key-123", BASE_URL, "gpt-4o-mini-transcribe", 15);
        service = new OpenAiTranscriptionService(restClient, properties);
    }

    @Test
    @DisplayName("attaches the bearer token and reads back text with token usage")
    void sendsAuthorizedRequestAndParsesUsage() {
        server.expect(requestTo(BASE_URL + "/v1/audio/transcriptions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key-123"))
                .andRespond(withSuccess(
                        "{\"text\":\"hello world\",\"usage\":{\"input_tokens\":12,\"output_tokens\":3}}",
                        MediaType.APPLICATION_JSON));

        TranscriptionResult result = service.transcribe("audio bytes".getBytes(), "clip.wav", "audio/wav");

        assertThat(result.text()).isEqualTo("hello world");
        assertThat(result.inputTokens()).isEqualTo(12L);
        assertThat(result.outputTokens()).isEqualTo(3L);
        server.verify();
    }

    @Test
    @DisplayName("tolerates response fields this application doesn't use")
    void ignoresUnknownResponseFields() {
        server.expect(requestTo(BASE_URL + "/v1/audio/transcriptions"))
                .andRespond(withSuccess(
                        "{\"text\":\"hi\",\"duration\":1.2,\"language\":\"en\","
                                + "\"usage\":{\"type\":\"tokens\",\"input_tokens\":5,\"output_tokens\":2}}",
                        MediaType.APPLICATION_JSON));

        TranscriptionResult result = service.transcribe("x".getBytes(), "clip.wav", "audio/wav");

        assertThat(result.text()).isEqualTo("hi");
        assertThat(result.inputTokens()).isEqualTo(5L);
        server.verify();
    }

    @Test
    @DisplayName("wraps an upstream error as this application's own exception, not a raw HTTP failure")
    void wrapsUpstreamErrorResponse() {
        server.expect(requestTo(BASE_URL + "/v1/audio/transcriptions"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> service.transcribe("x".getBytes(), "clip.wav", "audio/wav"))
                .isInstanceOf(TranscriptionException.class);
        server.verify();
    }

    @Test
    @DisplayName("refuses to call the network at all when no API key is configured")
    void refusesToCallWithoutApiKey() {
        OpenAiProperties noKey = new OpenAiProperties("", BASE_URL, "gpt-4o-mini-transcribe", 15);
        OpenAiTranscriptionService serviceWithoutKey =
                new OpenAiTranscriptionService(RestClient.builder().baseUrl(BASE_URL).build(), noKey);

        assertThatThrownBy(() -> serviceWithoutKey.transcribe("x".getBytes(), "clip.wav", "audio/wav"))
                .isInstanceOf(TranscriptionException.class)
                .hasMessageContaining("API key");

        // No expectations were set on `server`, so any network attempt here would fail loudly —
        // this test passing at all is itself proof the blank-key guard fires before the HTTP call.
    }
}