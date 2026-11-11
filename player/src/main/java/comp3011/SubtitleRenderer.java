/*
 * Adelaide University COMP3011 Assignment 3.
 *
 * Authors:
 *   1. Kimchhay Leng (a2942784)
 *
 * Copyright 2026 Kimchhay Leng
 */
package comp3011;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws subtitle text as white sans-serif letters with a black outline, then blends it onto video frames.
 *
 * <p>
 * Drawing antialiased, outlined text is slow compared with a frame's time budget, but a subtitle stays on screen for
 * dozens of frames. So {@link #render} draws each subtitle once into a small bitmap (Java2D, which unlike JavaFX
 * can draw on any thread), and {@link Bitmap#blendOnto} copies that bitmap onto every frame, touching only the pixels
 * inside the text. The outline keeps the text readable on any background, light or dark, and the text has no
 * background box, so it covers as little of the picture as possible.
 * </p>
 */
final class SubtitleRenderer{
	private static final double FONT_HEIGHT_FRACTION = 1.0 / 18; // Text height relative to the frame height.
	private static final double MAX_WIDTH_FRACTION = 0.9; // The longest line may use 90% of the frame width.
	private static final double BOTTOM_MARGIN_FRACTION = 0.06;
	private static final double OUTLINE_FRACTION = 0.14; // Outline thickness relative to the font size.
	private static final int MIN_FONT_SIZE = 10;
	
	private SubtitleRenderer() {
	}
	
	/*
	 * A drawn subtitle: ARGB pixels and where their top left corner goes on the
	 * frame.
	 */
	record Bitmap(int[] argb, int width, int height, int x, int y) {
		
		/*
		 * Alpha-blends the subtitle onto an 8-bit frame. Every colour in it is a shade
		 * of grey between the white fill and the black outline, so it looks the same
		 * whatever order the frame stores its colour channels in.
		 */
		void blendOnto(ByteBuffer pixels, int frameWidth, int frameHeight, int stride, int channels) {
			int colourChannels = Math.min(channels, 3);
			for (int row = 0; row < height; row++) {
				int frameY = y + row;
				if (frameY < 0 || frameY >= frameHeight) {
					continue;
				}
				for (int column = 0; column < width; column++) {
					int frameX = x + column;
					int pixel = argb[row * width + column];
					int alpha = pixel >>> 24;
					if (alpha == 0 || frameX < 0 || frameX >= frameWidth) {
						continue;
					}
					int grey = pixel & 0xff;
					int base = frameY * stride + frameX * channels;
					for (int channel = 0; channel < colourChannels; channel++) {
						int under = pixels.get(base + channel) & 0xff;
						int blended = (grey * alpha + under * (255 - alpha) + 127) / 255;
						pixels.put(base + channel, (byte) blended);
					}
				}
			}
		}
	}
	
	/* Draws the lines centred near the bottom of a frame of the given size. */
	static Bitmap render(List<String> lines, int frameWidth, int frameHeight) {
		FontRenderContext context = new FontRenderContext(new AffineTransform(), true, true);
		float size = (float) Math.max(MIN_FONT_SIZE, frameHeight * FONT_HEIGHT_FRACTION);
		Font font = new Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(size);
		
		// Shrink the font if a line would be too wide, as on narrow 4:3 video.
		double widest = widestLine(lines, font, context);
		double maxWidth = frameWidth * MAX_WIDTH_FRACTION;
		if (widest > maxWidth) {
			size = (float) Math.max(MIN_FONT_SIZE, size * maxWidth / widest);
			font = font.deriveFont(size);
		}
		
		float outline = Math.max(1.5f, (float) (size * OUTLINE_FRACTION));
		List<TextLayout> layouts = new ArrayList<>();
		for (String line : lines) {
			layouts.add(new TextLayout(line, font, context));
		}
		double lineHeight = layouts.get(0).getAscent() + layouts.get(0).getDescent() + layouts.get(0).getLeading();
		double textWidth = widestLine(lines, font, context);
		int padding = (int) Math.ceil(outline) + 1;
		int width = (int) Math.ceil(textWidth) + 2 * padding;
		int height = (int) Math.ceil(lineHeight * lines.size()) + 2 * padding;
		
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			BasicStroke stroke = new BasicStroke(outline, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
			
			for(int i = 0; i < layouts.size(); i++) {
				TextLayout layout = layouts.get(i);
				double lineX = padding + (textWidth - layout.getAdvance()) / 2;
				double baseline = padding + i * lineHeight + layout.getAscent();
				Shape glyphs = layout.getOutline(AffineTransform.getTranslateInstance(lineX, baseline));
				
				g.setColor(Color.BLACK);
				g.setStroke(stroke);
				g.draw(glyphs); // The outline is drawn first, and th white fill on top keeps letters crisp.
				g.setColor(Color.WHITE);
				g.fill(glyphs);
			}
		} finally {
			g.dispose();
		}
		
		int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
		int x = (frameWidth - width) / 2;
		int y = (int) (frameHeight - frameHeight * BOTTOM_MARGIN_FRACTION) - height;
		return new Bitmap(argb, width, height, x, Math.max(0, y));
	}
	
	private static double widestLine(List<String> lines, Font font, FontRenderContext context) {
		double widest = 0;
		for (String line :: lines) {
			widest = Math.max(widest, new TextLayout(line, font, context).getAdvance());
		}
		return widest;
	}
}
