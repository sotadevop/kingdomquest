package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.building.Template;
import io.github.sotadevop.kingdomquest.npc.KingdomNpc;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What stands along the country roads between kingdoms and villages (design 19.6, 19.7, 19.11): the waystations
 * halfway (one every ~500 blocks on very long roads) and the road's extras, all growing with the kingdom's road level:
 * <ul>
 * <li>level 1: a camp at the waystation; signposts at crossroads; border stones where the realm begins</li>
 * <li>level 2: the waystation gets an inn with a bed, a stable with horses, a well and a horse trough; milestones
 * every ~100 blocks; wayside shrines at crossroads</li>
 * <li>level 3: a guarded station with a wall, a watchtower (a signal fire) and an arch over the road; gate towers on
 * big river bridges, covered wooden bridges in the north and the highlands ({@link RoadLink}), watchtowers on hills
 * along long roads</li>
 * </ul>
 * A waystation's place is chosen when its road is first laid (dry, open land, the station in the region's style) and
 * kept from then on: a road laid again is led through it, straight under its arch. Signs name the real places with
 * their distance along the road and follow renames. Everything is kept on the settlement the road starts from
 * ({@code names}): "ws|key|n" = "x,y,z|rotation|style|level|dir" (dir +1 when the template's +z leads to the road's
 * end), "road_path|key" = "length|x,y,z;..." (every {@link #SAMPLE}th cell), "road_bridges|key", "road_x|key" (extras
 * placed), "sign|x,y,z" (what a sign shows) and "junction|x,z".
 */
final class Roadside {
	static final String STATION = "ws|";
	static final String PATH = "road_path|";
	static final String BRIDGES = "road_bridges|";
	static final String EXTRAS = "road_x|";
	static final String SIGN = "sign|";
	static final String JUNCTION = "junction|";
	/** The road's straight stretch through a station (tools/roadside.py STRAIGHT). */
	static final int STRAIGHT = 14;
	/** Every how many road cells the stored path keeps one. */
	static final int SAMPLE = 4;
	/** No station, milestone or crossroads closer than this to a kingdom's heart (its walls, moat and fields). */
	static final int CLEAR = 128;
	/** The station's land in its template frame: x0, z0, x1, z1 (the road at x -1..1), and the slope around it. */
	private static final int[] SITE = {-7, -16, 24, 16};
	private static final int APRON = 14;
	private static final int NO_DROPS = Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS;
	/** Where the station's guards stand (level 3), template frame (tools/roadside.py WAYSTATION_GUARDS). */
	private static final BlockPos[] GUARD_POSTS = {new BlockPos(2, 0, 8), new BlockPos(21, 0, 1)};
	static final String GUARD = "way_guard";

	/** A waystation as stored. */
	record Station(Settlement owner, String key, int n, BlockPos origin, Rotation rot, String style, int level, int dir) {
		String id() {
			return STATION + this.key + "|" + this.n;
		}

		String tag() {
			return GUARD + "|" + this.owner.id() + "|" + this.key + "|" + this.n;
		}
	}

	private Roadside() {
	}

	static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 1200 == 300) {
				upkeep(server);
			}
		});
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
				!(level instanceof ServerLevel sl) || player.isCreative() || sl.dimension() != Level.OVERWORLD || !breakRoadsideBlock(sl, pos));
	}

	/** Roads that get stations and extras: to another kingdom or to a village (not the dock, not test roads). */
	static boolean regular(String key) {
		if (key.startsWith("v:")) {
			return true;
		}
		try {
			UUID.fromString(key);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	static int roadLevel(Settlement s) {
		return Math.max(1, Math.min(3, s.level(Progression.ROADS)));
	}

	/** A road to another kingdom or a village was planned ({@link Fealty#roadPlanned}, the menu): the lord hears of its stop. */
	static void planned(MinecraftServer server, Settlement from, String key, BlockPos start, BlockPos end) {
		ServerPlayer p = server.getPlayerList().getPlayer(from.owner());
		if (p != null && regular(key) && Math.sqrt(start.distSqr(end)) > 2 * CLEAR) {
			People.say(p, "Architect", "Halfway along it we'll set up a waystation: a camp now, an inn and a stable and later a guarded station, as our roads grow.");
		}
	}

	// ---- data ----

	private static Settlement byId(MinecraftServer server, String id) {
		for (Settlement s : Settlements.all(server)) {
			if (s.id().toString().equals(id)) {
				return s;
			}
		}
		return null;
	}

	/** Stores a value on the current state of the settlement. */
	private static void put(MinecraftServer server, UUID id, String key, String value) {
		Settlement s = byId(server, id.toString());
		if (s != null) {
			Settlements.putState(server, s.withName(key, value));
		}
	}

	private static BlockPos pos(String s) {
		String[] p = s.split(",");
		return new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
	}

	private static String str(BlockPos p) {
		return p.getX() + "," + p.getY() + "," + p.getZ();
	}

	static List<Station> stations(Settlement s) {
		List<Station> out = new ArrayList<>();
		for (Map.Entry<String, String> e : s.names().entrySet()) {
			if (e.getKey().startsWith(STATION)) {
				String rest = e.getKey().substring(STATION.length());
				int bar = rest.lastIndexOf('|');
				String[] v = e.getValue().split("\\|");
				out.add(new Station(s, rest.substring(0, bar), Integer.parseInt(rest.substring(bar + 1)), pos(v[0]), Rotation.values()[Integer.parseInt(v[1])], v[2],
						Integer.parseInt(v[3]), v.length > 4 ? Integer.parseInt(v[4]) : 1));
			}
		}
		return out;
	}

	static List<Station> stations(MinecraftServer server) {
		List<Station> out = new ArrayList<>();
		Settlements.all(server).forEach(s -> out.addAll(stations(s)));
		return out;
	}

	private static String value(Station st) {
		return str(st.origin()) + "|" + st.rot().ordinal() + "|" + st.style() + "|" + st.level() + "|" + st.dir();
	}

	/** The stored path of a road: its cells every {@link #SAMPLE}, with the road's height. */
	static List<BlockPos> path(Settlement s, String key) {
		List<BlockPos> out = new ArrayList<>();
		String v = s.names().get(PATH + key);
		if (v != null && v.contains("|")) {
			for (String c : v.substring(v.indexOf('|') + 1).split(";")) {
				if (!c.isEmpty()) {
					out.add(pos(c));
				}
			}
		}
		return out;
	}

	/** The road's length in blocks (cells), 0 if it has no stored path. */
	static int length(Settlement s, String key) {
		String v = s.names().get(PATH + key);
		return v == null || !v.contains("|") ? 0 : Integer.parseInt(v.substring(0, v.indexOf('|')));
	}

	/** The name of the place at the end of a road, or of a sign's destination ("s:id", "v:x,z", "n:name"). */
	static String placeName(MinecraftServer server, String ref) {
		if (ref.startsWith("s:")) {
			Settlement s = byId(server, ref.substring(2));
			return s == null ? "?" : s.name();
		}
		if (ref.startsWith("v:")) {
			String[] p = ref.substring(2).split(",");
			int x = Integer.parseInt(p[0]), z = Integer.parseInt(p[1]);
			String best = null;
			double bestD = 96 * 96;
			for (Settlement s : Settlements.all(server)) {
				for (Map.Entry<String, String> e : s.names().entrySet()) {
					if (e.getKey().startsWith("village|")) {
						String[] q = e.getKey().substring(8).split(",");
						double d = Math.pow(Integer.parseInt(q[0]) - x, 2) + Math.pow(Integer.parseInt(q[1]) - z, 2);
						if (d <= bestD) {
							bestD = d;
							best = e.getValue();
						}
					}
				}
			}
			return best != null ? best : "Village";
		}
		return ref.startsWith("n:") ? ref.substring(2) : ref;
	}

	/** The destination ref of a road key: another kingdom ("s:id") or a village ("v:x,z"). */
	/** Where a sign's destination lies ("s:id" its heart, "v:x,z" the village); {@code fallback} if unknown. */
	static BlockPos placePos(MinecraftServer server, String ref, BlockPos fallback) {
		if (ref.startsWith("s:")) {
			Settlement s = byId(server, ref.substring(2));
			return s == null ? fallback : s.heart();
		}
		if (ref.startsWith("v:")) {
			String[] p = ref.substring(2).split(",");
			return new BlockPos(Integer.parseInt(p[0]), fallback.getY(), Integer.parseInt(p[1]));
		}
		return fallback;
	}

	static String targetRef(String key) {
		return key.startsWith("v:") ? key : "s:" + key;
	}

	// ---- choosing and keeping the stations ----

	/** World direction of a template-frame offset. */
	private static BlockPos turn(int x, int y, int z, Rotation r) {
		return Template.rotate(new BlockPos(x, y, z), r);
	}

	private static Rotation inverse(Rotation r) {
		return r == Rotation.CLOCKWISE_90 ? Rotation.COUNTERCLOCKWISE_90 : r == Rotation.COUNTERCLOCKWISE_90 ? Rotation.CLOCKWISE_90 : r;
	}

	/** The natural ground top of a column (through plants, trees and snow layers) and whether water lies on it. */
	private static int[] ground(ServerLevel level, int x, int z) {
		level.getChunk(x >> 4, z >> 4); // the heightmap of an unloaded chunk reads as the bottom of the world
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, y, z);
		while (p.getY() > level.getMinY()) {
			BlockState st = level.getBlockState(p);
			if (st.getBlock() instanceof LiquidBlock) {
				return new int[] {p.getY(), 1};
			}
			if (!st.isAir() && st.isSolid() && !st.is(BlockTags.LOGS) && !st.is(BlockTags.LEAVES) && !st.canBeReplaced()) {
				return new int[] {p.getY(), 0};
			}
			p.move(0, -1, 0);
		}
		return new int[] {y, 0};
	}

	/**
	 * Before a road is laid: its stations are found (or the kept ones read) and the line is led straight through each
	 * of them. A new station's land is shaped at once, so the road is laid on it level.
	 */
	static List<BlockPos> route(ServerLevel level, String settlementId, String key, List<BlockPos> line) {
		MinecraftServer server = level.getServer();
		Settlement s = byId(server, settlementId);
		if (s == null || !regular(key) || line.size() < 40) {
			return line;
		}
		List<Station> mine = stations(s).stream().filter(st -> st.key().equals(key)).toList();
		if (mine.isEmpty()) {
			int len = line.size();
			int count = len > 1000 ? 2 : 1;
			List<Station> made = new ArrayList<>();
			for (int k = 1; k <= count; k++) {
				Station st = choose(level, s, key, k, line, len * k / (count + 1), made);
				if (st != null) {
					made.add(st);
					put(server, s.id(), st.id(), value(st));
					grade(level, st);
					io.github.sotadevop.kingdomquest.KingdomQuest.LOG.info("Waystation for road {} of {} at {} ({} of the {}-block road)", key, s.name(), st.origin(), len * k / (count + 1), len);
				}
			}
			mine = made;
		}
		List<BlockPos> out = line;
		for (Station st : mine) {
			out = through(out, st);
		}
		return out;
	}

	/** The best place for a station near index {@code at} of the line, or null if the land there has none. */
	private static Station choose(ServerLevel level, Settlement s, String key, int n, List<BlockPos> line, int at, List<Station> made) {
		MinecraftServer server = level.getServer();
		int len = line.size();
		List<BlockPos> others = new ArrayList<>();
		stations(server).forEach(st -> others.add(st.origin()));
		made.forEach(st -> others.add(st.origin()));
		Station best = null;
		double bestScore = Double.MAX_VALUE;
		for (int d = 0; d <= len / 5; d += 6) {
			for (int m : new int[] {at - d, at + d}) {
				if (m < 30 || m >= len - 30 || d == 0 && m != at) {
					continue;
				}
				BlockPos o = line.get(m);
				if (Settlements.all(server).stream().anyMatch(o2 -> horizontal(o2.heart(), o) < CLEAR)
						|| others.stream().anyMatch(p -> horizontal(p, o) < 200)) {
					continue;
				}
				BlockPos a = line.get(Math.max(0, m - 10)), b = line.get(Math.min(len - 1, m + 10));
				boolean alongX = Math.abs(b.getX() - a.getX()) > Math.abs(b.getZ() - a.getZ());
				boolean wetNear = false;
				for (int k = Math.max(0, m - 44); k < Math.min(len, m + 44) && !wetNear; k += 2) {
					wetNear = ground(level, line.get(k).getX(), line.get(k).getZ())[1] == 1;
				}
				if (wetNear) {
					continue;
				}
				for (Rotation r : alongX ? new Rotation[] {Rotation.CLOCKWISE_90, Rotation.COUNTERCLOCKWISE_90} : new Rotation[] {Rotation.NONE, Rotation.CLOCKWISE_180}) {
					int[] cost = siteCost(level, o, r);
					if (cost == null) {
						continue;
					}
					double score = cost[0] + 4.0 * d;
					if (score < bestScore) {
						bestScore = score;
						BlockPos origin = new BlockPos(o.getX(), cost[1] + 1, o.getZ());
						best = new Station(s, key, n, origin, r, Style.forBiome(level.getBiome(origin).getRegisteredName()), 0, 1);
					}
				}
			}
			if (best != null && d >= 24) {
				break;
			}
		}
		return best;
	}

	private static double horizontal(BlockPos a, BlockPos b) {
		return Math.sqrt(Math.pow(a.getX() - b.getX(), 2) + Math.pow(a.getZ() - b.getZ(), 2));
	}

	/**
	 * Earth to move for a station at {@code o} turned {@code r}: {cost, ground height}; null where it can't stand
	 * (water, a village, or land too steep).
	 */
	private static int[] siteCost(ServerLevel level, BlockPos o, Rotation r) {
		List<Integer> hs = new ArrayList<>();
		for (int x = SITE[0]; x <= SITE[2]; x += 2) {
			for (int z = SITE[1]; z <= SITE[3]; z += 2) {
				BlockPos w = o.offset(turn(x, 0, z, r));
				int[] g = ground(level, w.getX(), w.getZ());
				if (g[1] == 1) {
					return null;
				}
				if ((x % 8 == 0 && z % 8 == 0) && Fealty.villageAt(level, new BlockPos(w.getX(), g[0] + 1, w.getZ())) != null) {
					return null;
				}
				hs.add(g[0]);
			}
		}
		List<Integer> sorted = new ArrayList<>(hs);
		java.util.Collections.sort(sorted);
		int y = sorted.get(sorted.size() / 2);
		int cost = 0, worst = 0;
		for (int h : hs) {
			cost += Math.abs(h - y);
			worst = Math.max(worst, Math.abs(h - y));
		}
		return worst > 12 ? null : new int[] {cost, y};
	}

	/** The line led straight through a station along its axis, with short diagonal joins to the old line. */
	private static List<BlockPos> through(List<BlockPos> line, Station st) {
		int n = line.size();
		int m = 0;
		for (int k = 1; k < n; k++) {
			if (horizontal(line.get(k), st.origin()) < horizontal(line.get(m), st.origin())) {
				m = k;
			}
		}
		if (horizontal(line.get(m), st.origin()) > 40) {
			return line; // the road runs elsewhere now: the station stays where it was built
		}
		BlockPos axis = turn(0, 0, 1, st.rot());
		BlockPos o = new BlockPos(st.origin().getX(), line.get(m).getY(), st.origin().getZ());
		BlockPos p1 = o.offset(axis.getX() * -STRAIGHT, 0, axis.getZ() * -STRAIGHT), p2 = o.offset(axis.getX() * STRAIGHT, 0, axis.getZ() * STRAIGHT);
		int ia = Math.max(0, m - 36), ib = Math.min(n - 1, m + 36);
		if (horizontal(line.get(ia), p2) < horizontal(line.get(ia), p1)) {
			BlockPos t = p1;
			p1 = p2;
			p2 = t;
		}
		List<BlockPos> out = new ArrayList<>(line.subList(0, ia + 1));
		join(out, p1);
		join(out, p2);
		join(out, line.get(ib));
		out.addAll(line.subList(ib + 1, n));
		return out;
	}

	/** Cells from the last cell of {@code out} to {@code to} (one step along the longer axis each). */
	private static void join(List<BlockPos> out, BlockPos to) {
		BlockPos a = out.get(out.size() - 1);
		int dx = to.getX() - a.getX(), dz = to.getZ() - a.getZ();
		int steps = Math.max(Math.abs(dx), Math.abs(dz));
		for (int i = 1; i <= steps; i++) {
			out.add(new BlockPos(a.getX() + Math.round((float) dx * i / steps), a.getY(), a.getZ() + Math.round((float) dz * i / steps)));
		}
	}

	/**
	 * The station's land: level at its ground height over the whole site, then a natural slope of a block per block
	 * (with ragged edges) back to the land around; trees on the site go whole, cut ground gets its turf back, new
	 * ground the region's earth and a little grass. Anything built is left alone.
	 */
	static void grade(ServerLevel level, Station st) {
		int y0 = st.origin().getY() - 1;
		BlockState surface = SiteWork.surface(level, st.origin());
		BlockState fill = SiteWork.subsoil(surface);
		Rotation inv = inverse(st.rot());
		BlockPos c1 = st.origin().offset(turn(SITE[0] - APRON, 0, SITE[1] - APRON, st.rot()));
		BlockPos c2 = st.origin().offset(turn(SITE[2] + APRON, 0, SITE[3] + APRON, st.rot()));
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int x = Math.min(c1.getX(), c2.getX()); x <= Math.max(c1.getX(), c2.getX()); x++) {
			for (int z = Math.min(c1.getZ(), c2.getZ()); z <= Math.max(c1.getZ(), c2.getZ()); z++) {
				BlockPos local = Template.rotate(new BlockPos(x - st.origin().getX(), 0, z - st.origin().getZ()), inv);
				int d = Math.max(Math.max(SITE[0] - local.getX(), local.getX() - SITE[2]), Math.max(SITE[1] - local.getZ(), local.getZ() - SITE[3]));
				d = Math.max(0, d);
				if (d > APRON) {
					continue;
				}
				int[] g = ground(level, x, z);
				if (g[1] == 1 && d > 0) {
					continue;
				}
				int slack = d == 0 ? 0 : d + Math.floorMod(x * 31 + z * 17, 3) - 1;
				int target = g[0] > y0 + slack ? y0 + Math.max(0, slack) : g[0] < y0 - slack ? y0 - Math.max(0, slack) : g[0];
				if (d == 0) {
					clearAbove(level, x, Math.min(target, g[0]), z, y0 + 16);
				}
				if (target < g[0]) {
					for (int y = g[0]; y > target; y--) {
						BlockState was = level.getBlockState(p.set(x, y, z));
						if (!(SiteWork.natural(was) || was.canBeReplaced())) {
							break;
						}
						level.setBlock(p, Blocks.AIR.defaultBlockState(), NO_DROPS);
					}
					clearAbove(level, x, target, z, g[0] + 12);
					if (SiteWork.natural(level.getBlockState(p.set(x, target, z)))) {
						level.setBlock(p, surface, Block.UPDATE_CLIENTS);
					}
				} else if (target > g[0]) {
					for (int y = g[0] + 1; y <= target; y++) {
						BlockState was = level.getBlockState(p.set(x, y, z));
						if (!(was.isAir() || was.canBeReplaced() || was.getBlock() instanceof LiquidBlock)) {
							break;
						}
						level.setBlock(p, y == target ? surface : fill, Block.UPDATE_CLIENTS);
					}
					BlockState under = level.getBlockState(p.set(x, g[0], z));
					if (SiteWork.isTopSoil(under)) {
						level.setBlock(p, fill, Block.UPDATE_CLIENTS); // buried turf becomes earth
					}
				}
				// a little grass on the new and the cut ground outside the yard
				if (d >= 2 && target != g[0] && surface.is(Blocks.GRASS_BLOCK) && Math.floorMod(x * 13 + z * 7, 5) == 0
						&& level.getBlockState(p.set(x, target + 1, z)).isAir()) {
					level.setBlock(p, Blocks.SHORT_GRASS.defaultBlockState(), Block.UPDATE_CLIENTS);
				}
			}
		}
	}

	/** Trees, plants and loose natural blocks over a column of the site go (trees whole). */
	private static void clearAbove(ServerLevel level, int x, int top, int z, int upTo) {
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int y = top + 1; y <= upTo; y++) {
			BlockState st = level.getBlockState(p.set(x, y, z));
			if (st.isAir()) {
				continue;
			}
			if (st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES)) {
				SiteWork.removeTreeAt(level, p.immutable(), Set.of());
			} else if (st.getFluidState().isEmpty() && (st.canBeReplaced() || SiteWork.natural(st))) {
				level.setBlock(p, Blocks.AIR.defaultBlockState(), NO_DROPS);
			}
		}
	}

	// ---- after a road is built ----

	/** A road was finished: its path is kept, its stations are built or raised to the road level, the extras placed. */
	static void roadBuilt(MinecraftServer server, String settlementId, String key, List<BlockPos> line, int[] height, boolean[] water) {
		Settlement s = byId(server, settlementId);
		if (s == null || !regular(key)) {
			return;
		}
		StringBuilder enc = new StringBuilder().append(line.size()).append('|');
		for (int k = 0; k < line.size(); k += SAMPLE) {
			BlockPos p = line.get(k);
			enc.append(k == 0 ? "" : ";").append(p.getX()).append(',').append(height[k] / 2 - 1).append(',').append(p.getZ());
		}
		StringBuilder bridges = new StringBuilder();
		ServerLevel level = server.overworld();
		for (int k = 0; k < line.size(); k++) {
			if (!water[k]) {
				continue;
			}
			int j = k;
			int wet = 0;
			while (j + 1 < line.size() && water[j + 1]) {
				j++;
			}
			for (int q = k; q <= j; q++) {
				wet += ground(level, line.get(q).getX(), line.get(q).getZ())[1];
			}
			if (j > k) {
				bridges.append(bridges.isEmpty() ? "" : ";").append(str(line.get(k).atY(height[k] / 2 - 1))).append(',').append(str(line.get(j).atY(height[j] / 2 - 1)))
						.append(',').append(wet);
			}
			k = j;
		}
		Settlements.putState(server, s.withName(PATH + key, enc.toString()).withName(BRIDGES + key, bridges.toString()));
		// which way the road runs through each station (for its signs)
		for (Station st : stations(byId(server, settlementId))) {
			if (!st.key().equals(key)) {
				continue;
			}
			int m = 0;
			for (int k = 1; k < line.size(); k++) {
				if (horizontal(line.get(k), st.origin()) < horizontal(line.get(m), st.origin())) {
					m = k;
				}
			}
			BlockPos a = line.get(Math.max(0, m - 4)), b = line.get(Math.min(line.size() - 1, m + 4));
			BlockPos axis = turn(0, 0, 1, st.rot());
			int dir = (b.getX() - a.getX()) * axis.getX() + (b.getZ() - a.getZ()) * axis.getZ() >= 0 ? 1 : -1;
			put(server, s.id(), st.id(), value(new Station(st.owner(), st.key(), st.n(), st.origin(), st.rot(), st.style(), st.level(), dir)));
		}
		update(server, byId(server, settlementId));
	}

	/** The kingdom's road level changed (or a road was finished): stations and extras follow it. */
	static void update(MinecraftServer server, Settlement s) {
		if (s == null) {
			return;
		}
		int lvl = roadLevel(s);
		for (Station st : stations(s)) {
			if (st.level() != lvl) {
				build(server, st, lvl);
			}
		}
		for (String key : new ArrayList<>(byId(server, s.id().toString()).names().keySet())) {
			if (key.startsWith(PATH)) {
				dress(server, s.id().toString(), key.substring(PATH.length()));
			}
		}
		junctions(server, s.owner());
	}

	/** Builds (or raises, or lowers) a station to {@code lvl}: blocks of its old level that the new one hasn't go. */
	static void build(MinecraftServer server, Station st, int lvl) {
		ServerLevel level = server.overworld();
		Settlement s = byId(server, st.owner().id().toString());
		Template t = Template.get(st.style() + "/waystation_" + lvl);
		if (s == null || t == null) {
			return;
		}
		List<Template.Placement> now = t.placements(Settlements.dye(s.color()), st.rot());
		if (st.level() > 0) {
			Template old = Template.get(st.style() + "/waystation_" + st.level());
			Set<Long> keep = new HashSet<>();
			now.forEach(p -> keep.add(st.origin().offset(p.offset()).asLong()));
			if (old != null) {
				for (Template.Placement p : old.placements(Settlements.dye(s.color()), st.rot())) {
					BlockPos at = st.origin().offset(p.offset());
					if (!keep.contains(at.asLong()) && level.getBlockState(at).getBlock() == p.state().getBlock()) {
						level.setBlock(at, Blocks.AIR.defaultBlockState(), NO_DROPS);
					}
				}
			}
		}
		Set<Long> placed = place(level, now, st.origin(), SiteWork.surface(level, st.origin()));
		if (lvl >= 2 && st.level() < 2) {
			for (Template.Animal a : t.animals) {
				spawnAnimal(level, st, a);
			}
		}
		Station done = new Station(s, st.key(), st.n(), st.origin(), st.rot(), st.style(), lvl, st.dir());
		put(server, s.id(), st.id(), value(done));
		// the signpost's two signs: each shows the place the reader is heading for
		for (Template.Placement p : now) {
			if (p.state().getBlock() instanceof WallSignBlock) {
				BlockPos at = st.origin().offset(p.offset());
				int idx = stationIndex(server, done);
				int len = length(byId(server, s.id().toString()), st.key());
				BlockPos fwd = turn(0, 0, st.dir(), st.rot());
				int toTarget = len - idx > 0 ? len - idx : (int) horizontal(st.origin(), placePos(server, targetRef(st.key()), st.origin()));
				List<Dest> dests = List.of(new Dest("s:" + s.id(), Math.max(0, idx), -fwd.getX(), -fwd.getZ()),
						new Dest(targetRef(st.key()), toTarget, fwd.getX(), fwd.getZ()));
				Direction facing = p.state().getValue(WallSignBlock.FACING);
				List<Dest> ahead = dests.stream().filter(d -> !behind(facing, d)).toList();
				sign(server, s.id(), at, ahead.isEmpty() ? dests : ahead); // a sign across the road names both ways
			}
		}
		// a rebuilt station may have moved or re-placed its signs: forget the gone ones, write the text on the rest
		Settlement cur = byId(server, s.id().toString());
		if (cur != null) {
			for (Map.Entry<String, String> e : List.copyOf(cur.names().entrySet())) {
				if (!e.getKey().startsWith(SIGN) || horizontal(pos(e.getKey().substring(SIGN.length())), st.origin()) > 16) {
					continue;
				}
				BlockPos sp = pos(e.getKey().substring(SIGN.length()));
				if (level.getBlockState(sp).getBlock() instanceof SignBlock) {
					write(server, sp, e.getValue());
				} else {
					put(server, s.id(), e.getKey(), null);
				}
			}
		}
		if (st.style().equals(Style.FROZEN)) {
			BlockPos a = st.origin().offset(turn(SITE[0], 0, SITE[1], st.rot())), b = st.origin().offset(turn(SITE[2], 0, SITE[3], st.rot()));
			SiteWork.snowCover(level, Math.min(a.getX(), b.getX()), Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()), Math.max(a.getZ(), b.getZ()), placed);
		}
		ServerPlayer owner = server.getPlayerList().getPlayer(s.owner());
		if (owner != null) {
			String what = lvl == 1 ? "a camp with a fire and a signpost" : lvl == 2 ? "an inn with a bed for the night, a stable and a well"
					: "a guarded station with a watchtower and an arch over the road";
			People.say(owner, "Architect", "The waystation on the road to " + placeName(server, targetRef(st.key())) + " is now " + what + " (at "
					+ st.origin().getX() + ", " + st.origin().getZ() + ").");
		}
		upkeep(server);
	}

	/** Where along its road a station stands (cells from the road's start). */
	private static int stationIndex(MinecraftServer server, Station st) {
		Settlement s = byId(server, st.owner().id().toString());
		List<BlockPos> path = path(s, st.key());
		int m = 0;
		for (int k = 1; k < path.size(); k++) {
			if (horizontal(path.get(k), st.origin()) < horizontal(path.get(m), st.origin())) {
				m = k;
			}
		}
		if (path.isEmpty() || horizontal(path.get(m), st.origin()) > 16) {
			// no stored path near the station: the straight distance home
			return (int) horizontal(st.origin(), s.heart());
		}
		return m * SAMPLE;
	}

	/**
	 * Places template blocks (solid ones first, then what hangs on them) at {@code origin}; under the lowest block of
	 * every column the ground is carried up in earth or stone so nothing floats. Returns the positions placed.
	 */
	static Set<Long> place(ServerLevel level, List<Template.Placement> list, BlockPos origin, BlockState surface) {
		List<Template.Placement> sorted = new ArrayList<>(list);
		sorted.sort(java.util.Comparator.comparingInt((Template.Placement p) -> hangs(p.state()) ? 1 : 0));
		Set<Long> placed = new HashSet<>();
		Map<Long, Integer> low = new HashMap<>();
		for (Template.Placement p : sorted) {
			BlockPos at = origin.offset(p.offset());
			BlockState now = level.getBlockState(at);
			if (now.is(BlockTags.LOGS) || now.is(BlockTags.LEAVES)) {
				SiteWork.removeTreeAt(level, at, placed);
			}
			BlockState want = p.state().getBlock() instanceof CrossCollisionBlock || p.state().getBlock() instanceof WallBlock
					? Block.updateFromNeighbourShapes(p.state(), level, at) : p.state();
			level.setBlock(at, want, NO_DROPS);
			placed.add(at.asLong());
			if (!want.isAir() && p.offset().getY() <= 0) {
				low.merge(BlockPos.asLong(at.getX(), 0, at.getZ()), at.getY(), Math::min);
			}
		}
		BlockState fill = SiteWork.subsoil(surface);
		BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
		for (Map.Entry<Long, Integer> e : low.entrySet()) {
			int x = BlockPos.getX(e.getKey()), z = BlockPos.getZ(e.getKey());
			BlockState foot = level.getBlockState(q.set(x, e.getValue(), z));
			boolean masonry = !SiteWork.natural(foot) && foot.isSolid();
			for (int y = e.getValue() - 1, d = 0; d < 40 && y > level.getMinY(); y--, d++) {
				BlockState st = level.getBlockState(q.set(x, y, z));
				if (!(st.isAir() || st.canBeReplaced() || st.getBlock() instanceof LiquidBlock || st.is(BlockTags.LEAVES))) {
					break;
				}
				level.setBlock(q, masonry ? Blocks.STONE_BRICKS.defaultBlockState() : fill, Block.UPDATE_CLIENTS);
			}
		}
		for (Template.Placement p : sorted) { // fences and walls join what was placed after them
			BlockPos at = origin.offset(p.offset());
			BlockState st = level.getBlockState(at);
			if (st.getBlock() instanceof CrossCollisionBlock || st.getBlock() instanceof WallBlock) {
				level.setBlock(at, Block.updateFromNeighbourShapes(st, level, at), Block.UPDATE_CLIENTS);
			}
		}
		return placed;
	}

	private static boolean hangs(BlockState st) {
		Block b = st.getBlock();
		return b instanceof net.minecraft.world.level.block.LadderBlock || b instanceof net.minecraft.world.level.block.LanternBlock
				|| b instanceof net.minecraft.world.level.block.AbstractBannerBlock || b instanceof net.minecraft.world.level.block.CarpetBlock
				|| b instanceof net.minecraft.world.level.block.DoorBlock || b instanceof net.minecraft.world.level.block.BedBlock
				|| b instanceof net.minecraft.world.level.block.TrapDoorBlock || b instanceof net.minecraft.world.level.block.SignBlock
				|| b instanceof net.minecraft.world.level.block.FlowerPotBlock || b instanceof net.minecraft.world.level.block.CandleBlock;
	}

	private static void spawnAnimal(ServerLevel level, Station st, Template.Animal a) {
		Template t = Template.get(st.style() + "/waystation_" + Math.max(2, st.level()));
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(a.type());
		Entity e = type.create(level, EntitySpawnReason.MOB_SUMMONED);
		if (e == null || t == null) {
			return;
		}
		BlockPos pos = st.origin().offset(t.offset(a.pos(), st.rot()));
		for (int up = 0; up < 6 && !(level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()); up++) {
			pos = pos.above();
		}
		e.snapTo(Vec3.atBottomCenterOf(pos), level.getRandom().nextFloat() * 360, 0);
		e.setAttached(People.ROLE, "way_animal|" + st.owner().id() + "|" + st.key() + "|" + st.n());
		level.addFreshEntity(e);
	}

	// ---- signs ----

	/** A destination on a sign: the place, its distance along the road, and which way it lies (world x/z). */
	record Dest(String ref, int dist, int dx, int dz) {
		String encode() {
			return this.ref + "," + this.dist + "," + this.dx + "," + this.dz;
		}

		static Dest decode(String s) {
			int c = s.lastIndexOf(','), b = s.lastIndexOf(',', c - 1), a = s.lastIndexOf(',', b - 1);
			return new Dest(s.substring(0, a), Integer.parseInt(s.substring(a + 1, b)), Integer.parseInt(s.substring(b + 1, c)), Integer.parseInt(s.substring(c + 1)));
		}
	}

	/** Whether the place lies straight behind someone reading a sign that faces {@code f} (he came from there). */
	private static boolean behind(Direction f, Dest d) {
		return d.dx() * f.getStepX() + d.dz() * f.getStepZ() > Math.abs(d.dx() * f.getStepZ() - d.dz() * f.getStepX());
	}

	/** The arrow a reader in front of a sign facing {@code f} sees for a place lying (dx, dz) from it. */
	static String arrow(Direction f, int dx, int dz) {
		Direction ahead = f.getOpposite();
		Direction right = ahead.getClockWise();
		int fwd = dx * ahead.getStepX() + dz * ahead.getStepZ();
		int side = dx * right.getStepX() + dz * right.getStepZ();
		if (Math.abs(fwd) >= Math.abs(side)) {
			return fwd >= 0 ? "↑" : "↓";
		}
		return side > 0 ? "→" : "←";
	}

	/** Keeps what a sign shows (at most two places) and writes it. */
	static void sign(MinecraftServer server, UUID owner, BlockPos at, List<Dest> dests) {
		StringBuilder b = new StringBuilder();
		for (Dest d : dests.subList(0, Math.min(2, dests.size()))) {
			b.append(b.isEmpty() ? "" : ";").append(d.encode());
		}
		put(server, owner, SIGN + str(at), b.toString());
		write(server, at, b.toString());
	}

	/** Text last written per sign block entity: a sign re-placed by a rebuild is a new entity and gets written again. */
	private static final Map<SignBlockEntity, String> WRITTEN = new java.util.WeakHashMap<>();

	/** The sign's lines from its places' current names: "↑ Eastmarch" / "240 blocks" for each. */
	static List<String> lines(MinecraftServer server, Direction facing, String value) {
		List<String> lines = new ArrayList<>();
		for (String part : value.split(";")) {
			if (part.isEmpty()) {
				continue;
			}
			Dest d = Dest.decode(part);
			lines.add(arrow(facing, d.dx(), d.dz()) + " " + placeName(server, d.ref()));
			lines.add(d.dist() + " blocks");
		}
		if (lines.size() == 2) {
			lines.add(0, "");
		}
		while (lines.size() < 4) {
			lines.add("");
		}
		return lines;
	}

	private static void write(MinecraftServer server, BlockPos at, String value) {
		ServerLevel level = server.overworld();
		if (!level.isLoaded(at)) {
			return;
		}
		BlockState st = level.getBlockState(at);
		if (!(level.getBlockEntity(at) instanceof SignBlockEntity be) || !st.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
			return;
		}
		List<String> text = lines(server, st.getValue(BlockStateProperties.HORIZONTAL_FACING), value);
		String joined = String.join("\n", text);
		if (joined.equals(WRITTEN.get(be))) {
			return;
		}
		WRITTEN.put(be, joined);
		List<Component> comps = text.stream().map(l -> (Component) Component.literal(l)).toList();
		SignText t = new SignText(comps, comps, DyeColor.BLACK, false);
		for (net.minecraft.world.level.block.entity.SignTextSlot slot : net.minecraft.world.level.block.entity.SignTextSlot.values()) {
			be.setText(t, slot);
		}
		be.setWaxed(true);
		be.setChanged();
		level.sendBlockUpdated(at, st, st, Block.UPDATE_ALL);
	}

	/** Every sign of the realm shows its places' current names (a renamed kingdom or village, within a minute). */
	static void refreshSigns(MinecraftServer server) {
		for (Settlement s : Settlements.all(server)) {
			for (Map.Entry<String, String> e : s.names().entrySet()) {
				if (e.getKey().startsWith(SIGN)) {
					BlockPos sp = pos(e.getKey().substring(SIGN.length()));
					if (server.overworld().isLoaded(sp) && !(server.overworld().getBlockState(sp).getBlock() instanceof SignBlock)) {
						put(server, s.id(), e.getKey(), null); // the sign is gone (rebuilt elsewhere)
					} else {
						write(server, sp, e.getValue());
					}
				}
			}
		}
	}

	// ---- the road's extras ----

	private static Set<String> extras(Settlement s, String key) {
		Set<String> out = new HashSet<>();
		for (String e : s.names().getOrDefault(EXTRAS + key, "").split(";")) {
			if (!e.isEmpty()) {
				out.add(e);
			}
		}
		return out;
	}

	private static void addExtra(MinecraftServer server, UUID id, String key, String extra) {
		Settlement s = byId(server, id.toString());
		if (s != null) {
			String now = s.names().getOrDefault(EXTRAS + key, "");
			put(server, id, EXTRAS + key, now.isEmpty() ? extra : now + ";" + extra);
		}
	}

	/** The extras of one road at its kingdom's road level: border stones, milestones, bridge towers, watchtowers. */
	static void dress(MinecraftServer server, String settlementId, String key) {
		Settlement s = byId(server, settlementId);
		if (s == null || !regular(key)) {
			return;
		}
		ServerLevel level = server.overworld();
		List<BlockPos> path = path(s, key);
		int len = length(s, key);
		if (path.size() < 4) {
			return;
		}
		int lvl = roadLevel(s);
		Set<String> have = extras(s, key);
		String target = targetRef(key);
		Settlement far = key.startsWith("v:") ? null : byId(server, key);
		// level 1: border stones where the realm begins, at each kingdom's end of the road
		for (Settlement end : far == null ? List.of(s) : List.of(s, far)) {
			String tag = "stone@" + end.id();
			if (have.contains(tag)) {
				continue;
			}
			for (int i = 0; i < path.size(); i++) {
				int k = end == s ? i : path.size() - 1 - i;
				if (horizontal(path.get(k), end.heart()) >= CLEAR + 24) {
					BlockPos cell = path.get(k);
					List<BlockPos> stone = Fealty.borderStone(level, cell, end.heart(), Style.forBiome(level.getBiome(cell).getRegisteredName()), end.color());
					if (!stone.isEmpty() || i > 40) {
						addExtra(server, s.id(), key, tag);
						break;
					}
				}
			}
		}
		if (lvl >= 2) {   // milestones every ~100 blocks, away from the stations and the kingdoms
			List<BlockPos> st = stations(s).stream().filter(x -> x.key().equals(key)).map(Station::origin).toList();
			for (int k = 100 / SAMPLE; k < path.size() - 40 / SAMPLE; k += 100 / SAMPLE) {
				BlockPos cell = path.get(k);
				String tag = "mile@" + k;
				if (have.contains(tag) || st.stream().anyMatch(o -> horizontal(o, cell) < 40)
						|| Settlements.all(server).stream().anyMatch(o -> horizontal(o.heart(), cell) < CLEAR - 20)) {
					continue;
				}
				BlockPos a = path.get(k - 1), b = path.get(k + 1);
				if (milestone(server, s, level, cell, b.getX() - a.getX(), b.getZ() - a.getZ(), k * SAMPLE, len, target)) {
					addExtra(server, s.id(), key, tag);
				}
			}
		}
		if (lvl >= 3) {
			bridgeTowers(server, s, key, have);
			hillTowers(server, s, key, path, have);
		}
	}

	/** Stone materials per region for milestones: foot, carved head, cap. */
	private static Block[] mileStone(String style) {
		return switch (style) {
			case Style.FROZEN -> new Block[] {Blocks.POLISHED_DEEPSLATE, Blocks.CHISELED_DEEPSLATE, Blocks.POLISHED_DEEPSLATE_SLAB};
			case Style.ARID -> new Block[] {Blocks.CUT_SANDSTONE, Blocks.CHISELED_SANDSTONE, Blocks.SMOOTH_SANDSTONE_SLAB};
			case Style.HIGHLAND -> new Block[] {Blocks.MOSSY_COBBLESTONE, Blocks.CHISELED_STONE_BRICKS, Blocks.STONE_BRICK_SLAB};
			case Style.WILD -> new Block[] {Blocks.MOSSY_STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS, Blocks.MOSSY_STONE_BRICK_SLAB};
			default -> new Block[] {Blocks.STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS, Blocks.STONE_BRICK_SLAB};
		};
	}

	/** Sign wood per region (tools/roadside.py RS sign). */
	static Block wallSign(String style) {
		return switch (style) {
			case Style.ARID -> Blocks.ACACIA_WALL_SIGN;
			case Style.HIGHLAND -> Blocks.DARK_OAK_WALL_SIGN;
			case Style.WILD -> Blocks.BAMBOO_WALL_SIGN;
			default -> Blocks.SPRUCE_WALL_SIGN;
		};
	}

	static Block postWood(String style) {
		return switch (style) {
			case Style.ARID -> Blocks.ACACIA_FENCE;
			case Style.WILD -> Blocks.BAMBOO_FENCE;
			case Style.HIGHLAND -> Blocks.DARK_OAK_FENCE;
			default -> Blocks.SPRUCE_FENCE;
		};
	}

	/** A free spot on natural ground beside the road, {@code off} blocks to one side of {@code cell}, or null. */
	static BlockPos besideRoad(ServerLevel level, BlockPos cell, Direction side, int height) {
		for (int off : new int[] {3, -3, 4, -4}) {
			BlockPos col = cell.relative(side, off);
			int[] g = ground(level, col.getX(), col.getZ());
			BlockPos foot = new BlockPos(col.getX(), g[0], col.getZ());
			BlockState top = level.getBlockState(foot);
			if (g[1] == 1 || Math.abs(g[0] - cell.getY()) > 2 || !SiteWork.isGround(top) || top.is(Blocks.DIRT_PATH) || top.is(Blocks.GRAVEL)
					|| top.is(Blocks.COBBLESTONE)) {
				continue;
			}
			boolean free = true;
			for (int h = 1; h <= height && free; h++) {
				BlockState st = level.getBlockState(foot.above(h));
				free = st.isAir() || st.canBeReplaced() || st.is(BlockTags.LEAVES);
			}
			if (free) {
				return foot.above();
			}
		}
		return null;
	}

	/** A milestone: a stone with a carved head and a sign toward the road naming both ends of it and how far they are. */
	private static boolean milestone(MinecraftServer server, Settlement s, ServerLevel level, BlockPos cell, int dx, int dz, int at, int len, String target) {
		Direction along = Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? Direction.EAST : Direction.WEST) : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
		Direction side = along.getClockWise();
		BlockPos base = besideRoad(level, cell, side, 3);
		if (base == null) {
			return false;
		}
		String style = Style.forBiome(level.getBiome(base).getRegisteredName());
		Block[] m = mileStone(style);
		level.setBlock(base, m[0].defaultBlockState(), NO_DROPS);
		level.setBlock(base.above(), m[1].defaultBlockState(), NO_DROPS);
		level.setBlock(base.above(2), m[2].defaultBlockState(), NO_DROPS);
		Direction toRoad = horizontalDir(cell.getX() - base.getX(), cell.getZ() - base.getZ());
		BlockPos signAt = base.above().relative(toRoad);
		if (level.getBlockState(signAt).isAir() || level.getBlockState(signAt).canBeReplaced()) {
			level.setBlock(signAt, wallSign(style).defaultBlockState().setValue(WallSignBlock.FACING, toRoad), NO_DROPS);
			sign(server, s.id(), signAt, List.of(new Dest("s:" + s.id(), at, -dx, -dz), new Dest(target, Math.max(0, len - at), dx, dz)));
		}
		return true;
	}

	static Direction horizontalDir(int dx, int dz) {
		return Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? Direction.EAST : Direction.WEST) : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
	}

	/** Level 3: a gate tower at the far end of every big river bridge (16 or more blocks of water), at both ends of a long one. */
	private static void bridgeTowers(MinecraftServer server, Settlement s, String key, Set<String> have) {
		ServerLevel level = server.overworld();
		String v = s.names().getOrDefault(BRIDGES + key, "");
		for (String b : v.split(";")) {
			if (b.isEmpty()) {
				continue;
			}
			String[] p = b.split(",");
			BlockPos a = new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
			BlockPos e = new BlockPos(Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5]));
			int wet = Integer.parseInt(p[6]);
			int len = Math.max(Math.abs(e.getX() - a.getX()), Math.abs(e.getZ() - a.getZ()));
			if (wet < 16 || len < 10 || a.getX() != e.getX() && a.getZ() != e.getZ()) {
				continue;
			}
			Direction fwd = horizontalDir(e.getX() - a.getX(), e.getZ() - a.getZ());
			Rotation r = fwd.getAxis() == Direction.Axis.Z ? Rotation.NONE : Rotation.CLOCKWISE_90;
			List<BlockPos> ends = new ArrayList<>(List.of(e.relative(fwd, -2)));
			if (wet >= 32) {
				ends.add(a.relative(fwd, 2));
			}
			for (BlockPos end : ends) {
				String tag = "btower@" + str(end.above());
				if (have.stream().anyMatch(h -> h.startsWith(tag + "@"))) {
					continue;
				}
				String style = Style.forBiome(level.getBiome(end).getRegisteredName());
				Template t = Template.get(style + "/bridge_tower");
				if (t != null) {
					place(level, t.placements(Settlements.dye(s.color()), r), end.above(), Blocks.STONE.defaultBlockState());
					addExtra(server, s.id(), key, tag + "@" + r.ordinal() + "@" + style);
				}
			}
		}
	}

	/** Level 3: watchtowers with signal fires on hilltops along roads of 250 blocks or more, 200 apart at least. */
	private static void hillTowers(MinecraftServer server, Settlement s, String key, List<BlockPos> path, Set<String> have) {
		if (path.size() * SAMPLE < 250 || have.stream().anyMatch(h -> h.startsWith("wtower"))) {
			return;
		}
		ServerLevel level = server.overworld();
		List<BlockPos> taken = new ArrayList<>();
		stations(server).forEach(st -> taken.add(st.origin()));
		int placed = 0;
		for (int k = 40 / SAMPLE; k < path.size() - 40 / SAMPLE && placed < 3; k++) {
			BlockPos c = path.get(k);
			int y = c.getY();
			boolean top = true;
			int low = y;
			for (int j = Math.max(0, k - 60 / SAMPLE); j < Math.min(path.size(), k + 60 / SAMPLE) && top; j++) {
				top = path.get(j).getY() <= y;
				low = Math.min(low, path.get(j).getY());
			}
			BlockPos cell = c;
			if (!top || y - low < 6 || taken.stream().anyMatch(t -> horizontal(t, cell) < 200)
					|| Settlements.all(server).stream().anyMatch(o -> horizontal(o.heart(), cell) < CLEAR)) {
				continue;
			}
			BlockPos a = path.get(k - 1), b = path.get(k + 1);
			Direction along = horizontalDir(b.getX() - a.getX(), b.getZ() - a.getZ());
			Rotation r = switch (along) {
				case SOUTH -> Rotation.NONE;
				case NORTH -> Rotation.CLOCKWISE_180;
				case WEST -> Rotation.CLOCKWISE_90;
				default -> Rotation.COUNTERCLOCKWISE_90;
			};
			String style = Style.forBiome(level.getBiome(cell).getRegisteredName());
			Template t = Template.get(style + "/watchtower");
			if (t == null || !towerFits(level, t, cell, r)) {
				continue;
			}
			place(level, t.placements(Settlements.dye(s.color()), r), cell.above(), SiteWork.surface(level, cell));
			addExtra(server, s.id(), key, "wtower@" + str(cell.above()) + "@" + r.ordinal() + "@" + style);
			taken.add(cell);
			placed++;
		}
	}

	/** Whether the tower's ground lies within 3 of the road there and is dry and natural. */
	private static boolean towerFits(ServerLevel level, Template t, BlockPos cell, Rotation r) {
		for (Template.Placement p : t.placements("white", r)) {
			if (p.offset().getY() != 0 || Math.abs(p.offset().getX()) <= 2 && Math.abs(p.offset().getZ()) <= 2) {
				continue;
			}
			BlockPos at = cell.offset(p.offset());
			int[] g = ground(level, at.getX(), at.getZ());
			if (g[1] == 1 || Math.abs(g[0] - cell.getY()) > 3) {
				return false;
			}
		}
		return true;
	}

	// ---- crossroads ----

	/** A road of the realm: its settlement, key, stored path and length. */
	private record Road(Settlement s, String key, List<BlockPos> path, int len) {
		Dest toStart(int k, BlockPos at) {
			BlockPos p = this.path.get(Math.max(0, k - 3));
			return new Dest("s:" + this.s.id(), k * SAMPLE, p.getX() - at.getX(), p.getZ() - at.getZ());
		}

		Dest toEnd(int k, BlockPos at) {
			BlockPos p = this.path.get(Math.min(this.path.size() - 1, k + 3));
			return new Dest(targetRef(this.key), Math.max(0, this.len - k * SAMPLE), p.getX() - at.getX(), p.getZ() - at.getZ());
		}
	}

	/**
	 * Crossroads of a lord's roads: where two roads cross, and where roads part in front of a kingdom's gate. Each
	 * gets a signpost (road level 1) with a sign toward every road that comes in, naming the places the other roads
	 * lead to; and a wayside shrine (level 2).
	 */
	static void junctions(MinecraftServer server, UUID owner) {
		List<Road> roads = new ArrayList<>();
		for (Settlement s : Settlements.all(server)) {
			if (!s.owner().equals(owner)) {
				continue;
			}
			for (String k : s.names().keySet()) {
				if (k.startsWith(PATH) && regular(k.substring(PATH.length()))) {
					String key = k.substring(PATH.length());
					List<BlockPos> p = path(s, key);
					if (p.size() >= 4) {
						roads.add(new Road(s, key, p, length(s, key)));
					}
				}
			}
		}
		Map<BlockPos, List<Dest>> found = new HashMap<>();
		Map<BlockPos, Road> along = new HashMap<>();
		Map<BlockPos, Direction> from = new HashMap<>();
		// roads parting in front of a gate: they share their first cells, then go their own ways
		for (Settlement g : Settlements.all(server)) {
			if (!g.owner().equals(owner)) {
				continue;
			}
			List<Dest> dests = new ArrayList<>();
			BlockPos exit = RoadLink.gateExit(g);
			BlockPos out = Template.rotate(new BlockPos(0, 0, 1), Rotation.values()[g.rotation()]);
			BlockPos at = exit.offset(out.getX() * 8, 0, out.getZ() * 8);
			Road any = null;
			for (Road r : roads) {
				if (r.s().id().equals(g.id())) {
					dests.add(r.toEnd(Math.min(r.path().size() - 1, 20 / SAMPLE), at));
					any = r;
				} else if (r.key().equals(g.id().toString())) {
					dests.add(r.toStart(r.path().size() - 1, at));
				}
			}
			if (dests.size() >= 2 && any != null) {
				// on the shared road itself, a little way out of the gate (past the drawbridge and the bank)
				BlockPos j = any.path().get(Math.min(any.path().size() - 1, 2));
				for (BlockPos c : any.path()) {
					if (horizontal(c, exit) < 10 || horizontal(c, exit) > 48) {
						continue;
					}
					server.overworld().getChunk(c.getX() >> 4, c.getZ() >> 4);
					boolean room = false;
					for (Direction d : Direction.Plane.HORIZONTAL) {
						room |= besideRoad(server.overworld(), c, d, 4) != null;
					}
					if (room) {
						j = c;
						break;
					}
				}
				found.put(j, dests);
				along.put(j, any);
				from.put(j, horizontalDir(-out.getX(), -out.getZ())); // a sign for those coming out of the gate
			}
		}
		// roads crossing each other in the open
		for (int i = 0; i < roads.size(); i++) {
			for (int j = i + 1; j < roads.size(); j++) {
				Road a = roads.get(i), b = roads.get(j);
				for (int p = 0; p + 1 < a.path().size(); p++) {
					for (int q = 0; q + 1 < b.path().size(); q++) {
						BlockPos x = cross(a.path().get(p), a.path().get(p + 1), b.path().get(q), b.path().get(q + 1));
						if (x == null || Settlements.all(server).stream().anyMatch(o -> horizontal(o.heart(), x) < CLEAR - 28)) {
							continue;
						}
						BlockPos at = x.atY(a.path().get(p).getY());
						found.put(at, List.of(a.toStart(p, at), a.toEnd(p + 1, at), b.toStart(q, at), b.toEnd(q + 1, at)));
						along.put(at, a);
					}
				}
			}
		}
		for (Map.Entry<BlockPos, List<Dest>> e : found.entrySet()) {
			crossroads(server, owner, e.getKey(), e.getValue(), along.get(e.getKey()), from.get(e.getKey()));
		}
	}

	/** Where segment a1-a2 crosses segment b1-b2 (not just touching at their ends), or null. */
	private static BlockPos cross(BlockPos a1, BlockPos a2, BlockPos b1, BlockPos b2) {
		double rx = a2.getX() - a1.getX(), rz = a2.getZ() - a1.getZ(), sx = b2.getX() - b1.getX(), sz = b2.getZ() - b1.getZ();
		double den = rx * sz - rz * sx;
		if (Math.abs(den) < 1e-6 || Math.abs(den) < 0.4 * Math.hypot(rx, rz) * Math.hypot(sx, sz)) {
			return null; // parallel, or meeting at a slant too flat to be a crossroads
		}
		double t = ((b1.getX() - a1.getX()) * sz - (b1.getZ() - a1.getZ()) * sx) / den;
		double u = ((b1.getX() - a1.getX()) * rz - (b1.getZ() - a1.getZ()) * rx) / den;
		if (t < 0 || t >= 1 || u < 0 || u >= 1) {
			return null;
		}
		return new BlockPos((int) Math.round(a1.getX() + t * rx), a1.getY(), (int) Math.round(a1.getZ() + t * rz));
	}

	private static void crossroads(MinecraftServer server, UUID owner, BlockPos at, List<Dest> dests, Road road, Direction gate) {
		ServerLevel level = server.overworld();
		String have = null;
		Settlement keeper = null;
		for (Settlement s : Settlements.all(server)) {
			if (!s.owner().equals(owner)) {
				continue;
			}
			for (Map.Entry<String, String> e : s.names().entrySet()) {
				if (e.getKey().startsWith(JUNCTION)) {
					String[] p = e.getKey().substring(JUNCTION.length()).split(",");
					if (Math.abs(Integer.parseInt(p[0]) - at.getX()) <= 20 && Math.abs(Integer.parseInt(p[1]) - at.getZ()) <= 20) {
						have = e.getKey() + "=" + e.getValue();
						keeper = s;
					}
				}
			}
		}
		Settlement s = keeper != null ? keeper : road.s();
		int lvl = roadLevel(road.s());
		String jkey = have != null ? have.substring(0, have.indexOf('=')) : JUNCTION + at.getX() + "," + at.getZ();
		String done = have != null ? have.substring(have.indexOf('=') + 1) : "";
		if (!level.isLoaded(at)) {
			return;
		}
		String now = done;
		BlockPos post = null;
		if (!done.contains("post")) {
			post = signpost(server, s, level, at, dests, gate);
			if (post != null) {
				now = now.isEmpty() ? "post@" + str(post) : now + ";post@" + str(post);
			}
		}
		if (lvl >= 2 && !now.contains("shrine") && now.contains("post")) {
			// the shrine on the far side of the road from the signpost, turned to the road
			int k = 0;
			for (int i = 1; i < road.path().size(); i++) {
				if (horizontal(road.path().get(i), at) < horizontal(road.path().get(k), at)) {
					k = i;
				}
			}
			BlockPos cell = road.path().get(k);
			BlockPos a = road.path().get(Math.max(0, k - 1)), b = road.path().get(Math.min(road.path().size() - 1, k + 1));
			Direction dir = horizontalDir(b.getX() - a.getX(), b.getZ() - a.getZ());
			// a little way along the road, where the land beside it is level enough
			search:
			for (int dist : new int[] {8, 12, 16, 20, -8, -12, -16, -20}) {
				BlockPos c = cell.relative(dir, dist);
				level.getChunk(c.getX() >> 4, c.getZ() >> 4);
				String style = Style.forBiome(level.getBiome(c).getRegisteredName());
				Template t = Template.get(style + "/shrine");
				if (t == null) {
					break;
				}
				for (Rotation r : List.of(Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.COUNTERCLOCKWISE_90)) {
					BlockPos z = turn(0, 0, 1, r);
					if (Math.abs(z.getX()) != Math.abs(dir.getStepX())) {
						continue; // the template's road along this road
					}
					BlockPos side = turn(1, 0, 0, r);
					int[] g = ground(level, c.getX() + side.getX() * 3, c.getZ() + side.getZ() * 3);
					if (g[1] == 1 || Math.abs(g[0] - c.getY()) > 2 || Math.abs(g[0] - cell.getY()) > 4) {
						continue;
					}
					BlockPos origin = new BlockPos(c.getX(), g[0] + 1, c.getZ());
					place(level, t.placements("white", r), origin, SiteWork.surface(level, c));
					now = now + ";shrine@" + str(origin) + "@" + r.ordinal() + "@" + style;
					break search;
				}
			}
		}
		if (!now.equals(done)) {
			put(server, s.id(), jkey, now);
		}
	}

	/** A signpost by the crossroads: a sign toward every road that comes in, naming the places the others lead to. */
	private static BlockPos signpost(MinecraftServer server, Settlement s, ServerLevel level, BlockPos at, List<Dest> dests, Direction gate) {
		BlockPos base = null;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			base = base != null ? base : besideRoad(level, at, d, 4);
		}
		if (base == null) {
			return null;
		}
		String style = Style.forBiome(level.getBiome(base).getRegisteredName());
		BlockState fence = postWood(style).defaultBlockState();
		level.setBlock(base, fence, NO_DROPS);
		level.setBlock(base.above(), fence, NO_DROPS);
		BlockPos head = base.above(2);
		level.setBlock(head, (style.equals(Style.ARID) ? Blocks.STRIPPED_ACACIA_LOG : style.equals(Style.WILD) ? Blocks.STRIPPED_JUNGLE_LOG
				: Blocks.STRIPPED_SPRUCE_LOG).defaultBlockState(), NO_DROPS);
		level.setBlock(head.above(), Blocks.LANTERN.defaultBlockState(), NO_DROPS);
		Set<Direction> faces = new HashSet<>();
		for (Dest d : dests) {
			faces.add(horizontalDir(d.dx(), d.dz()));
		}
		if (gate != null) {
			faces.add(gate);
		}
		for (Direction f : faces) {
			List<Dest> shown = new ArrayList<>(dests.stream().filter(d -> !behind(f, d) && d.dist() > 8).toList());
			shown.sort(java.util.Comparator.comparingInt(d -> -(d.dx() * f.getOpposite().getStepX() + d.dz() * f.getOpposite().getStepZ())));
			BlockPos at2 = head.relative(f);
			if (shown.isEmpty() || !(level.getBlockState(at2).isAir() || level.getBlockState(at2).canBeReplaced())) {
				continue;
			}
			level.setBlock(at2, wallSign(style).defaultBlockState().setValue(WallSignBlock.FACING, f), NO_DROPS);
			sign(server, s.id(), at2, shown);
		}
		return base;
	}

	// ---- signal fires, guards, horses, blocks ----

	/** The signal fires of a lord's waystations and roadside watchtowers kept on this settlement (see {@link SignalFires}). */
	static List<BlockPos> fires(Settlement s) {
		List<BlockPos> out = new ArrayList<>();
		for (Station st : stations(s)) {
			if (st.level() >= 3) {
				out.addAll(firesOf(st.style() + "/waystation_3", st.origin(), st.rot()));
			}
		}
		for (Map.Entry<String, String> e : s.names().entrySet()) {
			if (e.getKey().startsWith(EXTRAS)) {
				for (String x : e.getValue().split(";")) {
					String[] p = x.split("@");
					if (p[0].equals("wtower") && p.length >= 4) {
						out.addAll(firesOf(p[3] + "/watchtower", pos(p[1]), Rotation.values()[Integer.parseInt(p[2])]));
					}
				}
			}
		}
		return out;
	}

	private static List<BlockPos> firesOf(String template, BlockPos origin, Rotation r) {
		Template t = Template.get(template);
		List<BlockPos> out = new ArrayList<>();
		if (t != null) {
			for (Template.Placement p : t.placements("white", r)) {
				if (p.state().is(Blocks.CAMPFIRE) && !p.state().getValue(CampfireBlock.LIT)) {
					out.add(origin.offset(p.offset()));
				}
			}
		}
		return out;
	}

	/** Every minute: guards at level-3 stations, horses back in their stables, signs showing the current names. */
	static void upkeep(MinecraftServer server) {
		ServerLevel level = server.overworld();
		for (Station st : stations(server)) {
			if (!level.isLoaded(st.origin()) || st.level() < 2) {
				continue;
			}
			String animal = "way_animal|" + st.owner().id() + "|" + st.key() + "|" + st.n();
			Template t = Template.get(st.style() + "/waystation_" + st.level());
			AABB box = new AABB(st.origin()).inflate(40, 16, 40);
			long horses = level.getEntities((Entity) null, box, e -> e.isAlive() && animal.equals(People.role(e))).size();
			if (t != null && horses == 0) {
				t.animals.forEach(a -> spawnAnimal(level, st, a));
			}
			if (st.level() < 3) {
				continue;
			}
			int lvl = Math.max(1, st.owner().level(Progression.BARRACKS));
			List<KingdomNpc> guards = level.getEntitiesOfClass(KingdomNpc.class, box, g -> g.isAlive() && st.tag().equals(People.role(g)));
			Set<Long> manned = new HashSet<>();
			for (KingdomNpc g : guards) {
				if (!manned.add(g.post())) {
					g.discard();
				}
			}
			for (BlockPos local : GUARD_POSTS) {
				BlockPos post = st.origin().offset(turn(local.getX(), local.getY(), local.getZ(), st.rot()));
				if (manned.add(post.asLong())) {
					KingdomNpc g = KingdomNpc.TYPE.create(level, EntitySpawnReason.EVENT);
					if (g == null) {
						continue;
					}
					g.setPost(post);
					g.guardGear(lvl, false);
					g.setup("guard", KingdomNpc.GUARD_SKIN, st.owner().owner());
					g.setTier(lvl);
					g.setHome(post);
					g.setCustomName(Component.literal("Guard of " + st.owner().name()));
					g.setAttached(People.ROLE, st.tag());
					g.snapTo(post.getX() + 0.5, post.getY(), post.getZ() + 0.5, level.getRandom().nextFloat() * 360, 0);
					level.addFreshEntity(g);
				}
			}
		}
		refreshSigns(server);
	}

	/** Blocks of a waystation or a roadside building are broken: they go without dropping anything, like built blocks. */
	private static boolean breakRoadsideBlock(ServerLevel level, BlockPos pos) {
		for (Settlement s : Settlements.all(level.getServer())) {
			List<Object[]> builds = new ArrayList<>();
			for (Station st : stations(s)) {
				if (st.level() > 0 && horizontal(st.origin(), pos) < 40) {
					builds.add(new Object[] {st.style() + "/waystation_" + st.level(), st.origin(), st.rot()});
				}
			}
			for (Map.Entry<String, String> e : s.names().entrySet()) {
				if (e.getKey().startsWith(EXTRAS) || e.getKey().startsWith(JUNCTION)) {
					for (String x : e.getValue().split(";")) {
						String[] p = x.split("@");
						if (p.length >= 4 && horizontal(pos(p[1]), pos) < 16) {
							String kind = p[0].equals("wtower") ? "watchtower" : p[0].equals("btower") ? "bridge_tower" : "shrine";
							builds.add(new Object[] {p[3] + "/" + kind, pos(p[1]), Rotation.values()[Integer.parseInt(p[2])]});
						}
					}
				}
			}
			for (Object[] b : builds) {
				Template t = Template.get((String) b[0]);
				if (t == null) {
					continue;
				}
				for (Template.Placement p : t.placements("white", (Rotation) b[2])) {
					if (((BlockPos) b[1]).offset(p.offset()).equals(pos) && !p.state().isAir()) {
						level.setBlock(pos, Blocks.AIR.defaultBlockState(), NO_DROPS);
						return true;
					}
				}
			}
		}
		return false;
	}
}
