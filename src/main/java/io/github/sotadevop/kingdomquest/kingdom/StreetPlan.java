package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.building.Template;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The street plan (user, 2026-11-19, option A "road first"): one height for every road column of the settlement,
 * worked out once from the stored site plan and the road layout, never changed by later builds. Buildings take their
 * level from the street at their door, and the land beside the road is shaped to it, so streets are consistent.
 *
 * How the heights come about:
 * - the target is the site plan's land (the blurred natural land), and level 0 on the main building's plots
 * - smoothed along the road network, so a street rides the land's general shape, not its bumps
 * - the square and the gate passage stay at the square's level (the gate stands at the heart's level)
 * - streets climb at most 1 in 3, lanes 1 in 2; a lane that would have to cut or fill more than 3 blocks for that
 *   becomes a stepped lane (stairs, 1 in 1) where it is that steep
 * - a street is level across its width (all cells across a trunk share its height)
 * Heights are kept in half blocks: a .5 is a slab on the road.
 */
final class StreetPlan {
	private static final Map<UUID, StreetPlan> PLANS = new HashMap<>();
	private static final double TRUNK_GRADE = 1 / 3.0;
	private static final double LANE_GRADE = 0.5;
	private static final double STAIR_GRADE = 1.0;
	/** A lane cutting or filling more than this below/above its land becomes a stepped lane there. */
	private static final double STAIR_TRIGGER = 3.0;

	/** World column -> feet height relative to the heart, in half blocks. */
	private final Map<Long, Integer> half;
	/** World columns of stepped lanes. */
	private final Set<Long> steep;

	private StreetPlan(Map<Long, Integer> half, Set<Long> steep) {
		this.half = half;
		this.steep = steep;
	}

	static void forgetAll() {
		PLANS.clear();
	}

	/** Feet height relative to the heart at a road column (world x/z), in half blocks; null if not a street. */
	Integer halfAt(long column) {
		return this.half.get(column);
	}

	/** Whole-block feet height at a road column: the road block lies just under it; null if not a street. */
	Integer feetAt(long column) {
		Integer v = this.half.get(column);
		return v == null ? null : Math.floorDiv(v, 2);
	}

	boolean slab(long column) {
		Integer v = this.half.get(column);
		return v != null && Math.floorMod(v, 2) == 1;
	}

	boolean steep(long column) {
		return this.steep.contains(column);
	}

	Set<Long> columns() {
		return this.half.keySet();
	}

	/** The settlement's street plan, or null while its site plan can't be made (site not loaded) or it has no roads. */
	static StreetPlan of(MinecraftServer server, Settlement s) {
		StreetPlan plan = PLANS.get(s.id());
		if (plan != null) {
			return plan;
		}
		SitePlan site = SitePlan.of(server, s);
		Template roads = Template.get(s.style() + "/road_4");
		if (site == null || roads == null) {
			return null;
		}
		plan = compute(s, site, roads);
		PLANS.put(s.id(), plan);
		return plan;
	}

	private static StreetPlan compute(Settlement s, SitePlan site, Template roads) {
		Rotation r = Rotation.values()[s.rotation()];
		BlockPos h = s.heart();
		// the road cells, with the segment each belongs to
		Map<Long, String> seg = new HashMap<>();
		for (Template.Placement p : roads.placements("white", r)) {
			long col = BlockPos.asLong(h.getX() + p.offset().getX(), 0, h.getZ() + p.offset().getZ());
			String name = p.segment() >= 0 && p.segment() < roads.segments.size() ? roads.segments.get(p.segment()).name() : "";
			seg.putIfAbsent(col, name);
		}
		if (seg.isEmpty()) {
			return new StreetPlan(Map.of(), Set.of());
		}
		// the main building's plots are levelled to the square's level
		Set<Long> mainPads = new HashSet<>();
		Template main = Template.get(s.style() + "/citadel_1");
		if (main != null) {
			for (int[] pad : main.pads) {
				for (int x = pad[0]; x <= pad[2]; x++) {
					for (int z = pad[1]; z <= pad[3]; z++) {
						BlockPos rel = Template.rotate(new BlockPos(x - main.heart.getX(), 0, z - main.heart.getZ()), r);
						mainPads.add(BlockPos.asLong(h.getX() + rel.getX(), 0, h.getZ() + rel.getZ()));
					}
				}
			}
		}
		Map<Long, Double> target = new HashMap<>();
		Map<Long, Double> v = new HashMap<>();
		for (long c : seg.keySet()) {
			int at = site.at(BlockPos.getX(c), BlockPos.getZ(c));
			double t = mainPads.contains(c) ? 0 : at == Integer.MIN_VALUE ? 0 : at;
			target.put(c, t);
			v.put(c, t);
		}
		// fixed: the square and the gate passage (template z 36.. relative to the heart, the gate stands at level 0)
		Set<Long> fixed = new HashSet<>();
		for (long c : seg.keySet()) {
			int dx = BlockPos.getX(c) - h.getX(), dz = BlockPos.getZ(c) - h.getZ();
			BlockPos t = Template.rotate(new BlockPos(dx, 0, dz), back(r));
			if (Math.abs(t.getX()) <= 3 && Math.abs(t.getZ()) <= 3 || t.getZ() >= 36 && Math.abs(t.getX()) <= 2) {
				fixed.add(c);
				v.put(c, 0.0);
			}
		}
		// smoothed along the network: a street follows the land's shape, not its bumps
		for (int pass = 0; pass < 40; pass++) {
			Map<Long, Double> next = new HashMap<>(v);
			for (long c : seg.keySet()) {
				if (fixed.contains(c)) {
					continue;
				}
				double sum = 0;
				int n = 0;
				for (long nb : neighbours(c)) {
					Double w = v.get(nb);
					if (w != null) {
						sum += w;
						n++;
					}
				}
				next.put(c, n == 0 ? v.get(c) : 0.5 * v.get(c) + 0.5 * sum / n);
			}
			v = next;
		}
		Map<Long, Double> smooth = new HashMap<>(v);
		Map<Long, Double> grade = new HashMap<>();
		for (long c : seg.keySet()) {
			grade.put(c, lane(seg.get(c)) ? LANE_GRADE : TRUNK_GRADE);
		}
		Set<Long> steep = new HashSet<>();
		v = limit(seg.keySet(), smooth, grade, fixed);
		// lanes that would cut or fill too much for a gentle slope become stepped lanes where they are that steep
		for (long c : seg.keySet()) {
			if (lane(seg.get(c)) && Math.abs(v.get(c) - target.get(c)) > STAIR_TRIGGER) {
				steep.add(c);
				grade.put(c, STAIR_GRADE);
			}
		}
		if (!steep.isEmpty()) {
			v = limit(seg.keySet(), smooth, grade, fixed);
		}
		// level across: every cell across a trunk takes the mean of its row
		Map<String, List<Long>> bySeg = new HashMap<>();
		seg.forEach((c, name) -> bySeg.computeIfAbsent(name, k -> new ArrayList<>()).add(c));
		for (Map.Entry<String, List<Long>> e : bySeg.entrySet()) {
			if (lane(e.getKey())) {
				continue;
			}
			int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
			for (long c : e.getValue()) {
				minX = Math.min(minX, BlockPos.getX(c));
				maxX = Math.max(maxX, BlockPos.getX(c));
				minZ = Math.min(minZ, BlockPos.getZ(c));
				maxZ = Math.max(maxZ, BlockPos.getZ(c));
			}
			boolean alongX = maxX - minX >= maxZ - minZ;
			Map<Integer, double[]> rows = new HashMap<>();
			for (long c : e.getValue()) {
				int key = alongX ? BlockPos.getX(c) : BlockPos.getZ(c);
				rows.computeIfAbsent(key, k -> new double[2]);
				rows.get(key)[0] += v.get(c);
				rows.get(key)[1]++;
			}
			for (long c : e.getValue()) {
				if (fixed.contains(c)) {
					continue;
				}
				double[] row = rows.get(alongX ? BlockPos.getX(c) : BlockPos.getZ(c));
				v.put(c, row[0] / row[1]);
			}
		}
		Map<Long, Integer> half = new HashMap<>();
		for (long c : seg.keySet()) {
			half.put(c, (int) Math.round(v.get(c) * 2));
		}
		// rounding may leave a neighbour pair a whole block apart on a gentle street: pulled together again
		for (int pass = 0; pass < 6; pass++) {
			for (long c : seg.keySet()) {
				if (fixed.contains(c) || steep.contains(c)) {
					continue;
				}
				for (long nb : neighbours(c)) {
					Integer w = half.get(nb);
					if (w != null && !steep.contains(nb)) {
						int here = half.get(c);
						if (here - w > 2) {
							half.put(c, w + 2);
						} else if (w - here > 2) {
							half.put(c, w - 2);
						}
					}
				}
			}
		}
		return new StreetPlan(half, steep);
	}

	/**
	 * The road's edges (user, 2026-11-19): beside every laid street a verge one block wide lies level with the road;
	 * beyond it, land 2 off gets a one-block step, land 3 or more off a retaining wall of the region's masonry (holding
	 * back the hill where the road is cut in, carrying the verge with a parapet where the road runs above the land).
	 * Only natural ground and made ground nobody owns is shaped; buildings, door approaches and the moat are left alone.
	 */
	static void edges(MinecraftServer server, Settlement s, Set<Long> built) {
		StreetPlan plan = of(server, s);
		if (plan == null || plan.half.isEmpty()) {
			return;
		}
		ServerLevel level = server.overworld();
		int heartY = s.heart().getY();
		Set<Long> roadCols = new HashSet<>();
		for (long p : s.built().getOrDefault(Progression.ROADS, List.of())) {
			roadCols.add(BlockPos.asLong(BlockPos.getX(p), 0, BlockPos.getZ(p)));
		}
		Set<Long> builtCols = new HashSet<>();
		for (long p : built) {
			builtCols.add(BlockPos.asLong(BlockPos.getX(p), 0, BlockPos.getZ(p)));
		}
		Set<Long> keep = new HashSet<>(Settlements.doorApproaches(level, s));
		keep.addAll(Settlements.moatColumns(s));
		BlockState surface = SiteWork.surface(level, s.heart());
		BlockState wall = SiteWork.wallFor(surface);
		BlockState parapet = parapetFor(wall);
		Set<Long> done = new HashSet<>();
		for (long c : plan.half.keySet()) {
			if (!roadCols.contains(c)) {
				continue; // not laid yet
			}
			int top = heartY + plan.feetAt(c) - 1; // the road block
			int x = BlockPos.getX(c), z = BlockPos.getZ(c);
			for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
				long verge = BlockPos.asLong(x + d[0], 0, z + d[1]);
				if (plan.half.containsKey(verge) || builtCols.contains(verge) || keep.contains(verge) || !done.add(verge)) {
					continue;
				}
				int g = SiteWork.groundDeep(level, x + d[0], z + d[1], heartY, built);
				int beyondX = x + 2 * d[0], beyondZ = z + 2 * d[1];
				long beyond = BlockPos.asLong(beyondX, 0, beyondZ);
				boolean beyondFree = !plan.half.containsKey(beyond) && !builtCols.contains(beyond) && !keep.contains(beyond);
				if (g < top) { // the road runs above the land here: the verge is raised to it
					boolean high = top - g >= 3;
					for (int y = g + 1; y <= top && y - g <= 12; y++) {
						BlockPos q = new BlockPos(x + d[0], y, z + d[1]);
						if (!open(level, q, built)) {
							break;
						}
						level.setBlock(q, high ? wall : y == top ? surface : SiteWork.subsoil(surface), SiteWork.FLAGS);
					}
					if (high) { // a wall at the road's edge carries a parapet
						BlockPos q = new BlockPos(x + d[0], top + 1, z + d[1]);
						if (open(level, q, built)) {
							level.setBlock(q, parapet, Block.UPDATE_ALL);
						}
					} else if (beyondFree && top - SiteWork.groundDeep(level, beyondX, beyondZ, heartY, built) >= 2) {
						step(level, beyondX, beyondZ, heartY, top - 1, built, surface); // the bank eases off in one step
					}
				} else if (g > top) { // the road is cut into the land: the verge is cut down to it
					for (int y = top + 1; y <= g && y - top <= 12; y++) {
						BlockPos q = new BlockPos(x + d[0], y, z + d[1]);
						BlockState st = level.getBlockState(q);
						if (!built.contains(q.asLong()) && (SiteWork.natural(st) || SiteWork.earthwork(st))) {
							level.setBlock(q, Blocks.AIR.defaultBlockState(), SiteWork.FLAGS);
						}
					}
					BlockPos floor = new BlockPos(x + d[0], top, z + d[1]);
					if (!built.contains(floor.asLong()) && SiteWork.terrain(level.getBlockState(floor))) {
						level.setBlock(floor, surface, SiteWork.FLAGS);
					}
					if (beyondFree) {
						int g2 = SiteWork.groundDeep(level, beyondX, beyondZ, heartY, built);
						if (g2 - top >= 3) { // the hill beyond is held back by a retaining wall facing the road
							for (int y = top + 1; y <= g2 && y - top <= 12; y++) {
								BlockPos q = new BlockPos(beyondX, y, beyondZ);
								BlockState st = level.getBlockState(q);
								if (!built.contains(q.asLong()) && (SiteWork.natural(st) || SiteWork.earthwork(st)) && !st.isAir()) {
									level.setBlock(q, y == g2 ? surface : wall, SiteWork.FLAGS);
								}
							}
						} else if (g2 - top == 2) {
							step(level, beyondX, beyondZ, heartY, top + 1, built, surface);
						}
					}
				}
			}
		}
	}

	/** Air or a plant: room for made ground. */
	private static boolean open(ServerLevel level, BlockPos q, Set<Long> built) {
		BlockState st = level.getBlockState(q);
		return !built.contains(q.asLong()) && (st.isAir() || st.canBeReplaced() || SiteWork.plant(st)) && st.getFluidState().isEmpty();
	}

	/** Brings a column's ground top to {@code want} (fill with earth or cut natural ground). */
	private static void step(ServerLevel level, int x, int z, int heartY, int want, Set<Long> built, BlockState surface) {
		int g = SiteWork.groundDeep(level, x, z, heartY, built);
		if (g < want) {
			for (int y = g + 1; y <= want; y++) {
				BlockPos q = new BlockPos(x, y, z);
				if (!open(level, q, built)) {
					return;
				}
				level.setBlock(q, y == want ? surface : SiteWork.subsoil(surface), SiteWork.FLAGS);
			}
		} else if (g > want) {
			for (int y = want + 1; y <= g; y++) {
				BlockPos q = new BlockPos(x, y, z);
				BlockState st = level.getBlockState(q);
				if (built.contains(q.asLong()) || !(SiteWork.natural(st) || SiteWork.earthwork(st))) {
					return;
				}
				level.setBlock(q, Blocks.AIR.defaultBlockState(), SiteWork.FLAGS);
			}
			BlockPos floor = new BlockPos(x, want, z);
			if (!built.contains(floor.asLong()) && SiteWork.terrain(level.getBlockState(floor))) {
				level.setBlock(floor, surface, SiteWork.FLAGS);
			}
		}
	}

	/** The wall block that goes with a masonry block (a parapet on a retaining wall). */
	private static BlockState parapetFor(BlockState wall) {
		Block b = wall.getBlock();
		Block w = b == Blocks.STONE_BRICKS ? Blocks.STONE_BRICK_WALL : b == Blocks.MOSSY_STONE_BRICKS ? Blocks.MOSSY_STONE_BRICK_WALL
				: b == Blocks.SANDSTONE || b == Blocks.SMOOTH_SANDSTONE || b == Blocks.CUT_SANDSTONE ? Blocks.SANDSTONE_WALL
				: b == Blocks.RED_SANDSTONE || b == Blocks.SMOOTH_RED_SANDSTONE || b == Blocks.CUT_RED_SANDSTONE ? Blocks.RED_SANDSTONE_WALL
				: b == Blocks.MOSSY_COBBLESTONE ? Blocks.MOSSY_COBBLESTONE_WALL : b == Blocks.MUD_BRICKS ? Blocks.MUD_BRICK_WALL
				: b == Blocks.BRICKS ? Blocks.BRICK_WALL : Blocks.COBBLESTONE_WALL;
		return w.defaultBlockState();
	}

	/** Heights pulled within the grade of each cell from the fixed cells and from their neighbours. */
	private static Map<Long, Double> limit(Set<Long> cells, Map<Long, Double> start, Map<Long, Double> grade, Set<Long> fixed) {
		Map<Long, Double> v = new HashMap<>(start);
		for (long c : fixed) {
			v.put(c, 0.0);
		}
		for (int pass = 0; pass < 400; pass++) {
			boolean changed = false;
			for (long c : cells) {
				if (fixed.contains(c)) {
					continue;
				}
				double lo = -1e9, hi = 1e9;
				for (long nb : neighbours(c)) {
					Double w = v.get(nb);
					if (w != null) {
						double g = Math.max(grade.get(c), grade.get(nb));
						lo = Math.max(lo, w - g);
						hi = Math.min(hi, w + g);
					}
				}
				if (lo > hi) {
					continue;
				}
				double now = v.get(c);
				double clamped = Math.max(lo, Math.min(hi, now));
				if (Math.abs(clamped - now) > 1e-6) {
					v.put(c, clamped);
					changed = true;
				}
			}
			if (!changed) {
				break;
			}
		}
		return v;
	}

	/** A lane to one door or goal ("track_level_x_z"), not a street of the layout. */
	private static boolean lane(String name) {
		return name.split("_").length >= 4;
	}

	private static long[] neighbours(long c) {
		int x = BlockPos.getX(c), z = BlockPos.getZ(c);
		return new long[] {BlockPos.asLong(x + 1, 0, z), BlockPos.asLong(x - 1, 0, z), BlockPos.asLong(x, 0, z + 1), BlockPos.asLong(x, 0, z - 1)};
	}

	private static Rotation back(Rotation r) {
		return switch (r) {
			case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
			case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
			default -> r;
		};
	}
}
