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
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Every subtitle fetched so far for the open film, looked up by film time.
 *
 * <p>
 * Threading: the upload threads of {@link SubtitleFetcher} add cues and the {@link FrameSubtitler} stage thread
 * looks them up for every frame. The cues live in a {@link ConcurrentSkipListMap} keyed by start time, so a
 * lookup is a lock-free {@code floorEntry} that never waits for a writer, and the playback pipeline is never held up.
 * Writers take this object's lock, so two clips arriving at once cannot interleave their checks for overlapping
 * cues. Cues themselves are immutable records, so a reader always sees a whole cue.
 * </p>
 *
 * <p>
 * Neighbouring clips overlap by a couple of seconds (see {@link SubtitleFetcher}), so the start of a sentence cut
 * at a clip boundary can arrive twice: complete from the earlier clip, and as a fragment from the later one. When
 * two cues mostly overlap, the one from the earlier clip is kept, because it heard the sentence from its start.
 * </p>
 */
final class SubtitleTrack{
	private final ConcurrentSkipListMap<Long, SubtitleCue> cues = new ConcurrentSkipListMap<>();
	
	/* The cue showing at the given film time, or null if there is none. */
	SubtitleCue cueAt(long timestampUs) {
		Map.Entry<Long, SubtitleCue> entry = cues.floorEntry(timestampUs);
		if (entry == null || timestampUs >= entry.getValue().endUs()) {
			return null;
		}
		return entry.getValue();
	}
	
	synchronized void addAll(List<SubtitleCue> newCues) {
		for (SubtitleCue cue : newCues) {
			add(cue);
		}
	}
	
	synchronized void clear() {
		cues.clear();
	}
	
	int size() {
		return cues.size();
	}
	
	private void add(SubtitleCue cue) {
		// Any cue that could overlap this one starts before this one ends, and no cue lasts more than 7 seconds.
		List<SubtitleCue> overlapping = new ArrayList<>(
				cues.subMap(cue.startUs() - 8_000_000L, cue.endUs()).values());
		for (SubtitleCue existing : overlapping) {
			long overlapUs = Math.min(cue.endUs(), existing.endUs()) - Math.max(cue.startUs(), existing.startUs());
			if (overlapUs <= 0) {
				continue;
			}
			boolean sameWords = overlapUs * 2 > Math.min(cue.durationUs(), existing.durationUs());
			if (sameWords) {
				if (existing.chunk() <= cue.chunk()) {
					return; // The earlier clip's version is already here.
				}
				cues.remove(existing.startUs()); // Replace the later clip's fragment.
			} else if (existing.startUs() < cue.startUs()) {
				cues.put(existing.startUs(), existing.withEnd(cue.startUs())); // Just touching: trim the earlier.
			} else {
				cue = cue.withEnd(existing.startUs());
			}
		}
		if (cue.durationUs() > 0) {
			cues.put(cue.startUs(), cue);
		}			
	}
}