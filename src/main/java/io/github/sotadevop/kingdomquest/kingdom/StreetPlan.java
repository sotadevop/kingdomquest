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
		// the main building's plots are levelled to the square's level, and so is the way in to its doors: streets there
		// lie at that level (field test: the high street sank 4 below the plaza and a lane 7 above the hall's door)
		Set<Long> level0 = new HashSet<>();
		Template main = Template.get(s.style() + "/citadel_1");
		if (main != null) {
			for (int[] pad : main.pads) {
				for (int x = pad[0]; x <= pad[2]; x++) {
					for (int z = pad[1]; z <= pad[3]; z++) {
						BlockPos rel = Template.rotate(new BlockPos(x - main.heart.getX(), 0, z - main.heart.getZ()), r);
						level0.add(BlockPos.asLong(h.getX() + rel.getX(), 0, h.getZ() + rel.getZ()));
					}
				}
			}
		}
		List<Progression.Step> mainSteps = Progression.steps(Settlement.MAIN);
		for (Progression.Step step : mainSteps == null ? List.<Progression.Step>of() : mainSteps) {
			Template t = Template.get(s.style() + "/" + step.building());
			if (t == null) {
				continue;
			}
			for (Template.Placement p : t.placements("white", r)) {
				if (p.state().getBlock() instanceof net.minecraft.world.level.block.DoorBlock && p.offset().getY() <= 3) {
					for (int dx = -3; dx <= 3; dx++) {
						for (int dz = -3; dz <= 3; dz++) {
							level0.add(BlockPos.asLong(h.getX() + p.offset().getX() + dx, 0, h.getZ() + p.offset().getZ() + dz));
						}
					}
				}
			}
		}
		// units: every row across a street is one unit (so a street is level across by construction); a lane cell is
		// a unit of its own
		Map<Long, Integer> unitOf = new HashMap<>();
		List<List<Long>> units = new ArrayList<>();
		List<Boolean> laneUnit = new ArrayList<>();
		Map<String, List<Long>> bySeg = new HashMap<>();
		seg.forEach((c, name) -> bySeg.computeIfAbsent(name, k -> new ArrayList<>()).add(c));
		for (Map.Entry<String, List<Long>> e : bySeg.entrySet()) {
			if (lane(e.getKey())) {
				// the lane's last two blocks at its door are one flat landing: the porch foot meets it level wherever it
				// touches (field test: porch steps 1.5-2 above a lane still sloping past them)
				long goal = goal(e.getKey(), r, h);
				List<Long> landing = new ArrayList<>();
				for (long c : e.getValue()) {
					if (goal != Long.MIN_VALUE && Math.max(Math.abs(BlockPos.getX(c) - BlockPos.getX(goal)), Math.abs(BlockPos.getZ(c) - BlockPos.getZ(goal))) <= 2) {
						landing.add(c);
						continue;
					}
					unitOf.put(c, units.size());
					units.add(List.of(c));
					laneUnit.add(true);
				}
				if (!landing.isEmpty()) {
					for (long c : landing) {
						unitOf.put(c, units.size());
					}
					units.add(landing);
					laneUnit.add(true);
				}
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
			Map<Integer, List<Long>> rows = new HashMap<>();
			for (long c : e.getValue()) {
				rows.computeIfAbsent(alongX ? BlockPos.getX(c) : BlockPos.getZ(c), k -> new ArrayList<>()).add(c);
			}
			for (List<Long> row : rows.values()) {
				for (long c : row) {
					unitOf.put(c, units.size());
				}
				units.add(row);
				laneUnit.add(false);
			}
		}
		int n = units.size();
		List<Set<Integer>> adj = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			adj.add(new HashSet<>());
		}
		for (Map.Entry<Long, Integer> e : unitOf.entrySet()) {
			for (long nb : neighbours(e.getKey())) {
				Integer j = unitOf.get(nb);
				if (j != null && j != e.getValue().intValue()) {
					adj.get(e.getValue()).add(j);
					adj.get(j).add(e.getValue());
				}
			}
		}
		double[] target = new double[n];
		boolean[] fixed = new boolean[n];
		for (int i = 0; i < n; i++) {
			double sum = 0;
			for (long c : units.get(i)) {
				int at = site.at(BlockPos.getX(c), BlockPos.getZ(c));
				sum += level0.contains(c) || at == Integer.MIN_VALUE ? 0 : at;
				int dx = BlockPos.getX(c) - h.getX(), dz = BlockPos.getZ(c) - h.getZ();
				BlockPos t = Template.rotate(new BlockPos(dx, 0, dz), back(r));
				// the square, the gate passage (the gate stands at the square's level) and the main plots
				fixed[i] |= Math.abs(t.getX()) <= 3 && Math.abs(t.getZ()) <= 3 || t.getZ() >= 36 && Math.abs(t.getX()) <= 2 || level0.contains(c);
			}
			// streets stay within the height plots may follow them to, so a building can always stand at its street
			target[i] = Math.max(-Settlements.STREET_LIFT, Math.min(Settlements.STREET_LIFT, sum / units.get(i).size()));
		}
		double[] v = target.clone();
		for (int i = 0; i < n; i++) {
			if (fixed[i]) {
				v[i] = 0;
			}
		}
		// smoothed along the network: a street follows the land's shape, not its bumps
		for (int pass = 0; pass < 40; pass++) {
			double[] next = v.clone();
			for (int i = 0; i < n; i++) {
				if (fixed[i] || adj.get(i).isEmpty()) {
					continue;
				}
				double sum = 0;
				for (int j : adj.get(i)) {
					sum += v[j];
				}
				next[i] = 0.5 * v[i] + 0.5 * sum / adj.get(i).size();
			}
			v = next;
		}
		double[] smooth = v;
		double[] grade = new double[n];
		for (int i = 0; i < n; i++) {
			grade[i] = laneUnit.get(i) ? LANE_GRADE : TRUNK_GRADE;
		}
		v = limit(n, adj, smooth, grade, fixed);
		Set<Integer> steepUnits = new HashSet<>();
		// lanes that would cut or fill too much for a gentle slope become stepped lanes where they are that steep
		for (int i = 0; i < n; i++) {
			if (laneUnit.get(i) && !fixed[i] && Math.abs(v[i] - target[i]) > STAIR_TRIGGER) {
				steepUnits.add(i);
				grade[i] = STAIR_GRADE;
			}
		}
		if (!steepUnits.isEmpty()) {
			v = limit(n, adj, smooth, grade, fixed);
		}
		int[] halfV = new int[n];
		for (int i = 0; i < n; i++) {
			halfV[i] = fixed[i] ? 0 : (int) Math.round(v[i] * 2);
		}
		// rounding may leave two units a whole block apart where the grade allows less: pulled together again
		for (int pass = 0; pass < 8; pass++) {
			for (int i = 0; i < n; i++) {
				if (fixed[i]) {
					continue;
				}
				for (int j : adj.get(i)) {
					int most = (int) Math.ceil(2 * Math.max(grade[i], grade[j]) - 1e-9);
					if (halfV[i] - halfV[j] > most) {
						halfV[i] = halfV[j] + most;
					} else if (halfV[j] - halfV[i] > most) {
						halfV[i] = halfV[j] - most;
					}
				}
			}
		}
		Map<Long, Integer> half = new HashMap<>();
		Set<Long> steep = new HashSet<>();
		for (Map.Entry<Long, Integer> e : unitOf.entrySet()) {
			half.put(e.getKey(), halfV[e.getValue()]);
			if (steepUnits.contains(e.getValue())) {
				steep.add(e.getKey());
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

	/**
	 * The profile closest to {@code start} that keeps every step between neighbouring units within their grade and meets
	 * the fixed units: the middle of the highest and the lowest such profile under/over {@code start} (both keep the
	 * grade, so their middle does too), clamped into what the fixed units allow (also within the grade).
	 */
	private static double[] limit(int n, List<Set<Integer>> adj, double[] start, double[] grade, boolean[] fixed) {
		double[] under = relax(n, adj, start, grade, true);
		double[] over = relax(n, adj, start, grade, false);
		double[] capHigh = new double[n];
		double[] capLow = new double[n];
		for (int i = 0; i < n; i++) {
			capHigh[i] = fixed[i] ? 0 : 1e9;
			capLow[i] = fixed[i] ? 0 : -1e9;
		}
		capHigh = relax(n, adj, capHigh, grade, true);
		capLow = relax(n, adj, capLow, grade, false);
		double[] v = new double[n];
		for (int i = 0; i < n; i++) {
			v[i] = fixed[i] ? 0 : Math.max(capLow[i], Math.min(capHigh[i], (under[i] + over[i]) / 2));
		}
		return v;
	}

	/** The highest profile at or under {@code start} keeping the grade ({@code down}), or the lowest at or over it. */
	private static double[] relax(int n, List<Set<Integer>> adj, double[] start, double[] grade, boolean down) {
		double[] v = start.clone();
		java.util.PriorityQueue<double[]> pq = new java.util.PriorityQueue<>(
				(a, b) -> down ? Double.compare(a[0], b[0]) : Double.compare(b[0], a[0]));
		for (int i = 0; i < n; i++) {
			pq.add(new double[] {v[i], i});
		}
		while (!pq.isEmpty()) {
			double[] top = pq.poll();
			int i = (int) top[1]; // a unit index: small, exact as a double
			if (top[0] != v[i]) {
				continue;
			}
			for (int j : adj.get(i)) {
				double g = Math.max(grade[i], grade[j]);
				double bound = down ? top[0] + g : top[0] - g;
				if (down ? bound < v[j] : bound > v[j]) {
					v[j] = bound;
					pq.add(new double[] {bound, j});
				}
			}
		}
		return v;
	}

	/** The world column of a lane's goal (the cell in front of its door), from its name "track_level_x_z" (m = minus). */
	private static long goal(String name, Rotation r, BlockPos h) {
		String[] p = name.split("_");
		try {
			int x = Integer.parseInt(p[p.length - 2].replace('m', '-'));
			int z = Integer.parseInt(p[p.length - 1].replace('m', '-'));
			// lane names are in the layout's coordinates (tools/buildings.py: HEART = (7, 0, 10))
			BlockPos rel = Template.rotate(new BlockPos(x - 7, 0, z - 10), r);
			return BlockPos.asLong(h.getX() + rel.getX(), 0, h.getZ() + rel.getZ());
		} catch (NumberFormatException e) {
			return Long.MIN_VALUE;
		}
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
