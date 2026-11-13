package comp3011.assignment1.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

/**
 * Supplies the OpenAI API key for each outgoing call.
 *
 * The key comes from the {@code OPENAI_API_KEY} environment variable. If that is not set, it is
 * read from the file named by {@code OPENAI_API_KEY_FILE} instead. The file is read again on every
 * call rather than once at startup, because the course replaces the shared key often: dropping a
 * fresh {@code key.txt} in place then takes effect without restarting the server.
 *
 * The key is never logged and never stored anywhere else.
 */
@Component
public class ApiKeyProvider {
	private final OpenAiProperties openAiProperties;
	private final WhisperProperties whisperProperties;
	
	public ApiKeyProvider(OpenAiProperties openAiProperties, WhisperProperties whisperProperties) {
		this.openAiProperties = openAiProperties;
		this.whisperProperties = whisperProperties;
	}
	
	/* The API key, or an empty string if none is configured. */
	public String apiKey() {
		String fromEnvironment = openAiProperties.apiKey();
		if (fromEnvironment != null && !fromEnvironment.isBlank()) {
			return fromEnvironment.trim();
		}
		String file = whisperProperties.apiKeyFile();
		if(file == null || file.isBlank()) {
			return "";
		}
		try {
			return Files.readString(Path.of(file), StandardCharsets.UTF_8).trim();
		} catch (IOException e) {
			// A missing key file is treated exactly like a missing key: the caller reports it.
			return "";
		}
	}
}
