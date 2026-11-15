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
 * The realm: a map of all the lord's settlements and known villages with the roads between them (select two and
 * connect them, rename them, name the empire), and a register of the current settlement: a plan of its buildings
 * (the newest one highlighted), its people (with a button to change how they look) and its animals.
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

	private final JsonObject data;
	private final BlockPos heart;
	private final List<Node> nodes = new ArrayList<>();
	private final List<Button> themed = new ArrayList<>();
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
		BlockPos heart = mc.screen instanceof RealmScreen r ? r.heart : KingdomScreen.lastHeart();
		RealmScreen next = new RealmScreen(payload, heart);
		if (mc.screen instanceof RealmScreen r) {
			next.tab = r.tab;
			next.page = r.page;
			next.selA = r.selA;
			next.selB = r.selB;
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

	@Override
	protected void init() {
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
			if (this.tab == 2) {
				for (int i = 0; i < ROWS; i++) {
					int idx = this.page * ROWS + i;
					if (idx >= list.size()) {
						break;
					}
					JsonObject p = list.get(idx).getAsJsonObject();
					this.themed.add(this.addRenderableWidget(Button.builder(Component.translatable("kingdomquest.realm.look"),
							b -> send("realm_skin|" + p.get("uuid").getAsString() + "|" + (p.get("skin").getAsInt() + 1)))
							.bounds(this.left + W - 8 - 60, rowY(i) - 2, 56, 14).build()));
				}
			}
		}
		this.themed.add(this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
				.bounds(this.left + W - 8 - 60, fy, 60, 18).build()));
	}

	private int rowY(int i) {
		return this.top + 50 + i * 18;
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
		pts.add(new int[] {this.data.get("px").getAsInt(), this.data.get("pz").getAsInt()});
		return fit(pts);
	}

	private double[] plotFit() {
		List<int[]> pts = new ArrayList<>();
		for (JsonElement e : arr("buildings")) {
			JsonObject b = e.getAsJsonObject();
			if (b.get("track").getAsString().equals("walls")) {
				continue;
			}
			pts.add(new int[] {b.get("x0").getAsInt(), b.get("z0").getAsInt()});
			pts.add(new int[] {b.get("x1").getAsInt(), b.get("z1").getAsInt()});
		}
		pts.add(new int[] {this.data.get("hx").getAsInt(), this.data.get("hz").getAsInt()});
		return fit(pts);
	}

	private static void line(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
		int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
		for (int i = 0; i <= steps; i += 2) {
			int x = x0 + (x1 - x0) * i / Math.max(1, steps);
			int y = y0 + (y1 - y0) * i / Math.max(1, steps);
			g.fill(x, y, x + 2, y + 2, color);
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(g, mouseX, mouseY, partialTick);
		Theme t = ClientConfig.colors();
		Ui.panel(g, this.left, this.top, W, H, t);
		Ui.sunken(g, mapX0() - 4, mapY0() - 4, mapW() + 8, mapH() + 8, t);
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
		double[] f = realmFit();
		for (JsonElement e : arr("links")) {
			JsonObject l = e.getAsJsonObject();
			Node a = node(l.get("from").getAsString());
			if (a == null) {
				continue;
			}
			line(g, sx(f, a.x()), sy(f, a.z()), sx(f, l.get("x").getAsInt()), sy(f, l.get("z").getAsInt()), 0xFFC8A060);
		}
		Node hover = null;
		for (Node n : this.nodes) {
			int x = sx(f, n.x());
			int y = sy(f, n.z());
			int c = n.settlement() ? 0xFF000000 | DyeColor.byId(n.color()).getTextureDiffuseColor() : 0xFF4CAF50;
			boolean sel = n.key().equals(this.selA) || n.key().equals(this.selB);
			if (sel) {
				Ui.roundedFill(g, x - 6, y - 6, 12, 12, 3, t.heading());
			}
			Ui.roundedFill(g, x - 4, y - 4, 8, 8, n.settlement() ? 1 : 4, c);
			g.text(this.font, n.name(), x + 7, y - 4, n.settlement() ? t.text() : t.secondary(), false);
			if (Math.abs(mouseX - x) <= 5 && Math.abs(mouseY - y) <= 5) {
				hover = n;
			}
		}
		int px = sx(f, this.data.get("px").getAsInt());
		int py = sy(f, this.data.get("pz").getAsInt());
		g.fill(px - 1, py - 1, px + 2, py + 2, 0xFFFFFFFF);
		g.text(this.font, Component.translatable("kingdomquest.realm.you"), px + 4, py - 4, 0xFFFFFFFF, false);
		g.text(this.font, Component.translatable("kingdomquest.realm.map_hint"), mapX0(), mapY0() + mapH() - 10, t.secondary(), false);
		if (hover != null) {
			g.setTooltipForNextFrame(this.font, Component.literal(hover.name() + "  (" + hover.x() + ", " + hover.z() + ")"), mouseX, mouseY);
		}
	}

	private void drawPlot(GuiGraphicsExtractor g, int mouseX, int mouseY, Theme t) {
		double[] f = plotFit();
		JsonObject hover = null;
		JsonObject latest = null;
		for (JsonElement e : arr("buildings")) {
			JsonObject b = e.getAsJsonObject();
			String track = b.get("track").getAsString();
			if (track.equals("roads") || track.equals("lamps")) {
				continue;
			}
			int x0 = sx(f, b.get("x0").getAsInt());
			int y0 = sy(f, b.get("z0").getAsInt());
			int x1 = sx(f, b.get("x1").getAsInt() + 1);
			int y1 = sy(f, b.get("z1").getAsInt() + 1);
			boolean last = b.get("last").getAsBoolean();
			boolean area = b.get("area").getAsBoolean();
			if (last) {
				latest = b;
			}
			if (track.equals("walls")) {
				Ui.boxOutline(g, x0, y0, x1 - x0, y1 - y0, t.secondary());
				continue;
			}
			int fill = last ? 0xFFE0B040 : area ? Ui.blend(t.panelDark(), t.secondary(), 0.25f) : t.selected();
			g.fill(x0, y0, Math.max(x0 + 2, x1), Math.max(y0 + 2, y1), fill);
			Ui.boxOutline(g, x0, y0, Math.max(2, x1 - x0), Math.max(2, y1 - y0), last ? 0xFFFFFFFF : t.panelDark());
			if (!area && x1 - x0 > 20) {
				g.text(this.font, Text.fit(this.font, Component.translatable("kingdomquest.track." + track).getString(), x1 - x0 - 2),
						x0 + 2, y0 + 2, last ? 0xFF000000 : t.text(), false);
			}
			if (mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1 && (hover == null || !area)) {
				hover = b;
			}
		}
		int hx = sx(f, this.data.get("hx").getAsInt());
		int hy = sy(f, this.data.get("hz").getAsInt());
		Ui.roundedFill(g, hx - 3, hy - 3, 6, 6, 3, t.heading());
		if (latest != null) {
			g.text(this.font, Component.translatable("kingdomquest.realm.latest", Component.translatable(latest.get("title").getAsString()),
					where(latest)), mapX0(), mapY0() + mapH() - 10, 0xFFE0B040, false);
		}
		if (hover != null) {
			List<Component> lines = new ArrayList<>();
			lines.add(Component.translatable(hover.get("title").getAsString()));
			lines.add(Component.translatable("kingdomquest.realm.at", where(hover)));
			g.setComponentTooltipForNextFrame(this.font, lines, mouseX, mouseY);
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
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (this.tab == 0 && event.x() >= mapX0() && event.x() < mapX0() + mapW() && event.y() >= mapY0() && event.y() < mapY0() + mapH()) {
			double[] f = realmFit();
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
	public boolean isPauseScreen() {
		return false;
	}
}
