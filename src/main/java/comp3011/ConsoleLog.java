/*
 * Adelaide University COMP3011 Assignment 3.
 *
 * Authors:
 *   1. Kimchhay Leng (a2942784)
 *
 * Copyright 2026 Kimchhay Leng
 */
package comp3011;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Writes console messages from a background thread, so that no other thread ever waits for the console.
 *
 * <p>
 * Writing to {@code System.out} is blocking I/O: when whatever reads the console falls behind, the pipe fills and the
 * writing thread waits until it is read. If that thread were the JavaFX thread or a pipeline stage, playback would
 * freeze. Here messages are only put on a bounded queue, which never blocks, and a single daemon thread prints them.
 * If the console stops being read, that thread waits instead, the queue fills, and further messages are dropped.
 * Losing a log line is better than freezing the video.
 * </p>
 */
final class ConsoleLog {
	private static final int MAX_QUEUED_MESSAGES = 1000;
	private static final BlockingQueue<String> messages = new LinkedBlockingQueue<>(MAX_QUEUED_MESSAGES);

	static {
		Thread printer = new Thread(ConsoleLog::printMessages, "console-log");
		printer.setDaemon(true);
		printer.start();
	}

	private ConsoleLog() {
	}

	static void println(String message) {
		messages.offer(message); // Returns false instead of waiting when the queue is full.
	}

	static void printf(String format, Object... args) {
		println(String.format(format, args));
	}

	private static void printMessages() {
		try {
			while (true) {
				System.out.println(messages.take());
			}
		} catch (InterruptedException e) {
			// The application is exiting.
		}
	}
}
