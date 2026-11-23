package io.github.sotadevop.kingdomquest.building;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.sotadevop.kingdomquest.KingdomQuest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A building from tools/buildings.py: blocks relative to the plot, the heart position and the build time.
 * Palette names may contain {color}, swapped for the kingdom's dye color when the template is placed.
 */
public final class Template {
	/** @param segment road/lamp segment index, -1 = always placed */
	public record Entry(int x, int y, int z, String name, Map<String, String> props, int segment, int ax, int az) {
	}

	/** A road segment and the (track, level) rules of which any one makes it appear. */
	public record Segment(String name, List<Map.Entry<String, Integer>> any) {
	}

	/** A block ready to place: position relative to the heart (already rotated) and its state. */
	/** @param anchor column (relative to the heart, y = 0) whose ground height a terrain-following block uses */
	public record Placement(BlockPos offset, BlockState state, int segment, BlockPos anchor) {
	}

	private static final Map<String, Template> CACHE = new HashMap<>();

	public final String id;
	public final int seconds;
	public final BlockPos heart;
	public final int sizeX;
	public final int sizeZ;
	public final List<Entry> entries;
	public final List<Segment> segments;
	/** Blocks follow the terrain (roads, lamps, palisades) instead of the heart's height; no levelling. */
	public final boolean follow;
	/** Placed at an anchor the mod finds in the world (the dock at the nearest water), not around the heart. */
	public final boolean anchored;
	/** Container the add-on fills with its production (relative to the plot), or null. */
	public final BlockPos storage;
	/** Land graded outside the template's area (walls), 0 for none. */
	public final int apron;
	/** The track's whole plot, {x0, z0, x1, z1} per level: levelled with every level, so upgrades find the room ready. */
	public final List<int[]> pads;
	/** Animals spawned once when the building is finished: entity id and position relative to the plot. */
	public final List<Animal> animals;

	public record Animal(Identifier type, BlockPos pos) {
	}

	/** Goods the storehouse shows as its stock grows: a block and the stage (1-8) from which it stands there. */
	public final List<Entry> fill;
	public final int[] fillStages;

	private Template(String id, JsonObject json) {
		this.id = id;
		this.seconds = json.get("seconds").getAsInt();
		JsonArray h = json.getAsJsonArray("heart");
		this.heart = new BlockPos(h.get(0).getAsInt(), h.get(1).getAsInt(), h.get(2).getAsInt());
		JsonArray size = json.getAsJsonArray("size");
		this.sizeX = size.get(0).getAsInt();
		this.sizeZ = size.get(2).getAsInt();
		JsonArray palette = json.getAsJsonArray("palette");
		List<String> names = new ArrayList<>();
		List<Map<String, String>> props = new ArrayList<>();
		for (JsonElement e : palette) {
			JsonObject o = e.getAsJsonObject();
			names.add(o.get("Name").getAsString());
			Map<String, String> p = new HashMap<>();
			if (o.has("Properties")) {
				o.getAsJsonObject("Properties").entrySet().forEach(kv -> p.put(kv.getKey(), kv.getValue().getAsString()));
			}
			props.add(p);
		}
		List<Entry> list = new ArrayList<>();
		for (JsonElement e : json.getAsJsonArray("blocks")) {
			JsonArray a = e.getAsJsonArray();
			int i = a.get(3).getAsInt();
			list.add(new Entry(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(), names.get(i), props.get(i), a.size() > 4 ? a.get(4).getAsInt() : -1,
					a.size() > 6 ? a.get(5).getAsInt() : a.get(0).getAsInt(), a.size() > 6 ? a.get(6).getAsInt() : a.get(2).getAsInt()));
		}
		this.entries = List.copyOf(list);
		List<Segment> segs = new ArrayList<>();
		JsonArray sg = json.getAsJsonArray("segments");
		if (sg != null) {
			for (JsonElement e : sg) {
				JsonObject o = e.getAsJsonObject();
				List<Map.Entry<String, Integer>> any = new ArrayList<>();
				for (JsonElement r : o.getAsJsonArray("any")) {
					any.add(Map.entry(r.getAsJsonArray().get(0).getAsString(), r.getAsJsonArray().get(1).getAsInt()));
				}
				segs.add(new Segment(o.get("name").getAsString(), List.copyOf(any)));
			}
		}
		this.segments = List.copyOf(segs);
		this.follow = json.has("follow") && json.get("follow").getAsBoolean();
		this.anchored = json.has("anchored") && json.get("anchored").getAsBoolean();
		this.apron = json.has("apron") ? json.get("apron").getAsInt() : 0;
		List<int[]> pads = new ArrayList<>();
		if (json.has("pads")) {
			for (JsonElement e : json.getAsJsonArray("pads")) {
				JsonArray a = e.getAsJsonArray();
				pads.add(new int[] {a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(), a.get(3).getAsInt()});
			}
		}
		this.pads = List.copyOf(pads);
		JsonArray st = json.getAsJsonArray("storage");
		this.storage = st == null ? null : new BlockPos(st.get(0).getAsInt(), st.get(1).getAsInt(), st.get(2).getAsInt());
		List<Animal> animals = new ArrayList<>();
		JsonArray an = json.getAsJsonArray("animals");
		if (an != null) {
			for (JsonElement e : an) {
				JsonArray a = e.getAsJsonArray();
				animals.add(new Animal(Identifier.withDefaultNamespace(a.get(0).getAsString()),
						new BlockPos(a.get(1).getAsInt(), a.get(2).getAsInt(), a.get(3).getAsInt())));
			}
		}
		this.animals = List.copyOf(animals);
		List<Entry> fill = new ArrayList<>();
		JsonArray fl = json.getAsJsonArray("fill");
		this.fillStages = new int[fl == null ? 0 : fl.size()];
		if (fl != null) {
			for (JsonElement e : fl) {
				JsonArray a = e.getAsJsonArray();
				int i = a.get(3).getAsInt();
				this.fillStages[fill.size()] = a.get(4).getAsInt();
				fill.add(new Entry(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(), names.get(i), props.get(i), -1,
						a.get(0).getAsInt(), a.get(2).getAsInt()));
			}
		}
		this.fill = List.copyOf(fill);
	}

	/** Loads "style/name" from the mod jar (kingdomquest/buildings/style/name.json); null if missing. */
	public static synchronized Template get(String id) {
		return CACHE.computeIfAbsent(id, key -> {
			try (InputStream in = Template.class.getResourceAsStream("/kingdomquest/buildings/" + key + ".json")) {
				if (in == null) {
					return null;
				}
				return new Template(key, JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
			} catch (Exception e) {
				KingdomQuest.LOG.error("Couldn't load building {}", key, e);
				return null;
			}
		});
	}

	/** All blocks for this kingdom color and rotation, bottom layer first. Unknown blocks are skipped (and logged). */
	public List<Placement> placements(String color, Rotation rotation) {
		List<Placement> out = new ArrayList<>();
		for (Entry e : this.entries) {
			BlockState state = state(e, color, null);
			if (state == null) {
				continue;
			}
			BlockPos rel = rotate(new BlockPos(e.x - this.heart.getX(), e.y - this.heart.getY(), e.z - this.heart.getZ()), rotation);
			BlockPos anchor = rotate(new BlockPos(e.ax() - this.heart.getX(), 0, e.az() - this.heart.getZ()), rotation);
			out.add(new Placement(rel, state.rotate(rotation), e.segment(), anchor));
		}
		return out;
	}

	/** The fill blocks (see {@link #fill}) ready to place, in the same order as {@link #fillStages}; unknown blocks are null. */
	public List<Placement> fillPlacements(Rotation rotation) {
		List<Placement> out = new ArrayList<>();
		for (Entry e : this.fill) {
			BlockState state = state(e, "white", null);
			BlockPos rel = rotate(new BlockPos(e.x - this.heart.getX(), e.y - this.heart.getY(), e.z - this.heart.getZ()), rotation);
			out.add(state == null ? null : new Placement(rel, state.rotate(rotation), -1, rel));
		}
		return out;
	}

	/** Offset of a plot position from the heart for this rotation. */
	public BlockPos offset(BlockPos plotPos, Rotation rotation) {
		return rotate(plotPos.subtract(this.heart), rotation);
	}

	/** The storage container's offset from the heart for this rotation, or null. */
	public BlockPos storageOffset(Rotation rotation) {
		return this.storage == null ? null : rotate(this.storage.subtract(this.heart), rotation);
	}

	/** Rotates an offset around the heart the same way {@link Rotation} turns directions. */
	public static BlockPos rotate(BlockPos p, Rotation r) {
		return switch (r) {
			case NONE -> p;
			case CLOCKWISE_90 -> new BlockPos(-p.getZ(), p.getY(), p.getX());
			case CLOCKWISE_180 -> new BlockPos(-p.getX(), p.getY(), -p.getZ());
			case COUNTERCLOCKWISE_90 -> new BlockPos(p.getZ(), p.getY(), -p.getX());
		};
	}

	/** Builds the block state; problems are added to {@code errors} (if given) or logged. */
	static BlockState state(Entry e, String color, List<String> errors) {
		String name = e.name.replace("{color}", color);
		if (!name.contains(":")) {
			name = "minecraft:" + name;
		}
		Identifier id = Identifier.tryParse(name);
		if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
			report(errors, "unknown block " + name);
			return null;
		}
		Block block = BuiltInRegistries.BLOCK.getValue(id);
		BlockState state = block.defaultBlockState();
		for (Map.Entry<String, String> kv : e.props.entrySet()) {
			Property<?> property = block.getStateDefinition().getProperty(kv.getKey());
			if (property == null) {
				report(errors, name + " has no property " + kv.getKey());
				continue;
			}
			BlockState next = with(state, property, kv.getValue());
			if (next == null) {
				report(errors, name + ": bad value " + kv.getKey() + "=" + kv.getValue());
				continue;
			}
			state = next;
		}
		return state;
	}

	private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> property, String value) {
		return property.getValue(value).map(v -> state.setValue(property, v)).orElse(null);
	}

	private static void report(List<String> errors, String message) {
		if (errors != null) {
			errors.add(message);
		} else {
			KingdomQuest.LOG.warn("Building block skipped: {}", message);
		}
	}

	/** Checks every block of every template; used by /kingdomquest selftest. */
	public static List<String> selfTest(List<String> ids) {
		List<String> errors = new ArrayList<>();
		for (String id : ids) {
			Template t = get(id);
			if (t == null) {
				errors.add("missing template " + id);
				continue;
			}
			for (Entry e : java.util.stream.Stream.concat(t.entries.stream(), t.fill.stream()).toList()) {
				int before = errors.size();
				state(e, "blue", errors);
				for (int i = before; i < errors.size(); i++) {
					errors.set(i, id + ": " + errors.get(i));
				}
			}
		}
		return errors;
	}
}
