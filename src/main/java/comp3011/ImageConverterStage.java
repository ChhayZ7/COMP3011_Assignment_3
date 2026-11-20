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

import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.scene.image.Image;

/**
 * The last stage of the {@link PlaybackPipeline}: converts each finished frame to a JavaFX {@link Image}, returns the
 * pooled frame for reuse, and queues the image for the JavaFX thread to display.
 *
 * <p>
 * The converter copies the pixels into a new image, so the pooled frame can be released straight after conversion.
 * The converter object is confined to this stage's thread. The display queue is small and bounded, so this stage
 * waits when the JavaFX thread is not taking frames (for example while paused), which in turn holds back the rest of
 * the pipeline.
 * </p>
 */
final class ImageConverterStage extends PipelineStage {
	private final BlockingQueue<DisplayFrame> output;
	private final JavaFXFrameConverter converter = new JavaFXFrameConverter();
	
	ImageConverterStage(
			BlockingQueue<PipelineFrame> input,
			BlockingQueue<DisplayFrame> output,
			LongSupplier currentGeneration,
			Consumer<Exception> errorHandler
			){
		super(input, currentGeneration, errorHandler);
		this.output = output;
	}
	
	@Override
	protected void handle(PipelineFrame frame, boolean stale) throws InterruptedException{
		if(frame.isEndOfStream()) {
			if(!stale) {
				output.put(DisplayFrame.endOfStream(frame.generation()));
			}
			return;
		}
		
		if (stale) {
			frame.recycle();
			return;
		}
		
		Image image;
		try {
			image = converter.convert(frame.frame());
		} finally {
			frame.recycle(); // Released before waiting on the display queue, so the decoder can reuse it.
		}
		output.put(new DisplayFrame(
				frame.generation(),
				image,
				frame.info().frameNumber(),
				frame.info().timestampUs(),
				System.nanoTime()
				));
	}

}
