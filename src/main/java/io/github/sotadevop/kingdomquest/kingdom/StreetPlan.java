package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.building.Template;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Rotation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The street plan: the height of every road column of the settlement, read from the grading plan ({@link Grade}) of the
 * site, so streets lie on the land the site was shaped to and never change with later builds. On the flat plateau every
 * street is level with the square; on the hills of the hill looks a street climbs with the land: a single step gets a
 * slab on its lower block; where it climbs two or more over four blocks it becomes a stair street (the big stairs of the
 * motte, the stepped alleys of a hill town).
 * Heights are kept in half blocks: a .5 is a slab on the road.
 */
final class StreetPlan {
	private static final Map<UUID, StreetPlan> PLANS = new HashMap<>();

	/** World column -> feet height relative to the heart, in half blocks. */
	private final Map<Long, Integer> half;
	/** World columns of stepped lanes (stairs). */
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

	/** The settlement's street plan, or null while its grading plan isn't there (an old site not loaded yet). */
	static StreetPlan of(MinecraftServer server, Settlement s) {
		StreetPlan plan = PLANS.get(s.id());
		if (plan != null) {
			return plan;
		}
		Grade grade = Grade.of(server, s);
		Template roads = Template.get(s.style() + "/road_4");
		if (grade == null || roads == null) {
			return null;
		}
		plan = compute(s, grade, roads);
		PLANS.put(s.id(), plan);
		return plan;
	}

	private static StreetPlan compute(Settlement s, Grade grade, Template roads) {
		Rotation r = Rotation.values()[s.rotation()];
		BlockPos h = s.heart();
		Map<Long, Integer> feet = new HashMap<>();
		for (Template.Placement p : roads.placements("white", r)) {
			BlockPos c = h.offset(p.offset());
			long col = BlockPos.asLong(c.getX(), 0, c.getZ());
			int top = grade.topAt(c.getX(), c.getZ());
			if (top != Integer.MIN_VALUE) {
				feet.put(col, top + 1 - h.getY());
			}
		}
		// a street never steps two blocks: where a terrace meets a hill's street, the lower cells are raised (filled
		// under) until every step is one block, so the road ramps instead of leaving a step to jump
		int[][] near = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		for (boolean changed = true; changed; ) {
			changed = false;
			for (Map.Entry<Long, Integer> e : feet.entrySet()) {
				int x = BlockPos.getX(e.getKey()), z = BlockPos.getZ(e.getKey());
				for (int[] d : near) {
					Integer n = feet.get(BlockPos.asLong(x + d[0], 0, z + d[1]));
					if (n != null && n - e.getValue() >= 2) {
						e.setValue(n - 1);
						changed = true;
					}
				}
			}
		}
		Map<Long, Integer> half = new HashMap<>();
		Set<Long> steep = new HashSet<>();
		int[][] four = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		for (Map.Entry<Long, Integer> e : feet.entrySet()) {
			int x = BlockPos.getX(e.getKey()), z = BlockPos.getZ(e.getKey()), f = e.getValue();
			boolean up = false;
			for (int[] d : four) {
				Integer n = feet.get(BlockPos.asLong(x + d[0], 0, z + d[1]));
				if (n != null && n == f + 1) {
					up = true;
				}
				// climbing two or more over four blocks: a stair street (big stairs, stepped alleys up the hills)
				Integer far = feet.get(BlockPos.asLong(x + 2 * d[0], 0, z + 2 * d[1]));
				Integer back = feet.get(BlockPos.asLong(x - 2 * d[0], 0, z - 2 * d[1]));
				if (far != null && back != null && far - back >= 2) {
					steep.add(e.getKey());
				}
			}
			half.put(e.getKey(), 2 * f + (up ? 1 : 0));
		}
		return new StreetPlan(Map.copyOf(half), Set.copyOf(steep));
	}
}
