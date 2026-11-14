package io.github.sotadevop.kingdomquest.client;

import io.github.sotadevop.kingdomquest.charter.CharterData;
import io.github.sotadevop.kingdomquest.charter.FoundingPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.BlockItem;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Locale;

/**
 * Founding menu of the Royal Charter: kingdom name, color (one of the 16 dyes, used as an accent) and logo
 * (any block, with a search). Adapted from Sota Tweaks' waypoint editor. Can be reopened to change the choices.
 */
public class FoundingScreen extends Screen {
	private static final int PAD = 7;
	private static final int SLOT = 18;
	private static final int LABEL = 0xFF404040;
	private static List<Item> allItems;

	private final CharterData editing;
	private final boolean offhand;
	private EditBox name;
	private EditBox search;
	private Button save;
	private Item icon;
	private int color;
	private List<Item> shown = List.of();
	private int scroll;
	private int left;
	private int top;
	private int w;
	private int h;
	private int gridX;
	private int gridY;
	private int columns;
	private int rows;

	public FoundingScreen(CharterData editing, boolean offhand) {
		super(Component.translatable("kingdomquest.founding.title"));
		this.editing = editing;
		this.offhand = offhand;
		this.icon = editing == null ? null : BuiltInRegistries.ITEM.getValue(editing.logo());
		this.color = editing == null ? DyeColor.BLUE.getId() : editing.color();
	}

	public static void open(ItemStack stack, boolean offhand) {
		Minecraft.getInstance().setScreenAndShow(new FoundingScreen(stack.get(io.github.sotadevop.kingdomquest.charter.RoyalCharter.DATA), offhand));
	}

	private static List<Item> allItems() {
		if (allItems == null) {
			allItems = BuiltInRegistries.ITEM.stream().filter(item -> item != Items.AIR && item instanceof BlockItem).toList();
		}
		return allItems;
	}

	@Override
	protected void init() {
		this.w = Math.min(300, super.width - 20);
		this.h = Math.min(230, super.height - 16);
		this.left = (super.width - this.w) / 2;
		this.top = (super.height - this.h) / 2;
		String oldName = this.name == null ? (this.editing == null ? "" : this.editing.name()) : this.name.getValue();
		String oldSearch = this.search == null ? "" : this.search.getValue();
		int labelW = 34;
		this.name = new EditBox(this.font, this.left + PAD + labelW, this.top + 18, this.w - 2 * PAD - labelW, 16, Component.translatable("kingdomquest.founding.name"));
		this.name.setMaxLength(CharterData.MAX_NAME);
		this.name.setValue(oldName);
		this.name.setResponder(value -> updateSave());
		this.addRenderableWidget(this.name);
		this.search = new EditBox(this.font, this.left + PAD + labelW, this.top + 62, this.w - 2 * PAD - labelW, 16, Component.translatable("kingdomquest.founding.search"));
		this.search.setHint(Component.translatable("kingdomquest.founding.search"));
		this.search.setValue(oldSearch);
		this.search.setResponder(value -> filter());
		this.addRenderableWidget(this.search);
		this.gridX = this.left + PAD + 1;
		this.gridY = this.top + 84;
		this.columns = (this.w - 2 * PAD - 2) / SLOT;
		this.rows = Math.max(1, (this.h - 84 - 30) / SLOT);
		int buttonsY = this.top + this.h - 25;
		this.save = Button.builder(Component.translatable("kingdomquest.founding.confirm"), b -> save())
				.bounds(this.left + this.w / 2 - 102, buttonsY, 100, 18).build();
		this.addRenderableWidget(this.save);
		this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> this.onClose())
				.bounds(this.left + this.w / 2 + 2, buttonsY, 100, 18).build());
		filter();
		updateSave();
		this.setInitialFocus(this.name);
	}

	private void filter() {
		String query = this.search.getValue().toLowerCase(Locale.ROOT).strip();
		this.shown = allItems().stream()
				.filter(item -> query.isEmpty() || new ItemStack(item).getHoverName().getString().toLowerCase(Locale.ROOT).contains(query)
						|| BuiltInRegistries.ITEM.getKey(item).getPath().contains(query))
				.toList();
		this.scroll = 0;
	}

	private void updateSave() {
		if (this.save != null) {
			this.save.active = this.icon != null && !this.name.getValue().isBlank();
		}
	}

	private void save() {
		var id = BuiltInRegistries.ITEM.getKey(this.icon);
		String text = this.name.getValue().strip();
		ClientPlayNetworking.send(new FoundingPayload(new CharterData(text, this.color, id), this.offhand));
		this.onClose();
	}

	private int swatchX(int i) {
		return this.left + PAD + 34 + i * 14;
	}

	private int swatchY() {
		return this.top + 42;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);
		// plain vanilla-like panel: light gray window with a dark border, sunken grid
		graphics.fill(this.left - 1, this.top - 1, this.left + this.w + 1, this.top + this.h + 1, 0xFF000000);
		graphics.fill(this.left, this.top, this.left + this.w, this.top + this.h, 0xFFC6C6C6);
		graphics.fill(this.gridX - 1, this.gridY - 1, this.gridX + this.columns * SLOT + 1, this.gridY + this.rows * SLOT + 1, 0xFF8B8B8B);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.text(this.font, this.title, this.left + PAD + 1, this.top + PAD - 1, LABEL, false);
		graphics.text(this.font, Component.translatable("kingdomquest.founding.name"), this.left + PAD + 1, this.top + 22, LABEL, false);
		graphics.text(this.font, Component.translatable("kingdomquest.founding.color"), this.left + PAD + 1, this.top + 44, LABEL, false);
		graphics.text(this.font, Component.translatable("kingdomquest.founding.logo"), this.left + PAD + 1, this.top + 66, LABEL, false);
		for (DyeColor dye : DyeColor.values()) {
			int x = swatchX(dye.getId());
			int y = swatchY();
			boolean picked = dye.getId() == this.color;
			graphics.fill(x - 1, y - 1, x + 12, y + 12, picked ? 0xFFFFFFFF : 0xFF000000);
			graphics.fill(x, y, x + 11, y + 11, 0xFF000000 | dye.getTextureDiffuseColor());
			if (mouseX >= x && mouseX < x + 11 && mouseY >= y && mouseY < y + 11) {
				graphics.setTooltipForNextFrame(this.font, Component.translatable("color.minecraft." + dye.getName()), mouseX, mouseY);
			}
		}
		int perPage = this.columns * this.rows;
		this.scroll = Math.clamp(this.scroll, 0, Math.max(0, (this.shown.size() - perPage + this.columns - 1) / this.columns * this.columns));
		for (int i = 0; i < perPage && this.scroll + i < this.shown.size(); i++) {
			Item item = this.shown.get(this.scroll + i);
			int x = this.gridX + (i % this.columns) * SLOT;
			int y = this.gridY + (i / this.columns) * SLOT;
			boolean hover = mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT;
			if (item == this.icon) {
				graphics.fill(x, y, x + SLOT, y + SLOT, 0xFF000000 | DyeColor.byId(this.color).getTextureDiffuseColor());
			} else if (hover) {
				graphics.fill(x, y, x + SLOT, y + SLOT, 0x60FFFFFF);
			}
			graphics.item(new ItemStack(item), x + 1, y + 1);
			if (hover) {
				graphics.setTooltipForNextFrame(this.font, new ItemStack(item).getHoverName(), mouseX, mouseY);
			}
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
			int mx = (int) event.x();
			int my = (int) event.y();
			for (DyeColor dye : DyeColor.values()) {
				int x = swatchX(dye.getId());
				if (mx >= x && mx < x + 11 && my >= swatchY() && my < swatchY() + 11) {
					this.color = dye.getId();
					return true;
				}
			}
			if (mx >= this.gridX && mx < this.gridX + this.columns * SLOT && my >= this.gridY && my < this.gridY + this.rows * SLOT) {
				int i = this.scroll + (my - this.gridY) / SLOT * this.columns + (mx - this.gridX) / SLOT;
				if (i < this.shown.size()) {
					this.icon = this.shown.get(i);
					updateSave();
				}
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		this.scroll = Math.max(0, this.scroll - (int) Math.signum(scrollY) * this.columns);
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
