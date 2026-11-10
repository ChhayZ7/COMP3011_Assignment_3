/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 *
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Kimchhay Leng (a2942784)
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

/**
 * A chunk of decoded audio waiting to be written to the {@link AudioPlayer} when it falls due.
 *
 * <p>
 * Created by the decoder thread and handed to the JavaFX thread through a concurrent queue. After that only the
 * JavaFX thread reads it or advances its offset. The generation identifies which seek the audio was decoded after,
 * so audio from before a seek can be discarded.
 * </p>
 */
final class PendingAudio {
	private final long generation;
	private final long timestampUs;
	private final byte[] samples;
	private int offset;
	
	PendingAudio(long generation, long timestampUs, byte[] samples){
		this.generation = generation;
		this.timestampUs = timestampUs;
		this.samples = samples;
	}
	
	long generation() {
		return generation;
	}
	
	long timestampUs() {
		return timestampUs;
	}
	
	byte[] samples() {
		return samples;
	}
	
	int offset() {
		return offset;
	}
	
	int remaining() {
		return samples.length - offset;
	}
	
	void advance(int byteCount) {
		offset += byteCount;
	}
	
	boolean finished() {
		return offset >= samples.length;
	}
}