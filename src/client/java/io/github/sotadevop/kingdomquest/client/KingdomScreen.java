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
import java.util.List;

/**
 * The kingdom menu, opened at the heart: name, logo, current level, build progress, and one compact cell per
 * upgrade (two columns). Hovering a cell shows what it needs.
 */
public class KingdomScreen extends Screen {
	private static final int W = 420;
	private static final int COL = (W - 16) / 2;
	private static final int ROW = 28;
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

	private int cellX(int i) {
		return this.left + 8 + (i % 2) * COL;
	}

	private int cellY(int i) {
		return optionsTop() + (i / 2) * ROW;
	}

	private boolean creative() {
		return this.minecraft != null && this.minecraft.player != null && this.minecraft.player.isCreative();
	}

	@Override
	protected void init() {
		int rows = Math.max(1, (this.info.options().size() + 1) / 2);
		this.h = (this.info.percent() >= 0 ? 66 : 40) + rows * ROW + 30;
		this.left = (this.width - W) / 2;
		this.top = (this.height - this.h) / 2;
		List<KingdomInfoPayload.Option> options = this.info.options();
		for (int i = 0; i < options.size(); i++) {
			KingdomInfoPayload.Option o = options.get(i);
			Button build = Button.builder(Component.translatable("kingdomquest.menu.build"), b -> {
				ClientPlayNetworking.send(new UpgradePayload(this.info.heart(), o.track()));
				this.onClose();
			}).bounds(cellX(i) + COL - 54, cellY(i) + 2, 48, 18).build();
			// creative may start a new build while one runs; the server finishes the running one first
			build.active = (this.info.percent() < 0 || creative()) && o.requirementMet() && o.canPay();
			this.addRenderableWidget(build);
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
		for (int i = 0; i < this.info.options().size(); i++) {
			g.fill(cellX(i), cellY(i), cellX(i) + COL - 4, cellY(i) + ROW - 4, 0xFFB4B4B4);
		}
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
		List<KingdomInfoPayload.Option> options = this.info.options();
		if (options.isEmpty()) {
			g.text(this.font, Component.translatable("kingdomquest.menu.max"), this.left + 8, optionsTop(), TEXT, false);
			return;
		}
		boolean creative = creative();
		for (int i = 0; i < options.size(); i++) {
			KingdomInfoPayload.Option o = options.get(i);
			int x = cellX(i) + 4;
			int y = cellY(i) + 3;
			g.text(this.font, Component.translatable(o.title()), x, y, TEXT, false);
			String status = !o.requirementMet() ? "kingdomquest.menu.locked" : !o.canPay() ? "kingdomquest.menu.missing" : "kingdomquest.menu.ready";
			g.text(this.font, Component.translatable(status), x, y + 11, o.requirementMet() && o.canPay() ? GOOD : BAD, false);
			if (mouseX >= cellX(i) && mouseX < cellX(i) + COL - 4 && mouseY >= cellY(i) && mouseY < cellY(i) + ROW - 4) {
				List<Component> lines = new ArrayList<>();
				lines.add(Component.translatable(o.title()));
				if (!o.requirement().isEmpty()) {
					lines.add(Component.translatable("kingdomquest.menu.requires", Component.translatable(o.requirement()))
							.withStyle(o.requirementMet() ? ChatFormatting.GREEN : ChatFormatting.RED));
				}
				lines.add(Component.translatable("kingdomquest.menu.cost", o.cost()).withStyle(o.canPay() ? ChatFormatting.GREEN : ChatFormatting.RED));
				if (creative) {
					lines.add(Component.literal("Creative: free, no requirement").withStyle(ChatFormatting.GRAY));
				}
				g.setComponentTooltipForNextFrame(this.font, lines, mouseX, mouseY);
			}
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
