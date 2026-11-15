package io.github.sotadevop.kingdomquest.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;

/**
 * Shapes shared by the journal, Discoveries, scroll bars and tracker, in two styles:
 * <ul>
 * <li>Vanilla: square boxes with the light/dark bevel of the game's own inventory.</li>
 * <li>Modern: flat boxes with rounded corners.</li>
 * </ul>
 * Rounded corners are cut pixel by pixel from plain fills (like vanilla tooltips), so they stay
 * sharp at every GUI scale and take any theme color. The rows of one shape never overlap, so
 * semi-transparent colors don't get darker where they would.
 */
final class Ui {

	static final int PANEL_RADIUS = 6;
	static final int BOX_RADIUS = 4;
	static final int SMALL_RADIUS = 3;

	private Ui() {
	}

	static boolean modern() {
		return ClientConfig.modernStyle();
	}

	// ------------------------------------------------------------------ rounded primitives

	/** How far each of the top rows is cut in at the corner, for a quarter circle of this radius. */
	private static int inset(int radius, int row) {
		double dy = radius - row - 0.5;
		return (int) Math.round(radius - Math.sqrt(radius * radius - dy * dy));
	}

	/** A filled rectangle with rounded corners. The radius shrinks to fit small shapes. */
	static void roundedFill(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
		int r = Math.min(radius, Math.min(w, h) / 2);
		if (r <= 0) {
			g.fill(x, y, x + w, y + h, color);
			return;
		}
		for (int row = 0; row < r; row++) {
			int cut = inset(r, row);
			g.fill(x + cut, y + row, x + w - cut, y + row + 1, color);
			g.fill(x + cut, y + h - row - 1, x + w - cut, y + h - row, color);
		}
		g.fill(x, y + r, x + w, y + h - r, color);
	}

	/** A 1 pixel rounded border without a fill. */
	static void roundedOutline(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
		int r = Math.min(radius, Math.min(w, h) / 2);
		if (r <= 0) {
			g.outline(x, y, w, h, color);
			return;
		}
		int previous = inset(r, 0);
		g.fill(x + previous, y, x + w - previous, y + 1, color);
		g.fill(x + previous, y + h - 1, x + w - previous, y + h, color);
		for (int row = 1; row < r; row++) {
			int cut = inset(r, row);
			// Connect to the row above, so steep parts of the curve have no gaps.
			int from = cut;
			int to = Math.max(cut + 1, previous);
			g.fill(x + from, y + row, x + to, y + row + 1, color);
			g.fill(x + w - to, y + row, x + w - from, y + row + 1, color);
			g.fill(x + from, y + h - row - 1, x + to, y + h - row, color);
			g.fill(x + w - to, y + h - row - 1, x + w - from, y + h - row, color);
			previous = cut;
		}
		g.fill(x, y + r, x + 1, y + h - r, color);
		g.fill(x + w - 1, y + r, x + w, y + h - r, color);
	}

	/** Mixes two opaque colors: 0 = a, 1 = b. */
	static int blend(int a, int b, float amount) {
		int r = Math.round(((a >> 16) & 0xFF) * (1 - amount) + ((b >> 16) & 0xFF) * amount);
		int gr = Math.round(((a >> 8) & 0xFF) * (1 - amount) + ((b >> 8) & 0xFF) * amount);
		int bl = Math.round((a & 0xFF) * (1 - amount) + (b & 0xFF) * amount);
		return 0xFF000000 | r << 16 | gr << 8 | bl;
	}

	// ------------------------------------------------------------------ styled shapes

	/** The journal's main panel with its outer border. */
	static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, Theme t) {
		if (modern()) {
			roundedFill(g, x - 1, y - 1, w + 2, h + 2, PANEL_RADIUS + 1, t.panelDark());
			roundedFill(g, x, y, w, h, PANEL_RADIUS, t.panel());
			return;
		}
		g.outline(x - 1, y - 1, w + 2, h + 2, 0xFF000000);
		raised(g, x, y, w, h, t.panel(), t.panelLight(), t.panelDark());
	}

	/** Raised box (vanilla bevel) or flat rounded box (modern). */
	static void raised(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill, int light, int dark) {
		if (modern()) {
			roundedFill(g, x, y, w, h, SMALL_RADIUS, fill);
			return;
		}
		g.fill(x, y, x + w, y + h, fill);
		g.fill(x, y, x + w - 1, y + 1, light);
		g.fill(x, y, x + 1, y + h - 1, light);
		g.fill(x + 1, y + h - 1, x + w, y + h, dark);
		g.fill(x + w - 1, y + 1, x + w, y + h, dark);
	}

	/** Sunken box like an inventory slot: dark top-left edge, light bottom-right edge. */
	static void sunken(GuiGraphicsExtractor g, int x, int y, int w, int h, Theme t) {
		if (modern()) {
			roundedFill(g, x, y, w, h, w <= 24 && h <= 24 ? SMALL_RADIUS : BOX_RADIUS, t.inset());
			return;
		}
		raised(g, x, y, w, h, t.inset(), t.insetDark(), t.insetLight());
	}

	static void divider(GuiGraphicsExtractor g, Theme t, int x, int top, int bottom) {
		if (modern()) {
			g.fill(x, top + 2, x + 1, bottom - 2, t.panelDark());
			return;
		}
		g.fill(x, top, x + 1, bottom, t.insetDark());
		g.fill(x + 1, top, x + 2, bottom, t.insetLight());
	}

	static void selectedRow(GuiGraphicsExtractor g, Theme t, int x, int y, int w, int h) {
		if (modern()) {
			roundedFill(g, x, y, w, h, SMALL_RADIUS, t.selected());
			return;
		}
		g.fill(x, y, x + w, y + h, t.selected());
		g.outline(x, y, w, h, 0xFFFFFFFF);
	}

	/** The outline of a warning or highlight box. */
	static void boxOutline(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		if (modern()) {
			roundedOutline(g, x, y, w, h, BOX_RADIUS, color);
			return;
		}
		g.outline(x, y, w, h, color);
	}

	/**
	 * Draws a vanilla button again in the modern style, over the one vanilla just drew. The vanilla
	 * widget stays underneath for clicks, keyboard focus and narration.
	 */
	static void modernButton(GuiGraphicsExtractor g, Font font, AbstractWidget button, Theme t) {
		if (!modern() || !button.visible) {
			return;
		}
		int x = button.getX();
		int y = button.getY();
		int w = button.getWidth();
		int h = button.getHeight();
		// An inactive button is the tab you're on, so it is the highlighted one.
		int fill = !button.active ? t.panelLight()
			: button.isHoveredOrFocused() ? blend(t.inset(), t.insetLight(), 0.3f) : t.inset();
		int text = !button.active ? t.heading() : t.insetText();
		roundedFill(g, x, y, w, h, BOX_RADIUS, t.panelDark());
		roundedFill(g, x + 1, y + 1, w - 2, h - 2, BOX_RADIUS - 1, fill);
		String label = Text.fit(font, button.getMessage().getString(), w - 8);
		g.text(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, text, false);
	}

	/**
	 * Places a search field. Vanilla style: the vanilla box. Modern style: a rounded box drawn here
	 * and the text field inside it without its own frame. Call every frame before the widgets draw.
	 */
	static void searchBox(GuiGraphicsExtractor g, EditBox box, Theme t, int x, int y, int width, int height) {
		boolean modern = modern();
		box.setBordered(!modern);
		if (modern) {
			roundedFill(g, x, y, width, height, BOX_RADIUS, box.isFocused() ? t.heading() : t.panelDark());
			roundedFill(g, x + 1, y + 1, width - 2, height - 2, BOX_RADIUS - 1, t.inset());
			// Without a frame the field draws its text at its own top left, so it moves inside the box.
			box.setX(x + 5);
			box.setY(y + (height - 8) / 2);
			box.setWidth(width - 10);
		} else {
			box.setX(x);
			box.setY(y);
			box.setWidth(width);
		}
	}
}
