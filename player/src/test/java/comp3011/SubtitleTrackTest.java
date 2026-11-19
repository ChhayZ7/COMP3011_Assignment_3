/*
 * Adelaide University COMP3011 Assignment 3.
 *
 * Authors:
 *   1. Kimchhay Leng (a2942784)
 *
 * Copyright 2026 Kimchhay Leng
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the lookup and the overlap handling in {@link SubtitleTrack}.
 */
class SubtitleTrackTest {
	private static SubtitleCue cue(double startSeconds, double endSeconds, String text, int chunk) {
		return new SubtitleCue((long) (startSeconds * 1e6), (long) (endSeconds * 1e6), List.of(text), chunk);
	}

	@Test
	void findsTheCueShowingAtAGivenTime() {
		SubtitleTrack track = new SubtitleTrack();
		SubtitleCue first = cue(1, 3, "first", 0);
		SubtitleCue second = cue(5, 6, "second", 0);
		track.addAll(List.of(first, second));

		assertNull(track.cueAt(500_000));
		assertSame(first, track.cueAt(1_000_000));
		assertSame(first, track.cueAt(2_999_999));
		assertNull(track.cueAt(3_000_000)); // End is exclusive.
		assertNull(track.cueAt(4_000_000));
		assertSame(second, track.cueAt(5_500_000));
	}

	@Test
	void keepsTheEarlierClipsVersionOfASentenceHeardTwice() {
		// Clip 0 heard "Where are we going tonight?" in full, running into its 2 second overlap.
		// Clip 1 starts at 30s and only heard the end of it.
		SubtitleCue full = cue(28.5, 31.5, "Where are we going tonight?", 0);
		SubtitleCue fragment = cue(30.0, 31.5, "going tonight?", 1);

		SubtitleTrack inOrder = new SubtitleTrack();
		inOrder.addAll(List.of(full));
		inOrder.addAll(List.of(fragment));

		// After a backwards seek the later clip can arrive first; the result must be the same.
		SubtitleTrack reversed = new SubtitleTrack();
		reversed.addAll(List.of(fragment));
		reversed.addAll(List.of(full));

		for (SubtitleTrack track : List.of(inOrder, reversed)) {
			assertEquals(1, track.size());
			assertEquals(List.of("Where are we going tonight?"), track.cueAt(31_000_000).lines());
		}
	}

	@Test
	void trimsCuesThatOnlyJustTouch() {
		SubtitleTrack track = new SubtitleTrack();
		track.addAll(List.of(cue(26, 30.4, "lingering", 0)));
		track.addAll(List.of(cue(30.1, 33, "next sentence", 1)));

		assertEquals(2, track.size());
		assertEquals(List.of("next sentence"), track.cueAt(30_200_000).lines());
		assertEquals(List.of("lingering"), track.cueAt(30_000_000).lines());
	}
}
