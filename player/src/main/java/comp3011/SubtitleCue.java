/*
 * Adelaide University COMP3011 Assignment 3.
 *
 * Authors:
 *   1. Kimchhay Leng (a2942784)
 *
 * Copyright 2026 Kimchhay Leng
 */
package comp3011;

import java.util.List;

/**
 * One subtitle on the film's own timeline: the lines to show and the film time in which to show them.
 *
 * <p>
 * The server times cues from the start of the clip it was sent; {@link SubtitleFetcher} adds the clip's position in
 * the film before storing them. The chunk is the clip the cue came from, which {@link SubtitleTrack} uses when two
 * neighbouring clips both transcribed the same words.
 * </p>
 */
record SubtitleCue(long startUs, long endUs, List<String> lines, int chunk) {
	SubtitleCue{
		lines = List.copyOf(lines);
	}
	
	long durationUs() {
		return endUs - startUs;
	}
	
	SubtitleCue withEnd(long newEndUs) {
		return new SubtitleCue(startUs, newEndUs, lines, chunk);
	}
}
