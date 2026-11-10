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
 * One step of the {@link PlaybackPipeline}, run on its own thread. It repeatedly takes the next frame from its input
 * queue, handles it, and hands it on. Because each queue is first-in first-out and each stage handles one frame at a
 * time, frames leave the pipeline in the order they were decoded.
 *
 * <p>
 * The thread stops when interrupted, which is how {@link PlaybackPipeline#stop()} shuts the pipeline down. Any other
 * exception is reported to the error handler and also stops the stage.
 * </p>
 */
abstract class PipelineStage implements Runnable {
	private final BlockingQueue<PipelineFrame> input;
	private final LongSupplier currentGeneration;
	private final Consumer<Exception> errorHandler;
	
	PipelineStage(
			BlockingQueue<PipelineFrame> input,
			LongSupplier currentGeneration,
			Consumer<Exception> errorHandler
			){
		this.input = input;
		this.currentGeneration = currentGeneration;
		this.errorHandler = errorHandler;
	}
	
	@Override
	public final void run() {
		try {
			while(!Thread.currentThread().isInterrupted()) {
				PipelineFrame frame = input.take();
				handle(frame, isStale(frame));
			}
		} catch (InterruptedException e) {
			// Interrupted by PlaybackPipeline.stop(): the pipeline is shutting down.
		} catch (Exception e) {
			errorHandler.accept(e);
		}
	}
	
    /**
     * Handles one frame. Stale frames were decoded before the most recent seek and must not be shown, so stages skip
     * the work on them.
     */
	protected abstract void handle(PipelineFrame frame, boolean stale) throws Exception;
	
	private boolean isStale(PipelineFrame frame) {
		return frame.generation() != currentGeneration.getAsLong();
	}
}
