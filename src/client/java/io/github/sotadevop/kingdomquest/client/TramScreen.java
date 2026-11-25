package io.github.sotadevop.kingdomquest.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.sotadevop.kingdomquest.kingdom.TramGoPayload;
import io.github.sotadevop.kingdomquest.kingdom.TramPayload;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** The tram stop's lectern: one button per other stop on the line; picking one starts the ride. */
public class TramScreen extends Screen {
	private final List<String> names = new ArrayList<>();
	private static final int W = 200;
	private static final int ROW = 22;

	private final BlockPos heart;
	private final int here;
	private final List<Integer> stops = new ArrayList<>();
	private int left;
	private int top;
	private int h;

	public TramScreen(TramPayload payload) {
		super(Component.translatable("kingdomquest.tram.title"));
		JsonObject o = JsonParser.parseString(payload.json()).getAsJsonObject();
		this.heart = new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
		this.here = o.get("here").getAsInt();
		o.getAsJsonArray("stops").forEach(e -> this.stops.add(e.getAsInt()));
		if (o.has("names")) {
			o.getAsJsonArray("names").forEach(e -> this.names.add(e.getAsString()));
		}
	}

	private Component stop(int i) {
		return Component.translatable("kingdomquest.tram.stop." + (i < this.names.size() ? this.names.get(i) : "gate"));
	}

	@Override
	protected void init() {
		int others = (int) this.stops.stream().filter(i -> i != this.here).count();
		this.h = 46 + Math.max(1, others) * ROW + 8;
		this.left = (this.width - W) / 2;
		this.top = (this.height - this.h) / 2;
		int y = this.top + 44;
		for (int i : this.stops) {
			if (i == this.here) {
				continue;
			}
			int to = i;
			this.addRenderableWidget(Button.builder(stop(i), b -> {
				ClientPlayNetworking.send(new TramGoPayload(this.heart, this.here, to));
				this.onClose();
			}).bounds(this.left + 8, y, W - 16, 18).build());
			y += ROW;
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(g, mouseX, mouseY, partialTick);
		Ui.panel(g, this.left, this.top, W, this.h, ClientConfig.colors());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.text(this.font, this.title, this.left + 8, this.top + 8, ClientConfig.colors().heading(), false);
		g.text(this.font, Component.translatable("kingdomquest.tram.here", stop(this.here)), this.left + 8, this.top + 20,
				ClientConfig.colors().heading(), false);
		boolean any = this.stops.stream().anyMatch(i -> i != this.here);
		g.text(this.font, Component.translatable(any ? "kingdomquest.tram.where" : "kingdomquest.tram.none"), this.left + 8,
				this.top + 32, ClientConfig.colors().heading(), false);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
