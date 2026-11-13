package comp3011.assignment1.model;

import java.util.List;

/**
 * Provider-neutral transcription with timing information, as needed for subtitles.
 *
 * Like {@link TranscriptionResult}, this is deliberately not OpenAI's response shape, so the
 * subtitle logic and its tests do not depend on any one provider's JSON.
 *
 * @param segments      phrases or sentences, each with its own text and confidence figures
 * @param words         every recognised word with its own start and end time
 * @param billedSeconds seconds of audio the provider charged for
 */
public record TimedTranscript(List<Segment> segments, List<Word> words, double billedSeconds) {
	public TimedTranscript {
		segments = List.copyOf(segments);
		words = List.copyOf(words);
	}
	
    /**
     * A phrase or sentence as the provider split it.
     *
     * @param start        seconds from the start of the clip
     * @param end          seconds from the start of the clip
     * @param text         the text, with punctuation
     * @param avgLogProb   average log probability of the text; very low means the model was guessing
     * @param noSpeechProb probability that the segment contains no speech at all
     */
	public record Segment(double start, double end, String text, double avgLogProb, double noSpeechProb) {
	}
	
    /**
     * A single word. Whisper's word list carries no punctuation, so the subtitle builder matches
     * these back up with the punctuated segment text.
     *
     * @param word  the word, without punctuation
     * @param start seconds from the start of the clip
     * @param end   seconds from the start of the clip
     */
	public record Word(String word, double start, double end) {
	}
}
