# COMP3011 Assignment 2 - Multi-threaded Video Player

For more information on this code and how to get started, see https://learn.adelaide.edu.au/courses/30747/assignments/94505

## Running

    mvn javafx:run "-Djavafx.args=[options] movie.mp4"

Run `mvn javafx:run -Djavafx.args=--help` for the full list of options and frame processors.

## Threading design

Playback runs as a pipeline. Each job has its own thread, and the threads are connected by queues:

    FrameDecoder --> Effect 1 --> Effect 2 --> ... --> Effect N --> ImageConverter --> JavaFX thread
      (thread)       (thread)     (thread)              (thread)      (thread)          (display)
             queue          queue        queue              queue              display queue

- **FrameDecoder** is the only thread that uses the FFmpeg grabber. It copies each decoded frame into a
  reusable frame from a `FramePool` (FFmpeg reuses its own frame) and queues audio for playback.
- **One `ProcessorStage` thread per effect.** Each effect object is only ever used by its own thread, so
  effects that keep working buffers are never shared. While one effect works on frame N, the effect
  before it is already working on frame N+1, so several effects use several CPU cores at once.
- **ImageConverterStage** turns each finished frame into a JavaFX `Image` and returns the frame to the pool.
- **The JavaFX thread** only shows frames when they are due, writes audio and handles the controls.
  It never waits on the pipeline, so the window stays responsive while effects run.

`PlaybackPipeline` creates, starts and stops all of these threads.

## Avoiding race conditions

- **One owner per frame.** Frames pass between threads through `BlockingQueue`s, so exactly one thread
  holds a frame at any time and frames stay in decode order.
- **Thread confinement.** The grabber, each effect and the image converter each belong to a single thread.
- **Back-pressure.** The fixed-size `FramePool` limits how many frames are in flight. When it is empty,
  the decoder waits instead of running ahead.
- **Seeking.** A seek increments a generation number. Frames and audio decoded before the seek carry the
  old number and are discarded, so the pipeline does not need to stop.
- **Shutdown.** Threads are interrupted and joined before the grabber is closed and the frame memory is freed.