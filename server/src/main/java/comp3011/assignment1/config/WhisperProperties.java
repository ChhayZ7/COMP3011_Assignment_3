package comp3011.assignment1.config;

import org.springframeworl.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code stt.whisper.*} properties used by the subtitle endpoint (Assignment 3).
 *
 * Kept separate from {@link OpenAiProperties} because subtitles have different needs from the
 * Assignment 1 transcription endpoint: the assignment fixes the model to {@code whisper-1}, and a
 * verbose transcription of a 30 second clip can take far longer than a short browser recording, so
 * it gets its own, longer timeout.
 *
 * @param model          the transcription model, {@code whisper-1} as the assignment requires
 * @param timeoutSeconds connect and read timeout for one Whisper call
 * @param apiKeyFile     optional path to a file holding the API key (e.g. {@code key.txt} on the
 *                       viva USB stick). Used only when {@code OPENAI_API_KEY} is not set.
 */
@COnfigurationProperties(prefix = "stt.whisper")
public record WhisperProperties(String model, int timeoutSeconds, String apiKeyFile) {
}
