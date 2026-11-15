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
	public record Entry(int x, int y, int z, String name, Map<String, String> props) {
	}

	/** A block ready to place: position relative to the heart (already rotated) and its state. */
	public record Placement(BlockPos offset, BlockState state) {
	}

	private static final Map<String, Template> CACHE = new HashMap<>();

	public final String id;
	public final int seconds;
	public final BlockPos heart;
	public final int sizeX;
	public final int sizeZ;
	public final List<Entry> entries;
	/** Container the add-on fills with its production (relative to the plot), or null. */
	public final BlockPos storage;

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
			list.add(new Entry(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(), names.get(i), props.get(i)));
		}
		this.entries = List.copyOf(list);
		JsonArray st = json.getAsJsonArray("storage");
		this.storage = st == null ? null : new BlockPos(st.get(0).getAsInt(), st.get(1).getAsInt(), st.get(2).getAsInt());
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
			out.add(new Placement(rel, state.rotate(rotation)));
		}
		return out;
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
			for (Entry e : t.entries) {
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
