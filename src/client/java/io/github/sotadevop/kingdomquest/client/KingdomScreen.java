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
	private static final int W = 440;
	private static final int HEADER = 34;
	private static final int TAB_H = 18;
	private static final int CARD_H = 50;
	private static final int CARD_GAP = 4;
	private static final int MAX_CARDS = 4;
	private static final int TABS_PER_ROW = 6;
	private static final int H = HEADER + 2 * TAB_H + 10 + MAX_CARDS * (CARD_H + CARD_GAP) + 26;

	private record Tab(String key, List<String> tracks) {
	}

	private static final List<Tab> TABS = List.of(
			new Tab("kingdomquest.tab.settlement", List.of("main", "cellar", "royal", "townhall")),
			new Tab("kingdomquest.tab.defense", List.of("walls", "gate", "barracks", "belfry")),
			new Tab("kingdomquest.tab.guild", List.of("guild")),
			new Tab("kingdomquest.tab.streets", List.of("roads", "lamps")),
			new Tab("kingdomquest.tab.farming", List.of("farm", "stables", "apiary", "garden")),
			new Tab("kingdomquest.tab.resources", List.of("wood", "mine", "dock")),
			new Tab("kingdomquest.tab.crafts", List.of("smith", "workshop", "armorer")),
			new Tab("kingdomquest.tab.trade", List.of("tavern", "market", "markethall", "tannery")),
			new Tab("kingdomquest.tab.culture", List.of("library", "brewery", "music", "church")),
			new Tab("kingdomquest.tab.discovery", List.of("cartographer", "archaeology", "nether", "monastery")),
			new Tab("kingdomquest.tab.pets", List.of("kennel", "trophy", "lair")),
			new Tab("kingdomquest.tab.stores", List.of("storehouse")));
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

	private static net.minecraft.core.BlockPos lastHeart = net.minecraft.core.BlockPos.ZERO;

	static net.minecraft.core.BlockPos lastHeart() {
		return lastHeart;
	}

	public static void open(KingdomInfoPayload info) {
		lastHeart = info.heart();
		RealmScreen.forget();
		Minecraft.getInstance().setScreenAndShow(new KingdomScreen(info));
	}

	private boolean creative() {
		return this.minecraft != null && this.minecraft.player != null && this.minecraft.player.isCreative();
	}

	/** Only what exists or can be built now: tracks not unlocked yet stay hidden. */
	private List<KingdomInfoPayload.Option> cards(Tab tab) {
		List<KingdomInfoPayload.Option> list = new ArrayList<>();
		for (String track : tab.tracks()) {
			KingdomInfoPayload.Option o = this.byTrack.get(track);
			if (o != null && (o.level() > 0 || !o.locked())) {
				list.add(o);
			}
		}
		return list;
	}

	private List<Tab> visibleTabs() {
		return TABS.stream().filter(t -> !cards(t).isEmpty()).toList();
	}

	private List<KingdomInfoPayload.Option> cards() {
		List<Tab> tabs = visibleTabs();
		return tabs.isEmpty() ? List.of() : cards(tabs.get(Math.min(this.tab, tabs.size() - 1)));
	}

	private int cardY(int i) {
		return this.top + HEADER + 2 * TAB_H + 10 + i * (CARD_H + CARD_GAP);
	}

	@Override
	protected void init() {
		this.left = (this.width - W) / 2;
		this.top = (this.height - H) / 2;
		this.tabButtons.clear();
		this.buildButtons.clear();
		List<Tab> tabs = visibleTabs();
		this.tab = Math.min(this.tab, Math.max(0, tabs.size() - 1));
		int tabW = (W - 16 - (TABS_PER_ROW - 1) * 2) / TABS_PER_ROW;
		for (int i = 0; i < tabs.size(); i++) {
			int index = i;
			Button b = Button.builder(Component.translatable(tabs.get(i).key()), btn -> {
				this.tab = index;
				lastTab = index;
				this.rebuildWidgets();
			}).bounds(this.left + 8 + (i % TABS_PER_ROW) * (tabW + 2), this.top + HEADER + (i / TABS_PER_ROW) * (TAB_H + 2), tabW, TAB_H).build();
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
		Button color = Button.builder(Component.translatable("kingdomquest.menu.color"), b ->
				this.minecraft.setScreenAndShow(new ColorScreen(this.info.heart(), this.info.color(), this))).bounds(this.left + 8, this.top + H - 24, 60, 18).build();
		color.active = this.info.percent() < 0;
		this.tabButtons.add(this.addRenderableWidget(color));
		// tab actions in the footer: drawbridge (Defense), roads to other places (Streets)
		String tabKey = tabs.isEmpty() ? "" : tabs.get(this.tab).key();
		int fx = this.left + 72;
		if (tabKey.equals("kingdomquest.tab.defense") && this.info.bridge() >= 0) {
			this.tabButtons.add(this.addRenderableWidget(action(this.info.bridge() == 1 ? "kingdomquest.bridge.lower" : "kingdomquest.bridge.raise",
					"bridge", fx, 100)));
		}
		if (tabKey.equals("kingdomquest.tab.settlement") && this.info.damaged() > 0) {
			Button repair = Button.builder(Component.translatable("kingdomquest.menu.repair", this.info.damaged()), b -> {
				ClientPlayNetworking.send(new io.github.sotadevop.kingdomquest.kingdom.ActionPayload(this.info.heart(), "repair"));
				this.onClose();
			}).bounds(fx, this.top + H - 24, 80, 18).build();
			this.tabButtons.add(this.addRenderableWidget(repair));
		}
		int ux = fx + (this.info.damaged() > 0 ? 84 : 0);
		if (tabKey.equals("kingdomquest.tab.settlement") && this.info.percent() < 0) {
			// rebuilds everything from the mod's current plans (after an update to the buildings)
			this.tabButtons.add(this.addRenderableWidget(action("kingdomquest.menu.rebuild", "rebuild", ux, 70)));
			ux += 74;
		}
		if (tabKey.equals("kingdomquest.tab.settlement") && this.info.changed() > 0 && this.info.percent() < 0) {
			// the lord's own changes (blocks he broke or placed): repair leaves them; this puts the plan back
			Button undo = Button.builder(Component.translatable("kingdomquest.menu.undo", this.info.changed()), b -> {
				ClientPlayNetworking.send(new io.github.sotadevop.kingdomquest.kingdom.ActionPayload(this.info.heart(), "undo_changes"));
				this.onClose();
			}).bounds(ux, this.top + H - 24, 54, 18).tooltip(net.minecraft.client.gui.components.Tooltip.create(
					Component.translatable("kingdomquest.menu.undo_hint", this.info.changed()))).build();
			this.tabButtons.add(this.addRenderableWidget(undo));
		}
		// the realm: map of all settlements, villages and roads, plus the register of buildings, people and animals
		this.tabButtons.add(this.addRenderableWidget(Button.builder(Component.translatable("kingdomquest.realm.open"), b ->
				ClientPlayNetworking.send(new io.github.sotadevop.kingdomquest.kingdom.ActionPayload(this.info.heart(), "realm")))
				.bounds(this.left + W - 8 - 70 - 4 - 70, this.top + H - 24, 70, 18).build()));
		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
				.bounds(this.left + W - 8 - 70, this.top + H - 24, 70, 18).build());
	}

	private Button action(String label, String action, int x, int w) {
		return Button.builder(Component.translatable(label), b -> {
			ClientPlayNetworking.send(new io.github.sotadevop.kingdomquest.kingdom.ActionPayload(this.info.heart(), action));
			this.onClose();
		}).bounds(x, this.top + H - 24, w, 18).build();
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
		} else if (this.info.site() >= 0) {
			// the land of the site is still being shaped: only near the lord, the rest follows when he comes closer
			int bx = this.left + W - 8 - 110;
			g.text(this.font, Component.translatable("kingdomquest.menu.site_short", this.info.site()), bx, this.top + 7, t.text(), false);
			Ui.roundedFill(g, bx, this.top + 19, 110, 6, 3, t.panelDark());
			Ui.roundedFill(g, bx, this.top + 19, Math.max(6, 110 * this.info.site() / 100), 6, 3, t.done());
			if (mouseX >= bx && mouseX < bx + 110 && mouseY >= this.top + 5 && mouseY < this.top + 26) {
				g.setTooltipForNextFrame(this.font, Component.translatable("kingdomquest.menu.site", this.info.site()), mouseX, mouseY);
			}
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
