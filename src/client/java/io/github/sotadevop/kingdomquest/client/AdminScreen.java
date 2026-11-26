package io.github.sotadevop.kingdomquest.client;

import io.github.sotadevop.kingdomquest.kingdom.ActionPayload;
import io.github.sotadevop.kingdomquest.kingdom.KingdomInfoPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The administration (Verwaltung) of a kingdom: everything that runs the kingdom rather than builds it, out of the
 * main menu's footer (user, 2026-11-26): the colour, the drawbridge, repair, rebuild and undoing the lord's changes.
 */
public class AdminScreen extends Screen {
	private static final int W = 220;
	private static final int ROW = 22;

	private final KingdomInfoPayload info;
	private final Screen parent;
	private final List<Button> buttons = new ArrayList<>();
	private int left;
	private int top;
	private int h;

	public AdminScreen(KingdomInfoPayload info, Screen parent) {
		super(Component.translatable("kingdomquest.admin.title"));
		this.info = info;
		this.parent = parent;
	}

	private void send(String action) {
		ClientPlayNetworking.send(new ActionPayload(this.info.heart(), action));
		this.minecraft.setScreen(null);
	}

	private record Row(Component label, Runnable run, boolean active, Component hint) {
	}

	@Override
	protected void init() {
		this.buttons.clear();
		boolean idle = this.info.percent() < 0;
		List<Row> rows = new ArrayList<>();
		rows.add(new Row(Component.translatable("kingdomquest.menu.color"),
				() -> this.minecraft.setScreenAndShow(new ColorScreen(this.info.heart(), this.info.color(), this)), idle, null));
		if (this.info.bridge() >= 0) {
			rows.add(new Row(Component.translatable(this.info.bridge() == 1 ? "kingdomquest.bridge.lower" : "kingdomquest.bridge.raise"),
					() -> send("bridge"), true, null));
		}
		rows.add(new Row(Component.translatable("kingdomquest.menu.repair", this.info.damaged()), () -> send("repair"), this.info.damaged() > 0, null));
		rows.add(new Row(Component.translatable("kingdomquest.menu.rebuild"), () -> send("rebuild"), idle,
				Component.translatable("kingdomquest.admin.rebuild_hint")));
		rows.add(new Row(Component.translatable("kingdomquest.menu.undo", this.info.changed()), () -> send("undo_changes"),
				idle && this.info.changed() > 0, Component.translatable("kingdomquest.menu.undo_hint", this.info.changed())));
		this.h = 30 + rows.size() * ROW + 30;
		this.left = (this.width - W) / 2;
		this.top = (this.height - this.h) / 2;
		for (int i = 0; i < rows.size(); i++) {
			Row r = rows.get(i);
			Button.Builder bb = Button.builder(r.label(), b -> r.run().run()).bounds(this.left + 8, this.top + 26 + i * ROW, W - 16, 18);
			if (r.hint() != null) {
				bb.tooltip(Tooltip.create(r.hint()));
			}
			Button b = bb.build();
			b.active = r.active();
			this.buttons.add(this.addRenderableWidget(b));
		}
		this.buttons.add(this.addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> this.onClose())
				.bounds(this.left + W - 8 - 70, this.top + this.h - 24, 70, 18).build()));
	}

	@Override
	public void onClose() {
		this.minecraft.setScreenAndShow(this.parent);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(g, mouseX, mouseY, partialTick);
		Ui.panel(g, this.left, this.top, W, this.h, ClientConfig.colors());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		Theme t = ClientConfig.colors();
		for (Button b : this.buttons) {
			Ui.modernButton(g, this.font, b, t);
		}
		g.text(this.font, this.title, this.left + 8, this.top + 9, t.heading(), false);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
