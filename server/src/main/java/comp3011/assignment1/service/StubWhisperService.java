package comp3011.assignment1.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import comp3011.assignment1.error.TranscriptionException;
import comp3011.assignment1.model.TimedTranscript;

/**
 * Offline stand-in for Whisper under the {@code stub} profile.
 *
 * Produces a fixed sentence every few seconds across the whole clip, with word timings, after an
 * artificial delay. That is enough to see subtitles in the player, test seeking and caching, and
 * run the tests, all without an API key.
 */
@Service
@Profile("stub")
public class StubWhisperService implements TimedTranscriptionService {
    private static final String[] SENTENCES = {
            "This is a stubbed subtitle.",
            "The real text comes from Whisper, when a key is configured.",
            "Seeking, pausing and caching all work the same way.",
    };
    private static final double SENTENCE_EVERY_SECONDS = 4.0;
    private static final double WORD_SECONDS = 0.3;
    // 16 kHz, 16-bit mono WAV, which is what the video player sends.
    private static final double BYTES_PER_SECOND = 16_000 * 2;
    private static final int WAV_HEADER_BYTES = 44;

    private final long latencyMillis;

    public StubWhisperService(@Value("${stt.stub.latency-millis:250}") long latencyMillis) {
        this.latencyMillis = latencyMillis;
    }

    @Override
    public TimedTranscript transcribe(byte[] audio, String filename, String contentType) {
        if (latencyMillis > 0) {
            try {
                Thread.sleep(latencyMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TranscriptionException("Transcription interrupted", e);
            }
        }

        double duration = Math.max(1.0, (audio.length - WAV_HEADER_BYTES) / BYTES_PER_SECOND);
        List<TimedTranscript.Segment> segments = new ArrayList<>();
        List<TimedTranscript.Word> words = new ArrayList<>();
        int index = 0;
        for (double start = 0.5; start < duration - 1; start += SENTENCE_EVERY_SECONDS, index++) {
            String sentence = SENTENCES[index % SENTENCES.length];
            double time = start;
            for (String word : sentence.split(" ")) {
                words.add(new TimedTranscript.Word(word.replaceAll("[^A-Za-z0-9']", ""), time, time + WORD_SECONDS));
                time += WORD_SECONDS;
            }
            segments.add(new TimedTranscript.Segment(start, time, sentence, -0.2, 0.01));
        }
        return new TimedTranscript(segments, words, Math.ceil(duration));
    }
}
