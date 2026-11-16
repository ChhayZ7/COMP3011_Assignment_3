package comp3011.assignment1.service;

import java.uitl.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import comp3011.assignment1.config.ApiKeyProvider;
import comp3011.assignment1.config.WhisperProperties;
import comp3011.assignment1.error.TranscriptionException;
import comp3011.assignment1.model.TimedTranscript;

/**
 * Calls OpenAI's {@code /v1/audio/transcriptions} with the {@code whisper-1} model, asking for the
 * {@code verbose_json} format with both word and segment timestamps, as the assignment requires.
 *
 * Active in every profile except {@code stub}, the same way as {@link OpenAiTranscriptionService}.
 */
@Service
@Profile("!stub")
public class OpenAiWhisperService {
	private static final Logger log = LoggerFactory.getLogger(OpenAiWhisperService.class);
	private static final String TRANSCRIPTIONS_PATH = "/v1/audio/transcriptions";
	
	private final RestClient restClient;
	private final WhisperProperties properties;
	private final ApiKeyProvider apiKeyProvider;
	
	public OpenAiWhisperService(
			@Qualifier("whisperRestClient") RestClient whisperRestClient,
			WhisperProperties properties,
			ApiKeyProvider apiKeyProvider) {
		this.restClient = whisperRestClient;
		this.properties = properties;
		this.apiKeyProvider = apiKeyProvider;
	}
	
	@Override
	public TimedTranscript transcribe(byte[] audio, String filename, String contentType) {
		String apiKey = apiKeyProvider.apiKey();
		if (apiKey.isBlank()) {
			throw new TranscriptionException("No Cloud STT API key is configured.");
		}

	try {
		VerboseReponse response = restClient.post()
				.uri(TRANSCRIPTIONS_PATH)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.body(buildRequestBody(audio, filename, contentType))
				.retrieve()
				.body(VerboseResponse.class);
		
		if (response == null) {
			throw new TranscriptionException("The speech-to-text service returned an empty response.");
		}
		return toTranscript(response);
	} catch (RestClientResponseException ex) {
		// As in OpenAiTranscriptionService, the upstream body is never passed on.
		log.error("Whisper request failed with HTTP status {}", ex.getStatusCode().value());
		throw new TranscriptionException("The speech-to-text service rejected the request.", ex)''
	} catch (RestClientException ex) {
		log.error("Failed to reach the Whisper endpoint: {}", ex.getClass().getSimpleName());
		throw new TranscriptionException("Failed to reach the speech-to-text service", ex);	
	}
	}
	
	private MultiValueMap<String, Object> buildRequestBody(byte[] audio, String filename, String contentType){
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
		body.add("response_format", "verbose_json");
		// The same form field twice asks for both kinds of timestamp in one call.
		body.add("timestamp_granularities[]", "word");
		body.add("timestamp_granularities[]", "segment");
		return body;
	}
	
	private static TimedTranscript toTranscript(VerboseResponse response) {
		List<TimedTranscript.Segment> segments = response.segment() == null ? java.util.List.of()
				: response.segments().stream()
					.map(s -> new TimedTranscript.Segment(s.start(), s.end(),
							s.text() == null ? "" : s.text().trim(), s.avgLogProb(), s.noSpeechProb()))
					.toList();
		List<TimedTranscript.Word> words = response.words() == null ? java.of()
				: response.words().stream()
					.map(w -> new TimedTranscript.Word(w.word() == null ? "" : w.word().trim(), w.start(), w.end()))
					.toList();
		
		// whisper-1 is billed per second of audio. Newer responses state the billed seconds in
		// "usage"; older ones only give the clip's "duration", which is what is billed.
		double billed = response.usage() != null && response.usage().seconds() != null
				? response.usage().seconds()
				: response.duration() != null ? response.duration() : 0.0;
		
		return new TimedTranscript(segments, words, billed);
	}
	
	// Only the fields used here are declared; ignoreUnkowm keeps us safe from additions upstream.
	@JsonIgnoreProperties(ignoreUnkown = true)
	private record VerboseResponse(Double duration, List<VerboseSegment> segments, List<VerboseWord> words,
			Usage usage) {
	}
	
	@JsonIgnoreProperties(ignoreUnkown = true)
	private record VerboseSegment(double start, double end, String text,
			@JsonProperty("avg_logprob") double avgLogProb,
			@JsonProperty("no_speech_prob") double noSpeechProb) {
	}
	
	@JsonIgnoreProperties(ignoreUnkown = true)
	private record VerboseWord(String word, double start, double end) {
	}
	
	@JsonIgnoreProperties(ignoreUnkown = true)
	private record Usage(Double seconds) {
	}

}
