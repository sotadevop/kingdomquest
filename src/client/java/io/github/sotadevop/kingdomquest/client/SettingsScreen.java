package io.github.sotadevop.kingdomquest.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** KingdomQuest's settings, opened from Mod Menu. Vanilla buttons, saved right away. */
public final class SettingsScreen extends Screen {
	private static final int BUTTON_WIDTH = 200;
	private static final int ROW = 24;

	private final Screen parent;

	public SettingsScreen(Screen parent) {
		super(Component.literal("KingdomQuest Settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int x = (this.width - BUTTON_WIDTH) / 2;
		int y = this.height / 4 + 24;
		addRenderableWidget(CycleButton.<Boolean>builder(modern -> Component.literal(modern ? "Modern" : "Vanilla"), ClientConfig.modernStyle())
				.withValues(true, false)
				.create(x, y, BUTTON_WIDTH, 20, Component.literal("Style"), (b, v) -> ClientConfig.setModernStyle(v)));
		addRenderableWidget(CycleButton.<String>builder(t -> Component.literal(switch (t) {
					case "dark" -> "Dark";
					case "light" -> "Light";
					default -> "Auto";
				}), ClientConfig.theme())
				.withValues("auto", "light", "dark")
				.create(x, y + ROW, BUTTON_WIDTH, 20, Component.literal("Menu colors"), (b, v) -> ClientConfig.setTheme(v)));
		addRenderableWidget(CycleButton.onOffBuilder(ClientConfig.placementPreview())
				.create(x, y + 2 * ROW, BUTTON_WIDTH, 20, Component.literal("Founding preview"), (b, v) -> ClientConfig.setPlacementPreview(v)));
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(x, this.height - 40, BUTTON_WIDTH, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.centeredText(this.font, getTitle(), this.width / 2, 20, 0xFFFFFFFF);
	}

	@Override
	public void onClose() {
		this.minecraft.setScreenAndShow(this.parent);
	}
}
