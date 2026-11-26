package io.github.sotadevop.kingdomquest.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.sotadevop.kingdomquest.kingdom.ActionPayload;
import io.github.sotadevop.kingdomquest.kingdom.RealmPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import io.github.sotadevop.kingdomquest.npc.KingdomNpc;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
	private static final String[] TABS = {"kingdomquest.realm.map", "kingdomquest.realm.buildings", "kingdomquest.realm.people",
			"kingdomquest.realm.animals"};
	private static int lastTab;

	/** {@code liege}: for a village sworn in the Fealty Ceremony, the settlement it is sworn to, else null. */
	private record Node(String key, String name, int x, int z, boolean settlement, int color, String liege, String about) {
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
	/** The two layers painted once into textures (user, 2026-11-18: the map was laggy when drawn cell by cell every frame). */
	private Identifier landTex;
	private Identifier planTex;
	private static int serial;
	/** What the two maps show: {pixels per block, centre x, centre z} for the realm map and the settlement plan. */
	private final double[][] view = new double[2][];
	private int tab = lastTab;
	/** First row (or row of cards) shown in the people and animal lists; the mouse wheel moves it. */
	private int scroll;
	/** People as cards with their 3D figure instead of rows (user, 2026-11-18; the default since 2026-11-25); remembered while the game runs. */
	private static boolean cards = true;
	/** The person whose look is being chosen (the skin picker is shown over the list), or null. */
	private String picking;
	private int pickScroll;
	/** Arms for the chosen look: 0 as the skin is made, 1 wide, 2 slim. */
	private int pickArms;
	private String status;
	/** Stand-in figures for the 3D views, one per look (not in the world). */
	private final java.util.Map<Integer, KingdomNpc> models = new java.util.HashMap<>();
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
					o.get("color").getAsInt(), null, null));
		}
		for (JsonElement e : arr("villages")) {
			JsonObject o = e.getAsJsonObject();
			String liege = o.has("liege") ? o.get("liege").getAsString() : null;
			this.nodes.add(new Node(o.get("key").getAsString(), o.get("name").getAsString(), o.get("x").getAsInt(), o.get("z").getAsInt(), false,
					liege == null ? -1 : o.get("color").getAsInt(), liege, liege == null ? null : Component.translatable("kingdomquest.realm.vassal_of", liege,
							o.get("day").getAsInt(), o.get("villagers").getAsInt()).getString()));
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
			next.scroll = r.scroll;
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
	public void removed() {
		super.removed();
		TextureManager tm = Minecraft.getInstance().getTextureManager();
		for (Identifier id : new Identifier[] {this.landTex, this.planTex}) {
			if (id != null) {
				tm.release(id);
			}
		}
		this.landTex = null;
		this.planTex = null;
	}

	/** The layers' textures, painted when first needed (again after another screen was shown over this one). */
	private void paintLayers() {
		if (this.landTex == null && this.planTex == null) {
			int n = ++serial; // each screen its own textures: a refreshed screen is built before the old one goes
			this.landTex = paint(this.land, "realm/land_" + n);
			this.planTex = paint(this.plan, "realm/plan_" + n);
		}
	}

	/** Paints a layer into a texture once: a pixel per cell, unknown land transparent. */
	private static Identifier paint(Layer l, String path) {
		if (l == null || l.w() <= 0 || l.h() <= 0) {
			return null;
		}
		NativeImage img = new NativeImage(l.w(), l.h(), true);
		for (int j = 0; j < l.h(); j++) {
			for (int i = 0; i < l.w(); i++) {
				int c = l.px()[j * l.w() + i] & 0xFFFF;
				img.setPixel(i, j, c == 0 ? 0 : argb(c));
			}
		}
		Identifier id = Identifier.fromNamespaceAndPath("kingdomquest", path);
		DynamicTexture tex = new DynamicTexture(() -> "kingdomquest " + path, img);
		tex.upload();
		Minecraft.getInstance().getTextureManager().register(id, tex);
		return id;
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
				this.scroll = 0;
				this.picking = null;
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
			}).bounds(this.left + 8, fy, 80, 18).build();
			connect.active = a != null && b2 != null && (a.settlement() || b2.settlement());
			this.themed.add(this.addRenderableWidget(connect));
			this.themed.add(this.addRenderableWidget(Button.builder(Component.translatable("kingdomquest.realm.find"), b -> send("realm_find"))
					.bounds(this.left + 92, fy, 76, 18).build()));
			this.rename = new EditBox(this.font, this.left + 172, fy + 2, 90, 14, Component.translatable("kingdomquest.realm.name"));
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
			}).bounds(this.left + 266, fy, 44, 18).build();
			save.active = sel != null;
			this.themed.add(this.addRenderableWidget(save));
			// fast travel with the Dimension Globe of Sota Tweaks (the server checks the globe is carried)
			if (this.data.has("globe") && this.data.get("globe").getAsBoolean()) {
				Button travel = Button.builder(Component.translatable("kingdomquest.realm.travel"), b -> {
					Node n = this.selB != null ? node(this.selB) : node(this.selA);
					if (n != null) {
						send("realm_travel|" + n.key());
						this.onClose();
					}
				}).bounds(this.left + 314, fy, 54, 18).build();
				travel.active = sel != null;
				this.themed.add(this.addRenderableWidget(travel));
			}
		} else if (this.tab == 2 && this.picking != null) {
			this.themed.add(this.addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> {
				this.picking = null;
				this.status = null;
				this.rebuildWidgets();
			}).bounds(this.left + 8, fy, 50, 18).build()));
			this.themed.add(this.addRenderableWidget(Button.builder(Component.translatable("kingdomquest.realm.arms." + this.pickArms), b -> {
				this.pickArms = (this.pickArms + 1) % 3;
				this.rebuildWidgets();
			}).bounds(this.left + 62, fy, 96, 18).tooltip(net.minecraft.client.gui.components.Tooltip.create(
					Component.translatable("kingdomquest.realm.arms_hint"))).build()));
			if (solo()) {
				this.themed.add(this.addRenderableWidget(Button.builder(Component.literal("+"), b -> openSkinFolder())
						.bounds(this.left + 162, fy, 20, 18).tooltip(net.minecraft.client.gui.components.Tooltip.create(
								Component.translatable("kingdomquest.realm.add_skins"))).build()));
			}
		} else if (this.tab == 2 || this.tab == 3) {
			this.themed.add(this.addRenderableWidget(Button.builder(Component.translatable(cards ? "kingdomquest.realm.as_list" : "kingdomquest.realm.as_cards"), b -> {
				cards = !cards;
				this.scroll = 0;
				this.rebuildWidgets();
			}).bounds(this.left + 8, fy, 70, 18).build()));
		}
		this.themed.add(this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
				.bounds(this.left + W - 8 - 60, fy, 60, 18).build()));
	}

	private static final String[] LOOKS = {"Steve", "Alex", "Ari", "Efe", "Kai", "Makena", "Noor", "Sunny", "Zuri"};
	private static final int ROW_H = 16;
	private static final int CARD_GAP = 4;
	private static final int CARD_H = 98;
	private static final int CELL_W = 58;
	private static final int CELL_H = 82;

	private boolean lord() {
		return this.data.has("lord") && this.data.get("lord").getAsBoolean();
	}

	private boolean solo() {
		return this.data.has("solo") && this.data.get("solo").getAsBoolean();
	}

	/** Guards all wear the same face and armor: their look is not changed one by one. */
	private static boolean fixedLook(JsonObject person) {
		return person.has("guard") && person.get("guard").getAsBoolean() || "Guard".equals(person.get("role").getAsString());
	}

	private boolean canChange(JsonObject person) {
		return lord() && !fixedLook(person);
	}

	/** The actions on a person or animal for the lord: a new look (not guards), a new name, go there. */
	private List<String> actions(JsonObject o, boolean people) {
		List<String> out = new ArrayList<>();
		if (!lord()) {
			return out;
		}
		if (people && canChange(o)) {
			out.add("look");
		}
		if (o.has("uuid")) {
			out.add("name");
			out.add("go");
		}
		return out;
	}

	/** Draws the action buttons of one person or animal side by side in {@code x0..x1}. */
	private void drawActions(GuiGraphicsExtractor g, List<String> acts, int x0, int x1, int y, int h, int mouseX, int mouseY, boolean rowOver, Theme t) {
		if (acts.isEmpty()) {
			return;
		}
		int w = (x1 - x0) / acts.size();
		for (int i = 0; i < acts.size(); i++) {
			int bx = x0 + i * w;
			boolean on = rowOver && mouseX >= bx && mouseX < bx + w - 2 && mouseY >= y && mouseY < y + h;
			Ui.roundedFill(g, bx, y, w - 2, h, 2, on ? 0x70FFFFFF : 0x38FFFFFF);
			String label = Component.translatable("kingdomquest.realm.act_" + acts.get(i)).getString();
			g.text(this.font, Text.fit(this.font, label, w - 6), bx + (w - 2 - Math.min(this.font.width(label), w - 6)) / 2, y + (h - 8) / 2, t.text(), false);
		}
	}

	/** Which action sits at {@code mx} in {@code x0..x1}, or null. */
	private static String actionAt(List<String> acts, int x0, int x1, double mx) {
		if (acts.isEmpty() || mx < x0 || mx >= x1) {
			return null;
		}
		int i = (int) ((mx - x0) / ((x1 - x0) / acts.size()));
		return i >= 0 && i < acts.size() ? acts.get(i) : null;
	}

	/** Runs an action of a person or animal from the lists. */
	private void act(String action, JsonObject o, boolean people) {
		switch (action) {
			case "look" -> {
				this.picking = o.get("uuid").getAsString();
				this.pickScroll = 0;
				int arms = KingdomNpc.skinArms(o.get("skin").getAsInt());
				this.pickArms = arms == 3 ? 0 : arms;
				this.status = null;
				CustomSkins.rescan();
				this.rebuildWidgets();
			}
			case "name" -> this.minecraft.setScreenAndShow(new NameScreen(new io.github.sotadevop.kingdomquest.kingdom.NamePayload(this.heart,
					(people ? "npc|" : "mob|") + o.get("uuid").getAsString(), o.get("name").getAsString())));
			case "go" -> {
				send("realm_goto|" + o.get("uuid").getAsString());
				this.onClose();
			}
			default -> {
			}
		}
	}

	/** Rows of the list that fit under its header. */
	private int listRows() {
		return (mapH() - 14) / ROW_H;
	}

	private int rowTop(int i) {
		return mapY0() + 14 + i * ROW_H;
	}

	private int cardCols() {
		return Math.max(1, (mapW() + CARD_GAP) / (76 + CARD_GAP));
	}

	private int cardW() {
		return (mapW() - (cardCols() - 1) * CARD_GAP) / cardCols();
	}

	private int cardRows() {
		return Math.max(1, (mapH() + CARD_GAP) / (CARD_H + CARD_GAP));
	}

	/** Column edges of the list: name, role, where, look (the look column only for the lord's people). */
	private int[] columns(boolean people) {
		int lookW = lord() ? (people ? 96 : 64) : 0; // the actions: look, name, go (animals: name, go)
		int whereW = 74;
		int rest = mapW() - lookW - whereW - 12;
		int nameW = rest * 11 / 20;
		int x = mapX0() + 3;
		return new int[] {x, x + nameW + 4, mapX0() + mapW() - lookW - whereW, mapX0() + mapW() - lookW, mapX0() + mapW()};
	}

	private final java.util.Map<String, net.minecraft.world.entity.LivingEntity> animals = new java.util.HashMap<>();

	/** An animal's figure for its card (user, 2026-11-25: the animals as cards like the people). */
	private net.minecraft.world.entity.LivingEntity animal(String type) {
		return this.animals.computeIfAbsent(type, k -> {
			Minecraft mc = Minecraft.getInstance();
			net.minecraft.world.entity.EntityType<?> et = mc.level == null ? null
					: net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace(k));
			net.minecraft.world.entity.Entity e = et == null ? null : et.create(mc.level, net.minecraft.world.entity.EntitySpawnReason.LOAD);
			if (e instanceof net.minecraft.world.entity.LivingEntity le) {
				le.setId(-2_000_000 - this.animals.size());
				return le;
			}
			return null;
		});
	}

	private KingdomNpc model(int skin) {
		return this.models.computeIfAbsent(skin, k -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level == null) {
				return null;
			}
			KingdomNpc n = new KingdomNpc(KingdomNpc.TYPE, mc.level);
			n.setId(-1_000_000 - this.models.size()); // the renderer reads the id (held items); never one of the world's
			n.setSkin(k);
			return n;
		});
	}

	/** The looks to choose from: the nine vanilla skins and (single player) the skins in the folder. */
	private List<int[]> looks() {
		List<int[]> out = new ArrayList<>();
		// first the region's own people of the same sex as the one being changed (the generated skins), then the
		// nine vanilla skins and the folder's
		JsonObject p = this.picking == null ? null : person(this.picking);
		int now = p == null ? -1 : KingdomNpc.skinBase(p.get("skin").getAsInt());
		if (io.github.sotadevop.kingdomquest.npc.Folk.generated(now)) {
			int region = io.github.sotadevop.kingdomquest.npc.Folk.regionOf(now);
			boolean female = io.github.sotadevop.kingdomquest.npc.Folk.female(io.github.sotadevop.kingdomquest.npc.Folk.outfitOf(now));
			for (int o = 0; o < io.github.sotadevop.kingdomquest.npc.Folk.OUTFITS; o++) {
				if (io.github.sotadevop.kingdomquest.npc.Folk.female(o) != female || o == io.github.sotadevop.kingdomquest.npc.Folk.GUARD) {
					continue;
				}
				for (int v = 0; v < io.github.sotadevop.kingdomquest.npc.Folk.VARIANTS; v++) {
					out.add(new int[] {io.github.sotadevop.kingdomquest.npc.Folk.skin(region, o, v)});
				}
			}
		}
		for (int i = 0; i < LOOKS.length; i++) {
			out.add(new int[] {i});
		}
		if (solo()) {
			for (CustomSkins.Skin sk : CustomSkins.all()) {
				out.add(new int[] {sk.id()});
			}
		}
		return out;
	}

	private static final String[] OUTFIT_NAMES = {"Guard", "Peasant", "Smith", "Burgher", "Scholar", "Craftsman", "Captain", "Housewife",
			"Working woman", "Cook"};

	private String lookName(int base) {
		if (base < LOOKS.length) {
			return LOOKS[base];
		}
		if (io.github.sotadevop.kingdomquest.npc.Folk.generated(base)) {
			return OUTFIT_NAMES[io.github.sotadevop.kingdomquest.npc.Folk.outfitOf(base)] + " " + (base % io.github.sotadevop.kingdomquest.npc.Folk.VARIANTS + 1);
		}
		CustomSkins.Skin sk = CustomSkins.get(base);
		return sk == null ? "?" : sk.name();
	}

	private int pickCols() {
		return Math.max(1, (mapW() - 8) / CELL_W);
	}

	private int pickRows() {
		return Math.max(1, (mapH() - 16) / CELL_H);
	}

	private void openSkinFolder() {
		try {
			java.nio.file.Files.createDirectories(CustomSkins.folder());
			com.mojang.blaze3d.Blaze3D.openPath(CustomSkins.folder());
		} catch (java.io.IOException | RuntimeException e) {
			this.status = Component.translatable("kingdomquest.realm.folder_failed", CustomSkins.folder().toString()).getString();
		}
	}

	@Override
	public void onFilesDrop(List<java.nio.file.Path> files) {
		if (this.tab == 2 && solo()) {
			int n = CustomSkins.add(files);
			this.status = Component.translatable("kingdomquest.realm.skins_added", n).getString();
		}
	}

	private JsonObject person(String uuid) {
		for (JsonElement e : arr("people")) {
			if (e.getAsJsonObject().get("uuid").getAsString().equals(uuid)) {
				return e.getAsJsonObject();
			}
		}
		return null;
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

	/** Draws a layer's texture where its land lies, cut to the map area. */
	private void drawLayer(GuiGraphicsExtractor g, Layer l, Identifier tex, double[] f) {
		if (l == null || tex == null) {
			return;
		}
		int x0 = sx(f, l.x0()), y0 = sy(f, l.z0());
		int x1 = sx(f, l.x0() + (double) l.w() * l.step()), y1 = sy(f, l.z0() + (double) l.h() * l.step());
		if (x1 <= x0 || y1 <= y0) {
			return;
		}
		g.enableScissor(mapX0(), mapY0(), mapX0() + mapW(), mapY0() + mapH());
		g.blit(tex, x0, y0, x1, y1, 0f, 1f, 0f, 1f);
		g.disableScissor();
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
			paintLayers();
			drawLayer(g, this.land, this.landTex, f);
			if (this.tab == 1) {
				drawLayer(g, this.plan, this.planTex, f);
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
			case 2 -> drawList(g, arr("people"), true, t, mouseX, mouseY);
			default -> drawList(g, arr("animals"), false, t, mouseX, mouseY);
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
			int c = n.color() >= 0 ? 0xFF000000 | DyeColor.byId(n.color()).getTextureDiffuseColor() : 0xFF4CAF50;
			boolean sel = n.key().equals(this.selA) || n.key().equals(this.selB);
			Ui.roundedFill(g, x - 6, y - 6, 12, 12, 3, sel ? 0xFFFFFFFF : 0xFF101010);
			Ui.roundedFill(g, x - 4, y - 4, 8, 8, n.settlement() ? 1 : 4, c);
			if (n.liege() != null) { // a vassal village: its liege's color with a light heart, like a banner
				g.fill(x - 1, y - 1, x + 1, y + 1, 0xFFF0E0B0);
			}
			label(g, n.name(), x + 9, y - 4, n.settlement() || n.liege() != null ? 0xFFFFFFFF : 0xFFC8E6C9);
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
		vassalList(g);
		label(g, Component.translatable("kingdomquest.realm.map_hint").getString(), mapX0() + 3, mapY0() + mapH() - 11, 0xFFE0E0E0);
		if (hover != null) {
			List<Component> lines = new ArrayList<>(List.of(Component.literal(hover.name() + "  (" + hover.x() + ", " + hover.z() + ")")));
			if (hover.about() != null) {
				lines.add(Component.literal(hover.about()));
			}
			g.setComponentTooltipForNextFrame(this.font, lines, mouseX, mouseY);
		} else if (inMap(mouseX, mouseY) && mouseX < mapX0() + mapW() - 18) {
			int wx = (int) Math.floor(f[1] + (mouseX - (mapX0() + mapW() / 2.0)) / f[0]);
			int wz = (int) Math.floor(f[2] + (mouseY - (mapY0() + mapH() / 2.0)) / f[0]);
			g.setTooltipForNextFrame(this.font, Component.literal(wx + ", " + wz + "  (" + direction(wx, wz) + ")"), mouseX, mouseY);
		}
	}

	/** The realm's vassal villages, listed in the map's top left corner. */
	private void vassalList(GuiGraphicsExtractor g) {
		List<Node> vassals = this.nodes.stream().filter(n -> n.liege() != null).toList();
		if (vassals.isEmpty()) {
			return;
		}
		int shown = Math.min(vassals.size(), Math.max(1, (mapH() - 40) / 10 - 1));
		String head = Component.translatable("kingdomquest.realm.vassals", vassals.size()).getString();
		int w = this.font.width(head);
		for (Node n : vassals.subList(0, shown)) {
			w = Math.max(w, this.font.width(n.name() + "  " + n.liege()) + 8);
		}
		w = Math.min(mapW() / 2, w + 8);
		int x = mapX0() + 3;
		int y = mapY0() + 3;
		g.fill(x, y, x + w, y + 14 + shown * 10, 0xB0101010);
		g.text(this.font, head, x + 4, y + 3, 0xFFF0C070, false);
		for (int i = 0; i < shown; i++) {
			Node n = vassals.get(i);
			int ly = y + 14 + i * 10;
			g.fill(x + 4, ly + 1, x + 9, ly + 7, 0xFF000000 | DyeColor.byId(n.color()).getTextureDiffuseColor());
			String line = i == shown - 1 && shown < vassals.size() ? Component.translatable("kingdomquest.realm.more", vassals.size() - shown + 1).getString()
					: n.name() + "  " + n.liege();
			g.text(this.font, Text.fit(this.font, line, w - 16), x + 12, ly, 0xFFE0E0E0, false);
		}
	}

	/** The building under the mouse on the plan (a click without dragging visits it), and where the press began. */
	private String hoverTrack;
	private double pressX = -1, pressY = -1;

	private void drawPlot(GuiGraphicsExtractor g, int mouseX, int mouseY, Theme t) {
		double[] f = view(1);
		this.hoverTrack = null;
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
			this.hoverTrack = hover.get("track").getAsString();
			List<Component> lines = new ArrayList<>();
			lines.add(Component.translatable(hover.get("title").getAsString()));
			lines.add(Component.translatable("kingdomquest.realm.at", where(hover)));
			lines.add(Component.translatable("kingdomquest.realm.visit_hint").withStyle(net.minecraft.ChatFormatting.YELLOW));
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

	private void drawList(GuiGraphicsExtractor g, JsonArray list, boolean people, Theme t, int mouseX, int mouseY) {
		if (people && this.picking != null) {
			drawPicker(g, t, mouseX, mouseY);
			return;
		}
		if (list.isEmpty()) {
			g.text(this.font, Component.translatable(people ? "kingdomquest.realm.no_people" : "kingdomquest.realm.no_animals"), mapX0() + 3, mapY0() + 3,
					t.secondary(), false);
			return;
		}
		if (cards) {
			drawCards(g, list, people, t, mouseX, mouseY);
			return;
		}
		int[] c = columns(people);
		g.text(this.font, Component.translatable("kingdomquest.realm.col_name"), c[0], mapY0() + 3, t.heading(), false);
		g.text(this.font, Component.translatable(people ? "kingdomquest.realm.col_role" : "kingdomquest.realm.col_kind"), c[1], mapY0() + 3, t.heading(), false);
		g.text(this.font, Component.translatable("kingdomquest.realm.col_where"), c[2], mapY0() + 3, t.heading(), false);
		int rows = listRows();
		this.scroll = Math.max(0, Math.min(this.scroll, list.size() - rows));
		JsonObject hover = null;
		for (int i = 0; i < rows; i++) {
			int idx = this.scroll + i;
			if (idx >= list.size()) {
				break;
			}
			JsonObject o = list.get(idx).getAsJsonObject();
			int y = rowTop(i);
			boolean over = mouseX >= mapX0() && mouseX < mapX0() + mapW() && mouseY >= y && mouseY < y + ROW_H;
			if (over) {
				hover = o;
			}
			g.fill(mapX0(), y, mapX0() + mapW(), y + ROW_H - 1, over ? 0x30FFFFFF : (idx % 2 == 0 ? 0x10FFFFFF : 0));
			int ty = y + 4;
			g.text(this.font, Text.fit(this.font, o.get("name").getAsString(), c[1] - c[0] - 6), c[0], ty, t.text(), false);
			g.text(this.font, Text.fit(this.font, people ? o.get("role").getAsString() : o.get("type").getAsString(), c[2] - c[1] - 6), c[1], ty,
					t.secondary(), false);
			g.text(this.font, Text.fit(this.font, direction(o.get("x").getAsInt(), o.get("z").getAsInt()), c[3] - c[2] - 4), c[2], ty, t.secondary(), false);
			drawActions(g, actions(o, people), c[3], c[4], y + 1, ROW_H - 3, mouseX, mouseY, over, t);
		}
		scrollBar(g, list.size(), rows, this.scroll);
		if (hover != null) {
			g.setTooltipForNextFrame(this.font, Component.literal(hover.get("x").getAsInt() + " " + hover.get("y").getAsInt() + " " + hover.get("z").getAsInt()),
					mouseX, mouseY);
		}
	}

	private void scrollBar(GuiGraphicsExtractor g, int total, int shown, int first) {
		if (total <= shown) {
			return;
		}
		int x = mapX0() + mapW() + 1;
		int h = mapH();
		int barH = Math.max(10, h * shown / total);
		int y = mapY0() + (h - barH) * first / Math.max(1, total - shown);
		g.fill(x, mapY0(), x + 2, mapY0() + h, 0x30FFFFFF);
		g.fill(x, y, x + 2, y + barH, 0xC0FFFFFF);
	}

	/** People as cards: their figure (turning to the mouse), name, work and the button for a new look. */
	private void drawCards(GuiGraphicsExtractor g, JsonArray list, boolean people, Theme t, int mouseX, int mouseY) {
		int cols = cardCols(), rows = cardRows(), w = cardW();
		int totalRows = (list.size() + cols - 1) / cols;
		this.scroll = Math.max(0, Math.min(this.scroll, totalRows - rows));
		for (int r = 0; r < rows; r++) {
			for (int col = 0; col < cols; col++) {
				int idx = (this.scroll + r) * cols + col;
				if (idx >= list.size()) {
					break;
				}
				JsonObject o = list.get(idx).getAsJsonObject();
				int x = mapX0() + col * (w + CARD_GAP);
				int y = mapY0() + r * (CARD_H + CARD_GAP);
				boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + CARD_H;
				Ui.roundedFill(g, x, y, w, CARD_H, 3, over ? 0x40FFFFFF : 0x22FFFFFF);
				net.minecraft.world.entity.LivingEntity m = people ? model(o.get("skin").getAsInt()) : animal(o.get("type").getAsString());
				if (m != null) {
					// an animal's figure is scaled to fit the card (a horse is bigger than a cat)
					int size = people ? 28 : (int) Math.max(8, Math.min(30, 44 / Math.max(m.getBbHeight(), m.getBbWidth() * 1.2f)));
					InventoryScreen.extractEntityInInventoryFollowsMouse(g, x + 2, y + 2, x + w - 2, y + 62, size, 0.0625f, mouseX, mouseY, m);
				}
				g.text(this.font, Text.fit(this.font, o.get("name").getAsString(), w - 6), x + 3, y + 64, t.text(), false);
				g.text(this.font, Text.fit(this.font, people ? o.get("role").getAsString() : direction(o.get("x").getAsInt(), o.get("z").getAsInt()), w - 6), x + 3,
						y + 74, t.secondary(), false);
				drawActions(g, actions(o, people), x + 2, x + w - 2, y + CARD_H - 14, 12, mouseX, mouseY, over, t);
			}
		}
		scrollBar(g, totalRows, rows, this.scroll);
	}

	/** The skin picker over the people list: every look as a 3D figure; a click gives it to the person. */
	private void drawPicker(GuiGraphicsExtractor g, Theme t, int mouseX, int mouseY) {
		JsonObject p = person(this.picking);
		String who = p == null ? "" : p.get("name").getAsString();
		g.text(this.font, Text.fit(this.font, Component.translatable("kingdomquest.realm.pick_for", who).getString(), mapW() - 6), mapX0() + 3, mapY0() + 3,
				t.heading(), false);
		List<int[]> looks = looks();
		int cols = pickCols(), rows = pickRows();
		int totalRows = (looks.size() + cols - 1) / cols;
		this.pickScroll = Math.max(0, Math.min(this.pickScroll, totalRows - rows));
		int current = p == null ? -1 : KingdomNpc.skinBase(p.get("skin").getAsInt());
		for (int r = 0; r < rows; r++) {
			for (int col = 0; col < cols; col++) {
				int idx = (this.pickScroll + r) * cols + col;
				if (idx >= looks.size()) {
					break;
				}
				int base = looks.get(idx)[0];
				int x = mapX0() + 4 + col * CELL_W;
				int y = mapY0() + 16 + r * CELL_H;
				boolean over = mouseX >= x && mouseX < x + CELL_W - 4 && mouseY >= y && mouseY < y + CELL_H - 4;
				Ui.roundedFill(g, x, y, CELL_W - 4, CELL_H - 4, 3, base == current ? 0x50FFD040 : over ? 0x40FFFFFF : 0x22FFFFFF);
				KingdomNpc m = model(base | this.pickArms << 24);
				if (m != null) {
					InventoryScreen.extractEntityInInventoryFollowsMouse(g, x + 2, y + 2, x + CELL_W - 6, y + CELL_H - 18, 30, 0.0625f, mouseX, mouseY, m);
				}
				g.text(this.font, Text.fit(this.font, lookName(base), CELL_W - 8), x + 2, y + CELL_H - 15, t.text(), false);
			}
		}
		scrollBar(g, totalRows, rows, this.pickScroll);
		String hint = this.status != null ? this.status
				: solo() ? Component.translatable("kingdomquest.realm.drop_hint").getString() : Component.translatable("kingdomquest.realm.solo_only").getString();
		g.text(this.font, Text.fit(this.font, hint, W - 260), this.left + 188, this.top + H - 19, t.secondary(), false);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x(), my = event.y();
		if (this.tab == 2 && this.picking != null && inMap(mx, my)) {
			List<int[]> looks = looks();
			int col = (int) Math.floor((mx - mapX0() - 4) / CELL_W);
			int row = (int) Math.floor((my - mapY0() - 16) / CELL_H);
			int idx = (this.pickScroll + row) * pickCols() + col;
			if (col >= 0 && col < pickCols() && row >= 0 && row < pickRows() && idx < looks.size() && my >= mapY0() + 16) {
				send("realm_skin|" + this.picking + "|" + (looks.get(idx)[0] | this.pickArms << 24));
				this.picking = null;
				this.status = null;
				this.rebuildWidgets();
				return true;
			}
		} else if ((this.tab == 2 || this.tab == 3) && inMap(mx, my)) {
			boolean people = this.tab == 2;
			JsonArray list = arr(people ? "people" : "animals");
			JsonObject hit = null;
			String action = null;
			if (cards) {
				int w = cardW();
				int col = (int) Math.floor((mx - mapX0()) / (w + CARD_GAP));
				int row = (int) Math.floor((my - mapY0()) / (CARD_H + CARD_GAP));
				int y = mapY0() + row * (CARD_H + CARD_GAP);
				int idx = (this.scroll + row) * cardCols() + col;
				if (col >= 0 && col < cardCols() && row >= 0 && row < cardRows() && idx < list.size() && my >= y + CARD_H - 14 && my < y + CARD_H) {
					hit = list.get(idx).getAsJsonObject();
					int x = mapX0() + col * (w + CARD_GAP);
					action = actionAt(actions(hit, people), x + 2, x + w - 2, mx);
				}
			} else {
				int[] c = columns(people);
				int row = (int) Math.floor((my - rowTop(0)) / ROW_H);
				int idx = this.scroll + row;
				if (mx >= c[3] && row >= 0 && row < listRows() && idx < list.size()) {
					hit = list.get(idx).getAsJsonObject();
					action = actionAt(actions(hit, people), c[3], c[4], mx);
				}
			}
			if (hit != null && action != null) {
				act(action, hit, people);
				return true;
			}
		}
		if (this.tab == 1 && inMap(event.x(), event.y())) {
			this.pressX = event.x();
			this.pressY = event.y();
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
	public boolean mouseReleased(MouseButtonEvent event) {
		// a click on a building of the plan (not the end of a drag): the lord goes there (user, 2026-11-25)
		if (this.tab == 1 && this.pressX >= 0 && Math.abs(event.x() - this.pressX) < 3 && Math.abs(event.y() - this.pressY) < 3 && this.hoverTrack != null) {
			send("realm_visit|" + this.hoverTrack);
			this.pressX = -1;
			this.onClose();
			return true;
		}
		this.pressX = -1;
		return super.mouseReleased(event);
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
		if (this.tab >= 2 && inMap(mouseX, mouseY) && scrollY != 0) {
			if (this.picking != null) {
				this.pickScroll += scrollY > 0 ? -1 : 1;
			} else {
				this.scroll += (scrollY > 0 ? -1 : 1) * (cards && (this.tab == 2 || this.tab == 3) ? 1 : 3);
			}
			this.scroll = Math.max(0, this.scroll);
			this.pickScroll = Math.max(0, this.pickScroll);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
