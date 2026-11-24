package io.github.sotadevop.kingdomquest.building;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.sotadevop.kingdomquest.KingdomQuest;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The site plan of tools/buildings.py (kingdomquest/layout.json), the one source of truth for where things lie: the wall
 * ring, the gate, the wall towers, every track's plot and how far it moved from where its templates first drew it.
 *
 * Coordinates are in the "base frame" of tools/buildings.py: the heart at {@link #HX}, 0, {@link #HZ}, the gate in the
 * south. Positions written in the code for a track (worker posts, trophy stands, levers) are in that frame as the
 * building was first drawn, and {@link #at(BlockPos, Rotation, String, int, int, int)} moves them with the track.
 */
public final class Layout {
	public static final int HX;
	public static final int HZ;
	/** The wall ring in the base frame: x0, x1, z0, z1. */
	private static final int[] WALL;
	private static final int MOAT;
	private static final Map<String, int[]> MOVES = new HashMap<>();
	private static final Map<String, int[]> PLOTS = new HashMap<>();
	private static final Map<String, int[]> RESERVED = new HashMap<>();
	private static final Map<Integer, List<int[]>> TOWERS = new HashMap<>();
	private static final int[] KEEP;
	private static final List<int[]> STREETS = new ArrayList<>();
	private static final Map<String, List<int[]>> HOUSES = new HashMap<>();
	private static final int[] SQUARE;

	static {
		JsonObject j = new JsonObject();
		try (InputStream in = Layout.class.getResourceAsStream("/kingdomquest/layout.json")) {
			if (in != null) {
				j = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			}
		} catch (Exception e) {
			KingdomQuest.LOG.error("KingdomQuest: layout.json unreadable", e);
		}
		int[] heart = ints(j.getAsJsonArray("heart"), new int[] {7, 0, 10});
		HX = heart[0];
		HZ = heart[2];
		WALL = ints(j.getAsJsonArray("wall"), new int[] {-56, 76, -46, 50});
		MOAT = j.has("moat") ? j.get("moat").getAsInt() : 10;
		KEEP = ints(j.getAsJsonArray("keep"), new int[] {-4, -18, 18, 7});
		SQUARE = ints(j.getAsJsonArray("square"), new int[] {0, 8, 14, 16});
		readMap(j, "relocate", MOVES);
		readMap(j, "plots", PLOTS);
		readMap(j, "reserved", RESERVED);
		if (j.has("houses")) {
			for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("houses").entrySet()) {
				List<int[]> rs = new ArrayList<>();
				for (JsonElement r : e.getValue().getAsJsonArray()) {
					rs.add(ints(r.getAsJsonArray(), null));
				}
				HOUSES.put(e.getKey(), List.copyOf(rs));
			}
		}
		if (j.has("streets")) {
			for (JsonElement e : j.getAsJsonArray("streets")) {
				STREETS.add(ints(e.getAsJsonArray(), null));
			}
		}
		if (j.has("towers")) {
			for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("towers").entrySet()) {
				List<int[]> list = new ArrayList<>();
				for (JsonElement t : e.getValue().getAsJsonArray()) {
					list.add(ints(t.getAsJsonArray(), null));
				}
				TOWERS.put(Integer.parseInt(e.getKey()), List.copyOf(list));
			}
		}
	}

	private Layout() {
	}

	private static void readMap(JsonObject j, String key, Map<String, int[]> into) {
		if (j.has(key)) {
			for (Map.Entry<String, JsonElement> e : j.getAsJsonObject(key).entrySet()) {
				into.put(e.getKey(), ints(e.getValue().getAsJsonArray(), null));
			}
		}
	}

	private static int[] ints(JsonArray a, int[] fallback) {
		if (a == null) {
			return fallback;
		}
		int[] out = new int[a.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = a.get(i).getAsInt();
		}
		return out;
	}

	/** How far a track's plot moved from where its templates first drew it (dx, dz). */
	public static int[] move(String track) {
		return MOVES.getOrDefault(track, new int[] {0, 0});
	}

	/** A position of {@code track} in the base frame (as first drawn) in the world, for a heart and a rotation. */
	public static BlockPos at(BlockPos heart, Rotation r, String track, int bx, int by, int bz) {
		int[] m = move(track);
		return heart.offset(Template.rotate(new BlockPos(bx + m[0] - HX, by, bz + m[1] - HZ), r));
	}

	/** A position of the base frame (already where it lies in the big site) relative to the heart, rotated. */
	public static BlockPos rel(Rotation r, int bx, int by, int bz) {
		return Template.rotate(new BlockPos(bx - HX, by, bz - HZ), r);
	}

	/** The wall ring relative to the heart, unrotated: {x0, z0, x1, z1}. */
	public static int[] ring() {
		return new int[] {WALL[0] - HX, WALL[2] - HZ, WALL[1] - HX, WALL[3] - HZ};
	}

	/** The wall ring in the base frame: x0, x1, z0, z1. */
	public static int[] wall() {
		return WALL.clone();
	}

	/** The gate's middle in the base frame: x, z (on the south side of the ring). */
	public static int gateX() {
		return HX;
	}

	public static int gateZ() {
		return WALL[3];
	}

	/** How far the moat reaches out from the wall. */
	public static int moat() {
		return MOAT;
	}

	/** The wall towers (base frame x, z) of a wall level: the level-2 set below 4, the full set from 4. */
	public static List<int[]> towers(int wallLevel) {
		return TOWERS.getOrDefault(wallLevel >= 4 ? 4 : 2, List.of());
	}

	/** A track's plot in the base frame, all its levels together: x0, z0, x1, z1; null if it has none. */
	public static int[] plot(String track) {
		int[] p = PLOTS.get(track);
		return p == null ? null : p.clone();
	}

	public static Map<String, int[]> plots() {
		return Map.copyOf(PLOTS);
	}

	/** The town houses of the main track in a style: x0, z0, x1, z1 in the base frame. */
	public static List<int[]> houses(String style) {
		return HOUSES.getOrDefault(style, List.of());
	}

	/** Plots kept free for buildings of a later wave (the church): x0, z0, x1, z1 in the base frame. */
	public static Map<String, int[]> reserved() {
		return Map.copyOf(RESERVED);
	}

	/** The main building's own plot (castle, keep, motte), north of the square: x0, z0, x1, z1. */
	public static int[] keep() {
		return KEEP.clone();
	}

	/** The streets (not the lanes): x0, z0, x1, z1 and 0 for one running along z (north-south), 1 along x. */
	public static List<int[]> streets() {
		return List.copyOf(STREETS);
	}

	/** The town square: x0, z0, x1, z1. */
	public static int[] square() {
		return SQUARE.clone();
	}
}
