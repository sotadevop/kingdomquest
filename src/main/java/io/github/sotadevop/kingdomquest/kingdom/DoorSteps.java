package io.github.sotadevop.kingdomquest.kingdom;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Every door can be walked to (user, 2026-11-19, "do all"; field test: on raised plots the lane met the plot's plinth
 * 2 to 4 blocks below its top, beside the house's own ramp, which only runs straight out from the door). After a
 * build, a walk from the square finds the doors it can't reach; for each, the cheapest route over the land to a reached
 * cell is searched (any step allowed, big steps cost), and every step on it too high to walk gets a flight of stone
 * steps on its lower side. The steps belong to the door's building: repairs keep them, roads go round them.
 */
final class DoorSteps {
	/** How far from a door the route to walkable ground may run. */
	private static final int RANGE = 24;
	private static final int MAX_STEP = 8;

	private DoorSteps() {
	}

	static Settlement connect(MinecraftServer server, Settlement s) {
		ServerLevel level = server.overworld();
		Set<Long> reach = new HashSet<>();
		ArrayDeque<long[]> queue = new ArrayDeque<>();
		BlockPos h = s.heart();
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				for (int dy = -2; dy <= 3; dy++) {
					long key = BlockPos.asLong(h.getX() + dx, h.getY() + dy, h.getZ() + dz);
					if (!Double.isNaN(FieldTest.feet(level, h.getX() + dx, h.getY() + dy, h.getZ() + dz, false)) && reach.add(key)) {
						queue.add(new long[] {key});
					}
				}
			}
		}
		FieldTest.spread(level, h, reach, queue, Set.of(), 400_000);
		Set<Long> all = new HashSet<>();
		s.built().values().forEach(all::addAll);
		Settlement out = s;
		for (Map.Entry<String, List<Long>> e : s.built().entrySet()) {
			String track = e.getKey();
			if (!Settlements.LIFTED.contains(track) && !Settlement.MAIN.equals(track)) {
				continue;
			}
			List<Long> added = new ArrayList<>();
			for (long p : e.getValue()) {
				BlockPos d = BlockPos.of(p);
				BlockState st = level.getBlockState(d);
				if (!(st.getBlock() instanceof DoorBlock) || st.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
					continue;
				}
				Direction f = st.getValue(DoorBlock.FACING);
				List<BlockPos> fronts = new ArrayList<>();
				for (Direction side : new Direction[] {f, f.getOpposite()}) {
					BlockPos c = d.relative(side);
					if (!all.contains(c.asLong()) || !level.getBlockState(c).isSolid()) {
						fronts.add(c);
					}
				}
				if (reached(reach, d, fronts)) {
					continue;
				}
				List<long[]> route = route(level, fronts, reach, all);
				if (route != null) {
					added.addAll(lay(level, s, route, all));
				}
			}
			if (!added.isEmpty()) {
				List<Long> with = new ArrayList<>(out.built().getOrDefault(track, List.of()));
				with.addAll(added);
				out = out.withBuilt(track, with);
				all.addAll(added);
			}
		}
		return out;
	}

	private static boolean reached(Set<Long> reach, BlockPos door, List<BlockPos> fronts) {
		for (BlockPos c : fronts) {
			for (int dy = -1; dy <= 1; dy++) {
				if (reach.contains(c.above(dy).asLong())) {
					return true;
				}
			}
		}
		return reach.contains(door.asLong());
	}

	/** Cheapest route (cells x, y, z, feet*100) from a door's front to a reached cell; big steps cost much more. */
	private static List<long[]> route(ServerLevel level, List<BlockPos> fronts, Set<Long> reach, Set<Long> built) {
		Map<Long, Double> cost = new HashMap<>();
		Map<Long, Long> from = new HashMap<>();
		PriorityQueue<Map.Entry<Long, Double>> pq = new PriorityQueue<>((a, b) -> Double.compare(a.getValue(), b.getValue()));
		if (fronts.isEmpty()) {
			return null;
		}
		BlockPos origin = fronts.get(0);
		for (BlockPos c : fronts) {
			for (int dy = -1; dy <= 1; dy++) {
				double f = FieldTest.feet(level, c.getX(), c.getY() + dy, c.getZ(), false);
				if (!Double.isNaN(f)) {
					long k = c.above(dy).asLong();
					cost.put(k, 0.0);
					pq.add(Map.entry(k, 0.0));
				}
			}
		}
		long goal = Long.MIN_VALUE;
		int seen = 0;
		while (!pq.isEmpty() && seen++ < 20_000) {
			Map.Entry<Long, Double> top = pq.poll();
			long k = top.getKey();
			if (top.getValue() > cost.getOrDefault(k, Double.MAX_VALUE)) {
				continue;
			}
			if (reach.contains(k)) {
				goal = k;
				break;
			}
			int x = BlockPos.getX(k), y = BlockPos.getY(k), z = BlockPos.getZ(k);
			double f = FieldTest.feet(level, x, y, z, false);
			for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
				int nx = x + d[0], nz = z + d[1];
				if (Math.abs(nx - origin.getX()) > RANGE || Math.abs(nz - origin.getZ()) > RANGE) {
					continue;
				}
				for (int dy = MAX_STEP; dy >= -MAX_STEP; dy--) {
					double g = FieldTest.feet(level, nx, y + dy, nz, false);
					if (Double.isNaN(g) || Math.abs(g - f) > MAX_STEP) {
						continue;
					}
					double rise = Math.abs(g - f);
					double c = top.getValue() + 1 + (rise <= FieldTest.STEP ? 0 : 6 * rise);
					long nk = BlockPos.asLong(nx, y + dy, nz);
					if (c < cost.getOrDefault(nk, Double.MAX_VALUE)) {
						cost.put(nk, c);
						from.put(nk, k);
						pq.add(Map.entry(nk, c));
					}
				}
			}
		}
		if (goal == Long.MIN_VALUE) {
			return null;
		}
		List<long[]> path = new ArrayList<>();
		for (Long k = goal; k != null; k = from.get(k)) {
			int x = BlockPos.getX(k), y = BlockPos.getY(k), z = BlockPos.getZ(k);
			path.add(new long[] {x, y, z, Math.round(FieldTest.feet(level, x, y, z, false) * 100)});
		}
		return path; // from the reached cell to the door
	}

	/** Lays a flight of steps on the lower side of every step on the route that is too high to walk. */
	private static List<Long> lay(ServerLevel level, Settlement s, List<long[]> path, Set<Long> built) {
		List<Long> placed = new ArrayList<>();
		Block[] mat = material(level, s);
		for (int i = 0; i + 1 < path.size(); i++) {
			long[] a = path.get(i), b = path.get(i + 1);
			double fa = a[3] / 100.0, fb = b[3] / 100.0;
			if (Math.abs(fb - fa) <= FieldTest.STEP) {
				continue;
			}
			long[] lo = fb > fa ? a : b, hi = fb > fa ? b : a;
			int dx = (int) (lo[0] - hi[0]), dz = (int) (lo[2] - hi[2]);
			Direction up = dx < 0 ? Direction.EAST : dx > 0 ? Direction.WEST : dz < 0 ? Direction.SOUTH : Direction.NORTH; // the way it climbs
			double high = hi[3] / 100.0, low = lo[3] / 100.0;
			int top = (int) Math.ceil(high - 0.01); // a slab-high top (4.5) counts as 5: the last stair still meets it
			// on the lower side first: the flight stands out from the high ground, each step a block lower
			List<BlockPos> steps = new ArrayList<>();
			boolean ok = true;
			for (int k = 1; top - k > low + 0.01 && ok; k++) {
				BlockPos cell = new BlockPos((int) hi[0] + dx * k, top - k, (int) hi[2] + dz * k); // the body's cell on step k
				ok = free(level, cell, built) && !built.contains(cell.below().asLong());
				steps.add(cell.below());
			}
			boolean cut = false;
			if (!ok || steps.isEmpty()) {
				// the lower side is taken (the door's own floor, a neighbour): the flight is cut into the high ground instead,
				// starting at the low cell's level and climbing away from it
				steps.clear();
				ok = true;
				int base = (int) Math.floor(low + 0.01);
				for (int k = 1; base + k < high - 0.01 && ok; k++) {
					BlockPos cell = new BlockPos((int) lo[0] - dx * k, base + k, (int) lo[2] - dz * k);
					ok = !built.contains(cell.below().asLong()) && !built.contains(cell.asLong()) && !built.contains(cell.above().asLong());
					steps.add(cell.below());
				}
				cut = true;
			}
			if (!ok || steps.isEmpty()) {
				continue;
			}
			for (BlockPos st : steps) {
				if (cut) {
					for (int y = 1; y <= 3; y++) { // headroom through the cut
						BlockPos o = st.above(y);
						if (!built.contains(o.asLong()) && SiteWork.natural(level.getBlockState(o))) {
							level.setBlock(o, Blocks.AIR.defaultBlockState(), SiteWork.FLAGS);
						}
					}
				}
				level.setBlock(st, mat[1].defaultBlockState().setValue(StairBlock.FACING, up), SiteWork.FLAGS);
				placed.add(st.asLong());
				// carried down to the land: a flight on a stone base, not on stilts
				for (BlockPos u = st.below(); u.getY() > level.getMinY() && !built.contains(u.asLong()); u = u.below()) {
					BlockState there = level.getBlockState(u);
					if (!(there.isAir() || there.canBeReplaced() || !there.getFluidState().isEmpty() || SiteWork.plant(there))) {
						break;
					}
					level.setBlock(u, mat[0].defaultBlockState(), SiteWork.FLAGS);
					placed.add(u.asLong());
				}
			}
		}
		return placed;
	}

	/** Room to stand on a step: two open blocks, nothing built. */
	private static boolean free(ServerLevel level, BlockPos cell, Set<Long> built) {
		for (int y = 0; y <= 1; y++) {
			BlockPos head = cell.above(y);
			BlockState st = level.getBlockState(head);
			if (built.contains(head.asLong()) || !(st.isAir() || st.canBeReplaced())) {
				return false;
			}
		}
		return true;
	}

	/** Block and stairs of the steps, from the region's stone. */
	private static Block[] material(ServerLevel level, Settlement s) {
		boolean red = SiteWork.surface(level, s.heart()).is(Blocks.RED_SAND) || SiteWork.surface(level, s.heart()).is(Blocks.TERRACOTTA);
		return switch (s.style()) {
			case "arid" -> red ? new Block[] {Blocks.RED_SANDSTONE, Blocks.RED_SANDSTONE_STAIRS} : new Block[] {Blocks.SANDSTONE, Blocks.SANDSTONE_STAIRS};
			case "wild" -> new Block[] {Blocks.MOSSY_COBBLESTONE, Blocks.MOSSY_COBBLESTONE_STAIRS};
			case "frozen" -> new Block[] {Blocks.COBBLESTONE, Blocks.COBBLESTONE_STAIRS};
			default -> new Block[] {Blocks.STONE_BRICKS, Blocks.STONE_BRICK_STAIRS};
		};
	}
}
