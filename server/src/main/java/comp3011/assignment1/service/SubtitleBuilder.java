package comp3011.assignment1.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import comp3011.assignment1.model.SubtitleCue;
import comp3011.assignment1.model.TimedTranscript;
import comp3011.assignment1.model.TimedTranscript.Segment;
import comp3011.assignment1.model.TimedTranscript.Word;

/**
 * Turns a timed transcript into subtitle cues that follow the assignment's guidelines.
 *
 * <ul>
 *   <li>Segments that are probably not speech ({@code noSpeechProb > 0.6} and
 *       {@code avgLogProb < -1.0}) are dropped.</li>
 *   <li>At most 42 characters per line and at most two lines, with one line preferred.</li>
 *   <li>Each cue is shown for at least 1 second and at most 7 seconds.</li>
 *   <li>New cues start at sentence ends and natural pauses where the timing allows.</li>
 *   <li>Line breaks go after punctuation where possible.</li>
 * </ul>
 *
 * Doing this on the server rather than in the player means every client gets the same, already
 * laid out subtitles, and the cache stores the finished result. The class is pure logic with no
 * state, so one instance can be shared by every request thread.
 */
@Component
public class SubtitleBuilder {
	static final int MAX_LINE_LENGTH = 42;
	static final double MIN_DURATION = 1.0;
	static final double MAX_DURATION = 7.0;
	
	private static final double NO_SPEECH_LIMIT = 0.6;
	private static final double LOG_PROB_LIMIT = -1.0;
	// A silence at least this long between two words is a natural place for a new subtitle.
	private static final double PAUSE_SECONDS = 0.5;
	// A shorter silence still counts when choosing where to split a cue that has grown too long.
	private static final double SHORT_PAUSE_SECONDS = 0.25;
	// Subtitles stay up a little after the last word, so they are not snatched away mid-read.
	private static final double LINGER_SECONDS = 0.5;
	// Words can sit just outside their segment's start and end times.
	private static final double SEGMENT_TOLERANCE = 0.1;
	
	/*
	 * A piece of the segment's text (a word with any punctuation attached) and when it is spoken.
	 */
	record Token(String text, double start, double end) {
	}
	
	public List<SubtitleCue> build(TimedTranscript transcript){
		List<Word> words = transcript.words();
		boolean[] wordUsed = new boolean[words.size()];
		
		List<List<Token>> groups = new ArrayList<>();
		for (Segment segment : transcript.segments()) {
			List<Word> segmentWords = takeWords(segment, words, wordUsed);
			if (!isSpeech(segment) || segment.text().isBlank()) {
				continue;
			}
			List<Token> tokens = align(segment, segmentWords);
			groups.addAll(group(tokens));
		}
		
		groups = mergeShortGroups(groups);
		return toCues(groups);
	}
	
	static boolean isSpeech(Segment segment) {
		return !(segment.noSpeechProb() > NO_SPEECH_LIMIT && segment.avgLogProb() < LOG_PROB_LIMIT);
	}
	
	// The words whose middle falls inside the segment and that no earlier segment has claimed.
	private static List<Word> takeWords(Segment segment, List<Word> words, boolean[] used){
		List<Word> result = new ArrayList<>();
		for (int i = 0; i < words.size(); i++) {
			Word word = words.get(i);
			double middle = (word.start() + word.end()) / 2;
			if (!used[i] && middle >= segment.start() - SEGMENT_TOLERANCE
					&& middle <= segment.end() + SEGMENT_TOLERANCE) {
				used[i] = true;
				result.add(word);
			}
		}
		return result;
	}
	
    /**
     * Whisper's segment text has punctuation but no word timings; its word list has timings but no
     * punctuation. This walks both together so each punctuated piece of text gets its word's times.
     * Text that cannot be matched gets times spread evenly between its matched neighbours.
     */
    static List<Token> align(Segment segment, List<Word> words) {
        String[] parts = Arrays.stream(segment.text().trim().split("\\s+"))
                .filter(part -> !part.isEmpty())
                .toArray(String[]::new);
        double[] starts = new double[parts.length];
        double[] ends = new double[parts.length];
        Arrays.fill(starts, Double.NaN);
        Arrays.fill(ends, Double.NaN);

        int w = 0;
        for (int i = 0; i < parts.length && w < words.size(); i++) {
            String target = normalise(parts[i]);
            if (target.isEmpty()) {
                continue; // A lone dash or similar: timed from its neighbours below.
            }
            w = resynchronise(words, w, target);
            StringBuilder matched = new StringBuilder(normalise(words.get(w).word()));
            starts[i] = words.get(w).start();
            ends[i] = words.get(w).end();
            w++;
            // A token such as "New-York" can be several words in Whisper's list.
            while (matched.length() < target.length() && w < words.size()
                    && target.startsWith(matched + normalise(words.get(w).word()))) {
                matched.append(normalise(words.get(w).word()));
                ends[i] = words.get(w).end();
                w++;
            }
        }

        fillMissingTimes(starts, ends, segment.start(), segment.end());

        List<Token> tokens = new ArrayList<>(parts.length);
        for (int i = 0; i < parts.length; i++) {
            tokens.add(new Token(parts[i], starts[i], Math.max(starts[i], ends[i])));
        }
        return tokens;
    }
    
    // If the next word does not match, look a few words ahead in case Whisper's two lists disagree
    // (for example "10" in the text but "ten" in the words), so one mismatch does not shift the rest.
    private static int resynchronise(List<Word> words, int from, String target) {
	    	for (int k = from; k < Math.min(words.size(), from + 4); k++) {
	    		String candidate = normalise(words.get(k).word());
	    		if (!candidate.isEmpty() && target.startsWith(candidate)) {
	    			return k;
	    		}
	    	}
	    	return from;
    }
    
    private static void fillMissingTimes(double[] starts, double[] ends, double segmentStart, 
    		double segmentEnd) {
    		int i = 0;
    		while (i < starts.length) {
    			if (!Double.isNaN(starts[i])) {
    				i++;
    				continue;
    			}
    			int runEnd = i;
    			while (runEnd < starts.length && Double.isNaN(starts[runEnd])) {
    				runEnd++;
    			}
    			double from = i > 0 ? ends[i - 1] : segmentStart;
    			double to = runEnd < starts.length ? starts[runEnd] : Math.max(from, segmentEnd);
    			double step = (to - from) / (runEnd - i);
    			for (int k = i; k < runEnd; k++) {
    				starts[k] = from + step * (k - i);
    				ends[k] = from + step * (k - i + 1);
    			}
    			i = runEnd;
    		}
    }
    
    static String normalise(String text) {
    		return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
    
    /**
     * Splits one segment's tokens into cues. A cue is cut when the next word would not fit in two
     * lines or would make it last longer than 7 seconds; it is cut at the best point inside it (a
     * sentence end, then a comma, then a pause). It is also cut early at a sentence end or a
     * pause, and before it would need a second line if there is a comma there, so that one line
     * is used where possible.
     */
    static List<List<Token>> group(List<Token> tokens){
    		List<List<Token>> groups = new ArrayList<>();
    		List<Token> current = new ArrayList<>();
    		
    		for (Token token : tokens) {
    			// Cut when full
    			while(!current.isEmpty() &&(!fitsTwoLines(joinWith(current, token))
    					|| token.end() - current.get(0).start() > MAX_DURATION)) {
    				int cut = bestBreak(current);
    				groups.add(new ArrayList<>(current.subList(0, cut)));
    				current = new ArrayList<>(current.subList(cut, current.size()));
    			}
    			
    			// Cut early
    			if (!current.isEmpty() && durationOf(current) >= MIN_DURATION) {
    				Token previous = current.get(current.size() - 1);
    				boolean sentenceEnded = endsSentence(previous.text());
    				boolean paused = token.start() - previous.end() >= PAUSE_SECONDS;
    				boolean wouldNeedSecondLine = join(current).length() <= MAX_LINE_LENGTH
    						&& joinWith(current, token).length() > MAX_LINE_LENGTH;
    				boolean softBreak = endsClause(previous.text())
    						|| token.start() - previous.end() >= SHORT_PAUSE_SECONDS;
    				if (sentenceEnded || paused || (wouldNeedSecondLine && softBreak)) {
    					groups.add(current);
    					current = new ArrayList<>();
    				}
    			}
    			current.add(token);
    		}
    		if (!current.isEmpty()) {
    			groups.add(current);
    		}
    		return groups;
    }
    
    // Where to cut that has grown too long: the number of tokens to keep in the first part.
    private static int bestBreak(List<Token> tokens) {
    		int bestCut = tokens.size();
    		int bestRank = 0;
    		for (int cut = 1; cut < tokens.size(); cut++) {
    			Token before = tokens.get(cut - 1);
    			double gap = tokens.get(cut).start() - before.end();
    			int rank = endsSentence(before.text()) ? 3
    					: endsClause(before.text()) ? 2
    					: gap >= SHORT_PAUSE_SECONDS ? 1 : 0;
    			if (rank >= bestRank && rank > 0) {
    				bestRank = rank;
    				bestCut = cut;
    			}
    		}
    		return bestCut;
    }
    
	/*
	 * Two cues less than a second apart cannot both get their full second, so they
	 * become one cue if the result still fits on two lines and lasts no more than 7
	 * seconds.
	 */
    private static List<List<Token>> mergeShortGroups(List<List<Token>> groups){
    		List<List<Token>> merged = new ArrayList<>();
    		for (List<Token> group : groups) {
    			if(!merged.isEmpty()) {
    				List<Token> previous = merged.get(merged.size() - 1);
    				List<Token> combined = new ArrayList<>(previous);
    				combined.addAll(group);
    				boolean tooClose = group.get(0).start() - previous.get(0).start() < MIN_DURATION;
    				if (tooClose && fitsTwoLines(join(combined)) && durationOf(combined) <= MAX_DURATION) {
    					merged.set(merged.size() - 1, combined);
    					continue;
    				}
    			}
    			merged.add(group);
    		}
    		return merged;
    }
    
    private static List<SubtitleCue> toCues(List<List<Token>> groups){
    		List<SubtitleCue> cues = new ArrayList<>();
    		for (int i = 0; i < groups.size(); i++) {
    			List<Token> group = groups.get(i);
    			double start = group.get(0).start();
    			double end = Math.max(group.get(group.size() - 1).end() + LINGER_SECONDS, start + MIN_DURATION);
    			end = Math.min(end, start + MAX_DURATION);
    			if (i + 1 < groups.size()) {
    				// Never overlap the next cue; it is better to shorten this one than to delay speech.
    				end = Math.min(end, groups.get(i + 1).get(0).start());
    			}
    			cues.add(new SubtitleCue(round(start), round(Math.max(end, start)), splitLines(join(group))));
    		}
    		return cues;
    }
    
    /**
     * Lays text out on one line if it fits, otherwise on two. Of the places where both lines fit,
     * the one after punctuation is preferred, then the one with the most even line lengths.
     */
    static List<String> splitLines(String text){
    		if (text.length() <= MAX_LINE_LENGTH) {
    			return List.of(text);
    		}
    		int bestSpace = -1;
    		double bestScore = Double.MAX_VALUE;
    		for (int i = text.indexOf(' '); i >= 0; i = text.indexOf(' ', i + 1)) {
    			int first = i;
    			int second = text.length() - i - 1;
    			if (first > MAX_LINE_LENGTH || second > MAX_LINE_LENGTH) {
    				continue;
    			}
    			double score = Math.abs(first - second);
    			if (endsClause(text.substring(0, i)) || endsSentence(text.substring(0, i))) {
    				score -= 20; // Strongly prefer breaking after punctuation.
    			}
    			if (score < bestScore) {
    				bestScore = score;
    				bestSpace = i;
    			}
    		}
    		if (bestSpace < 0) {
    			// A single very long word; this should not happen with speech, so just cut it.
    			String first = text.substring(0, MAX_LINE_LENGTH);
    			String rest = text.substring(MAX_LINE_LENGTH).trim();
    			return List.of(first, rest.length() > MAX_LINE_LENGTH ? rest.substring(0, MAX_LINE_LENGTH) : rest);
    		}
    		return List.of(text.substring(0, bestSpace), text.substring(bestSpace + 1));
    }
    
    static boolean fitsTwoLines(String text) {
    		if (text.length() <= MAX_LINE_LENGTH) {
    			return true;
    		}
    		for (int i = text.indexOf(' '); i >= 0; i = text.indexOf(' ', i + 1)) {
    			if (i <= MAX_LINE_LENGTH && text.length() - i - 1 <= MAX_LINE_LENGTH) {
    				return true;
    			}
    		}
    		return false;
    }
    
    private static boolean endsSentence(String text) {
    		return text.matches("(?s).*[.?!…][\"')\\]]*$");
    }
    
    private static boolean endsClause(String text) {
    		return text.matches("(?s).*[,;:\\u2014-][\"')\\]]*$");
    }
    
    private static double durationOf(List<Token> tokens) {
    		return tokens.get(tokens.size() - 1).end() - tokens.get(0).start();
    }
    
    private static String join(List<Token> tokens) {
    		StringBuilder text = new StringBuilder();
    		for (Token token : tokens) {
    			if (!text.isEmpty()) {
    				text.append(' ');
    			}
    			text.append(token.text());
    		}
    		return text.toString();
    }
    
    private static String joinWith(List<Token> tokens, Token next) {
    		return join(tokens) + " " + next.text();
    }
    
    private static double round(double seconds) {
    		return Math.round(seconds * 1000.0) / 1000.0;
    }
}
