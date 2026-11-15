package io.github.sotadevop.kingdomquest.client;

import io.github.sotadevop.kingdomquest.kingdom.NamePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Asks the lord to name something new (a pet from the kennel). Closing without a change keeps the suggestion. */
public class NameScreen extends Screen {
	private static final int W = 220;
	private static final int H = 76;

	private final NamePayload request;
	private EditBox name;
	private int left;
	private int top;

	public NameScreen(NamePayload request) {
		super(Component.translatable("kingdomquest.name.title"));
		this.request = request;
	}

	@Override
	protected void init() {
		this.left = (this.width - W) / 2;
		this.top = (this.height - H) / 2;
		this.name = new EditBox(this.font, this.left + 8, this.top + 24, W - 16, 16, this.title);
		this.name.setMaxLength(24);
		this.name.setValue(this.request.name());
		this.addRenderableWidget(this.name);
		this.setInitialFocus(this.name);
		this.addRenderableWidget(Button.builder(Component.translatable("kingdomquest.name.done"), b -> this.onClose())
				.bounds(this.left + W - 8 - 60, this.top + H - 26, 60, 18).build());
	}

	@Override
	public void onClose() {
		String v = this.name.getValue().strip();
		ClientPlayNetworking.send(new NamePayload(this.request.heart(), this.request.key(), v.isEmpty() ? this.request.name() : v));
		super.onClose();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(g, mouseX, mouseY, partialTick);
		Ui.panel(g, this.left, this.top, W, H, ClientConfig.colors());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.text(this.font, this.title, this.left + 8, this.top + 8, ClientConfig.colors().heading(), false);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
