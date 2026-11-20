/*
 * Adelaide University COMP3011 Assignment 2.
 *
 * Authors:
 *   1. Kimchhay Leng (a2942784)
 *
 * Copyright 2026 Kimchhay Leng
 */
package comp3011;

import javafx.scene.image.Image;

/**
 * A fully processed frame, converted to a JavaFX {@link Image} and waiting for the JavaFX thread to show it on screen
 * at its due time. The image is a copy of the pixels, so it no longer depends on any pooled frame.
 *
 * <p>
 * readyAtNs records when the frame finished processing, so the player can report how much time it had to spare
 * before the frame was due. An end of stream marker has no image.
 * </p>
 */
record DisplayFrame(
		long generation,
		Image image,
		int frameNumber,
		long timestampUs,
		long readyAtNs
		) {
	static DisplayFrame endOfStream(long generation) {
		return new DisplayFrame(generation, null, -1, -1, System.nanoTime());
	}
	
	boolean isEndOfStream() {
		return image == null;
	}
}
