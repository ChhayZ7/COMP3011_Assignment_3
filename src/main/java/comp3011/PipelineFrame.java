/*
 * Adelaide University COMP3011 Assignment 2.
 *
 * Authors:
 *   1. Kimchhay Leng (a2942784)
 *
 * Copyright 2026 Kimchhay Leng
 */
package comp3011;

import org.bytedeco.javacv.Frame;

/**
 * A decoded video frame travelling along the {@link PlaybackPipeline}, from the decoder, through each
 * {@link ProcessorStage}, to the {@link ImageConverterStage}.
 *
 * <p>
 * Ownership of the pooled {@link Frame} passes from thread to thread with this object through the pipeline's blocking
 * queues, so only one thread ever touches its pixels at a time. The last stage returns the frame to its
 * {@link FramePool} with {@link #recycle()}.
 * </p>
 *
 * <p>
 * The generation identifies which seek the frame was decoded after. Frames from an older generation are stale and
 * are passed along unprocessed, then discarded. A frame with no image marks the end of the stream.
 * </p>
 */
record PipelineFrame(
        long generation,
        InfoVideo videoInfo,
        InfoFrame info,
        Frame frame,
        FramePool pool) {

    static PipelineFrame endOfStream(long generation) {
        return new PipelineFrame(generation, null, null, null, null);
    }

    boolean isEndOfStream() {
        return frame == null;
    }

    void recycle() {
        if (pool != null) {
            pool.release(frame);
        }
    }
}