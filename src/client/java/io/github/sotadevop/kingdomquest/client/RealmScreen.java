package io.github.sotadevop.kingdomquest.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.sotadevop.kingdomquest.kingdom.ActionPayload;
import io.github.sotadevop.kingdomquest.kingdom.RealmPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;

import java.util.ArrayList;
import java.util.List;

/**
 * The realm: a map of the land as the lord has seen it, with his settlements, the known villages and the roads
 * between them (drag to move, scroll to zoom, select two places and connect them, rename them, name the empire),
 * and a register of the current settlement: its plan from above, block by block, with every building marked
 * (the newest one highlighted) and its people and animals where they stand; then the lists of people (with a
 * button to change how they look) and animals.
 */
public class RealmScreen extends Screen {
	private static final int W = 440;
	private static final int H = 280;
	private static final int ROWS = 11;
	private static final String[] TABS = {"kingdomquest.realm.map", "kingdomquest.realm.buildings", "kingdomquest.realm.people",
			"kingdomquest.realm.animals"};
	private static int lastTab;

	private record Node(String key, String name, int x, int z, boolean settlement, int color) {
	}

	/** A picture of the land from above: w x h cells of {@code step} blocks from (x0, z0), 16-bit colours, 0 = unknown. */
	private record Layer(int x0, int z0, int step, int w, int h, short[] px) {
	}

	private static Layer layer(JsonObject data, String key) {
		if (!data.has(key)) {
			return null;
		}
		JsonObject o = data.getAsJsonObject(key);
		byte[] bytes = java.util.Base64.getDecoder().decode(o.get("data").getAsString());
		short[] px = new short[bytes.length / 2];
		for (int i = 0; i < px.length; i++) {
			px[i] = (short) ((bytes[i * 2] & 255) << 8 | bytes[i * 2 + 1] & 255);
		}
		return new Layer(o.get("x0").getAsInt(), o.get("z0").getAsInt(), o.get("step").getAsInt(), o.get("w").getAsInt(), o.get("h").getAsInt(), px);
	}

	private final JsonObject data;
	private final BlockPos heart;
	private final List<Node> nodes = new ArrayList<>();
	private final List<Button> themed = new ArrayList<>();
	private final Layer land;
	private final Layer plan;
	/** What the two maps show: {pixels per block, centre x, centre z} for the realm map and the settlement plan. */
	private final double[][] view = new double[2][];
	private int tab = lastTab;
	private int page;
	private String selA;
	private String selB;
	private EditBox empire;
	private EditBox rename;
	private int left;
	private int top;

	public RealmScreen(RealmPayload payload, BlockPos heart) {
		super(Component.translatable("kingdomquest.realm.title"));
		this.data = JsonParser.parseString(payload.json()).getAsJsonObject();
		this.heart = heart;
		this.land = layer(this.data, "map");
		this.plan = layer(this.data, "plan");
		for (JsonElement e : arr("settlements")) {
			JsonObject o = e.getAsJsonObject();
			this.nodes.add(new Node(o.get("id").getAsString(), o.get("name").getAsString(), o.get("x").getAsInt(), o.get("z").getAsInt(), true,
					o.get("color").getAsInt()));
		}
		for (JsonElement e : arr("villages")) {
			JsonObject o = e.getAsJsonObject();
			this.nodes.add(new Node(o.get("key").getAsString(), o.get("name").getAsString(), o.get("x").getAsInt(), o.get("z").getAsInt(), false, -1));
		}
	}

	/** Opens (or refreshes) the screen; the heart is the settlement whose menu it came from. */
	public static void open(RealmPayload payload) {
		Minecraft mc = Minecraft.getInstance();
		RealmScreen r = shown;
		BlockPos heart = r != null ? r.heart : KingdomScreen.lastHeart();
		RealmScreen next = new RealmScreen(payload, heart);
		if (r != null) {
			next.tab = r.tab;
			next.page = r.page;
			next.selA = r.selA;
			next.selB = r.selB;
			next.view[0] = r.view[0];
			next.view[1] = r.view[1];
		}
		mc.setScreenAndShow(next);
	}

	private JsonArray arr(String key) {
		return this.data.has(key) ? this.data.getAsJsonArray(key) : new JsonArray();
	}

	private void send(String action) {
		ClientPlayNetworking.send(new ActionPayload(this.heart, action));
	}

	private Node node(String key) {
		return this.nodes.stream().filter(n -> n.key().equals(key)).findFirst().orElse(null);
	}

	/** The realm screen on display (refreshed in place when the server answers an action). */
	private static RealmScreen shown;

	static void forget() {
		shown = null;
	}

	@Override
	public void onClose() {
		shown = null;
		super.onClose();
	}

	@Override
	protected void init() {
		shown = this;
		this.left = (this.width - W) / 2;
		this.top = (this.height - H) / 2;
		this.themed.clear();
		int tabW = (W - 16 - 3 * 2) / 4;
		for (int i = 0; i < TABS.length; i++) {
			int index = i;
			Button b = Button.builder(Component.translatable(TABS[i]), btn -> {
				this.tab = index;
				lastTab = index;
				this.page = 0;
				this.rebuildWidgets();
			}).bounds(this.left + 8 + i * (tabW + 2), this.top + 22, tabW, 18).build();
			b.active = i != this.tab;
			this.themed.add(this.addRenderableWidget(b));
		}
		int fy = this.top + H - 24;
		if (this.tab <= 1) {
			// zoom and "back to me" in the map's corner
			int bx = mapX0() + mapW() - 16;
			this.themed.add(this.addRenderableWidget(Button.builder(Component.literal("+"), b -> zoom(1.5, mapX0() + mapW() / 2.0, mapY0() + mapH() / 2.0))
					.bounds(bx, mapY0(), 16, 16).build()));
			this.themed.add(this.addRenderableWidget(Button.builder(Component.literal("-"), b -> zoom(1 / 1.5, mapX0() + mapW() / 2.0, mapY0() + mapH() / 2.0))
					.bounds(bx, mapY0() + 18, 16, 16).build()));
			this.themed.add(this.addRenderableWidget(Button.builder(Component.literal("o"), b -> this.view[this.tab] = null)
					.bounds(bx, mapY0() + 36, 16, 16).build()));
		}
		if (this.tab == 0) {
			this.empire = new EditBox(this.font, this.left + W - 8 - 150, this.top + 4, 150, 14, Component.translatable("kingdomquest.realm.empire"));
			this.empire.setMaxLength(32);
			this.empire.setValue(this.data.get("empire").getAsString());
			this.addRenderableWidget(this.empire);
			this.themed.add(this.addRenderableWidget(Button.builder(Component.translatable("kingdomquest.realm.save_empire"),
					b -> send("realm_empire|" + this.empire.getValue())).bounds(this.left + W - 8 - 150 - 44, this.top + 3, 40, 16).build()));
			Node a = this.selA == null ? null : node(this.selA);
			Node b2 = this.selB == null ? null : node(this.selB);
			Button connect = Button.builder(Component.translatable("kingdomquest.realm.connect"), b -> {
				Node from = node(this.selA);
				Node to = node(this.selB);
				if (from != null && to != null && !from.settlement() && to.settlement()) {
					Node t = from;
					from = to;
					to = t;
				}
				if (from != null && to != null) {
					send("realm_link|" + from.key() + "|" + to.key());
				}
			}).bounds(this.left + 8, fy, 90, 18).build();
			connect.active = a != null && b2 != null && (a.settlement() || b2.settlement());
			this.themed.add(this.addRenderableWidget(connect));
			this.themed.add(this.addRenderableWidget(Button.builder(Component.translatable("kingdomquest.realm.find"), b -> send("realm_find"))
					.bounds(this.left + 102, fy, 90, 18).build()));
			this.rename = new EditBox(this.font, this.left + 198, fy + 2, 110, 14, Component.translatable("kingdomquest.realm.name"));
			this.rename.setMaxLength(32);
			Node sel = this.selB != null ? b2 : a;
			this.rename.setValue(sel == null ? "" : sel.name());
			this.rename.active = sel != null;
			this.addRenderableWidget(this.rename);
			Button save = Button.builder(Component.translatable("kingdomquest.realm.rename"), b -> {
				Node n = this.selB != null ? node(this.selB) : node(this.selA);
				if (n != null) {
					send(n.settlement() ? "realm_rename|" + n.key() + "|" + this.rename.getValue()
							: "realm_vname|" + n.key().substring(2) + "|" + this.rename.getValue());
				}
			}).bounds(this.left + 312, fy, 50, 18).build();
			save.active = sel != null;
			this.themed.add(this.addRenderableWidget(save));
		} else if (this.tab == 2 || this.tab == 3) {
			JsonArray list = arr(this.tab == 2 ? "people" : "animals");
			int pages = Math.max(1, (list.size() + ROWS - 1) / ROWS);
			this.page = Math.min(this.page, pages - 1);
			Button prev = Button.builder(Component.literal("<"), b -> {
				this.page--;
				this.rebuildWidgets();
			}).bounds(this.left + 8, fy, 20, 18).build();
			prev.active = this.page > 0;
			Button next = Button.builder(Component.literal(">"), b -> {
				this.page++;
				this.rebuildWidgets();
			}).bounds(this.left + 32, fy, 20, 18).build();
			next.active = this.page < pages - 1;
			this.themed.add(this.addRenderableWidget(prev));
			this.themed.add(this.addRenderableWidget(next));
		}
		this.themed.add(this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
				.bounds(this.left + W - 8 - 60, fy, 60, 18).build()));
	}

	private int rowY(int i) {
		return this.top + 50 + i * 18;
	}

	private static final String[] LOOKS = {"Steve", "Alex", "Ari", "Efe", "Kai", "Makena", "Noor", "Sunny", "Zuri"};
	private static final int LOOK_W = 84;

	/** Left edge of the "look" field in a people row; a click on it gives that person the next look. */
	private int lookX() {
		return this.left + W - 8 - LOOK_W;
	}

	/** Guards all wear the same face and armor: their look is not changed one by one. */
	private static boolean fixedLook(JsonObject person) {
		return "Guard".equals(person.get("role").getAsString());
	}

	// ---- map geometry ----

	private int mapX0() {
		return this.left + 12;
	}

	private int mapY0() {
		return this.top + 46;
	}

	private int mapW() {
		return W - 24;
	}

	private int mapH() {
		return H - 46 - 32;
	}

	/** Scale and origin so every point of the list fits the map area, with a margin. */
	private double[] fit(List<int[]> pts) {
		int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
		for (int[] p : pts) {
			x0 = Math.min(x0, p[0]);
			z0 = Math.min(z0, p[1]);
			x1 = Math.max(x1, p[0]);
			z1 = Math.max(z1, p[1]);
		}
		double span = Math.max(Math.max(x1 - x0, z1 - z0), 64);
		double scale = Math.min((mapW() - 40) / span, (mapH() - 30) / span);
		double cx = (x0 + x1) / 2.0;
		double cz = (z0 + z1) / 2.0;
		return new double[] {scale, cx, cz};
	}

	private int sx(double[] f, double x) {
		return (int) Math.round(mapX0() + mapW() / 2.0 + (x - f[1]) * f[0]);
	}

	private int sy(double[] f, double z) {
		return (int) Math.round(mapY0() + mapH() / 2.0 + (z - f[2]) * f[0]);
	}

	private double[] realmFit() {
		List<int[]> pts = new ArrayList<>();
		this.nodes.forEach(n -> pts.add(new int[] {n.x(), n.z()}));
		int px = this.data.get("px").getAsInt();
		int pz = this.data.get("pz").getAsInt();
		pts.add(new int[] {px - 96, pz - 96});
		pts.add(new int[] {px + 96, pz + 96});
		return fit(pts);
	}

	private double[] plotFit() {
		List<int[]> pts = new ArrayList<>();
		for (JsonElement e : arr("buildings")) {
			JsonObject b = e.getAsJsonObject();
			pts.add(new int[] {b.get("x0").getAsInt(), b.get("z0").getAsInt()});
			pts.add(new int[] {b.get("x1").getAsInt(), b.get("z1").getAsInt()});
		}
		int hx = this.data.get("hx").getAsInt();
		int hz = this.data.get("hz").getAsInt();
		pts.add(new int[] {hx - 24, hz - 24});
		pts.add(new int[] {hx + 24, hz + 24});
		return fit(pts);
	}

	/** The view of a map tab; it starts fitted to what there is to see and then follows the mouse. */
	private double[] view(int which) {
		if (this.view[which] == null) {
			this.view[which] = which == 0 ? realmFit() : plotFit();
		}
		return this.view[which];
	}

	private boolean inMap(double x, double y) {
		return x >= mapX0() && x < mapX0() + mapW() && y >= mapY0() && y < mapY0() + mapH();
	}

	/** Zooms the shown map, keeping the block under (px, py) where it is. */
	private void zoom(double factor, double px, double py) {
		double[] f = view(this.tab);
		double wx = f[1] + (px - (mapX0() + mapW() / 2.0)) / f[0];
		double wz = f[2] + (py - (mapY0() + mapH() / 2.0)) / f[0];
		f[0] = Math.max(0.04, Math.min(8, f[0] * factor));
		f[1] = wx - (px - (mapX0() + mapW() / 2.0)) / f[0];
		f[2] = wz - (py - (mapY0() + mapH() / 2.0)) / f[0];
	}

	private static int argb(int c) {
		int r = (c >> 11 & 31) * 255 / 31;
		int gr = (c >> 5 & 63) * 255 / 63;
		int b = (c & 31) * 255 / 31;
		return 0xFF000000 | r << 16 | gr << 8 | b;
	}

	/** Paints a layer into the map area: rows of cells, runs of one colour as one rectangle, never finer than 2 pixels. */
	private void drawLayer(GuiGraphicsExtractor g, Layer l, double[] f) {
		if (l == null) {
			return;
		}
		int ax = mapX0();
		int ay = mapY0();
		int bx = ax + mapW();
		int by = ay + mapH();
		double cell = l.step() * f[0];
		int stride = cell >= 2 ? 1 : (int) Math.ceil(2 / cell);
		for (int j = 0; j < l.h(); j += stride) {
			int y0 = Math.max(ay, sy(f, l.z0() + (double) j * l.step()));
			int y1 = Math.min(by, sy(f, l.z0() + (double) (j + stride) * l.step()));
			if (y1 <= y0) {
				continue;
			}
			int last = 0;
			int from = 0;
			for (int i = 0; i <= l.w(); i += stride) {
				int c = i < l.w() ? l.px()[j * l.w() + i] & 0xFFFF : -1;
				if (c == last) {
					continue;
				}
				int x = Math.max(ax, Math.min(bx, sx(f, l.x0() + (double) i * l.step())));
				if (last > 0 && x > from) {
					g.fill(from, y0, x, y1, argb(last));
				}
				last = c;
				from = x;
			}
			if (last > 0) {
				int x = Math.max(ax, Math.min(bx, sx(f, l.x0() + (double) l.w() * l.step())));
				if (x > from) {
					g.fill(from, y0, x, y1, argb(last));
				}
			}
		}
	}

	/** Text on the map with a dark plate behind it, only if it lies inside the map area. */
	private void label(GuiGraphicsExtractor g, String text, int x, int y, int color) {
		int w = this.font.width(text);
		if (x < mapX0() || y < mapY0() || x + w + 2 > mapX0() + mapW() || y + 10 > mapY0() + mapH()) {
			return;
		}
		g.fill(x - 1, y - 1, x + w + 1, y + 9, 0xB0101010);
		g.text(this.font, text, x, y, color, false);
	}

	/** A bar in the corner that says how far it is. */
	private void scaleBar(GuiGraphicsExtractor g, double[] f) {
		int[] lengths = {8, 16, 32, 64, 128, 256, 512, 1024, 2048};
		int blocks = lengths[0];
		for (int len : lengths) {
			if (len * f[0] <= 90) {
				blocks = len;
			}
		}
		int w = (int) Math.round(blocks * f[0]);
		int x = mapX0() + 4;
		int y = mapY0() + 4;
		g.fill(x - 2, y - 2, x + w + 2 + this.font.width(blocks + " blocks") + 6, y + 10, 0xB0101010);
		g.fill(x, y + 6, x + w, y + 8, 0xFFFFFFFF);
		g.fill(x, y + 2, x + 1, y + 8, 0xFFFFFFFF);
		g.fill(x + w - 1, y + 2, x + w, y + 8, 0xFFFFFFFF);
		g.text(this.font, blocks + " blocks", x + w + 4, y, 0xFFFFFFFF, false);
		g.text(this.font, "N", mapX0() + mapW() - 30, mapY0() + 4, 0xFFFFFFFF, false);
		g.fill(mapX0() + mapW() - 28, mapY0() + 13, mapX0() + mapW() - 26, mapY0() + 22, 0xFFFFFFFF);
	}

	private void line(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
		int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
		if (steps > 4000) {
			return;
		}
		for (int i = 0; i <= steps; i += 2) {
			int x = x0 + (x1 - x0) * i / Math.max(1, steps);
			int y = y0 + (y1 - y0) * i / Math.max(1, steps);
			if (inMap(x, y) && inMap(x + 2, y + 2)) {
				g.fill(x, y, x + 2, y + 2, color);
			}
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(g, mouseX, mouseY, partialTick);
		Theme t = ClientConfig.colors();
		Ui.panel(g, this.left, this.top, W, H, t);
		Ui.sunken(g, mapX0() - 4, mapY0() - 4, mapW() + 8, mapH() + 8, t);
		// the land itself lies under the buttons and markers
		if (this.tab <= 1) {
			g.fill(mapX0(), mapY0(), mapX0() + mapW(), mapY0() + mapH(), 0xFF14181C);
			double[] f = view(this.tab);
			drawLayer(g, this.land, f);
			if (this.tab == 1) {
				drawLayer(g, this.plan, f);
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
		g.text(this.font, this.title, this.left + 8, this.top + 8, t.heading(), false);
		switch (this.tab) {
			case 0 -> drawMap(g, mouseX, mouseY, t);
			case 1 -> drawPlot(g, mouseX, mouseY, t);
			case 2 -> drawList(g, arr("people"), true, t);
			default -> drawList(g, arr("animals"), false, t);
		}
	}

	private void drawMap(GuiGraphicsExtractor g, int mouseX, int mouseY, Theme t) {
		double[] f = view(0);
		for (JsonElement e : arr("links")) {
			JsonObject l = e.getAsJsonObject();
			Node a = node(l.get("from").getAsString());
			if (a == null) {
				continue;
			}
			line(g, sx(f, a.x()), sy(f, a.z()), sx(f, l.get("x").getAsInt()), sy(f, l.get("z").getAsInt()), 0xFFF0C070);
		}
		Node hover = null;
		for (Node n : this.nodes) {
			int x = sx(f, n.x());
			int y = sy(f, n.z());
			if (!inMap(x - 7, y - 7) || !inMap(x + 7, y + 7)) {
				continue;
			}
			int c = n.settlement() ? 0xFF000000 | DyeColor.byId(n.color()).getTextureDiffuseColor() : 0xFF4CAF50;
			boolean sel = n.key().equals(this.selA) || n.key().equals(this.selB);
			Ui.roundedFill(g, x - 6, y - 6, 12, 12, 3, sel ? 0xFFFFFFFF : 0xFF101010);
			Ui.roundedFill(g, x - 4, y - 4, 8, 8, n.settlement() ? 1 : 4, c);
			label(g, n.name(), x + 9, y - 4, n.settlement() ? 0xFFFFFFFF : 0xFFC8E6C9);
			if (Math.abs(mouseX - x) <= 6 && Math.abs(mouseY - y) <= 6) {
				hover = n;
			}
		}
		int px = sx(f, this.data.get("px").getAsInt());
		int py = sy(f, this.data.get("pz").getAsInt());
		if (inMap(px - 3, py - 3) && inMap(px + 3, py + 3)) {
			g.fill(px - 3, py - 3, px + 3, py + 3, 0xFF101010);
			g.fill(px - 2, py - 2, px + 2, py + 2, 0xFFFFFFFF);
			label(g, Component.translatable("kingdomquest.realm.you").getString(), px + 6, py - 4, 0xFFFFFFFF);
		}
		scaleBar(g, f);
		label(g, Component.translatable("kingdomquest.realm.map_hint").getString(), mapX0() + 3, mapY0() + mapH() - 11, 0xFFE0E0E0);
		if (hover != null) {
			g.setTooltipForNextFrame(this.font, Component.literal(hover.name() + "  (" + hover.x() + ", " + hover.z() + ")"), mouseX, mouseY);
		} else if (inMap(mouseX, mouseY) && mouseX < mapX0() + mapW() - 18) {
			int wx = (int) Math.floor(f[1] + (mouseX - (mapX0() + mapW() / 2.0)) / f[0]);
			int wz = (int) Math.floor(f[2] + (mouseY - (mapY0() + mapH() / 2.0)) / f[0]);
			g.setTooltipForNextFrame(this.font, Component.literal(wx + ", " + wz + "  (" + direction(wx, wz) + ")"), mouseX, mouseY);
		}
	}

	private void drawPlot(GuiGraphicsExtractor g, int mouseX, int mouseY, Theme t) {
		double[] f = view(1);
		JsonObject hover = null;
		JsonObject latest = null;
		for (JsonElement e : arr("buildings")) {
			JsonObject b = e.getAsJsonObject();
			String track = b.get("track").getAsString();
			if (track.equals("roads") || track.equals("lamps") || track.equals("walls") || track.equals("cellar") || track.equals("gate")) {
				continue; // they show on the plan itself
			}
			int x0 = Math.max(mapX0(), sx(f, b.get("x0").getAsInt()));
			int y0 = Math.max(mapY0(), sy(f, b.get("z0").getAsInt()));
			int x1 = Math.min(mapX0() + mapW(), sx(f, b.get("x1").getAsInt() + 1));
			int y1 = Math.min(mapY0() + mapH(), sy(f, b.get("z1").getAsInt() + 1));
			if (x1 - x0 < 3 || y1 - y0 < 3) {
				continue;
			}
			boolean last = b.get("last").getAsBoolean();
			boolean over = mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1;
			if (last) {
				latest = b;
			}
			if (over) {
				g.fill(x0, y0, x1, y1, 0x50FFFFFF);
				if (hover == null || (x1 - x0) * (y1 - y0) < area(f, hover)) {
					hover = b; // the smallest building under the mouse
				}
			}
			Ui.boxOutline(g, x0, y0, x1 - x0, y1 - y0, last ? 0xFFFFD040 : over ? 0xFFFFFFFF : 0xC0101010);
			String name = Component.translatable("kingdomquest.track." + track).getString();
			if (this.font.width(name) + 4 <= x1 - x0 && y1 - y0 >= 12) {
				label(g, name, x0 + (x1 - x0 - this.font.width(name)) / 2, y0 + (y1 - y0 - 8) / 2, last ? 0xFFFFD040 : 0xFFFFFFFF);
			}
		}
		for (JsonElement e : arr("animals")) {
			dot(g, f, e.getAsJsonObject(), 0xFFFF80C0);
		}
		for (JsonElement e : arr("people")) {
			dot(g, f, e.getAsJsonObject(), 0xFFFFE040);
		}
		int hx = sx(f, this.data.get("hx").getAsInt());
		int hy = sy(f, this.data.get("hz").getAsInt());
		if (inMap(hx - 4, hy - 4) && inMap(hx + 4, hy + 4)) {
			Ui.roundedFill(g, hx - 4, hy - 4, 8, 8, 4, 0xFF101010);
			Ui.roundedFill(g, hx - 3, hy - 3, 6, 6, 3, 0xFFFF7020);
		}
		int px = sx(f, this.data.get("px").getAsInt());
		int py = sy(f, this.data.get("pz").getAsInt());
		if (inMap(px - 3, py - 3) && inMap(px + 3, py + 3)) {
			g.fill(px - 3, py - 3, px + 3, py + 3, 0xFF101010);
			g.fill(px - 2, py - 2, px + 2, py + 2, 0xFFFFFFFF);
			label(g, Component.translatable("kingdomquest.realm.you").getString(), px + 6, py - 4, 0xFFFFFFFF);
		}
		scaleBar(g, f);
		if (latest != null) {
			label(g, Component.translatable("kingdomquest.realm.latest", Component.translatable(latest.get("title").getAsString()), where(latest)).getString(),
					mapX0() + 3, mapY0() + mapH() - 22, 0xFFFFD040);
		}
		label(g, Component.translatable("kingdomquest.realm.plan_hint").getString(), mapX0() + 3, mapY0() + mapH() - 11, 0xFFE0E0E0);
		if (hover != null) {
			List<Component> lines = new ArrayList<>();
			lines.add(Component.translatable(hover.get("title").getAsString()));
			lines.add(Component.translatable("kingdomquest.realm.at", where(hover)));
			g.setComponentTooltipForNextFrame(this.font, lines, mouseX, mouseY);
		} else if (inMap(mouseX, mouseY) && mouseX < mapX0() + mapW() - 18) {
			int wx = (int) Math.floor(f[1] + (mouseX - (mapX0() + mapW() / 2.0)) / f[0]);
			int wz = (int) Math.floor(f[2] + (mouseY - (mapY0() + mapH() / 2.0)) / f[0]);
			g.setTooltipForNextFrame(this.font, Component.literal(wx + ", " + wz + "  (" + direction(wx, wz) + ")"), mouseX, mouseY);
		}
	}

	private int area(double[] f, JsonObject b) {
		return (sx(f, b.get("x1").getAsInt() + 1) - sx(f, b.get("x0").getAsInt())) * (sy(f, b.get("z1").getAsInt() + 1) - sy(f, b.get("z0").getAsInt()));
	}

	private void dot(GuiGraphicsExtractor g, double[] f, JsonObject o, int color) {
		int x = sx(f, o.get("x").getAsInt() + 0.5);
		int y = sy(f, o.get("z").getAsInt() + 0.5);
		if (inMap(x - 2, y - 2) && inMap(x + 2, y + 2)) {
			g.fill(x - 2, y - 2, x + 2, y + 2, 0xFF101010);
			g.fill(x - 1, y - 1, x + 1, y + 1, color);
		}
	}

	private String where(JsonObject b) {
		int x = (b.get("x0").getAsInt() + b.get("x1").getAsInt()) / 2;
		int z = (b.get("z0").getAsInt() + b.get("z1").getAsInt()) / 2;
		return x + ", " + z + " (" + direction(x, z) + ")";
	}

	private String direction(int x, int z) {
		var p = Minecraft.getInstance().player;
		if (p == null) {
			return "";
		}
		double dx = x + 0.5 - p.getX();
		double dz = z + 0.5 - p.getZ();
		int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
		String[] names = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
		int i = Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(-dx, dz)) / 45.0), 8);
		return dist + "m " + names[i];
	}

	private void drawList(GuiGraphicsExtractor g, JsonArray list, boolean people, Theme t) {
		if (list.isEmpty()) {
			g.text(this.font, Component.translatable(people ? "kingdomquest.realm.no_people" : "kingdomquest.realm.no_animals"), mapX0(), rowY(0),
					t.secondary(), false);
			return;
		}
		for (int i = 0; i < ROWS; i++) {
			int idx = this.page * ROWS + i;
			if (idx >= list.size()) {
				break;
			}
			JsonObject o = list.get(idx).getAsJsonObject();
			int y = rowY(i);
			g.text(this.font, Text.fit(this.font, o.get("name").getAsString(), 120), mapX0(), y, t.text(), false);
			g.text(this.font, Text.fit(this.font, people ? o.get("role").getAsString() : o.get("type").getAsString(), 100), mapX0() + 126, y,
					t.secondary(), false);
			int x = o.get("x").getAsInt();
			int z = o.get("z").getAsInt();
			g.text(this.font, x + " " + o.get("y").getAsInt() + " " + z + "  " + direction(x, z), mapX0() + 230, y, t.secondary(), false);
			if (people && !fixedLook(o)) {
				// drawn and clicked by the screen itself (the small buttons that were here could not be pressed)
				String look = Component.translatable("kingdomquest.realm.look").getString() + ": "
						+ LOOKS[Math.floorMod(o.get("skin").getAsInt(), LOOKS.length)] + " >";
				g.fill(lookX(), y - 3, lookX() + LOOK_W, y + 11, t.secondary() & 0x00FFFFFF | 0x38000000);
				g.text(this.font, Text.fit(this.font, look, LOOK_W - 6), lookX() + 4, y, t.text(), false);
			}
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (this.tab == 2 && event.x() >= lookX() && event.x() <= lookX() + LOOK_W) {
			JsonArray list = arr("people");
			for (int i = 0; i < ROWS && this.page * ROWS + i < list.size(); i++) {
				JsonObject p = list.get(this.page * ROWS + i).getAsJsonObject();
				if (event.y() >= rowY(i) - 3 && event.y() <= rowY(i) + 11 && !fixedLook(p)) {
					send("realm_skin|" + p.get("uuid").getAsString() + "|" + (p.get("skin").getAsInt() + 1));
					return true;
				}
			}
		}
		if (this.tab == 0 && inMap(event.x(), event.y())) {
			double[] f = view(0);
			for (Node n : this.nodes) {
				if (Math.abs(event.x() - sx(f, n.x())) <= 6 && Math.abs(event.y() - sy(f, n.z())) <= 6) {
					if (this.selA == null || this.selB != null || n.key().equals(this.selA)) {
						this.selA = n.key();
						this.selB = null;
					} else {
						this.selB = n.key();
					}
					this.rebuildWidgets();
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (this.tab <= 1 && inMap(event.x(), event.y())) {
			double[] f = view(this.tab);
			f[1] -= dx / f[0];
			f[2] -= dy / f[0];
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (this.tab <= 1 && inMap(mouseX, mouseY) && scrollY != 0) {
			zoom(scrollY > 0 ? 1.25 : 0.8, mouseX, mouseY);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
