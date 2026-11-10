/*
 * Adelaide University COMP3011 Assignment 2.
 *
 * Authors:
 *   1. Kimchhay Leng (a2942784)
 *
 * Copyright 2026 Kimchhay Leng
 */
package comp3011;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

/**
 * The multi-threaded decoding and processing engine behind the {@link VideoPlayerModel}, built as a pipeline:
 *
 * <pre>
 *   FrameDecoder -> ProcessorStage 1 -> ... -> ProcessorStage N -> ImageConverterStage -> display queue
 *    (thread)          (thread)                    (thread)            (thread)         (JavaFX thread)
 * </pre>
 *
 * <p>
 * Each stage runs on its own thread and owns everything it works on (the grabber, one effect, the image converter),
 * so the threads share nothing but the queues between them. A frame is handed from stage to stage through those
 * queues, so exactly one thread holds it at a time and it arrives in decode order. The JavaFX thread only takes
 * finished images from the display queue, never waiting on the pipeline, so the user interface stays responsive
 * however many effects are applied.
 * </p>
 *
 * <p>
 * Seeking uses a generation number. Each seek increments it, and frames and audio decoded before the seek still
 * carry the old number, so every stage and the JavaFX thread can recognise and discard them without the pipeline
 * having to be stopped and drained.
 * </p>
 *
 * <p>
 * One pipeline is created for each playback session. The JavaFX thread calls every method here; the worker threads
 * only see the queues and the generation counter.
 * </p>
 */
final class PlaybackPipeline {
	private static final int DISPLAY_QUEUE_CAPACITY = 3;
	private static final int SPARE_POOLED_FRAMES = 3;
	private static final long STOP_TIMEOUT_MS = 2_000;
	
	private final AtomicLong generation = new AtomicLong();
	private final BlockingQueue<SeekRequest> seekRequests = new LinkedBlockingQueue<>();
	private final BlockingQueue<DisplayFrame> displayFrames = new ArrayBlockingQueue<>(DISPLAY_QUEUE_CAPACITY);
	private final Queue<PendingAudio> pendingAudio = new ConcurrentLinkedQueue<>();
	private final FrameDecoder decoder;
	private final List<Thread> threads = new ArrayList<>();
	private volatile boolean audioWanted;
	
	PlaybackPipeline(
			FFmpegFrameGrabber grabber,
			boolean grabAudio,
			Function<Frame, InfoVideo> describeVideo,
			List<FrameProcessor> frameProcessors,
			Consumer<Exception> errorHandler
			){
		BlockingQueue<PipelineFrame> decoded = new LinkedBlockingQueue<>();
		decoder = new FrameDecoder(
				grabber,
				grabAudio,
				describeVideo,
				frameProcessors.size() + SPARE_POOLED_FRAMES,
				decoded,
				seekRequests,
				pendingAudio,
				() -> audioWanted,
				errorHandler
				);
		threads.add(new Thread(decoder, "decoder"));
		
		BlockingQueue<PipelineFrame> input = decoded;
		for (int i = 0; i < frameProcessors.size(); i++) {
			FrameProcessor processor = frameProcessors.get(i);
			BlockingQueue<PipelineFrame> output = new LinkedBlockingQueue<>();
			ProcessorStage stage = new ProcessorStage(processor, input, output, generation::get, errorHandler);
			threads.add(new Thread(stage, "effect-" + (i + 1) + "-" + processor.getClass().getSimpleName()));
			input = output;
		}
		
		ImageConverterStage converter = new ImageConverterStage(input, displayFrames, generation::get, errorHandler);
		threads.add(new Thread(converter, "image_converter"));
	}
	
	void start() {
		for (Thread thread: threads) {
			thread.setDaemon(true);
			thread.setPriority(Thread.NORM_PRIORITY - 1);
			thread.start();
		}
	}
	
	 /**
     * Interrupts every pipeline thread and waits for them all to finish. Afterwards the grabber and the effects are
     * no longer used by any other thread, and the pooled frames are freed.
     */
	void stop() {
		for (Thread thread : threads) {
			thread.interrupt();
		}
		
		boolean allStopped = true;
		for (Thread thread : threads) {
			try {
				thread.join(STOP_TIMEOUT_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			if (thread.isAlive()) {
				allStopped = false;
				System.out.println("WARNING: pipeline thread " + thread.getName() + " did not stop in time.");
			}
		}
		
		// The pooled frames' native memory may only be freed once no thread can still be writing to it.
		FramePool pool = decoder.pool();
		if (pool != null && allStopped) {
			pool.close();
		}
		displayFrames.clear();
		pendingAudio.clear();
	}
	
    /**
     * Asks the decoder to move to a new position. Everything already in the pipeline becomes stale.
     */
	void requestSeek(long timestampUs) {
		long newGeneration = generation.incrementAndGet();
		displayFrames.clear();
		pendingAudio.clear();
		seekRequests.add(new SeekRequest(newGeneration, timestampUs));
	}
	
	long currentGeneration() {
		return generation.get();
	}
	
    /**
     * The next frame due for display, skipping any that are stale, or null if none is ready yet. The frame stays
     * queued until {@link #removeDisplayFrame()} is called.
     */
	DisplayFrame peekDisplayFrame() {
		DisplayFrame frame = displayFrames.peek();
		while (frame != null && frame.generation() != generation.get()) {
			displayFrames.poll();
			frame = displayFrames.peek();
		}
		return frame;
	}
	
	void removeDisplayFrame() {
		displayFrames.poll();
	}
	
    /**
     * Audio decoded so far, in order. Chunks from before the latest seek may still be present and should be skipped.
     */
	Queue<PendingAudio> pendingAudio(){
		return pendingAudio;
	}
	
	void setAudioWanted(boolean audioWanted) {
		this.audioWanted = audioWanted;
	}
}
