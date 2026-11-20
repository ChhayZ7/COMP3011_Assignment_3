/*
 * Adelaide University COMP3011 Assignment 2.
 *
 * Authors:
 *   1. Kimchhay Leng (a2942784)
 *
 * Copyright 2026 Kimchhay Leng
 */
package comp3011;

import java.util.concurrent.BlockingQueue;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Applies one {@link FrameProcessor} effect to each frame on a thread of its own.
 *
 * <p>
 * Each effect object is confined to its stage's thread: only that thread ever calls its
 * {@link FrameProcessor#initialise(InfoVideo)} or {@link FrameProcessor#process(org.bytedeco.javacv.Frame, InfoFrame)}.
 * This matters because several effects keep working buffers between calls, which would be corrupted if two threads
 * used the same effect at once. While this stage works on frame N, the stage before it can already work on frame N+1,
 * so a chain of effects keeps one CPU core busy per effect.
 * </p>
 */
final class ProcessorStage extends PipelineStage{
	private final FrameProcessor processor;
	private final BlockingQueue<PipelineFrame> output;
	private InfoVideo initialisedFor;
	
	ProcessorStage(
			FrameProcessor processor,
			BlockingQueue<PipelineFrame> input,
			BlockingQueue<PipelineFrame> output,
			LongSupplier currentGeneration,
			Consumer<Exception> errorHandler
			){
		super(input, currentGeneration, errorHandler);
		this.processor = processor;
		this.output = output;
	}
	
	@Override
	protected void handle(PipelineFrame frame, boolean stale) throws Exception {
		if (!stale && !frame.isEndOfStream()) {
			// Initialise on this thread, on the first frame of each newly opened video.
			if(frame.videoInfo() != initialisedFor) {
				processor.initialise(frame.videoInfo());
				initialisedFor = frame.videoInfo();
			}
			processor.process(frame.frame(), frame.info());
		}
		output.put(frame);
	}
}
