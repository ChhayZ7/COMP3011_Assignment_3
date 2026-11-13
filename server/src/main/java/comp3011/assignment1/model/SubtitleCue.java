package comp3011.assignment1.model;

import java.util.List;

/**
 * One subtitle, ready to draw: the one or two lines of text shown together, and when to show them.
 *
 * Times are in seconds from the start of the audio clip that was uploaded. The player adds the
 * clip's position in the film to get the time at which the subtitle appears in the film.
 *
 * @param start seconds from the start of the clip at which the subtitle appears
 * @param end   seconds from the start of the clip at which it disappears
 * @param lines one or two lines, each at most 42 characters
 */
public record SubtitleCue(double start, double end, List<String> lines) {
	public SubtitleCue{
		lines = List.copyOf(lines);
	}
	
	/*
	 * The cue's text on a single line, as used when merging and measuring cues.
	 */
	public String text() {
		return String.join(" ", lines);
	}
}
