package comp3011.assignment1.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import comp3011.assignment1.config.ApiKeyProvider;
import comp3011.assignment1.config.OpenAiProperties;
import comp3011.assignment1.config.WhisperProperties;
import comp3011.assignment1.error.TranscriptionException;
import comp3011.assignment1.model.TimedTranscript;

/**
 * Verifies the Whisper request this server sends, and how it reads the reply, against a mocked
 * HTTP layer; no key, network or cost involved.
 */
class OpenAiWhisperServiceTest {
    private static final String BASE_URL = "https://api.openai.com";
    private static final String VERBOSE_JSON = """
            {"task":"transcribe","language":"english","duration":8.47,"text":"Hello there. Bye.",
             "words":[{"word":"Hello","start":0.0,"end":0.4},{"word":"there","start":0.5,"end":0.9},
                      {"word":"Bye","start":2.0,"end":2.3}],
             "segments":[{"id":0,"seek":0,"start":0.0,"end":1.0,"text":" Hello there.","avg_logprob":-0.2,
                          "compression_ratio":1.1,"no_speech_prob":0.01,"temperature":0.0,"tokens":[1,2]},
                         {"id":1,"seek":0,"start":2.0,"end":2.4,"text":" Bye.","avg_logprob":-1.3,
                          "compression_ratio":1.1,"no_speech_prob":0.7,"temperature":0.0,"tokens":[3]}],
             "usage":{"type":"duration","seconds":9}}
            """;

    @TempDir
    Path tempDir;

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
    }

    private OpenAiWhisperService service(String envKey, String keyFile) {
        OpenAiProperties openAi = new OpenAiProperties(envKey, BASE_URL, "unused", 15);
        WhisperProperties whisper = new WhisperProperties("whisper-1", 60, keyFile);
        return new OpenAiWhisperService(builder.build(), whisper, new ApiKeyProvider(openAi, whisper));
    }

    @Test
    @DisplayName("asks whisper-1 for verbose_json with word and segment timestamps")
    void sendsRequiredFields() {
        server.expect(requestTo(BASE_URL + "/v1/audio/transcriptions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer key-1"))
                .andExpect(request -> {
                    String body = request.getBody().toString();
                    assertThat(body).contains("whisper-1", "verbose_json", "timestamp_granularities[]");
                    assertThat(body).contains("word").contains("segment");
                })
                .andRespond(withSuccess(VERBOSE_JSON, MediaType.APPLICATION_JSON));

        TimedTranscript transcript = service("key-1", "").transcribe(new byte[10], "clip.wav", "audio/wav");

        assertThat(transcript.segments()).hasSize(2);
        assertThat(transcript.segments().get(0).text()).isEqualTo("Hello there.");
        assertThat(transcript.segments().get(1).noSpeechProb()).isEqualTo(0.7);
        assertThat(transcript.words()).hasSize(3);
        assertThat(transcript.billedSeconds()).isEqualTo(9.0);
        server.verify();
    }

    @Test
    @DisplayName("reads the key from a file when the environment variable is not set")
    void readsKeyFile() throws Exception {
        Path keyFile = Files.writeString(tempDir.resolve("key.txt"), "file-key\n");
        server.expect(requestTo(BASE_URL + "/v1/audio/transcriptions"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer file-key"))
                .andRespond(withSuccess(VERBOSE_JSON, MediaType.APPLICATION_JSON));

        service("", keyFile.toString()).transcribe(new byte[10], "clip.wav", "audio/wav");
        server.verify();
    }

    @Test
    @DisplayName("wraps upstream failures in this application's own exception")
    void wrapsUpstreamError() {
        server.expect(requestTo(BASE_URL + "/v1/audio/transcriptions"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> service("key-1", "").transcribe(new byte[10], "clip.wav", "audio/wav"))
                .isInstanceOf(TranscriptionException.class);
    }

    @Test
    @DisplayName("refuses to call out with no key at all")
    void noKey() {
        assertThatThrownBy(() -> service("", "").transcribe(new byte[10], "clip.wav", "audio/wav"))
                .isInstanceOf(TranscriptionException.class)
                .hasMessageContaining("API key");
    }
}
