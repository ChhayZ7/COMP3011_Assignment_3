package comp3011.assignment1.service;

import comp3011.assignment1.model.TranscriptionResult;

/**
 * Abstraction over the Cloud speech-to-text provider.
 *
 * Controllers depend on this interface, never on a concrete provider. That is what allows a
 * stub implementation to be injected during regression testing so controller behaviour can be
 * verified without network access, cost, or a live API key.
 */
public interface TranscriptionService {
    /**
     * Converts audio to text.
     *
     * @param audio       raw audio bytes as uploaded by the browser
     * @param filename    original filename, used by the provider to infer the container format
     * @param contentType MIME type of the audio
     * @return the transcribed text together with token usage for that call
     * @throws comp3011.assignment1.error.TranscriptionException if conversion fails
     */
	TranscriptionResult transcribe(byte[] audio, String filename, String contentType);
}
