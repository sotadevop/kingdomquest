package io.github.sotadevop.kingdomquest.client;

import io.github.sotadevop.kingdomquest.kingdom.Production;
import io.github.sotadevop.kingdomquest.kingdom.StoreActionPayload;
import io.github.sotadevop.kingdomquest.kingdom.StorePayload;
import io.github.sotadevop.kingdomquest.kingdom.Storehouse;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The storehouse ledger, opened at the lectern in the storehouse. Two pages:
 * <ul>
 * <li>Stock: everything in store as a grid with search, tabs (crops, mob drops, ores, blocks, misc) and sorting
 * (A-Z, amount, newest). Click selects (take 1 / a stack / all, stock limit), shift-click takes a stack,
 * right-click one. Below, the lord's inventory: click a slot to store it; "Deposit all" stores everything but the
 * hotbar and armor; "Unload" fills the hall's real chests.</li>
 * <li>Production: each producer with its level, what it makes a day (click an item to switch that drop off or on),
 * what the next levels unlock and the upgrade button.</li>
 * </ul>
 * The screen only asks; the server does every change and sends the ledger back, which refreshes this screen.
 */
public class StorehouseScreen extends Screen {
	private static final int W = 352;
	private static final int H = 290;
	private static final int CELL = 20;
	private static final int COLS = 17;
	private static final int ROWS = 5;
	private static final int SLOT = 18;
	private static final int PROD_H = 44;
	private static final int PROD_ROWS = 4;
	private static final String[] TABS = {"all", "crops", "mob_drops", "ores", "blocks", "misc"};
	private static final String[] SORTS = {"name", "amount", "newest"};

	private static StorehouseScreen shown;
	private static int lastPage;
	private static int lastTab;
	private static int lastSort;

	private StorePayload data;
	private int page = lastPage;
	private int tab = lastTab;
	private int sort = lastSort;
	private int scroll;
	private int selected = -1;
	private String search = "";
	private EditBox searchBox;
	private EditBox limitBox;
	private final List<Button> themed = new ArrayList<>();
	private int left;
	private int top;

	private StorehouseScreen(StorePayload data) {
		super(Component.translatable("kingdomquest.store.title", data.name()));
		this.data = data;
	}

	/** Opens the ledger, or refreshes it in place when it is already shown for this storehouse. */
	public static void open(StorePayload data) {
		if (shown != null && shown.data.heart().equals(data.heart())) {
			shown.data = data;
			shown.rebuildWidgets();
			return;
		}
		Minecraft.getInstance().setScreenAndShow(new StorehouseScreen(data));
	}

	private void send(String action, int id, int amount) {
		ClientPlayNetworking.send(new StoreActionPayload(this.data.heart(), action, id, amount));
	}

	@Override
	public void removed() {
		if (shown == this) {
			shown = null;
		}
		super.removed();
	}

	// ---- layout ----

	private int gridX() {
		return this.left + 6;
	}

	private int gridY() {
		return this.top + 74;
	}

	private int invX() {
		return this.left + 8;
	}

	private int invY() {
		return gridY() + ROWS * CELL + 8;
	}

	private int prodY() {
		return this.top + 52;
	}

	@Override
	protected void init() {
		shown = this;
		this.left = (this.width - W) / 2;
		this.top = (this.height - H) / 2;
		this.themed.clear();
		for (int i = 0; i < 2; i++) {
			int index = i;
			Button b = Button.builder(Component.translatable(i == 0 ? "kingdomquest.store.stock" : "kingdomquest.store.production"), btn -> {
				this.page = index;
				lastPage = index;
				this.scroll = 0;
				this.rebuildWidgets();
			}).bounds(this.left + 8 + i * 74, this.top + 30, 72, 18).build();
			b.active = i != this.page;
			this.themed.add(this.addRenderableWidget(b));
		}
		int fy = this.top + H - 24;
		if (this.page == 0) {
			this.searchBox = new EditBox(this.font, this.left + W - 8 - 130, this.top + 32, 130, 14, Component.translatable("kingdomquest.store.search"));
			this.searchBox.setHint(Component.translatable("kingdomquest.store.search"));
			this.searchBox.setMaxLength(40);
			this.searchBox.setValue(this.search);
			this.searchBox.setResponder(v -> {
				this.search = v;
				this.scroll = 0;
			});
			this.addRenderableWidget(this.searchBox);
			int tw = 46;
			for (int i = 0; i < TABS.length; i++) {
				int index = i;
				Button b = Button.builder(Component.translatable("kingdomquest.store.tab." + TABS[i]), btn -> {
					this.tab = index;
					lastTab = index;
					this.scroll = 0;
					this.rebuildWidgets();
				}).bounds(this.left + 8 + i * (tw + 2), this.top + 52, tw, 18).build();
				b.active = i != this.tab;
				this.themed.add(this.addRenderableWidget(b));
			}
			this.themed.add(this.addRenderableWidget(Button.builder(Component.translatable("kingdomquest.store.sort." + SORTS[this.sort]), b -> {
				this.sort = (this.sort + 1) % SORTS.length;
				lastSort = this.sort;
				this.rebuildWidgets();
			}).bounds(this.left + W - 8 - 70, this.top + 52, 70, 18).build()));
			// the selected line: take, stock limit
			StorePayload.Line sel = line(this.selected);
			int px = this.left + 180;
			int py = invY() + 22;
			if (sel != null) {
				int stack = sel.item().getMaxStackSize();
				this.themed.add(this.addRenderableWidget(Button.builder(Component.literal("1"), b -> send("take", sel.id(), 1))
						.bounds(px, py, 28, 16).tooltip(Tooltip.create(Component.translatable("kingdomquest.store.take_one"))).build()));
				this.themed.add(this.addRenderableWidget(Button.builder(Component.literal(String.valueOf(stack)), b -> send("take", sel.id(), stack))
						.bounds(px + 30, py, 28, 16).tooltip(Tooltip.create(Component.translatable("kingdomquest.store.take_stack"))).build()));
				this.themed.add(this.addRenderableWidget(Button.builder(Component.translatable("kingdomquest.store.all"),
						b -> send("take", sel.id(), Integer.MAX_VALUE)).bounds(px + 60, py, 40, 16)
						.tooltip(Tooltip.create(Component.translatable("kingdomquest.store.take_all"))).build()));
				this.limitBox = new EditBox(this.font, px + 30, py + 20, 50, 14, Component.translatable("kingdomquest.store.limit"));
				this.limitBox.setMaxLength(7);
				this.limitBox.setValue(sel.limit() > 0 ? String.valueOf(sel.limit()) : "");
				this.limitBox.setHint(Component.translatable("kingdomquest.store.no_limit"));
				this.addRenderableWidget(this.limitBox);
				this.themed.add(this.addRenderableWidget(Button.builder(Component.translatable("kingdomquest.store.set"), b -> {
					int v = 0;
					try {
						v = this.limitBox.getValue().isBlank() ? 0 : Integer.parseInt(this.limitBox.getValue().trim());
					} catch (NumberFormatException e) {
						return;
					}
					send("limit", sel.id(), Math.max(0, v));
				}).bounds(px + 84, py + 19, 36, 16).tooltip(Tooltip.create(Component.translatable("kingdomquest.store.limit_hint"))).build()));
			}
			this.themed.add(this.addRenderableWidget(Button.builder(Component.translatable("kingdomquest.store.deposit_all"), b -> send("deposit_all", 0, 0))
					.bounds(this.left + 8, fy, 84, 18).tooltip(Tooltip.create(Component.translatable("kingdomquest.store.deposit_hint"))).build()));
			this.themed.add(this.addRenderableWidget(Button.builder(Component.translatable("kingdomquest.store.unload"), b -> send("unload", 0, 0))
					.bounds(this.left + 96, fy, 96, 18).tooltip(Tooltip.create(Component.translatable("kingdomquest.store.unload_hint"))).build()));
		} else {
			List<StorePayload.Prod> prods = this.data.producers();
			this.scroll = Math.max(0, Math.min(this.scroll, prods.size() - PROD_ROWS));
			for (int r = 0; r < PROD_ROWS && this.scroll + r < prods.size(); r++) {
				StorePayload.Prod p = prods.get(this.scroll + r);
				if (p.maxed()) {
					continue;
				}
				List<Component> tip = new ArrayList<>();
				tip.add(Component.translatable("kingdomquest.store.next_level", p.level() + 1, Production.multiplier(p.level() + 1)));
				if (!p.requirement().isEmpty()) {
					tip.add(Component.translatable("kingdomquest.menu.requires", Component.translatable(p.requirement()))
							.withStyle(p.requirementMet() ? ChatFormatting.GREEN : ChatFormatting.RED));
				}
				tip.add(Component.translatable("kingdomquest.menu.cost", p.cost()).withStyle(p.canPay() ? ChatFormatting.GREEN : ChatFormatting.RED));
				Button up = Button.builder(Component.translatable("kingdomquest.store.upgrade"), b -> send("upgrade|" + p.id(), 0, 0))
						.bounds(this.left + W - 8 - 58, prodY() + r * PROD_H + 4, 58, 16).tooltip(Tooltip.create(join(tip))).build();
				up.active = p.requirementMet() && p.canPay() || creative();
				this.themed.add(this.addRenderableWidget(up));
			}
		}
		this.themed.add(this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
				.bounds(this.left + W - 8 - 60, fy, 60, 18).build()));
	}

	private static Component join(List<Component> lines) {
		var out = Component.empty();
		for (int i = 0; i < lines.size(); i++) {
			out.append(lines.get(i));
			if (i < lines.size() - 1) {
				out.append("\n");
			}
		}
		return out;
	}

	private boolean creative() {
		return this.minecraft != null && this.minecraft.player != null && this.minecraft.player.isCreative();
	}

	// ---- the stock ----

	private StorePayload.Line line(int id) {
		for (StorePayload.Line l : this.data.lines()) {
			if (l.id() == id) {
				return l;
			}
		}
		return null;
	}

	/** The lines shown: in the tab, matching the search, sorted. */
	private List<StorePayload.Line> lines() {
		String q = this.search.trim().toLowerCase(Locale.ROOT);
		List<StorePayload.Line> out = new ArrayList<>();
		for (StorePayload.Line l : this.data.lines()) {
			if (this.tab != 0 && !TABS[this.tab].equals(Storehouse.tab(l.item()))) {
				continue;
			}
			if (!q.isEmpty() && !l.item().getHoverName().getString().toLowerCase(Locale.ROOT).contains(q)) {
				continue;
			}
			out.add(l);
		}
		Comparator<StorePayload.Line> byName = Comparator.comparing(l -> l.item().getHoverName().getString());
		out.sort(switch (SORTS[this.sort]) {
			case "amount" -> Comparator.comparingLong(StorePayload.Line::count).reversed().thenComparing(byName);
			case "newest" -> Comparator.comparingLong(StorePayload.Line::since).reversed().thenComparing(byName);
			default -> byName;
		});
		return out;
	}

	static String compact(double n) {
		if (n < 1000) {
			return n == Math.floor(n) ? String.valueOf((long) n) : String.format(Locale.ROOT, "%.1f", n);
		}
		if (n < 10_000) {
			return String.format(Locale.ROOT, "%.1fk", n / 1000);
		}
		if (n < 1_000_000) {
			return (long) (n / 1000) + "k";
		}
		return String.format(Locale.ROOT, "%.1fM", n / 1_000_000);
	}

	// ---- drawing ----

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(g, mouseX, mouseY, partialTick);
		Theme t = ClientConfig.colors();
		Ui.panel(g, this.left, this.top, W, H, t);
		if (this.page == 0) {
			Ui.sunken(g, gridX() - 1, gridY() - 1, COLS * CELL + 2, ROWS * CELL + 2, t);
		} else {
			List<StorePayload.Prod> prods = this.data.producers();
			for (int r = 0; r < PROD_ROWS && this.scroll + r < prods.size(); r++) {
				Ui.raised(g, this.left + 8, prodY() + r * PROD_H, W - 16, PROD_H - 3, t.selected(), t.panelLight(), t.panelDark());
			}
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		Theme t = ClientConfig.colors();
		for (Button b : this.themed) {
			Ui.modernButton(g, this.font, b, t);
		}
		if (this.searchBox != null && this.page == 0) {
			Ui.searchBox(g, this.searchBox, t, this.left + W - 8 - 130, this.top + 30, 130, 18);
		}
		// header: name, level, how full
		g.text(this.font, this.title, this.left + 8, this.top + 7, t.heading(), false);
		g.text(this.font, Component.translatable("kingdomquest.storehouse.storehouse_" + Math.max(1, this.data.level())), this.left + 8, this.top + 18,
				t.secondary(), false);
		int bx = this.left + W - 8 - 120;
		g.text(this.font, Component.translatable("kingdomquest.store.room", this.data.used(), this.data.capacity()), bx, this.top + 7, t.text(), false);
		Ui.roundedFill(g, bx, this.top + 19, 120, 6, 3, t.panelDark());
		float full = this.data.capacity() <= 0 ? 1 : Math.min(1f, (float) this.data.used() / this.data.capacity());
		Ui.roundedFill(g, bx, this.top + 19, Math.max(6, Math.round(120 * full)), 6, 3, full >= 1 ? t.warning() : t.done());
		if (this.page == 0) {
			drawStock(g, mouseX, mouseY, t);
		} else {
			drawProduction(g, mouseX, mouseY, t);
		}
	}

	private void drawStock(GuiGraphicsExtractor g, int mouseX, int mouseY, Theme t) {
		List<StorePayload.Line> lines = lines();
		int rows = (lines.size() + COLS - 1) / COLS;
		this.scroll = Math.max(0, Math.min(this.scroll, rows - ROWS));
		StorePayload.Line hover = null;
		for (int r = 0; r < ROWS; r++) {
			for (int c = 0; c < COLS; c++) {
				int i = (this.scroll + r) * COLS + c;
				if (i >= lines.size()) {
					break;
				}
				StorePayload.Line l = lines.get(i);
				int x = gridX() + c * CELL;
				int y = gridY() + r * CELL;
				if (l.id() == this.selected) {
					Ui.selectedRow(g, t, x, y, CELL, CELL);
				}
				g.item(l.item(), x + 2, y + 2);
				g.itemDecorations(this.font, l.item(), x + 2, y + 2, compact(l.count()));
				if (mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL) {
					hover = l;
				}
			}
		}
		if (lines.isEmpty()) {
			g.centeredText(this.font, Component.translatable(this.data.lines().isEmpty() ? "kingdomquest.store.empty" : "kingdomquest.store.none_found"),
					gridX() + COLS * CELL / 2, gridY() + ROWS * CELL / 2 - 4, t.secondary());
		}
		if (rows > ROWS) {
			int track = ROWS * CELL;
			int h = Math.max(8, track * ROWS / rows);
			int y = gridY() + (track - h) * this.scroll / Math.max(1, rows - ROWS);
			g.fill(gridX() + COLS * CELL + 1, y, gridX() + COLS * CELL + 3, y + h, t.panelDark());
		}
		// the lord's inventory: main rows, then the hotbar
		Inventory inv = this.minecraft.player.getInventory();
		int hoverSlot = -1;
		for (int k = 0; k < 36; k++) {
			int[] xy = slotXY(k);
			Ui.sunken(g, xy[0], xy[1], SLOT, SLOT, t);
			ItemStack st = inv.getItem(k);
			if (!st.isEmpty()) {
				g.item(st, xy[0] + 1, xy[1] + 1);
				g.itemDecorations(this.font, st, xy[0] + 1, xy[1] + 1);
			}
			if (mouseX >= xy[0] && mouseX < xy[0] + SLOT && mouseY >= xy[1] && mouseY < xy[1] + SLOT) {
				hoverSlot = k;
			}
		}
		// the selected line
		StorePayload.Line sel = line(this.selected);
		int px = this.left + 180;
		int py = invY();
		if (sel != null) {
			g.item(sel.item(), px, py);
			g.text(this.font, Text.fit(this.font, sel.item().getHoverName().getString(), W - 8 - px - 20 - 8), px + 20, py, t.heading(), false);
			g.text(this.font, Component.translatable("kingdomquest.store.in_store", sel.count()), px + 20, py + 10, t.secondary(), false);
			g.text(this.font, Component.translatable("kingdomquest.store.limit"), px, py + 45, t.text(), false);
		} else {
			for (int i = 0; i < 3; i++) {
				g.text(this.font, Component.translatable("kingdomquest.store.help_" + i), px, py + i * 11, t.secondary(), false);
			}
		}
		if (hover != null) {
			List<Component> tip = new ArrayList<>(Screen.getTooltipFromItem(this.minecraft, hover.item()));
			tip.add(Component.translatable("kingdomquest.store.in_store", hover.count()).withStyle(ChatFormatting.GOLD));
			if (hover.limit() > 0) {
				tip.add(Component.translatable("kingdomquest.store.limit_is", hover.limit()).withStyle(ChatFormatting.GRAY));
			}
			g.setComponentTooltipForNextFrame(this.font, tip, mouseX, mouseY);
		} else if (hoverSlot >= 0 && !inv.getItem(hoverSlot).isEmpty()) {
			List<Component> tip = new ArrayList<>(Screen.getTooltipFromItem(this.minecraft, inv.getItem(hoverSlot)));
			tip.add(Component.translatable("kingdomquest.store.click_store").withStyle(ChatFormatting.GRAY));
			g.setComponentTooltipForNextFrame(this.font, tip, mouseX, mouseY);
		}
	}

	private int[] slotXY(int k) {
		int row = k < 9 ? 3 : (k - 9) / 9;
		int col = k % 9;
		return new int[] {invX() + col * SLOT, invY() + row * SLOT + (k < 9 ? 4 : 0)};
	}

	private void drawProduction(GuiGraphicsExtractor g, int mouseX, int mouseY, Theme t) {
		List<StorePayload.Prod> prods = this.data.producers();
		if (prods.isEmpty()) {
			g.centeredText(this.font, Component.translatable("kingdomquest.store.no_producers"), this.left + W / 2, prodY() + 40, t.secondary());
			return;
		}
		List<Component> tip = null;
		for (int r = 0; r < PROD_ROWS && this.scroll + r < prods.size(); r++) {
			StorePayload.Prod p = prods.get(this.scroll + r);
			int y = prodY() + r * PROD_H;
			int x = this.left + 14;
			g.text(this.font, Component.translatable("kingdomquest.producer." + p.id()), x, y + 5, t.heading(), false);
			if (p.level() > 0) {
				String lv = Component.translatable("kingdomquest.store.level", p.level(), Production.MAX_LEVEL, Production.multiplier(p.level())).getString();
				int lx = this.left + W - 8 - 64 - this.font.width(lv);
				g.text(this.font, lv, lx, y + 5, t.secondary(), false);
				for (int k = 0; k < Production.MAX_LEVEL; k++) {
					Ui.roundedFill(g, lx - 6 - (Production.MAX_LEVEL - k) * 9, y + 5, 7, 7, Ui.modern() ? 2 : 0, k < p.level() ? t.done() : t.panelDark());
				}
			}
			for (int k = 0; k < p.outs().size(); k++) {
				StorePayload.Out o = p.outs().get(k);
				int ix = x + k * 18;
				int iy = y + 20;
				if (ix + 16 > this.left + W - 10) {
					break;
				}
				g.item(o.item(), ix, iy);
				boolean open = o.perDay() > 0 || !o.on() && p.level() >= o.level() && o.reached();
				g.itemDecorations(this.font, o.item(), ix, iy, open && o.on() ? compact(o.perDay()) : "");
				if (!open || !o.on()) {
					g.nextStratum();
					g.fill(ix, iy, ix + 16, iy + 16, !o.on() ? 0x90801010 : 0xB0202020);
					if (!o.on()) {
						g.text(this.font, "x", ix + 5, iy + 4, 0xFFFF5555, true);
					} else {
						g.text(this.font, String.valueOf(o.level()), ix + 5, iy + 4, 0xFFFFFFFF, true);
					}
				}
				if (mouseX >= ix && mouseX < ix + 16 && mouseY >= iy && mouseY < iy + 16) {
					tip = new ArrayList<>();
					tip.add(o.item().getHoverName());
					if (open && o.on()) {
						tip.add(Component.translatable("kingdomquest.store.per_day", compact(o.perDay())).withStyle(ChatFormatting.GOLD));
					}
					if (p.level() > 0 && p.level() < o.level()) {
						tip.add(Component.translatable("kingdomquest.store.unlocks_at", o.level()).withStyle(ChatFormatting.YELLOW));
					}
					if (!o.milestone().isEmpty()) {
						tip.add(Component.translatable("kingdomquest.store.needs", Component.translatable(o.milestone()))
								.withStyle(o.reached() ? ChatFormatting.GREEN : ChatFormatting.RED));
					}
					if (open) {
						tip.add(Component.translatable(o.on() ? "kingdomquest.store.click_off" : "kingdomquest.store.click_on").withStyle(ChatFormatting.GRAY));
					}
				}
			}
		}
		if (prods.size() > PROD_ROWS) {
			g.text(this.font, Component.translatable("kingdomquest.store.scroll", this.scroll + 1, Math.min(prods.size(), this.scroll + PROD_ROWS), prods.size()),
					this.left + 8, this.top + H - 19, t.secondary(), false);
		}
		if (tip != null) {
			g.setComponentTooltipForNextFrame(this.font, tip, mouseX, mouseY);
		}
	}

	// ---- input ----

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		if (this.page == 0) {
			if (mx >= gridX() && mx < gridX() + COLS * CELL && my >= gridY() && my < gridY() + ROWS * CELL) {
				int i = (this.scroll + (int) ((my - gridY()) / CELL)) * COLS + (int) ((mx - gridX()) / CELL);
				List<StorePayload.Line> lines = lines();
				if (i < lines.size()) {
					StorePayload.Line l = lines.get(i);
					if (event.button() == 1) {
						send("take", l.id(), 1);
					} else if (event.hasShiftDown()) {
						send("take", l.id(), l.item().getMaxStackSize());
					} else {
						this.selected = l.id() == this.selected ? -1 : l.id();
						this.rebuildWidgets();
					}
					return true;
				}
			}
			for (int k = 0; k < 36; k++) {
				int[] xy = slotXY(k);
				if (mx >= xy[0] && mx < xy[0] + SLOT && my >= xy[1] && my < xy[1] + SLOT) {
					if (!this.minecraft.player.getInventory().getItem(k).isEmpty()) {
						send("deposit", k, 0);
					}
					return true;
				}
			}
		} else {
			List<StorePayload.Prod> prods = this.data.producers();
			for (int r = 0; r < PROD_ROWS && this.scroll + r < prods.size(); r++) {
				StorePayload.Prod p = prods.get(this.scroll + r);
				int iy = prodY() + r * PROD_H + 20;
				for (int k = 0; k < p.outs().size(); k++) {
					int ix = this.left + 14 + k * 18;
					StorePayload.Out o = p.outs().get(k);
					if (mx >= ix && mx < ix + 16 && my >= iy && my < iy + 16 && p.level() >= o.level() && o.reached()) {
						send("toggle|" + p.id() + "|" + o.spec(), 0, 0);
						return true;
					}
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY != 0) {
			this.scroll = Math.max(0, this.scroll + (scrollY > 0 ? -1 : 1));
			if (this.page == 1) {
				this.rebuildWidgets();
			}
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
