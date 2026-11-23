package io.github.sotadevop.kingdomquest.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.sotadevop.kingdomquest.kingdom.FealtyPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * The Fealty Ceremony's menu, opened by ringing a village's bell as its future liege. First "Claim Fealty": name the
 * village (a suggestion in the region's style is filled in) and see how many live there. Then the charter on
 * parchment: hold the mouse button on the line and the ruler's name is written in ink, letter by letter, with the
 * scratch of the quill; when it is done the kingdom's seal comes down in wax. Only then is the oath sent to the server.
 */
public class FealtyScreen extends Screen {
	private static final int W = 260;
	private static final int H = 136;
	private static final int INK = 0xFF2E2014;
	private static final int FADED = 0xFF7A5E3C;
	private static final int SIGN_INK = 0xFF1C2440;
	/** Ticks of holding to write the whole name. */
	private static final int SIGN_TICKS = 44;
	/** Ticks the seal takes to come down, and the pause after it before the ceremony starts. */
	private static final int DROP = 9;
	private static final int AFTER = 36;

	private final BlockPos bell;
	private final JsonObject info;
	private String villageName;
	private EditBox name;
	private boolean parchment;
	private float signed;
	private int sealTicks = -1;
	private int scratch;
	private int mouseX;
	private int mouseY;
	private int left;
	private int top;
	private int pw;
	private int ph;

	public FealtyScreen(FealtyPayload payload) {
		super(Component.translatable("kingdomquest.fealty.title"));
		this.bell = payload.bell();
		this.info = JsonParser.parseString(payload.json()).getAsJsonObject();
		this.villageName = this.info.get("name").getAsString();
	}

	public static void open(FealtyPayload payload) {
		Minecraft.getInstance().setScreenAndShow(new FealtyScreen(payload));
	}

	private int villagers() {
		return this.info.get("villagers").getAsInt();
	}

	private String kingdom() {
		return this.info.get("kingdom").getAsString();
	}

	private String ruler() {
		return this.info.get("ruler").getAsString();
	}

	private int dye() {
		return 0xFF000000 | DyeColor.byId(this.info.get("color").getAsInt()).getTextureDiffuseColor();
	}

	@Override
	protected void init() {
		if (this.parchment) {
			this.pw = Math.min(280, this.width - 20);
			this.ph = Math.min(214, this.height - 16);
			this.left = (this.width - this.pw) / 2;
			this.top = (this.height - this.ph) / 2;
			return;
		}
		this.left = (this.width - W) / 2;
		this.top = (this.height - H) / 2;
		this.name = new EditBox(this.font, this.left + 52, this.top + 22, W - 60, 16, Component.translatable("kingdomquest.fealty.village"));
		this.name.setMaxLength(32);
		this.name.setValue(this.villageName);
		this.addRenderableWidget(this.name);
		this.setInitialFocus(this.name);
		Button draw = Button.builder(Component.translatable("kingdomquest.fealty.draw_up"), b -> {
			this.villageName = this.name.getValue().strip();
			this.parchment = true;
			sound(SoundEvents.BOOK_PAGE_TURN, 1f, 1f);
			this.rebuildWidgets();
		}).bounds(this.left + W - 8 - 60 - 4 - 130, this.top + H - 26, 130, 18).build();
		draw.active = villagers() > 0;
		this.name.setResponder(v -> draw.active = villagers() > 0 && !v.isBlank());
		this.addRenderableWidget(draw);
		this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> this.onClose())
				.bounds(this.left + W - 8 - 60, this.top + H - 26, 60, 18).build());
	}

	private static void sound(SoundEvent sound, float pitch, float volume) {
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
	}

	// ---- signing ----

	private int lineX0() {
		return this.left + 24;
	}

	private int lineX1() {
		return this.left + this.pw / 2 + 16;
	}

	private int lineY() {
		return this.top + this.ph - 34;
	}

	private boolean onLine(int x, int y) {
		return x >= lineX0() - 8 && x <= lineX1() + 8 && y >= lineY() - 20 && y <= lineY() + 8;
	}

	@Override
	public void tick() {
		super.tick();
		if (!this.parchment) {
			return;
		}
		RandomSource r = RandomSource.create();
		if (this.signed < 1 && Minecraft.getInstance().mouseHandler.isLeftPressed() && onLine(this.mouseX, this.mouseY)) {
			this.signed = Math.min(1, this.signed + 1f / SIGN_TICKS);
			if (--this.scratch <= 0) {
				this.scratch = 3 + r.nextInt(3);
				sound(SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 0.85f + r.nextFloat() * 0.35f, 0.7f);
			}
			if (this.signed >= 1) {
				this.sealTicks = 0;
			}
		} else if (this.sealTicks >= 0) {
			this.sealTicks++;
			if (this.sealTicks == DROP) {
				sound(SoundEvents.HONEYCOMB_WAX_ON, 0.8f, 1f);
				sound(SoundEvents.BOOK_PUT, 0.6f, 1f);
				JsonObject answer = new JsonObject();
				answer.addProperty("name", this.villageName);
				ClientPlayNetworking.send(new FealtyPayload(this.bell, answer.toString()));
			}
			if (this.sealTicks >= DROP + AFTER) {
				this.onClose();
			}
		}
	}

	// ---- drawing ----

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(g, mouseX, mouseY, partialTick);
		if (this.parchment) {
			drawParchment(g);
		} else {
			Ui.panel(g, this.left, this.top, W, H, ClientConfig.colors());
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		this.mouseX = mouseX;
		this.mouseY = mouseY;
		if (this.parchment) {
			drawCharter(g, partialTick);
			return;
		}
		Theme t = ClientConfig.colors();
		g.text(this.font, this.title, this.left + 8, this.top + 8, t.heading(), false);
		g.text(this.font, Component.translatable("kingdomquest.fealty.village"), this.left + 8, this.top + 26, t.heading(), false);
		int y = this.top + 46;
		g.text(this.font, villagers() > 0 ? Component.translatable("kingdomquest.fealty.villagers", villagers())
				: Component.translatable("kingdomquest.fealty.nobody"), this.left + 8, y, t.text(), false);
		g.text(this.font, Component.translatable("kingdomquest.fealty.swear", kingdom()), this.left + 8, y + 12, t.text(), false);
		for (String line : wrap(Component.translatable("kingdomquest.fealty.grants", kingdom()).getString(), W - 16)) {
			y += 10;
			g.text(this.font, line, this.left + 8, y + 16, t.secondary(), false);
		}
	}

	/** Old parchment: darker toward its uneven edges, a few stains, a shadow under it. */
	private void drawParchment(GuiGraphicsExtractor g) {
		int x0 = this.left;
		int y0 = this.top;
		int x1 = x0 + this.pw;
		int y1 = y0 + this.ph;
		g.fill(x0 + 5, y0 + 6, x1 + 5, y1 + 6, 0x70000000);
		int[] edge = {0xFF6E4E28, 0xFF9C7C4A, 0xFFBEA26E, 0xFFD6BF8C};
		for (int i = 0; i < edge.length; i++) {
			g.fill(x0 + i, y0 + i, x1 - i, y1 - i, edge[i]);
		}
		g.fill(x0 + 4, y0 + 4, x1 - 4, y1 - 4, 0xFFE9D9B0);
		RandomSource r = RandomSource.create(this.bell.asLong());
		for (int x = x0; x < x1; x += 2) { // the edges are worn: little bites of darker brown
			g.fill(x, y0, x + 2, y0 + 1 + r.nextInt(3), 0xFF5A3E1E);
			g.fill(x, y1 - 1 - r.nextInt(3), x + 2, y1, 0xFF5A3E1E);
		}
		for (int y = y0; y < y1; y += 2) {
			g.fill(x0, y, x0 + 1 + r.nextInt(3), y + 2, 0xFF5A3E1E);
			g.fill(x1 - 1 - r.nextInt(3), y, x1, y + 2, 0xFF5A3E1E);
		}
		for (int i = 0; i < 16; i++) {
			int sw = 6 + r.nextInt(26);
			int sh = 4 + r.nextInt(14);
			int sx = x0 + 6 + r.nextInt(Math.max(1, this.pw - 12 - sw));
			int sy = y0 + 6 + r.nextInt(Math.max(1, this.ph - 12 - sh));
			Ui.roundedFill(g, sx, sy, sw, sh, 4, 0x14603A10);
		}
	}

	private void drawCharter(GuiGraphicsExtractor g, float partialTick) {
		int cx = this.left + this.pw / 2;
		int y = this.top + 12;
		String title = Component.translatable("kingdomquest.fealty.charter").getString();
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(cx, y);
		pose.scale(2f, 2f);
		g.text(this.font, title, -this.font.width(title) / 2, 0, INK, false);
		pose.popMatrix();
		y += 22;
		String realm = Component.translatable("kingdomquest.fealty.of_realm", this.info.get("empire").getAsString()).getString();
		g.text(this.font, Component.literal(realm).withStyle(ChatFormatting.ITALIC), cx - this.font.width(realm) / 2, y, FADED, false);
		y += 13;
		g.fill(cx - 60, y, cx - 4, y + 1, FADED); // a divider with a small diamond
		g.fill(cx + 4, y, cx + 60, y + 1, FADED);
		g.fill(cx - 1, y - 2, cx + 1, y + 3, FADED);
		g.fill(cx - 2, y - 1, cx + 2, y + 2, FADED);
		y += 8;
		String body = Component.translatable("kingdomquest.fealty.charter_text", this.villageName, villagers(), kingdom(), ruler(),
				this.info.get("day").getAsInt()).getString();
		for (String line : wrap(body, this.pw - 44)) {
			g.text(this.font, line, this.left + 22, y, INK, false);
			y += 10;
		}
		// the signature
		int ly = lineY();
		g.text(this.font, Component.translatable("kingdomquest.fealty.signed").withStyle(ChatFormatting.ITALIC), lineX0(), ly - 22, FADED, false);
		for (int x = lineX0(); x < lineX1(); x += 3) {
			g.fill(x, ly, x + 2, ly + 1, FADED);
		}
		String who = ruler();
		int shown = Math.round(who.length() * Math.min(1, this.signed));
		int x = lineX0() + 8;
		for (int i = 0; i < shown; i++) {
			String ch = who.substring(i, i + 1);
			int jitter = Math.floorMod(who.hashCode() * 31 + i * 17, 3) - 1; // a hand, not a printer
			g.text(this.font, ch, x, ly - 10 + jitter, SIGN_INK, false);
			x += this.font.width(ch) + 1;
		}
		if (this.signed > 0 && this.signed < 1) {
			g.item(new ItemStack(Items.FEATHER), x - 2, ly - 22); // the quill moves along with the name
		}
		if (this.signed < 1) {
			String hint = Component.translatable("kingdomquest.fealty.hold").getString();
			boolean blink = this.signed == 0 && Minecraft.getInstance().level != null && Minecraft.getInstance().level.getGameTime() / 10 % 2 == 0;
			g.text(this.font, hint, lineX0(), ly + 5, blink ? INK : FADED, false);
		}
		drawSeal(g, partialTick);
		if (this.sealTicks >= DROP) {
			String done = Component.translatable("kingdomquest.fealty.sealed", this.villageName).getString();
			g.text(this.font, done, cx - this.font.width(done) / 2, this.top + this.ph - 14, INK, false);
		}
	}

	/** The kingdom's seal: an empty ring until the name is signed, then the wax comes down with the logo pressed into it. */
	private void drawSeal(GuiGraphicsExtractor g, float partialTick) {
		int cx = this.left + this.pw - 54;
		int cy = lineY() - 10;
		int d = 38;
		if (this.sealTicks < 0) {
			Ui.roundedOutline(g, cx - d / 2, cy - d / 2, d, d, d / 2, 0x40603A10);
			return;
		}
		float k = Math.min(1f, (this.sealTicks + partialTick) / DROP);
		float scale = 2.4f - 1.4f * k * k; // falls fast at the end, like a stamp pressed down
		int ribbon = Ui.blend(dye(), 0xFF000000, 0.15f);
		int wax = Ui.blend(dye(), 0xFF000000, 0.4f);
		if (k >= 1) { // the ribbon under the wax, two tails
			for (int i = 0; i < 16; i++) {
				g.fill(cx - 9 - i / 2, cy + 10 + i, cx - 3 - i / 2, cy + 11 + i, ribbon);
				g.fill(cx + 3 + i / 2, cy + 10 + i, cx + 9 + i / 2, cy + 11 + i, ribbon);
			}
			g.fill(cx - 17, cy + 26, cx - 11, cy + 27, Ui.blend(ribbon, 0xFF000000, 0.3f));
			g.fill(cx + 11, cy + 26, cx + 17, cy + 27, Ui.blend(ribbon, 0xFF000000, 0.3f));
		}
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(cx, cy);
		pose.scale(scale, scale);
		if (k < 1) {
			Ui.roundedFill(g, -d / 2 + 3, -d / 2 + 4, d, d, d / 2, 0x40000000); // its shadow on the paper
		}
		RandomSource r = RandomSource.create(this.bell.asLong() * 7);
		for (int i = 0; i < 7; i++) { // the wax runs out unevenly at the rim
			double a = i * Math.PI * 2 / 7 + r.nextFloat();
			int bx = (int) Math.round(Math.cos(a) * (d / 2.0 - 3));
			int by = (int) Math.round(Math.sin(a) * (d / 2.0 - 3));
			Ui.roundedFill(g, bx - 5, by - 5, 10, 10, 5, wax);
		}
		Ui.roundedFill(g, -d / 2, -d / 2, d, d, d / 2, wax);
		Ui.roundedOutline(g, -d / 2 + 4, -d / 2 + 4, d - 8, d - 8, (d - 8) / 2, Ui.blend(wax, 0xFF000000, 0.35f));
		g.fill(-9, -d / 2 + 3, -2, -d / 2 + 4, Ui.blend(wax, 0xFFFFFFFF, 0.3f)); // a shine on the wax
		Identifier logo = Identifier.tryParse(this.info.get("logo").getAsString());
		g.item(new ItemStack(logo == null ? Items.STONE : BuiltInRegistries.ITEM.getValue(logo)), -8, -8);
		Ui.roundedFill(g, -9, -9, 18, 18, 4, (wax & 0x00FFFFFF) | 0x80000000); // pressed into the wax: it takes the wax's color
		pose.popMatrix();
	}

	private List<String> wrap(String text, int width) {
		List<String> out = new ArrayList<>();
		String line = "";
		for (String word : text.split(" ")) {
			String next = line.isEmpty() ? word : line + " " + word;
			if (this.font.width(next) > width && !line.isEmpty()) {
				out.add(line);
				line = word;
			} else {
				line = next;
			}
		}
		if (!line.isEmpty()) {
			out.add(line);
		}
		return out;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
