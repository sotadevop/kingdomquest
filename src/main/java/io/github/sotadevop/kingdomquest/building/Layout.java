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
	/** The tram loop (square, round): its cells in order, base frame. */
	private static final int[][][] TRAM_LOOP = new int[2][][];
	private static final String[][] TRAM_STOP_NAMES = new String[2][];
	private static final int[][] TRAM_STOP_AT = new int[2][];
	/** The greenery spots (square, round): x, z, kind (0 tree, 1 flowers, 2 bush), base frame. */
	private static final int[][][] GREEN = new int[2][][];
	public static final int HX;
	public static final int HZ;
	/** The wall ring in the base frame: x0, x1, z0, z1. */
	private static final int[] WALL;
	private static final int MOAT;
	private static final Map<String, int[]> MOVES = new HashMap<>();
	private static final Map<String, int[]> PLOTS = new HashMap<>();
	private static final Map<String, int[]> RESERVED = new HashMap<>();
	private static final Map<String, int[]> OUTSIDE = new HashMap<>();
	private static final Map<Integer, List<int[]>> TOWERS = new HashMap<>();
	private static final int[] KEEP;
	private static final List<int[]> STREETS = new ArrayList<>();
	private static final Map<String, List<int[]>> HOUSES = new HashMap<>();
	private static final int[] SQUARE;
	/** The round castle's ring box (x0, x1, z0, z1), ellipse (centre x, z, half axes), towers and streets. */
	private static final int[] ROUND_WALL;
	private static final int[] ELLIPSE;
	private static final Map<Integer, List<int[]>> ROUND_TOWERS = new HashMap<>();
	private static final List<int[]> ROUND_STREETS = new ArrayList<>();

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
		JsonObject round = j.has("round") ? j.getAsJsonObject("round") : new JsonObject();
		ROUND_WALL = ints(round.getAsJsonArray("wall"), WALL.clone());
		ELLIPSE = ints(round.getAsJsonArray("ellipse"), new int[] {7, 0, 154, 128});
		if (round.has("towers")) {
			for (Map.Entry<String, com.google.gson.JsonElement> e : round.getAsJsonObject("towers").entrySet()) {
				List<int[]> l = new ArrayList<>();
				e.getValue().getAsJsonArray().forEach(t -> l.add(ints(t.getAsJsonArray(), new int[2])));
				ROUND_TOWERS.put(Integer.parseInt(e.getKey()), l);
			}
		}
		readTram(j, 0);
		readTram(round, 1);
		GREEN[0] = readSpots(j.getAsJsonArray("green"));
		GREEN[1] = readSpots(round.getAsJsonArray("green"));
		if (round.has("streets")) {
			round.getAsJsonArray("streets").forEach(t -> ROUND_STREETS.add(ints(t.getAsJsonArray(), new int[5])));
		}
		readMap(j, "relocate", MOVES);
		readMap(j, "plots", PLOTS);
		readMap(j, "reserved", RESERVED);
		readMap(j, "outside", OUTSIDE);
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


	private static void readTram(JsonObject o, int shape) {
		if (!o.has("tram")) {
			TRAM_LOOP[shape] = new int[0][];
			TRAM_STOP_NAMES[shape] = new String[0];
			TRAM_STOP_AT[shape] = new int[0];
			return;
		}
		JsonObject t = o.getAsJsonObject("tram");
		JsonArray loop = t.getAsJsonArray("loop");
		TRAM_LOOP[shape] = new int[loop.size()][];
		for (int i = 0; i < loop.size(); i++) {
			TRAM_LOOP[shape][i] = ints(loop.get(i).getAsJsonArray(), null);
		}
		JsonArray stops = t.getAsJsonArray("stops");
		TRAM_STOP_NAMES[shape] = new String[stops.size()];
		TRAM_STOP_AT[shape] = new int[stops.size()];
		for (int i = 0; i < stops.size(); i++) {
			TRAM_STOP_NAMES[shape][i] = stops.get(i).getAsJsonArray().get(0).getAsString();
			TRAM_STOP_AT[shape][i] = stops.get(i).getAsJsonArray().get(1).getAsInt();
		}
	}

	private static int[][] readSpots(JsonArray a) {
		if (a == null) {
			return new int[0][];
		}
		int[][] out = new int[a.size()][];
		for (int i = 0; i < out.length; i++) {
			out[i] = ints(a.get(i).getAsJsonArray(), null);
		}
		return out;
	}

	public static int[][] green(boolean round) {
		return GREEN[round ? 1 : 0];
	}

	public static int[][] tramLoop(boolean round) {
		return TRAM_LOOP[round ? 1 : 0];
	}

	public static String[] tramStops(boolean round) {
		return TRAM_STOP_NAMES[round ? 1 : 0];
	}

	public static int[] tramStopAt(boolean round) {
		return TRAM_STOP_AT[round ? 1 : 0];
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

	/** The wall ring of a square or round castle relative to the heart, unrotated: {x0, z0, x1, z1} (its box). */
	public static int[] ring(boolean round) {
		int[] w = round ? ROUND_WALL : WALL;
		return new int[] {w[0] - HX, w[2] - HZ, w[1] - HX, w[3] - HZ};
	}

	/** The wall ring in the base frame: x0, x1, z0, z1. */
	public static int[] wall() {
		return WALL.clone();
	}

	/** The wall ring (its box for a round castle) in the base frame: x0, x1, z0, z1. */
	public static int[] wall(boolean round) {
		return (round ? ROUND_WALL : WALL).clone();
	}

	/** The round castle's ellipse in the base frame: centre x, z, half axes along x and z. */
	public static int[] ellipse() {
		return ELLIPSE.clone();
	}

	/**
	 * How far inside the wall line a base-frame column lies (negative: outside): for the square castle the distance
	 * to the nearest side, for the round one along the ray from the centre.
	 */
	public static double inside(boolean round, double bx, double bz) {
		if (!round) {
			return Math.min(Math.min(bx - WALL[0], WALL[1] - bx), Math.min(bz - WALL[2], WALL[3] - bz));
		}
		double dx = bx - ELLIPSE[0], dz = bz - ELLIPSE[1];
		double r = Math.sqrt(dx * dx + dz * dz);
		if (r < 1e-6) {
			return Math.min(ELLIPSE[2], ELLIPSE[3]);
		}
		double f = Math.sqrt(dx * dx / ((double) ELLIPSE[2] * ELLIPSE[2]) + dz * dz / ((double) ELLIPSE[3] * ELLIPSE[3]));
		return r / f - r;
	}

	/** The gate's middle in the base frame: x, z (on the south side of the ring). */
	public static int gateX() {
		return HX;
	}

	public static int gateZ() {
		return WALL[3];
	}

	public static int gateZ(boolean round) {
		return (round ? ROUND_WALL : WALL)[3];
	}

	/** How far the moat reaches out from the wall. */
	public static int moat() {
		return MOAT;
	}

	/** The wall towers (base frame x, z) of a wall level: the level-2 set below 4, the full set from 4. */
	public static List<int[]> towers(int wallLevel) {
		return TOWERS.getOrDefault(wallLevel >= 4 ? 4 : 2, List.of());
	}

	public static List<int[]> towers(int wallLevel, boolean round) {
		return (round ? ROUND_TOWERS : TOWERS).getOrDefault(wallLevel >= 4 ? 4 : 2, List.of());
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

	/** Places outside the walls levelled with the plateau (the tournament field): x0, z0, x1, z1 in the base frame. */
	public static Map<String, int[]> outside() {
		return Map.copyOf(OUTSIDE);
	}

	/** The main building's own plot (castle, keep, motte), north of the square: x0, z0, x1, z1. */
	public static int[] keep() {
		return KEEP.clone();
	}

	/** The streets (not the lanes): x0, z0, x1, z1 and 0 for one running along z (north-south), 1 along x. */
	public static List<int[]> streets() {
		return List.copyOf(STREETS);
	}

	public static List<int[]> streets(boolean round) {
		return List.copyOf(round ? ROUND_STREETS : STREETS);
	}

	/** The town square: x0, z0, x1, z1. */
	public static int[] square() {
		return SQUARE.clone();
	}
}
