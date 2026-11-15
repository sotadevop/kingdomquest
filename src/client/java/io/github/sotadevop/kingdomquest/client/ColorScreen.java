package io.github.sotadevop.kingdomquest.client;

import io.github.sotadevop.kingdomquest.kingdom.RecolorPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;

/** Picks a new kingdom color; the server swaps every colored block of the settlement at once. */
public class ColorScreen extends Screen {
	private static final int SWATCH = 18;
	private static final int W = 8 * (SWATCH + 4) + 16;
	private static final int H = 2 * (SWATCH + 4) + 60;

	private final BlockPos heart;
	private final Screen parent;
	private int color;
	private int left;
	private int top;

	public ColorScreen(BlockPos heart, int color, Screen parent) {
		super(Component.translatable("kingdomquest.menu.color_title"));
		this.heart = heart;
		this.color = color;
		this.parent = parent;
	}

	private int sx(int i) {
		return this.left + 8 + (i % 8) * (SWATCH + 4);
	}

	private int sy(int i) {
		return this.top + 22 + (i / 8) * (SWATCH + 4);
	}

	@Override
	protected void init() {
		this.left = (this.width - W) / 2;
		this.top = (this.height - H) / 2;
		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> {
			ClientPlayNetworking.send(new RecolorPayload(this.heart, this.color));
			this.onClose();
		}).bounds(this.left + W - 8 - 60, this.top + H - 26, 60, 18).build());
		this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> this.minecraft.setScreenAndShow(this.parent))
				.bounds(this.left + 8, this.top + H - 26, 60, 18).build());
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(g, mouseX, mouseY, partialTick);
		Theme t = ClientConfig.colors();
		Ui.panel(g, this.left, this.top, W, H, t);
		for (DyeColor dye : DyeColor.values()) {
			int i = dye.getId();
			int c = 0xFF000000 | dye.getTextureDiffuseColor();
			if (i == this.color) {
				Ui.roundedFill(g, sx(i) - 2, sy(i) - 2, SWATCH + 4, SWATCH + 4, Ui.modern() ? 4 : 0, t.heading());
			}
			Ui.roundedFill(g, sx(i), sy(i), SWATCH, SWATCH, Ui.modern() ? 3 : 0, c);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.text(this.font, this.title, this.left + 8, this.top + 8, ClientConfig.colors().heading(), false);
		for (DyeColor dye : DyeColor.values()) {
			int i = dye.getId();
			if (mouseX >= sx(i) && mouseX < sx(i) + SWATCH && mouseY >= sy(i) && mouseY < sy(i) + SWATCH) {
				g.setTooltipForNextFrame(this.font, Component.translatable("color.minecraft." + dye.getName()), mouseX, mouseY);
			}
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (DyeColor dye : DyeColor.values()) {
			int i = dye.getId();
			if (event.x() >= sx(i) && event.x() < sx(i) + SWATCH && event.y() >= sy(i) && event.y() < sy(i) + SWATCH) {
				this.color = i;
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
