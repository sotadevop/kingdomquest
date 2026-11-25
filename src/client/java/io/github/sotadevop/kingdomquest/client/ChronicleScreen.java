package io.github.sotadevop.kingdomquest.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.sotadevop.kingdomquest.kingdom.ChroniclePayload;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The kingdom's chronicle, read like a book: the newest page first, one entry per paragraph with its day, the
 * highlights (founding, tiers, landmarks, vassals, battles, milestones) in gold. Toggle between the highlights and
 * everything.
 */
public class ChronicleScreen extends Screen {
	private static final int W = 300;
	private static final int H = 220;
	private static final int PAPER = 0xFFF3E9CF;
	private static final int INK = 0xFF3B2A1A;
	private static final int GOLD = 0xFF8A5A00;

	private final String kingdom;
	private final List<JsonObject> all = new ArrayList<>();
	private boolean highlights = true;
	private int page;
	private List<List<String>> pages = List.of();
	private List<List<Boolean>> golds = List.of();
	private int left;
	private int top;
	private Button toggle;
	/** The kingdom menu it was opened from: "Back" returns there (user, 2026-11-25: the chronicle had no way back). */
	private final Screen parent;

	public ChronicleScreen(ChroniclePayload payload, Screen parent) {
		super(Component.literal(""));
		this.parent = parent;
		JsonObject o = JsonParser.parseString(payload.json()).getAsJsonObject();
		this.kingdom = o.get("name").getAsString();
		JsonArray e = o.getAsJsonArray("entries");
		for (JsonElement x : e) {
			this.all.add(x.getAsJsonObject());
		}
		this.highlights = this.all.stream().anyMatch(x -> x.get("h").getAsBoolean());
	}

	private static Component text(JsonObject e) {
		JsonArray a = e.getAsJsonArray("a");
		Object[] args = new Object[a.size()];
		for (int i = 0; i < a.size(); i++) {
			String s = a.get(i).getAsString();
			args[i] = s.startsWith("kingdomquest.") ? Component.translatable(s) : Component.literal(s);
		}
		return Component.translatable("kingdomquest.chronicle." + e.get("k").getAsString(), args);
	}

	/** Breaks the entries (newest first) into pages of wrapped lines. */
	private void paginate() {
		List<List<String>> ps = new ArrayList<>();
		List<List<Boolean>> gs = new ArrayList<>();
		List<String> cur = new ArrayList<>();
		List<Boolean> curG = new ArrayList<>();
		int lines = (H - 64) / 10;
		for (int i = this.all.size() - 1; i >= 0; i--) {
			JsonObject e = this.all.get(i);
			boolean h = e.get("h").getAsBoolean();
			if (this.highlights && !h) {
				continue;
			}
			List<String> para = new ArrayList<>();
			para.add(Component.translatable("kingdomquest.chronicle.day", e.get("d").getAsInt()).getString());
			para.addAll(wrap(text(e).getString(), W - 40));
			if (cur.size() + para.size() + 1 > lines && !cur.isEmpty()) {
				ps.add(cur);
				gs.add(curG);
				cur = new ArrayList<>();
				curG = new ArrayList<>();
			}
			for (int k = 0; k < para.size(); k++) {
				cur.add(para.get(k));
				curG.add(k == 0 ? null : h);
			}
			cur.add("");
			curG.add(false);
		}
		if (!cur.isEmpty()) {
			ps.add(cur);
			gs.add(curG);
		}
		this.pages = ps;
		this.golds = gs;
		this.page = Math.min(this.page, Math.max(0, ps.size() - 1));
	}

	private List<String> wrap(String text, int width) {
		List<String> out = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			String next = line.isEmpty() ? word : line + " " + word;
			if (this.font.width(next) > width && !line.isEmpty()) {
				out.add(line.toString());
				line = new StringBuilder(word);
			} else {
				line = new StringBuilder(next);
			}
		}
		if (!line.isEmpty()) {
			out.add(line.toString());
		}
		return out;
	}

	@Override
	protected void init() {
		this.left = (this.width - W) / 2;
		this.top = (this.height - H) / 2;
		paginate();
		this.addRenderableWidget(Button.builder(Component.literal("<"), b -> {
			this.page = Math.max(0, this.page - 1);
		}).bounds(this.left + 12, this.top + H - 28, 20, 18).build());
		this.addRenderableWidget(Button.builder(Component.literal(">"), b -> {
			this.page = Math.min(Math.max(0, this.pages.size() - 1), this.page + 1);
		}).bounds(this.left + W - 32, this.top + H - 28, 20, 18).build());
		this.addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> this.onClose())
				.bounds(this.left + 36, this.top + H - 28, 50, 18).build());
		this.toggle = this.addRenderableWidget(Button.builder(label(), b -> {
			this.highlights = !this.highlights;
			this.page = 0;
			paginate();
			b.setMessage(label());
		}).bounds(this.left + W / 2 - 40, this.top + H - 28, 100, 18).build());
	}

	@Override
	public void onClose() {
		if (this.parent != null && this.minecraft != null) {
			this.minecraft.setScreenAndShow(this.parent);
		} else {
			super.onClose();
		}
	}

	private Component label() {
		return Component.translatable(this.highlights ? "kingdomquest.chronicle.all" : "kingdomquest.chronicle.highlights");
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(g, mouseX, mouseY, partialTick);
		g.fill(this.left - 2, this.top - 2, this.left + W + 2, this.top + H + 2, 0xFF5A3A1E);
		g.fill(this.left, this.top, this.left + W, this.top + H, PAPER);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		Component title = Component.translatable("kingdomquest.chronicle.title", this.kingdom);
		g.text(this.font, title, this.left + (W - this.font.width(title)) / 2, this.top + 10, INK, false);
		g.fill(this.left + 20, this.top + 22, this.left + W - 20, this.top + 23, 0xFFB89B6A);
		if (this.pages.isEmpty()) {
			Component none = Component.translatable("kingdomquest.chronicle.empty");
			g.text(this.font, none, this.left + (W - this.font.width(none)) / 2, this.top + H / 2 - 10, INK, false);
			return;
		}
		List<String> lines = this.pages.get(this.page);
		List<Boolean> gs = this.golds.get(this.page);
		int y = this.top + 30;
		for (int i = 0; i < lines.size(); i++) {
			Boolean gold = gs.get(i);
			int color = gold == null ? 0xFF7A6248 : gold ? GOLD : INK;
			g.text(this.font, Component.literal(lines.get(i)), this.left + 20, y, color, false);
			y += 10;
		}
		Component pg = Component.translatable("kingdomquest.chronicle.page", this.page + 1, this.pages.size());
		g.text(this.font, pg, this.left + (W - this.font.width(pg)) / 2, this.top + H - 42, 0xFF7A6248, false);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
