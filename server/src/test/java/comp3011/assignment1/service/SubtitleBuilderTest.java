package comp3011.assignment1.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import comp3011.assignment1.model.SubtitleCue;
import comp3011.assignment1.model.TimedTranscript;
import comp3011.assignment1.model.TimedTranscript.Segment;
import comp3011.assignment1.model.TimedTranscript.Word;

/**
 * Checks that {@link SubtitleBuilder} follows the subtitle guidelines in the assignment.
 */
class SubtitleBuilderTest {
    private final SubtitleBuilder builder = new SubtitleBuilder();

    /** A segment whose words are spoken one after another, {@code wordSeconds} each. */
    private static TimedTranscript speech(double start, double wordSeconds, String text) {
        List<Word> words = new ArrayList<>();
        double time = start;
        for (String part : text.split(" ")) {
            words.add(new Word(part.replaceAll("[^A-Za-z']", ""), time, time + wordSeconds));
            time += wordSeconds;
        }
        return new TimedTranscript(List.of(new Segment(start, time, text, -0.2, 0.01)), words, time);
    }

    @Test
    @DisplayName("a short sentence becomes one cue on one line, timed to its words")
    void shortSentenceIsOneLine() {
        List<SubtitleCue> cues = builder.build(speech(2.0, 0.4, "Hello there, how are you?"));

        assertThat(cues).hasSize(1);
        assertThat(cues.get(0).lines()).containsExactly("Hello there, how are you?");
        assertThat(cues.get(0).start()).isEqualTo(2.0);
        assertThat(cues.get(0).end()).isGreaterThanOrEqualTo(4.0); // last word ends at 4.0
    }

    @Test
    @DisplayName("no line is longer than 42 characters and no cue has more than two lines")
    void respectsLineLimits() {
        String text = "We have been walking through these mountains for days, and honestly I am not sure "
                + "that anybody in this group still remembers which way the village is supposed to be.";
        List<SubtitleCue> cues = builder.build(speech(0, 0.25, text));

        assertThat(cues).isNotEmpty();
        for (SubtitleCue cue : cues) {
            assertThat(cue.lines()).hasSizeBetween(1, 2);
            cue.lines().forEach(line -> assertThat(line.length()).isLessThanOrEqualTo(42));
        }
        // Nothing is lost on the way.
        String rejoined = String.join(" ", cues.stream().map(SubtitleCue::text).toList());
        assertThat(rejoined).isEqualTo(text);
    }

    @Test
    @DisplayName("every cue lasts between 1 and 7 seconds and cues never overlap")
    void respectsDurations() {
        // Very slow speech: 1.5 seconds per word would make one 84-character cue last far too long.
        String text = "Slowly the old man lifted the lantern and looked out across the dark and silent water";
        List<SubtitleCue> cues = builder.build(speech(0, 1.5, text));

        for (int i = 0; i < cues.size(); i++) {
            SubtitleCue cue = cues.get(i);
            assertThat(cue.end() - cue.start()).isBetween(1.0, 7.0);
            if (i + 1 < cues.size()) {
                assertThat(cue.end()).isLessThanOrEqualTo(cues.get(i + 1).start());
            }
        }
    }

    @Test
    @DisplayName("a new cue starts at a sentence end when there is time")
    void breaksAtSentenceEnds() {
        List<SubtitleCue> cues = builder.build(speech(0, 0.4, "I am done. Let us go home now."));

        assertThat(cues).extracting(SubtitleCue::text).containsExactly("I am done.", "Let us go home now.");
    }

    @Test
    @DisplayName("two-line cues break after punctuation where possible")
    void lineBreakAfterPunctuation() {
        assertThat(SubtitleBuilder.splitLines("If you really want to know the truth, then sit down and listen"))
                .containsExactly("If you really want to know the truth,", "then sit down and listen");
    }

    @Test
    @DisplayName("segments that are probably not speech are dropped")
    void dropsNonSpeech() {
        TimedTranscript transcript = new TimedTranscript(List.of(
                new Segment(0, 2, "Real words.", -0.3, 0.1),
                new Segment(3, 5, "Thanks for watching!", -1.4, 0.8), // both limits exceeded: dropped
                new Segment(6, 8, "Kept, only one limit.", -1.4, 0.2)), // only one: kept
                List.of(), 8);

        assertThat(builder.build(transcript)).extracting(SubtitleCue::text)
                .containsExactly("Real words.", "Kept, only one limit.");
    }

    @Test
    @DisplayName("punctuated text is matched to unpunctuated word timings")
    void alignsPunctuationWithWords() {
        Segment segment = new Segment(0, 3, "Wait, what's that?", -0.1, 0.0);
        List<Word> words = List.of(new Word("Wait", 0.0, 0.5), new Word("what's", 1.0, 1.4), new Word("that", 1.5, 2.0));

        List<SubtitleBuilder.Token> tokens = SubtitleBuilder.align(segment, words);

        assertThat(tokens).extracting(SubtitleBuilder.Token::text).containsExactly("Wait,", "what's", "that?");
        assertThat(tokens.get(1).start()).isEqualTo(1.0);
        assertThat(tokens.get(2).end()).isEqualTo(2.0);
    }
}
