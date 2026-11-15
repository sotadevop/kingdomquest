package io.github.sotadevop.kingdomquest.client;

import io.github.sotadevop.kingdomquest.kingdom.KingdomInfoPayload;
import io.github.sotadevop.kingdomquest.kingdom.UpgradePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The kingdom menu, opened at the heart. Tabs group the upgrade tracks (Settlement, Defense, Streets, Add-ons);
 * each track is a card with its level pips (how many upgrades are left), the current and next level, what the
 * next one needs and a Build button. Hovering a card lists every level and the full cost.
 * Light/dark colors and the modern (rounded) or vanilla style follow the mod settings, like StoryQuest.
 */
public class KingdomScreen extends Screen {
	private static final int W = 360;
	private static final int HEADER = 34;
	private static final int TAB_H = 18;
	private static final int CARD_H = 50;
	private static final int CARD_GAP = 4;
	private static final int MAX_CARDS = 3;
	private static final int H = HEADER + TAB_H + 8 + MAX_CARDS * (CARD_H + CARD_GAP) + 26;

	private record Tab(String key, List<String> tracks) {
	}

	private static final List<Tab> TABS = List.of(
			new Tab("kingdomquest.tab.settlement", List.of("main")),
			new Tab("kingdomquest.tab.defense", List.of("walls")),
			new Tab("kingdomquest.tab.streets", List.of("roads", "lamps")),
			new Tab("kingdomquest.tab.farming", List.of("farm", "stables")),
			new Tab("kingdomquest.tab.crafts", List.of("smith", "wood")),
			new Tab("kingdomquest.tab.culture", List.of("library", "brewery")));
	private static int lastTab;

	private final KingdomInfoPayload info;
	private final Map<String, KingdomInfoPayload.Option> byTrack = new HashMap<>();
	private final List<Button> tabButtons = new ArrayList<>();
	private final List<Button> buildButtons = new ArrayList<>();
	private int tab;
	private int left;
	private int top;

	public KingdomScreen(KingdomInfoPayload info) {
		super(Component.literal(info.name()));
		this.info = info;
		info.options().forEach(o -> this.byTrack.put(o.track(), o));
		this.tab = Math.min(lastTab, TABS.size() - 1);
	}

	public static void open(KingdomInfoPayload info) {
		Minecraft.getInstance().setScreenAndShow(new KingdomScreen(info));
	}

	private boolean creative() {
		return this.minecraft != null && this.minecraft.player != null && this.minecraft.player.isCreative();
	}

	private List<KingdomInfoPayload.Option> cards() {
		List<KingdomInfoPayload.Option> list = new ArrayList<>();
		for (String track : TABS.get(this.tab).tracks()) {
			KingdomInfoPayload.Option o = this.byTrack.get(track);
			if (o != null) {
				list.add(o);
			}
		}
		return list;
	}

	private int cardY(int i) {
		return this.top + HEADER + TAB_H + 8 + i * (CARD_H + CARD_GAP);
	}

	@Override
	protected void init() {
		this.left = (this.width - W) / 2;
		this.top = (this.height - H) / 2;
		this.tabButtons.clear();
		this.buildButtons.clear();
		int tabW = (W - 16 - (TABS.size() - 1) * 2) / TABS.size();
		for (int i = 0; i < TABS.size(); i++) {
			int index = i;
			Button b = Button.builder(Component.translatable(TABS.get(i).key()), btn -> {
				this.tab = index;
				lastTab = index;
				this.rebuildWidgets();
			}).bounds(this.left + 8 + i * (tabW + 2), this.top + HEADER, tabW, TAB_H).build();
			b.active = i != this.tab; // the open tab is the highlighted one
			this.tabButtons.add(this.addRenderableWidget(b));
		}
		List<KingdomInfoPayload.Option> cards = cards();
		for (int i = 0; i < cards.size(); i++) {
			KingdomInfoPayload.Option o = cards.get(i);
			Button build = Button.builder(Component.translatable("kingdomquest.menu.build"), b -> {
				ClientPlayNetworking.send(new UpgradePayload(this.info.heart(), o.track()));
				this.onClose();
			}).bounds(this.left + W - 8 - 6 - 56, cardY(i) + CARD_H - 6 - 18, 56, 18).build();
			// creative may start a new build while one runs; the server finishes the running one first
			build.active = o.ready() && (this.info.percent() < 0 || creative());
			build.visible = !o.maxed() && !o.locked();
			this.buildButtons.add(this.addRenderableWidget(build));
		}
		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
				.bounds(this.left + W - 8 - 70, this.top + H - 24, 70, 18).build());
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(g, mouseX, mouseY, partialTick);
		Theme t = ClientConfig.colors();
		Ui.panel(g, this.left, this.top, W, H, t);
		// kingdom color swatch behind the logo
		int c = 0xFF000000 | DyeColor.byId(this.info.color()).getTextureDiffuseColor();
		Ui.raised(g, this.left + 8, this.top + 6, 22, 22, c, Ui.blend(c, 0xFFFFFFFF, 0.4f), Ui.blend(c, 0xFF000000, 0.4f));
		List<KingdomInfoPayload.Option> cards = cards();
		for (int i = 0; i < cards.size(); i++) {
			Ui.raised(g, this.left + 8, cardY(i), W - 16, CARD_H, t.selected(), t.panelLight(), t.panelDark());
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		Theme t = ClientConfig.colors();
		boolean dark = ClientConfig.darkMode();
		int bad = dark ? 0xFFFF6B6B : 0xFFB71C1C;
		for (Button b : this.tabButtons) {
			Ui.modernButton(g, this.font, b, t);
		}
		for (Button b : this.buildButtons) {
			Ui.modernButton(g, this.font, b, t);
		}
		// header: logo, name, current level, build progress
		g.item(new ItemStack(BuiltInRegistries.ITEM.getValue(this.info.logo())), this.left + 11, this.top + 9);
		g.text(this.font, this.title, this.left + 36, this.top + 7, t.heading(), false);
		g.text(this.font, Component.translatable(this.info.levelTitle()), this.left + 36, this.top + 18, t.secondary(), false);
		if (this.info.percent() >= 0) {
			int bx = this.left + W - 8 - 110;
			g.text(this.font, Component.translatable("kingdomquest.menu.building", this.info.percent()), bx, this.top + 7, t.text(), false);
			Ui.roundedFill(g, bx, this.top + 19, 110, 6, 3, t.panelDark());
			Ui.roundedFill(g, bx, this.top + 19, Math.max(6, 110 * this.info.percent() / 100), 6, 3, t.done());
		}
		List<KingdomInfoPayload.Option> cards = cards();
		for (int i = 0; i < cards.size(); i++) {
			drawCard(g, cards.get(i), cardY(i), mouseX, mouseY, t, bad);
		}
	}

	private void drawCard(GuiGraphicsExtractor g, KingdomInfoPayload.Option o, int y, int mouseX, int mouseY, Theme t, int bad) {
		int x = this.left + 14;
		int right = this.left + W - 14;
		int max = o.steps().size();
		g.text(this.font, Component.translatable("kingdomquest.track." + o.track()), x, y + 5, t.heading(), false);
		// level pips: filled = built, the rest = upgrades left
		String count = o.level() + " / " + max;
		int countX = right - this.font.width(count);
		g.text(this.font, count, countX, y + 5, t.secondary(), false);
		int pip = 7;
		int px = countX - 6 - max * (pip + 2);
		for (int p = 0; p < max; p++) {
			int color = p < o.level() ? t.done() : t.panelDark();
			Ui.roundedFill(g, px + p * (pip + 2), y + 5, pip, pip, Ui.modern() ? 2 : 0, color);
		}
		Component now = o.level() > 0 ? Component.translatable(o.steps().get(o.level() - 1)) : Component.translatable("kingdomquest.menu.not_built");
		g.text(this.font, Component.translatable("kingdomquest.menu.now", now), x, y + 17, t.secondary(), false);
		if (o.maxed()) {
			g.text(this.font, Component.translatable("kingdomquest.menu.complete"), x, y + 31, t.done(), false);
		} else if (o.locked()) {
			g.text(this.font, Component.translatable("kingdomquest.menu.unlocks_with", Component.translatable(o.lockedBy())), x, y + 31, t.locked(), false);
		} else {
			int textW = W - 28 - 64;
			g.text(this.font, Text.fit(this.font, Component.translatable("kingdomquest.menu.next", Component.translatable(o.steps().get(o.level())))
					.getString(), textW), x, y + 29, t.text(), false);
			String status = !o.requirementMet() ? Component.translatable("kingdomquest.menu.requires", Component.translatable(o.requirement())).getString()
					: !o.canPay() ? Component.translatable("kingdomquest.menu.missing").getString()
					: Component.translatable("kingdomquest.menu.ready").getString();
			g.text(this.font, Text.fit(this.font, status, textW), x, y + 39, o.ready() ? t.done() : bad, false);
		}
		boolean hover = mouseX >= this.left + 8 && mouseX < this.left + W - 8 && mouseY >= y && mouseY < y + CARD_H;
		if (hover) {
			List<Component> lines = new ArrayList<>();
			lines.add(Component.translatable("kingdomquest.track." + o.track()));
			for (int p = 0; p < max; p++) {
				String mark = p < o.level() ? "✔ " : p == o.level() ? "▶ " : "  ";
				lines.add(Component.literal(mark).append(Component.translatable(o.steps().get(p)))
						.withStyle(p < o.level() ? ChatFormatting.GREEN : p == o.level() ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
			}
			if (!o.maxed() && !o.locked()) {
				if (!o.requirement().isEmpty()) {
					lines.add(Component.translatable("kingdomquest.menu.requires", Component.translatable(o.requirement()))
							.withStyle(o.requirementMet() ? ChatFormatting.GREEN : ChatFormatting.RED));
				}
				lines.add(Component.translatable("kingdomquest.menu.cost", o.cost()).withStyle(o.canPay() ? ChatFormatting.GREEN : ChatFormatting.RED));
				if (creative()) {
					lines.add(Component.translatable("kingdomquest.menu.creative").withStyle(ChatFormatting.GRAY));
				}
			}
			g.setComponentTooltipForNextFrame(this.font, lines, mouseX, mouseY);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
