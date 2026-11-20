/*
 * Adelaide University COMP3011 Assignment 2.
 *
 * Authors:
 *   1. Kimchhay Leng (a2942784)
 *
 * Copyright 2026 Kimchhay Leng
 */
package comp3011;

import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

/**
 * The first stage of the {@link PlaybackPipeline}: decodes the media on its own thread.
 *
 * <p>
 * The grabber is confined to this thread while the pipeline runs. The JavaFX thread opens it before the pipeline
 * starts and closes it only after this thread has stopped, and seeks are sent here as {@link SeekRequest}s instead of
 * being performed directly. Each decoded image is copied into a {@link FramePool} frame (the grabber reuses its own
 * frame on every grab) and sent down the pipeline. Audio is copied into {@link PendingAudio} chunks for the JavaFX
 * thread to write when due.
 * </p>
 *
 * <p>
 * This thread only ever blocks waiting for a free pooled frame or, at the end of the stream, for the next seek. Both
 * waits wake up for a seek request or an interrupt, so a seek or stop is never held up by a full pipeline.
 * </p>
 */
final class FrameDecoder implements Runnable {
	private static final long POOL_WAIT_MS = 10;
	
	private final FFmpegFrameGrabber grabber;
	private final boolean grabAudio;
	private final Function<Frame, InfoVideo> describeVideo;
	private final int poolSize;
	private final BlockingQueue<PipelineFrame> output;
	private final BlockingQueue<SeekRequest> seekRequests;
	private final Queue<PendingAudio> pendingAudio;
	private final BooleanSupplier audioWanted;
	private final Consumer<Exception> errorHandler;
	
	private long generation;
	private InfoVideo videoInfo;
	private FramePool pool;
	
	FrameDecoder(
			FFmpegFrameGrabber grabber,
			boolean grabAudio,
			Function<Frame, InfoVideo> describeVideo,
			int poolSize,
			BlockingQueue<PipelineFrame> output,
			BlockingQueue<SeekRequest> seekRequests,
			Queue<PendingAudio> pendingAudio,
			BooleanSupplier audioWanted,
			Consumer<Exception> errorHandler
			){
		this.grabber = grabber;
		this.grabAudio = grabAudio;
		this.describeVideo = describeVideo;
		this.poolSize = poolSize;
		this.output = output;
		this.seekRequests = seekRequests;
		this.pendingAudio = pendingAudio;
		this.audioWanted = audioWanted;
		this.errorHandler = errorHandler;
	}
	
    /**
     * The frame pool, created when the first image is decoded. Only safe to call once this thread has stopped.
     */
	FramePool pool() {
		return pool;
	}
	
	@Override
	public void run() {
		try {
			boolean endOfStream = false;
			while (!Thread.currentThread().isInterrupted()) {
				SeekRequest seek = endOfStream? waitForSeek() : latestSeek();
				if (seek != null) {
					grabber.setTimestamp(seek.timestampUs());
					generation = seek.generation();
					endOfStream = false;
				}
				
				Frame grabbed = grabAudio ? grabber.grab() : grabber.grabImage();
				if(grabbed == null) {
					output.put(PipelineFrame.endOfStream(generation));
					endOfStream = true;
					continue;
				}
				
				long timestampUs = grabber.getTimestamp();
				if (grabbed.samples != null) {
					queueAudio(timestampUs, grabbed);
				}
				if (grabbed.image != null) {
					sendImage(grabbed, new InfoFrame(grabber.getFrameNumber(), timestampUs));
				}
			}
		} catch (InterruptedException e) {
			// Interrupted by PlaybackPipeline.stop(): the pipeline is shutting down.
		} catch (Exception e) {
			errorHandler.accept(e);
		}
	}
	
	private void sendImage(Frame grabbed, InfoFrame info) throws InterruptedException{
		if(videoInfo == null) {
			videoInfo = describeVideo.apply(grabbed);
			pool = new FramePool(poolSize, grabbed);
		}
		
		Frame pooled = acquirePooledFrame();
		if(pooled == null) {
			return; // A seek arrived while waiting, so this frame is no longer wanted.
		}
		FramePool.copyImage(grabbed, pooled);
		output.put(new PipelineFrame(generation, videoInfo, info, pooled, pool));
	}
	
	// Waits for a pooled frame to come free, giving up early if a seek request arrives.
	private Frame acquirePooledFrame() throws InterruptedException{
		while(true) {
			Frame frame = pool.acquire(POOL_WAIT_MS, TimeUnit.MILLISECONDS);
			if(frame != null || !seekRequests.isEmpty()) {
				return frame;
			}
		}
	}
	
	private void queueAudio(long timestampUs, Frame grabbed) {
		if(!audioWanted.getAsBoolean()) {
			return;
		}
		
		byte[] samples = AudioPlayer.copySamples(grabbed);
		if(samples.length > 0) {
			pendingAudio.add(new PendingAudio(generation, timestampUs, samples));
		}
	}
	
	// Only the newest seek matters if the user pressed seek keys several times in quick successions.
	private SeekRequest latestSeek() {
		SeekRequest latest = null;
		SeekRequest next;
		while ((next = seekRequests.poll()) != null) {
			latest = next;
		}
		return latest;
	}
	
	private SeekRequest waitForSeek() throws InterruptedException{
		SeekRequest first = seekRequests.take();
		SeekRequest later = latestSeek();
		return later != null ? later : first;
	}
	
}
