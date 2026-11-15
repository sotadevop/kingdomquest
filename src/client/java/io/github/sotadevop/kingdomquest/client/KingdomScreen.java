package io.github.sotadevop.kingdomquest.client;

import io.github.sotadevop.kingdomquest.kingdom.KingdomInfoPayload;
import io.github.sotadevop.kingdomquest.kingdom.UpgradePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;

/** The kingdom menu, opened at the heart: name, logo, current level, build progress and the next upgrade. */
public class KingdomScreen extends Screen {
	private static final int W = 240;
	private static final int ROW = 40;
	private static final int TEXT = 0xFF404040;
	private static final int GOOD = 0xFF2E7D32;
	private static final int BAD = 0xFFB71C1C;

	private final KingdomInfoPayload info;
	private int left;
	private int top;
	private int h;

	public KingdomScreen(KingdomInfoPayload info) {
		super(Component.literal(info.name()));
		this.info = info;
	}

	public static void open(KingdomInfoPayload info) {
		Minecraft.getInstance().setScreenAndShow(new KingdomScreen(info));
	}

	private int optionsTop() {
		return this.top + (this.info.percent() >= 0 ? 66 : 40);
	}

	@Override
	protected void init() {
		this.h = (this.info.percent() >= 0 ? 66 : 40) + Math.max(1, this.info.options().size()) * ROW + 30;
		this.left = (this.width - W) / 2;
		this.top = (this.height - this.h) / 2;
		int y = optionsTop();
		for (KingdomInfoPayload.Option o : this.info.options()) {
			Button build = Button.builder(Component.translatable("kingdomquest.menu.build"), b -> {
				ClientPlayNetworking.send(new UpgradePayload(this.info.heart(), o.track()));
				this.onClose();
			}).bounds(this.left + W - 58, y + 10, 50, 18).build();
			// creative may start a new build while one runs; the server finishes the running one first
			boolean creative = this.minecraft != null && this.minecraft.player != null && this.minecraft.player.isCreative();
			build.active = (this.info.percent() < 0 || creative) && o.requirementMet() && o.canPay();
			this.addRenderableWidget(build);
			y += ROW;
		}
		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
				.bounds(this.left + W - 88, this.top + this.h - 26, 80, 18).build());
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(g, mouseX, mouseY, partialTick);
		g.fill(this.left - 1, this.top - 1, this.left + W + 1, this.top + this.h + 1, 0xFF000000);
		g.fill(this.left, this.top, this.left + W, this.top + this.h, 0xFFC6C6C6);
		int c = 0xFF000000 | DyeColor.byId(this.info.color()).getTextureDiffuseColor();
		g.fill(this.left + 8, this.top + 8, this.left + 28, this.top + 28, c);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		var logo = BuiltInRegistries.ITEM.getValue(this.info.logo());
		g.item(new ItemStack(logo), this.left + 10, this.top + 10);
		g.text(this.font, this.title, this.left + 34, this.top + 9, TEXT, false);
		g.text(this.font, Component.translatable(this.info.levelTitle()), this.left + 34, this.top + 20, TEXT, false);
		if (this.info.percent() >= 0) {
			int y = this.top + 40;
			g.text(this.font, Component.translatable("kingdomquest.menu.building", this.info.percent()), this.left + 8, y, TEXT, false);
			g.fill(this.left + 8, y + 12, this.left + W - 8, y + 18, 0xFF8B8B8B);
			g.fill(this.left + 8, y + 12, this.left + 8 + (W - 16) * this.info.percent() / 100, y + 18, GOOD);
		}
		int y = optionsTop();
		if (this.info.options().isEmpty()) {
			g.text(this.font, Component.translatable("kingdomquest.menu.max"), this.left + 8, y, TEXT, false);
			return;
		}
		for (KingdomInfoPayload.Option o : this.info.options()) {
			g.fill(this.left + 6, y - 3, this.left + W - 6, y - 2, 0xFF8B8B8B);
			g.text(this.font, Component.translatable("kingdomquest.menu.next", Component.translatable(o.title())), this.left + 8, y, TEXT, false);
			if (!o.requirement().isEmpty()) {
				g.text(this.font, Component.translatable("kingdomquest.menu.requires", Component.translatable(o.requirement())),
						this.left + 8, y + 11, o.requirementMet() ? GOOD : BAD, false);
			}
			g.text(this.font, Component.translatable("kingdomquest.menu.cost", o.cost()), this.left + 8, y + 22, o.canPay() ? GOOD : BAD, false);
			y += ROW;
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
