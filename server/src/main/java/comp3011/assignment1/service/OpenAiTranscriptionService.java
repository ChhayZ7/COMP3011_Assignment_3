package comp3011.assignment1.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import comp3011.assignment1.config.OpenAiProperties;
import comp3011.assignment1.error.TranscriptionException;
import comp3011.assignment1.model.TranscriptionResult;

/**
 * Real Cloud STT provider: calls OpenAI's {@code /v1/audio/transcriptions} endpoint.
 *
 * Active in every profile except {@code stub}, so a bare {@code java -jar} with no profile
 * flag — exactly how TITAN launches the application — resolves to this implementation, while
 * {@code @Profile("stub")} on {@link StubTranscriptionService} takes over during local
 * development and testing. Exactly one {@link TranscriptionService} bean exists in any given
 * profile; the two annotations are deliberately complementary rather than independently toggled.
 */
@Service
@Profile("!stub")
public class OpenAiTranscriptionService implements TranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiTranscriptionService.class);
    private static final String TRANSCRIPTIONS_PATH = "/v1/audio/transcriptions";

    private final RestClient restClient;
    private final OpenAiProperties properties;

    public OpenAiTranscriptionService(RestClient openAiRestClient, OpenAiProperties properties) {
        this.restClient = openAiRestClient;
        this.properties = properties;
    }

    @Override
    public TranscriptionResult transcribe(byte[] audio, String filename, String contentType) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            // Fails fast with this application's own exception vocabulary, before ever reaching
            // the network. A blank key would otherwise surface as an opaque 401 from OpenAI.
            throw new TranscriptionException("No Cloud STT API key is configured.");
        }

        MultiValueMap<String, Object> body = buildRequestBody(audio, filename, contentType);

        try {
            OpenAiTranscriptionResponse response = restClient.post()
                    .uri(TRANSCRIPTIONS_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(OpenAiTranscriptionResponse.class);

            if (response == null || response.text() == null) {
                throw new TranscriptionException("The speech-to-text service returned an empty response.");
            }

            long inputTokens = response.usage() != null && response.usage().inputTokens() != null
                    ? response.usage().inputTokens() : 0L;
            long outputTokens = response.usage() != null && response.usage().outputTokens() != null
                    ? response.usage().outputTokens() : 0L;

            return new TranscriptionResult(response.text(), inputTokens, outputTokens);

        } catch (RestClientResponseException ex) {
            // The response body is deliberately not forwarded to the client or included in the
            // exception message: upstream error bodies can echo request details, and this
            // application's own failure vocabulary is TranscriptionException, not OpenAI's.
            log.error("OpenAI transcription request failed with HTTP status {}", ex.getStatusCode().value());
            throw new TranscriptionException("The speech-to-text service rejected the request.", ex);
        } catch (RestClientException ex) {
            log.error("Failed to reach the OpenAI transcription endpoint", ex);
            throw new TranscriptionException("Failed to reach the speech-to-text service.", ex);
        }
    }

    private MultiValueMap<String, Object> buildRequestBody(byte[] audio, String filename, String contentType) {
        ByteArrayResource audioResource = new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                return filename;
            }
        };

        HttpHeaders filePartHeaders = new HttpHeaders();
        filePartHeaders.setContentType(MediaType.parseMediaType(contentType));

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(audioResource, filePartHeaders));
        body.add("model", properties.model());
        body.add("response_format", "json");
        return body;
    }

    /**
     * Minimal shape of OpenAI's transcription response. {@code ignoreUnknown} matters here: the
     * real payload carries additional fields (duration, language, and more) that this application
     * has no use for, and a schema change on OpenAI's side should not be able to break
     * deserialisation of the two fields this application actually reads.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenAiTranscriptionResponse(String text, Usage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Usage(
            @JsonProperty("input_tokens") Long inputTokens,
            @JsonProperty("output_tokens") Long outputTokens) {
    }
}