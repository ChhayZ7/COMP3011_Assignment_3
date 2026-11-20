/*
 * Adelaide University COMP3011 Assignment 2.
 *
 * Authors:
 *   1. Kimchhay Leng (a2942784)
 *
 * Copyright 2026 Kimchhay Leng
 */
package comp3011;

/**
 * A request from the JavaFX thread for the decoder thread to move the grabber to a new position. Only the decoder
 * thread touches the grabber, so seeks are sent to it rather than performed directly. Frames decoded after the seek
 * carry the request's generation.
 */
record SeekRequest(
		long generation,
		long timestampUs) {
}
