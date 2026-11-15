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
	private static final int H = 150;
	private static final int TEXT = 0xFF404040;
	private static final int GOOD = 0xFF2E7D32;
	private static final int BAD = 0xFFB71C1C;

	private final KingdomInfoPayload info;
	private int left;
	private int top;

	public KingdomScreen(KingdomInfoPayload info) {
		super(Component.literal(info.name()));
		this.info = info;
	}

	public static void open(KingdomInfoPayload info) {
		Minecraft.getInstance().setScreenAndShow(new KingdomScreen(info));
	}

	@Override
	protected void init() {
		this.left = (this.width - W) / 2;
		this.top = (this.height - H) / 2;
		boolean hasNext = !this.info.nextTitle().isEmpty();
		Button build = Button.builder(Component.translatable("kingdomquest.menu.build"), b -> {
			ClientPlayNetworking.send(new UpgradePayload(this.info.heart()));
			this.onClose();
		}).bounds(this.left + 8, this.top + H - 26, 110, 18).build();
		build.active = hasNext && this.info.percent() < 0 && this.info.requirementMet() && this.info.canPay();
		build.visible = hasNext;
		this.addRenderableWidget(build);
		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
				.bounds(this.left + W - 88, this.top + H - 26, 80, 18).build());
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(g, mouseX, mouseY, partialTick);
		g.fill(this.left - 1, this.top - 1, this.left + W + 1, this.top + H + 1, 0xFF000000);
		g.fill(this.left, this.top, this.left + W, this.top + H, 0xFFC6C6C6);
		int c = 0xFF000000 | DyeColor.byId(this.info.color()).getTextureDiffuseColor();
		g.fill(this.left + 8, this.top + 8, this.left + 28, this.top + 28, c);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		var logo = BuiltInRegistries.ITEM.getValue(this.info.logo());
		g.item(new ItemStack(logo), this.left + 10, this.top + 10);
		g.text(this.font, this.title, this.left + 34, this.top + 9, TEXT, false);
		g.text(this.font, Component.translatable("kingdomquest.level.tent_" + this.info.level()), this.left + 34, this.top + 20, TEXT, false);
		int y = this.top + 40;
		if (this.info.percent() >= 0) {
			g.text(this.font, Component.translatable("kingdomquest.menu.building", this.info.percent()), this.left + 8, y, TEXT, false);
			g.fill(this.left + 8, y + 12, this.left + W - 8, y + 18, 0xFF8B8B8B);
			g.fill(this.left + 8, y + 12, this.left + 8 + (W - 16) * this.info.percent() / 100, y + 18, GOOD);
			y += 26;
		}
		if (this.info.nextTitle().isEmpty()) {
			g.text(this.font, Component.translatable("kingdomquest.menu.max"), this.left + 8, y, TEXT, false);
			return;
		}
		g.text(this.font, Component.translatable("kingdomquest.menu.next", Component.translatable(this.info.nextTitle())), this.left + 8, y, TEXT, false);
		if (!this.info.requirement().isEmpty()) {
			g.text(this.font, Component.translatable("kingdomquest.menu.requires", Component.translatable(this.info.requirement())),
					this.left + 8, y + 12, this.info.requirementMet() ? GOOD : BAD, false);
		}
		g.text(this.font, Component.translatable("kingdomquest.menu.cost", this.info.cost()), this.left + 8, y + 24,
				this.info.canPay() ? GOOD : BAD, false);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
