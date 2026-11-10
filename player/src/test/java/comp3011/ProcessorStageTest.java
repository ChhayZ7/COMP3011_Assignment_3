/*
 * Adelaide University COMP3011 Assignment 2.
 *
 * Authors:
 *   1. Kimchhay Leng (a2942784)
 *
 * Copyright 2026 Kimchhay Leng
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests that a chain of {@link ProcessorStage}s, each on its own thread, produces exactly the same frames, in the
 * same order, as applying the same effects one after another on a single thread.
 *
 * <p>
 * The effects used here do not take a random seed, so separate instances of them always give the same output and
 * the threaded result can be compared with a sequential one.
 * </p>
 */
public class ProcessorStageTest {
	private static final int WIDTH = 64;
	private static final int HEIGHT = 48;
	private static final int FRAME_COUNT = 60;
	private static final long TIMEOUT_SECONDS = 10;
	
	private static final List<Supplier<FrameProcessor>> EFFECTS = List.of(
			FrameBleeder::new,
			FrameYellower::new,
			FrameVignetter::new,
			FrameBlackAndWhiter::new,
			FrameNumberer::new
			);
	
	private final List<Thread> threads = new ArrayList<>();
	private final List<Exception> errors = new CopyOnWriteArrayList<>();
	
	@AfterEach
	void stopThreads() throws InterruptedException{
		for (Thread thread: threads) {
			thread.interrupt();
		}
		for (Thread thread: threads) {
			thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
		}
	}
	
	@Test
	void threadedStagesGiveSameFramesInSameOrderAsSequentialProcessing() throws Exception {
		InfoVideo videoInfo = videoInfo(newFrame(0));
		List<byte[]> expected = processSequentially(videoInfo);
		
		BlockingQueue<PipelineFrame> input = new LinkedBlockingQueue<>();
		BlockingQueue<PipelineFrame> output = startStages(input, () -> 0);
		for (int i = 0; i < FRAME_COUNT; i++) {
			input.put(new PipelineFrame(0, videoInfo, new InfoFrame(i, i * 41_667L), newFrame(i), null));
		}
		
		for (int i = 0; i < FRAME_COUNT; i++) {
			PipelineFrame frame = output.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			assertNotNull(frame, "Frame " + i + " never came out of the pipeline");
			assertEquals(i, frame.info().frameNumber(), "Frames came out of order");
			assertArrayEquals(expected.get(i), pixels(frame.frame()), "Frame " + i + " differs");
		}
		assertTrue(errors.isEmpty(), "Stage errors: " + errors);
	}
	
	@Test
	void staleFramesAndEndOfStreamPassThroughUnprocessed() throws Exception {
		InfoVideo videoInfo = videoInfo(newFrame(0));
		BlockingQueue<PipelineFrame> input = new LinkedBlockingQueue<>();
		BlockingQueue<PipelineFrame> output = startStages(input, () -> 2); // The current generation is 2.
		
		Frame staleFrame = newFrame(7);
		byte[] originalPixels = pixels(staleFrame);
		input.put(new PipelineFrame(1, videoInfo, new InfoFrame(7, 0), staleFrame, null));
		input.put(PipelineFrame.endOfStream(2));
		
		PipelineFrame stale = output.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		assertNotNull(stale);
		assertArrayEquals(originalPixels, pixels(stale.frame()), "A stale frame was processed");
		
		PipelineFrame end = output.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		assertNotNull(end);
		assertTrue(end.isEndOfStream());
		assertTrue(errors.isEmpty(), "Stage errors: " + errors);
	}
	
	// Starts one stage thread per effect, chained by queues, and returns the last queue.
	private BlockingQueue<PipelineFrame> startStages(BlockingQueue<PipelineFrame> input, LongSupplier generation){
		BlockingQueue<PipelineFrame> queue = input;
		for (Supplier<FrameProcessor> effect : EFFECTS) {
			BlockingQueue<PipelineFrame> next = new LinkedBlockingQueue<>();
			Thread thread = new Thread(new ProcessorStage(effect.get(), queue, next, generation, errors::add));
			thread.setDaemon(true);
			thread.start();
			threads.add(thread);
			queue = next;
		}
		return queue;
	}
	
	private List<byte[]> processSequentially(InfoVideo videoInfo) throws Exception {
		List<FrameProcessor> processors = new ArrayList<>();
		for (Supplier<FrameProcessor> effect : EFFECTS) {
			FrameProcessor processor = effect.get();
			processor.initialise(videoInfo);
			processors.add(processor);
		}
		
		List<byte[]> results = new ArrayList<>();
		for (int i = 0; i < FRAME_COUNT; i++) {
			Frame frame = newFrame(i);
			for (FrameProcessor processor : processors) {
				processor.process(frame, new InfoFrame(i, i * 41_667L));
			}
			results.add(pixels(frame));
		}
		return results;
	}
	
	// A frame filled with pixels that depend only on its frame number.
	private static Frame newFrame(int frameNumber) {
		Frame frame = new Frame(WIDTH, HEIGHT, Frame.DEPTH_UBYTE, 3);
		ByteBuffer buffer = (ByteBuffer) frame.image[0];
		Random random = new Random(frameNumber);
		for(int i = 0; i < buffer.capacity(); i++) {
			buffer.put(i, (byte) random.nextInt(256));
		}
		return frame;
	}
	
	private static InfoVideo videoInfo(Frame frame) {
		return new InfoVideo("test", FRAME_COUNT, frame.imageWidth, frame.imageHeight, frame.imageDepth,
				frame.imageChannels, frame.imageStride, 24.0, 24, 41_667, 0);
	}
	
	private static byte[] pixels(Frame frame) {
		ByteBuffer buffer = ((ByteBuffer) frame.image[0]).duplicate();
		buffer.clear();
		byte[] bytes = new byte[buffer.remaining()];
		buffer.get(bytes);
		return bytes;
	}
}
