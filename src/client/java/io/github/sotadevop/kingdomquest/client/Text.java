package io.github.sotadevop.kingdomquest.client;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Small text helpers shared by the HUD, toast and journal. */
final class Text {

	private static final String ELLIPSIS = "...";
	private static final int MIN_WRAP_WIDTH = 40;

	private Text() {
	}

	/** Cuts a string to fit a width, adding "..." when something was cut. */
	static String fit(Font font, String text, int width) {
		if (font.width(text) <= width) {
			return text;
		}
		return font.plainSubstrByWidth(text, Math.max(0, width - font.width(ELLIPSIS))) + ELLIPSIS;
	}

	/** Word-wraps text (line breaks in the text are kept) into lines of at most the given width. */
	static List<FormattedCharSequence> wrap(Font font, String text, int width) {
		// A tiny window can make columns very narrow; never ask the splitter for a width below one word.
		return font.split(Component.literal(text), Math.max(width, MIN_WRAP_WIDTH));
	}
}
