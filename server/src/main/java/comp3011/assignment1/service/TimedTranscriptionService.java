package comp3011.assignment1.service;

import comp3011.assignment1.model.TimedTranscript;

/**
 * Speech-to-text with timings, used for subtitles.
 *
 * Mirrors {@link TranscriptionService}: the real implementation calls OpenAI's Whisper model and a
 * stub replaces it under the {@code stub} profile, so the subtitle endpoint can be developed and
 * tested with no key, network or cost.
 */
public interface TimedTranscriptionService {
    /**
     * @param audio       the audio clip
     * @param filename    file name sent to the provider, which uses it to detect the format
     * @param contentType MIME type of the audio
     * @return segments and words with times relative to the start of the clip
     * @throws comp3011.assignment1.error.TranscriptionException if the provider fails
     */
	TimedTranscript transcribe(byte[] audio, String filename, String contentType);
}
