/*
 * Adelaide University COMP3011 Assignment 2.
 *
 * Authors:
 *   1. Kimchhay Leng (a2942784)
 *
 * Copyright 2026 Kimchhay Leng
 */
package comp3011;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.bytedeco.javacv.Frame;

/**
 * A fixed set of reusable image frames that circulate through the {@link PlaybackPipeline}.
 *
 * <p>
 * The FFmpeg grabber overwrites the same {@link Frame} on every grab, so each decoded image is copied into a frame
 * from this pool before it is handed to another thread. Reusing a fixed number of frames avoids allocating several
 * megabytes of native memory for every video frame, and the pool size also caps how many frames can be in flight:
 * when every pooled frame is in use, the decoder waits in {@link #acquire(long, TimeUnit)} until one is released.
 * This is the pipeline's back-pressure.
 * </p>
 *
 * <p>
 * Thread safety: {@link #acquire(long, TimeUnit)} and {@link #release(Frame)} may be called from any thread. A frame
 * belongs to exactly one thread between being acquired and released. {@link #close()} must only be called once every
 * thread using the pool has stopped.
 * </p>
 */
final class FramePool implements AutoCloseable{
	private final List<Frame> allFrames = new ArrayList<>();
	private final BlockingQueue<Frame> freeFrames = new LinkedBlockingQueue<>();
	
	FramePool(int size, Frame template){
		for (int i = 0; i < size; i++) {
			Frame frame = new Frame(
					template.imageWidth,
					template.imageHeight,
					template.imageDepth,
					template.imageChannels,
					template.imageStride);
			allFrames.add(frame);
			freeFrames.add(frame);
		}
	}
	
	// Waits up to the given time for a free frame, returning null if none became free in
	// time.
	Frame acquire(long timeout, TimeUnit unit) throws InterruptedException {
		return freeFrames.poll(timeout, unit);
	}
	
	void release(Frame frame) {
		freeFrames.add(frame);
	}
	
	// Copies the image pixels of a grabbed frame into a pooled frame of the same geometry.
	static void copyImage(Frame source, Frame target) {
		ByteBuffer from = ((ByteBuffer) source.image[0]).duplicate();
		ByteBuffer to = ((ByteBuffer) target.image[0]).duplicate();
		from.clear();
		to.clear();
		from.limit(Math.min(from.capacity(), to.capacity()));
		to.put(from);
		}
	
	@Override
	public void close() {
		for (Frame frame : allFrames) {
			frame.close();
		}
		allFrames.clear();
		freeFrames.clear();
	}
}
