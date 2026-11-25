package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.building.Template;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Roads between places: from a settlement's gate to another of the player's settlements, a village or the dock.
 * Every link is stored on the settlement it starts from ({@code names} "link|<target>" = "x,y,z"), so the realm
 * map can show it and it can be upgraded. Two tiers, like real country roads: a trodden dirt road, and once the
 * village's own roads are cobblestone (roads level 3), gravel with cobble edges and lantern posts.
 * Water is crossed on a stone bridge two blocks above the surface: ramps on both banks, railings with lanterns,
 * and pillars with arches down to the riverbed.
 */
final class RoadLink {
	private record Job(UUID player, String settlement, String key, List<BlockPos> line, int[] index, int tier, int[] height, boolean[] water) {
	}

	private static final Map<String, Job> JOBS = new HashMap<>();
	private static final int PER_TICK = 6;
	private static final int MAX_LENGTH = 1200;
	static final String PREFIX = "link|";

	private RoadLink() {
	}

	/** The point just outside a settlement's gate (beyond the moat and the landing), in world coordinates. */
	static BlockPos gateExit(Settlement s) {
		return s.heart().offset(Template.rotate(new BlockPos(0, 0, io.github.sotadevop.kingdomquest.building.Layout.ring(Settlements.round(s))[3] + 11), Rotation.values()[s.rotation()]));
	}

	static int tier(Settlement s) {
		return s.level(Progression.ROADS) >= 3 ? 2 : 1;
	}

	/** Old menu buttons: a road to the nearest own settlement or the nearest village. */
	static void connect(ServerPlayer player, Settlement from, boolean village) {
		MinecraftServer server = player.level().getServer();
		BlockPos start = gateExit(from);
		if (village) {
			BlockPos v = server.overworld().findNearestMapStructure(StructureTags.VILLAGE, start, 64, false);
			if (v == null) {
				player.sendOverlayMessage(Component.translatable("kingdomquest.link.no_village"));
				return;
			}
			link(server, player, from, "v:" + v.getX() + "," + v.getZ(), v, "the village");
			Roadside.planned(server, from, "v:" + v.getX() + "," + v.getZ(), start, v);
			return;
		}
		Settlement best = null;
		for (Settlement o : Settlements.all(server)) {
			if (!o.id().equals(from.id()) && o.owner().equals(player.getUUID())
					&& (best == null || o.heart().distSqr(from.heart()) < best.heart().distSqr(from.heart()))) {
				best = o;
			}
		}
		if (best == null) {
			player.sendOverlayMessage(Component.translatable("kingdomquest.link.no_kingdom"));
			return;
		}
		link(server, player, from, best.id().toString(), gateExit(best), best.name());
		Roadside.planned(server, from, best.id().toString(), start, gateExit(best));
	}

	/** Builds (or rebuilds at the current tier) a road from {@code from}'s gate to {@code end} and records it. */
	static void link(MinecraftServer server, ServerPlayer player, Settlement from, String key, BlockPos end, String name) {
		String id = from.id() + "|" + key;
		if (JOBS.containsKey(id)) {
			return;
		}
		// from the way out that gives the shortest road: the gate or a side gate
		List<BlockPos[]> route = null;
		double shortest = Double.MAX_VALUE;
		for (BlockPos[] exit : Settlements.exits(from)) {
			List<BlockPos[]> legs = legs(from, exit[0], exit[1], end);
			double len = 0;
			for (BlockPos[] leg : legs) {
				len += Math.sqrt(leg[0].distSqr(leg[1]));
			}
			if (len < shortest - 8) { // the gate unless a side gate is clearly shorter
				shortest = len;
				route = legs;
			}
		}
		BlockPos start = route.get(0)[0];
		if (Math.sqrt(end.distSqr(start)) > MAX_LENGTH) {
			if (player != null) {
				player.sendOverlayMessage(Component.translatable("kingdomquest.link.too_far"));
			}
			return;
		}
		Settlements.putState(server, from.withName(PREFIX + key, end.getX() + "," + end.getY() + "," + end.getZ()));
		List<BlockPos> line = new ArrayList<>();
		for (BlockPos[] leg : route) {
			int dx = leg[1].getX() - leg[0].getX();
			int dz = leg[1].getZ() - leg[0].getZ();
			int n = Math.max(1, Math.max(Math.abs(dx), Math.abs(dz)));
			for (int i = line.isEmpty() ? 0 : 1; i <= n; i++) {
				line.add(new BlockPos(leg[0].getX() + Math.round((float) dx * i / n), from.heart().getY(), leg[0].getZ() + Math.round((float) dz * i / n)));
			}
		}
		int steps = line.size() - 1;
		JOBS.put(id, new Job(from.owner(), from.id().toString(), key, line, new int[] {0}, tier(from), null, null));
		ServerPlayer p = player != null ? player : server.getPlayerList().getPlayer(from.owner());
		if (p != null) {
			People.say(p, "Architect", "My workers will build a road to " + name + ". It's " + steps + " blocks long.");
		}
	}

	/**
	 * The way of a road out of the gate (user, 2026-11-18: a road to a village ran straight back over the drawbridge,
	 * through the gate and the town, hanging over the moat): first 12 blocks straight away from the gate, then to the
	 * end, around the castle (its ring and moat with 10 to spare) by one or two of its corners if the straight way
	 * would cross it. Legs as {from, to}.
	 */
	static List<BlockPos[]> legs(Settlement s, BlockPos start, BlockPos out, BlockPos end) {
		Rotation turn = Rotation.values()[s.rotation()];
		BlockPos first = start.offset(out.getX() * 12, 0, out.getZ() * 12);
		BlockPos a = s.heart().offset(Template.rotate(new BlockPos(io.github.sotadevop.kingdomquest.building.Layout.ring(Settlements.round(s))[0] - 22, 0, io.github.sotadevop.kingdomquest.building.Layout.ring(Settlements.round(s))[1] - 22), turn));
		BlockPos b = s.heart().offset(Template.rotate(new BlockPos(io.github.sotadevop.kingdomquest.building.Layout.ring(Settlements.round(s))[2] + 22, 0, io.github.sotadevop.kingdomquest.building.Layout.ring(Settlements.round(s))[3] + 22), turn));
		int x0 = Math.min(a.getX(), b.getX()), x1 = Math.max(a.getX(), b.getX());
		int z0 = Math.min(a.getZ(), b.getZ()), z1 = Math.max(a.getZ(), b.getZ());
		int[] box = {x0 + 12, z0 + 12, x1 - 12, z1 - 12}; // what the road must not cross: the ring and moat, 10 to spare
		List<BlockPos[]> legs = new ArrayList<>();
		legs.add(new BlockPos[] {start, first});
		if (!crosses(first, end, box)) {
			legs.add(new BlockPos[] {first, end});
			return legs;
		}
		BlockPos[] corners = {new BlockPos(x0, start.getY(), z0), new BlockPos(x1, start.getY(), z0), new BlockPos(x1, start.getY(), z1),
				new BlockPos(x0, start.getY(), z1)};
		List<BlockPos> best = null;
		double bestLen = Double.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			for (int j = -1; j < 4; j++) { // one corner (j = -1) or two neighbouring corners
				if (j >= 0 && Math.abs(i - j) != 1 && Math.abs(i - j) != 3) {
					continue;
				}
				List<BlockPos> via = j < 0 ? List.of(corners[i]) : List.of(corners[i], corners[j]);
				List<BlockPos> path = new ArrayList<>();
				path.add(first);
				path.addAll(via);
				path.add(end);
				boolean ok = true;
				double len = 0;
				for (int k = 0; k + 1 < path.size() && ok; k++) {
					ok = !crosses(path.get(k), path.get(k + 1), box);
					len += Math.sqrt(path.get(k).distSqr(path.get(k + 1)));
				}
				if (ok && len < bestLen) {
					bestLen = len;
					best = path;
				}
			}
		}
		if (best == null) {
			legs.add(new BlockPos[] {first, end});
			return legs;
		}
		for (int k = 0; k + 1 < best.size(); k++) {
			legs.add(new BlockPos[] {best.get(k), best.get(k + 1)});
		}
		return legs;
	}

	/** Whether the straight way from a to b passes through the box {x0, z0, x1, z1}. */
	private static boolean crosses(BlockPos a, BlockPos b, int[] box) {
		int n = Math.max(1, Math.max(Math.abs(b.getX() - a.getX()), Math.abs(b.getZ() - a.getZ())));
		for (int i = 0; i <= n; i++) {
			double x = a.getX() + (double) (b.getX() - a.getX()) * i / n, z = a.getZ() + (double) (b.getZ() - a.getZ()) * i / n;
			if (x > box[0] && x < box[2] && z > box[1] && z < box[3]) {
				return true;
			}
		}
		return false;
	}

	/** The village roads reached cobblestone: every road out of this settlement is rebuilt at the better tier. */
	static void upgradeAll(MinecraftServer server, Settlement s) {
		for (Map.Entry<String, String> e : s.names().entrySet()) {
			if (e.getKey().startsWith(PREFIX)) {
				String[] p = e.getValue().split(",");
				link(server, null, s, e.getKey().substring(PREFIX.length()),
						new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])), "every connected place");
			}
		}
	}

	/** The point from a link key: another settlement's gate, or a stored position. */
	static BlockPos target(MinecraftServer server, String key, String value) {
		String[] p = value.split(",");
		return new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
	}

	// ---- building ----

	/**
	 * The top block of a column and whether it is water (or lava): the road's ground. Trees, plants and ice spikes are
	 * looked through (user, 2026-11-25: between packed ice spikes the road "bridged" over dry land, the spike tops
	 * taken for banks); ice lying on water counts as the water.
	 */
	private static int[] surfaceAt(ServerLevel level, int x, int z) {
		int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, top, z);
		while (pos.getY() > level.getMinY()) {
			BlockState st = level.getBlockState(pos);
			if (st.getBlock() instanceof LiquidBlock) {
				return new int[] {pos.getY(), 1};
			}
			if (st.is(Blocks.ICE) || st.is(Blocks.FROSTED_ICE)) {
				BlockState under = level.getBlockState(pos.below());
				if (under.getBlock() instanceof LiquidBlock || under.is(Blocks.ICE)) {
					return new int[] {pos.getY() - 1, 1};
				}
			}
			if (iceSpike(level, pos, st)) {
				pos.move(0, -1, 0);
				continue;
			}
			if (!st.isAir() && !st.canBeReplaced() && !st.is(BlockTags.LEAVES) && !st.is(BlockTags.LOGS) && st.isSolid()) {
				return new int[] {pos.getY(), 0};
			}
			pos.move(0, -1, 0);
		}
		return new int[] {pos.getY(), 0};
	}

	/**
	 * Packed or blue ice: ice spikes and glaciers, never ground a road or a site stands on (user, 2026-11-25: the road and
	 * the walls were built up and over ice spikes). Under it lies the land (snow, dirt, stone); the builders clear it.
	 */
	static boolean iceSpike(ServerLevel level, BlockPos pos, BlockState st) {
		if (!st.is(Blocks.PACKED_ICE) && !st.is(Blocks.BLUE_ICE)) {
			return false;
		}
		// an iceberg floats in the sea: it stays what it is (the sea's surface for a road, untouched by the shaping)
		BlockPos.MutableBlockPos p = pos.mutable();
		for (int d = 0; d < 64; d++) {
			p.move(0, -1, 0);
			BlockState below = level.getBlockState(p);
			if (below.is(Blocks.PACKED_ICE) || below.is(Blocks.BLUE_ICE) || below.is(Blocks.SNOW_BLOCK) || below.is(Blocks.ICE)) {
				continue;
			}
			return below.getFluidState().isEmpty();
		}
		return true;
	}

	/**
	 * Water is crossed straight, at a right angle like a real bridge (user, 2026-11-18: a diagonal bridge had zigzag
	 * railings that looked like castle walls in the river). Where the line meets water at a slant, the crossing is
	 * replaced by the shortest straight crossing along an axis that still leads on (east, west, north or south from the
	 * last bank cell), and the road joins its old line again on the far bank.
	 */
	private static List<BlockPos> straighten(ServerLevel level, List<BlockPos> line) {
		List<BlockPos> out = new ArrayList<>(line);
		BlockPos end = line.get(line.size() - 1);
		for (int round = 0; round < 6; round++) {
			int n = out.size();
			boolean[] wet = new boolean[n];
			for (int k = 0; k < n; k++) {
				wet[k] = surfaceAt(level, out.get(k).getX(), out.get(k).getZ())[1] == 1;
			}
			boolean changed = false;
			for (int i = 1; i < n && !changed; i++) {
				if (!wet[i] || wet[i - 1]) {
					continue;
				}
				int j = i;
				while (j < n && wet[j]) {
					j++;
				}
				if (j >= n) {
					break; // the end lies in water: nothing to join
				}
				boolean straight = true;
				for (int k = i; k <= j; k++) {
					straight &= out.get(k).getX() == out.get(i - 1).getX() || out.get(k).getZ() == out.get(i - 1).getZ();
				}
				// the narrowest straight crossing near here (user, 2026-11-25: fix the bridges reliably): from a bank cell up
				// to 12 back along the road, east, west, north or south, a few dry blocks up to the water, across, and land
				Direction best = null;
				int bestLen = 0, bestFrom = i - 1;
				double bestCost = Double.MAX_VALUE;
				int wetHere = j - i;
				for (int ia = i - 1; ia >= Math.max(0, i - 13); ia--) {
					BlockPos a = out.get(ia);
					for (Direction d : new Direction[] {Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH}) {
						// it must lead on: towards the far bank of the old crossing and the road's end
						int toward = (out.get(j).getX() - a.getX()) * d.getStepX() + (out.get(j).getZ() - a.getZ()) * d.getStepZ();
						int onward = (end.getX() - a.getX()) * d.getStepX() + (end.getZ() - a.getZ()) * d.getStepZ();
						if (toward <= 0 || onward <= 0) {
							continue;
						}
						int len = 1, dry = 0, water = 0;
						while (len < 96 && water == 0 && surfaceAt(level, a.getX() + d.getStepX() * len, a.getZ() + d.getStepZ() * len)[1] == 0) {
							dry++;
							len++;
							if (dry > 6) {
								break;
							}
						}
						if (dry > 6) {
							continue; // no water ahead this way
						}
						while (len < 96 && surfaceAt(level, a.getX() + d.getStepX() * len, a.getZ() + d.getStepZ() * len)[1] == 1) {
							water++;
							len++;
						}
						if (len >= 96 || water == 0) {
							continue;
						}
						double cost = 2.0 * water + dry + 0.5 * (i - 1 - ia);
						if (cost < bestCost && (straight ? water < wetHere : true)) {
							bestCost = cost;
							bestLen = len;
							best = d;
							bestFrom = ia;
						}
					}
				}
				if (best == null || straight && 2.0 * wetHere <= bestCost) {
					continue;
				}
				BlockPos a = out.get(bestFrom);
				BlockPos land = new BlockPos(a.getX() + best.getStepX() * bestLen, a.getY(), a.getZ() + best.getStepZ() * bestLen);
				int m = j; // where the old line goes on, nearest the landing
				for (int k = j; k < n; k++) {
					if (out.get(k).distManhattan(land) < out.get(m).distManhattan(land)) {
						m = k;
					}
				}
				List<BlockPos> next = new ArrayList<>(out.subList(0, bestFrom + 1));
				for (int k = 1; k <= bestLen; k++) {
					next.add(new BlockPos(a.getX() + best.getStepX() * k, a.getY(), a.getZ() + best.getStepZ() * k));
				}
				BlockPos join = out.get(m);
				int steps = Math.max(Math.abs(join.getX() - land.getX()), Math.abs(join.getZ() - land.getZ()));
				for (int k = 1; k < steps; k++) {
					next.add(new BlockPos(land.getX() + Math.round((float) (join.getX() - land.getX()) * k / steps), a.getY(),
							land.getZ() + Math.round((float) (join.getZ() - land.getZ()) * k / steps)));
				}
				next.addAll(out.subList(m, n));
				out = next;
				changed = true;
			}
			if (!changed) {
				break;
			}
		}
		return out;
	}

	/**
	 * The road's height over land, in half blocks, from the ground's: no single-block bumps (a boulder, a stump), and
	 * at most half a block up or down per block where the land allows it, so the road climbs on slabs instead of steps
	 * (user, 2026-11-25: a hillside road went up in full-block steps); never more than 4 blocks off the ground, and
	 * never a full block per block more. Crossings ({@code w}) are left out: the bridges set their own heights.
	 */
	static int[] smooth(int[] g, boolean[] w) {
		int n = g.length;
		int[] t = new int[n];
		for (int i = 0; i < n; i++) {
			t[i] = 2 * g[i] + 2;
		}
		for (int i = 0; i < n; ) {
			if (w[i]) {
				i++;
				continue;
			}
			int j = i;
			while (j < n && !w[j]) {
				j++;
			}
			int len = j - i;
			int[] s = new int[len];
			for (int k = 0; k < len; k++) { // the middle of five: a bump of one or two blocks is gone
				int[] win = new int[5];
				for (int d = -2; d <= 2; d++) {
					win[d + 2] = t[i + Math.max(0, Math.min(len - 1, k + d))];
				}
				java.util.Arrays.sort(win);
				s[k] = win[2];
			}
			int[] lo = s.clone(), hi = s.clone();
			for (int k = 1; k < len; k++) {
				lo[k] = Math.min(lo[k], lo[k - 1] + 1);
				hi[k] = Math.max(hi[k], hi[k - 1] - 1);
			}
			for (int k = len - 2; k >= 0; k--) {
				lo[k] = Math.min(lo[k], lo[k + 1] + 1);
				hi[k] = Math.max(hi[k], hi[k + 1] - 1);
			}
			for (int k = 0; k < len; k++) {
				int m = Math.floorDiv(lo[k] + hi[k], 2);
				int ground = t[i + k];
				t[i + k] = Math.max(ground - 8, Math.min(ground + 8, m));
			}
			for (int k = 1; k < len; k++) {
				t[i + k] = Math.max(t[i + k - 1] - 2, Math.min(t[i + k - 1] + 2, t[i + k]));
			}
			i = j;
		}
		return t;
	}

	/** Water a boat passes under a bridge's arches: the deck lies this many blocks above the water's top block. */
	private static final int DECK_ABOVE_WATER = 4;

	/**
	 * The road's height along the line, in half blocks (the top of what one walks on: 2 * y + 2 on a full block at y,
	 * one more on a slab over it). Bridges: one deck height per crossing, {@link #DECK_ABOVE_WATER} over the water so
	 * boats pass under the arches, never below either bank; ramps of half a block per block (slabs) on both banks.
	 */
	private static void profile(ServerLevel level, Job job) {
		// through its waystations (chosen now on a new road, kept on one laid again), then straight over the water
		List<BlockPos> line = straighten(level, Roadside.route(level, job.settlement(), job.key(), job.line()));
		int n = line.size();
		int[] g = new int[n];
		boolean[] w = new boolean[n];
		boolean[] liquid = new boolean[n];
		for (int i = 0; i < n; i++) {
			int[] sa = surfaceAt(level, line.get(i).getX(), line.get(i).getZ());
			g[i] = sa[0];
			w[i] = liquid[i] = sa[1] == 1;
		}
		int[] deckOf = new int[n];
		// a hole in the ground (a ravine, a cave mouth): where the land drops 5 or more in one step and comes back up
		// within 32 blocks, after 3 or more, the road keeps its height and crosses on a bridge, the same as over water
		for (int i = 1; i < n; i++) {
			if (w[i] || w[i - 1] || g[i - 1] - g[i] < 5) {
				continue;
			}
			int j = i;
			while (j < n && j < i + 32 && !w[j] && g[j] < g[i - 1] - 2) {
				j++;
			}
			if (j >= n || j >= i + 32 || w[j] || j - i < 3) {
				continue; // no far side, or a mere step: a slope, not a hole
			}
			for (int k = i; k < j; k++) {
				w[k] = true;
			}
			i = j;
		}
		// one bridge for one river (user, 2026-11-25: a road by the river crossed on many short bridges, arches sunk into
		// the banks between): water a few blocks apart, with only low land between (a sandbar, a bank's edge), is one crossing
		for (int i = 1; i < n; i++) {
			if (w[i] || !w[i - 1]) {
				continue;
			}
			int j = i;
			while (j < n && !w[j] && j - i < 4) {
				j++;
			}
			if (j < n && w[j] && j - i <= 3) {
				int water = Integer.MIN_VALUE;
				for (int k = Math.max(0, i - 3); k < Math.min(n, j + 3); k++) {
					if (liquid[k]) {
						water = Math.max(water, g[k]);
					}
				}
				boolean low = true;
				for (int k = i; k < j; k++) {
					low &= g[k] <= water + 2;
				}
				if (low) {
					for (int k = i; k < j; k++) {
						w[k] = true;
					}
				}
			}
			i = j - 1;
		}
		// a stream one or two blocks wide gets no bridge (the same complaint: a bridge with ramps over a trickle): the
		// road goes on at the banks' height on stone laid down into the water
		for (int i = 0; i < n; ) {
			if (!w[i]) {
				i++;
				continue;
			}
			int j = i;
			boolean allWater = true;
			while (j < n && w[j]) {
				allWater &= liquid[j];
				j++;
			}
			if (j - i <= 2 && allWater && i > 0 && j < n) {
				int bank = Math.max(g[i - 1], g[j]);
				for (int k = i; k < j; k++) {
					w[k] = false;
					g[k] = Math.max(g[k] + 1, bank);
				}
			}
			i = j;
		}
		int[] t = smooth(g, w);
		// the road starts level with the gate's landing (the heart's level) and meets the land on a ramp of half a
		// block per block, cut into a rise or built up over a dip: the landing built later lies flush with it (user,
		// 2026-11-18: a road laid on the hill in front of a young castle was left far above or below its gate)
		for (Settlement o : Settlements.all(level.getServer())) {
			if (o.id().toString().equals(job.settlement())) {
				int anchor = 2 * o.heart().getY();
				for (int k = 0; k < n && k < 64; k++) {
					int slope = (3 * k + 1) / 2; // three quarters of a block per block: slabs and full steps, never two
					t[k] = Math.max(anchor - slope, Math.min(anchor + slope, t[k]));
				}
			}
		}
		for (int i = 0; i < n; ) {
			if (!w[i]) {
				i++;
				continue;
			}
			int j = i;
			int deck = Integer.MIN_VALUE;
			while (j < n && w[j]) {
				j++;
			}
			int above = j - i <= 6 ? 2 : DECK_ABOVE_WATER; // a short bridge lies low: short ramps
			for (int k = i; k < j; k++) {
				deck = Math.max(deck, liquid[k] ? g[k] + above : g[k]);
			}
			if (i > 0) {
				deck = Math.max(deck, g[i - 1]); // level with the banks: never a step up off the bridge
			}
			if (j < n) {
				deck = Math.max(deck, g[j]);
			}
			for (int k = i; k < j; k++) {
				t[k] = 2 * deck + 2;
				deckOf[k] = deck;
			}
			for (int k = 1; k <= 48; k++) {
				int ramp = 2 * deck + 2 - k;
				if (i - k >= 0 && !w[i - k]) {
					t[i - k] = Math.max(t[i - k], ramp);
				}
				if (j - 1 + k < n && !w[j - 1 + k]) {
					t[j - 1 + k] = Math.max(t[j - 1 + k], ramp);
				}
			}
			i = j;
		}
		for (int k = 0; k < n; k++) {
			if (liquid[k]) {
				BRIDGE.put(job.settlement(), new BlockPos(line.get(k).getX(), t[k] / 2 - 1, line.get(k).getZ()));
				break;
			}
		}
		JOBS.put(job.settlement() + "|" + job.key(), new Job(job.player(), job.settlement(), job.key(), line, job.index(), job.tier(), t, w));
	}

	/** Where each settlement's last road crosses water (its first bridge cell), for the field test's views. */
	static final Map<String, BlockPos> BRIDGE = new HashMap<>();

	/** Builds every queued road to the end at once (field test). */
	static void runAll(MinecraftServer server) {
		for (int guard = 0; guard < 50_000 && !JOBS.isEmpty(); guard++) {
			tick(server);
		}
	}

	static void tick(MinecraftServer server) {
		if (JOBS.isEmpty()) {
			return;
		}
		ServerLevel level = server.overworld();
		for (String id : new ArrayList<>(JOBS.keySet())) {
			Job job = JOBS.get(id);
			if (job.height() == null) {
				profile(level, job);
				continue;
			}
			int[] i = job.index();
			int n = job.line().size();
			for (int c = 0; c < PER_TICK && i[0] < n; c++, i[0]++) {
				int k = i[0];
				BlockPos p = job.line().get(k);
				BlockPos next = job.line().get(Math.min(n - 1, k + 1));
				BlockPos prev = job.line().get(Math.max(0, k - 1));
				boolean alongX = Math.abs(next.getX() - prev.getX()) >= Math.abs(next.getZ() - prev.getZ());
				Direction forward = alongX ? (next.getX() >= prev.getX() ? Direction.EAST : Direction.WEST)
						: (next.getZ() >= prev.getZ() ? Direction.SOUTH : Direction.NORTH);
				Direction side = forward.getClockWise();
				int t = job.height()[k];
				int y = t / 2 - 1; // the full block the road lies on (with a slab on it when t is odd)
				boolean half = t % 2 == 1;
				boolean bridge = job.water()[k];
				int ground = groundAt(level, p);
				boolean raised = bridge || t > 2 * ground + 2;
				if (bridge) {
					bridgeCell(level, job, k, p, forward, side, y);
					continue;
				}
				if (t < 2 * ground + 2) { // cut into the land: the road lies in a cutting, 3 blocks of air over it
					for (int w = -1; w <= 1; w++) {
						BlockPos cell = new BlockPos(p.getX(), y, p.getZ()).relative(side, w);
						for (int up = 1; up <= ground - y + 3; up++) {
							BlockState st = level.getBlockState(cell.above(up));
							if (!st.isAir() && st.getFluidState().isEmpty() && (st.canBeReplaced() || SiteWork.natural(st) || SiteWork.earthwork(st))) {
								level.setBlock(cell.above(up), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
							}
						}
						pave(level, cell, w, job.tier(), false);
						if (half) {
							set(level, cell.above(), (job.tier() >= 2 ? Blocks.COBBLESTONE_SLAB : Blocks.MUD_BRICK_SLAB).defaultBlockState());
						}
					}
					// the sides of the cutting slope back a block per block, never a wall (field test: cuttings by a gate
					// left faces of land 8 to 11 blocks high); the new ground keeps its turf
					for (int sgn : new int[] {-1, 1}) {
						for (int w = 2; w <= 14; w++) {
							BlockPos col = p.relative(side, sgn * w);
							int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, col.getX(), col.getZ()) - 1;
							int keep = y + w - 1; // highest block the slope leaves here
							if (top <= keep) {
								break;
							}
							BlockState turf = level.getBlockState(new BlockPos(col.getX(), top, col.getZ()));
							for (int yy = top; yy > keep; yy--) {
								BlockPos a = new BlockPos(col.getX(), yy, col.getZ());
								BlockState st = level.getBlockState(a);
								if (st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES)) {
									SiteWork.removeTreeAt(level, a, Set.of());
								} else if (!st.isAir() && st.getFluidState().isEmpty() && (st.canBeReplaced() || SiteWork.natural(st) || SiteWork.earthwork(st))) {
									level.setBlock(a, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
								} else if (!st.isAir()) {
									break; // something built: left alone
								}
							}
							BlockPos newTop = new BlockPos(col.getX(), keep, col.getZ());
							if (level.getBlockState(newTop).is(Blocks.DIRT) && level.getBlockState(newTop.above()).isAir() && SiteWork.natural(turf)
									&& !turf.is(BlockTags.LOGS) && !turf.is(BlockTags.LEAVES) && turf.isSolid()) {
								level.setBlock(newTop, turf, Block.UPDATE_CLIENTS);
							}
						}
					}
					continue;
				}
				for (int w = -1; w <= 1; w++) {
					BlockPos cell = p.relative(side, w);
					if (raised) {
						BlockPos at = new BlockPos(cell.getX(), y, cell.getZ());
						pave(level, at, w, job.tier(), true);
						if (half) {
							set(level, at.above(), Blocks.STONE_BRICK_SLAB.defaultBlockState());
						}
					} else {
						int gy = groundAt(level, cell);
						BlockPos at = new BlockPos(cell.getX(), gy, cell.getZ());
						if (level.getBlockState(at).getBlock() instanceof LiquidBlock) { // the river's edge under a lane: stone up from the bed
							pave(level, new BlockPos(cell.getX(), Math.max(gy, y), cell.getZ()), w, job.tier(), true);
						} else {
							pave(level, at, w, job.tier(), false);
						}
					}
				}
				// an embankment two blocks or more above the land gets a parapet; a low fill doesn't (user, 2026-11-25:
				// railings along a road on solid ground)
				if (raised && t >= 2 * ground + 6) { // a ramp up to a bridge: an embankment with a parapet
					for (int w : new int[] {-2, 2}) {
						BlockPos rail = new BlockPos(p.getX(), half ? y + 1 : y, p.getZ()).relative(side, w);
						support(level, rail);
						set(level, rail, Blocks.STONE_BRICKS.defaultBlockState());
						set(level, rail.above(), Blocks.STONE_BRICK_WALL.defaultBlockState());
					}
				} else if (job.tier() >= 2 && k % 24 == 12) {
					post(level, p.relative(side, 2));
				}
			}
			if (i[0] >= n) {
				JOBS.remove(id);
				storeLine(server, job);
				Roadside.roadBuilt(server, job.settlement(), job.key(), job.line(), job.height(), job.water());
				ServerPlayer pl = server.getPlayerList().getPlayer(job.player());
				if (pl != null) {
					People.say(pl, "Architect", "The road is finished.");
				}
			}
		}
	}

	/**
	 * One cell of a bridge: a deck 5 wide (road 3, a parapet each side) on stone pillars. The pillars stand evenly,
	 * at most 6 apart, and between them the deck rests on arches; under the middle of each arch there are
	 * {@link #DECK_ABOVE_WATER} - 1 blocks of air over the water, so a boat passes.
	 */
	private static void bridgeCell(ServerLevel level, Job job, int k, BlockPos p, Direction forward, Direction side, int deck) {
		int a = k, b = k;
		while (a > 0 && job.water()[a - 1]) {
			a--;
		}
		while (b + 1 < job.water().length && job.water()[b + 1]) {
			b++;
		}
		int len = b - a + 1;
		int spans = Math.max(1, (len + 6) / 7); // piers between spans of at most 6 (the banks carry the ends)
		int pos = k - a;
		// piers at evenly spaced cells inside the crossing; distance to the nearest support (pier or bank)
		int dist = Integer.MAX_VALUE;
		boolean pier = false;
		Direction toSupport = forward;
		for (int m = 0; m <= spans; m++) {
			int at = m == 0 ? -1 : m == spans ? len : Math.round((float) m * (len + 1) / spans) - 1;
			int d = Math.abs(pos - at);
			if (d < dist) {
				dist = d;
				toSupport = at < pos ? forward.getOpposite() : forward;
			}
			pier |= m > 0 && m < spans && at == pos;
		}
		for (int w = -2; w <= 2; w++) {
			BlockPos cell = new BlockPos(p.getX(), deck, p.getZ()).relative(side, w);
			if (Math.abs(w) <= 1) {
				pave(level, cell, w, job.tier(), true, false);
			} else {
				set(level, cell, Blocks.STONE_BRICKS.defaultBlockState());
				boolean post = pier || pos == 0 || pos == len - 1;
				set(level, cell.above(), (post ? Blocks.STONE_BRICKS : Blocks.STONE_BRICK_WALL).defaultBlockState());
				if (post) {
					set(level, cell.above(2), Blocks.LANTERN.defaultBlockState());
				}
			}
			if (pier || bankUnder(level, cell)) {
				support(level, cell); // a pier down to the riverbed; over the bank, stone down to it (no arch sunk in the bank)
			} else if (dist == 1) { // the arch springs from the pier: two steps of upside-down stairs
				BlockState arch = Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, toSupport).setValue(StairBlock.HALF, Half.TOP);
				set(level, cell.below(), arch);
				set(level, cell.below(2), arch);
			} else if (dist == 2) {
				set(level, cell.below(), Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, toSupport).setValue(StairBlock.HALF,
						Half.TOP));
			}
		}
		String style = Style.forBiome(level.getBiome(p).getRegisteredName());
		if (job.tier() >= 2 && len <= 40 && (Style.FROZEN.equals(style) || Style.HIGHLAND.equals(style)) && Roadside.regular(job.key())) {
			cover(level, p, forward, side, deck, pos, len, style);
		}
	}

	/** Land (not water, not air) within two blocks under a bridge's deck: the bank, where the bridge rests on stone. */
	private static boolean bankUnder(ServerLevel level, BlockPos deck) {
		for (int d = 1; d <= 2; d++) {
			BlockState st = level.getBlockState(deck.below(d));
			if (st.getBlock() instanceof LiquidBlock) {
				return false;
			}
			if (!st.isAir() && !st.canBeReplaced() && st.isSolid()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A covered wooden bridge (road level 3, in the north and the highlands, crossings up to 40 long): timber posts on
	 * the parapets every third cell with a railing between, a tie beam along each side, and a steep shingle or slate
	 * roof over the road, high enough for a rider; a lantern hangs from the ridge every sixth cell.
	 */
	private static void cover(ServerLevel level, BlockPos p, Direction forward, Direction side, int deck, int pos, int len, String style) {
		boolean north = Style.FROZEN.equals(style);
		BlockState post = (north ? Blocks.SPRUCE_LOG : Blocks.DARK_OAK_LOG).defaultBlockState();
		BlockState beam = (north ? Blocks.STRIPPED_SPRUCE_LOG : Blocks.STRIPPED_DARK_OAK_LOG).defaultBlockState()
				.setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, forward.getAxis());
		BlockState rail = (north ? Blocks.SPRUCE_FENCE : Blocks.DARK_OAK_FENCE).defaultBlockState();
		BlockState stair = (north ? Blocks.SPRUCE_STAIRS : Blocks.DEEPSLATE_TILE_STAIRS).defaultBlockState();
		BlockState ridge = (north ? Blocks.DARK_OAK_PLANKS : Blocks.DEEPSLATE_TILES).defaultBlockState();
		BlockState cap = (north ? Blocks.DARK_OAK_SLAB : Blocks.DEEPSLATE_TILE_SLAB).defaultBlockState();
		boolean isPost = pos % 3 == 0 || pos == len - 1;
		for (int sgn : new int[] {-1, 1}) {
			BlockPos edge = new BlockPos(p.getX(), deck, p.getZ()).relative(side, 2 * sgn);
			for (int y = 1; y <= 3; y++) {
				level.setBlock(edge.above(y), isPost ? post : y == 1 ? rail : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
			}
			level.setBlock(edge.above(4), beam, Block.UPDATE_CLIENTS);
			Direction up = sgn > 0 ? side.getOpposite() : side; // a roof stair faces the ridge
			for (int w = 3; w >= 1; w--) {
				BlockPos r = new BlockPos(p.getX(), deck + 4 + (3 - w), p.getZ()).relative(side, w * sgn);
				level.setBlock(r, stair.setValue(StairBlock.FACING, up), Block.UPDATE_CLIENTS);
			}
		}
		BlockPos top = new BlockPos(p.getX(), deck + 6, p.getZ());
		level.setBlock(top, ridge, Block.UPDATE_CLIENTS);
		level.setBlock(top.above(), cap, Block.UPDATE_CLIENTS);
		if (pos % 6 == 3) {
			level.setBlock(top.below(), Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true), Block.UPDATE_CLIENTS);
		}
		for (int sgn : new int[] {-1, 1}) { // the railing joins its posts
			BlockPos r = new BlockPos(p.getX(), deck + 1, p.getZ()).relative(side, 2 * sgn);
			level.setBlock(r, Block.updateFromNeighbourShapes(level.getBlockState(r), level, r), Block.UPDATE_CLIENTS);
		}
	}

	/** Ground height at a column: walks down through air, plants and trees (which go, whole). */
	private static int groundAt(ServerLevel level, BlockPos c) {
		int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, c.getX(), c.getZ());
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(c.getX(), top, c.getZ());
		while (pos.getY() > level.getMinY()) {
			BlockState st = level.getBlockState(pos);
			if (st.getBlock() instanceof LiquidBlock) {
				return pos.getY();
			}
			if (st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES)) {
				SiteWork.removeTreeAt(level, pos.immutable(), Set.of());
			} else if (!st.isAir() && !st.canBeReplaced() && st.isSolid()) {
				return pos.getY();
			} else if (!st.isAir()) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
			}
			pos.move(0, -1, 0);
		}
		return c.getY();
	}

	private static void set(ServerLevel level, BlockPos pos, BlockState st) {
		BlockState now = level.getBlockState(pos);
		if (now.isAir() || now.canBeReplaced() || SiteWork.natural(now) || now.getBlock() instanceof LiquidBlock
				|| now.is(Blocks.GRAVEL) || now.is(Blocks.DIRT_PATH) || now.is(Blocks.SPRUCE_PLANKS) || now.is(Blocks.COBBLESTONE)) {
			level.setBlock(pos, Block.updateFromNeighbourShapes(st, level, pos), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
		}
	}

	/** Stone under a raised block, down to the ground or the riverbed. */
	private static void support(ServerLevel level, BlockPos top) {
		BlockPos.MutableBlockPos pos = top.below().mutable();
		for (int d = 0; d < 40 && pos.getY() > level.getMinY(); d++, pos.move(0, -1, 0)) {
			BlockState st = level.getBlockState(pos);
			if (!(st.isAir() || st.canBeReplaced() || st.getBlock() instanceof LiquidBlock || st.is(BlockTags.LEAVES))) {
				return;
			}
			level.setBlock(pos, (d % 4 == 3 ? Blocks.MOSSY_STONE_BRICKS : Blocks.STONE_BRICKS).defaultBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	private static void pave(ServerLevel level, BlockPos cell, int w, int tier, boolean raised) {
		pave(level, cell, w, tier, raised, raised);
	}

	/** {@code fillBelow}: stone under a raised block down to the ground (a ramp); a bridge deck has air under it. */
	private static void pave(ServerLevel level, BlockPos cell, int w, int tier, boolean raised, boolean fillBelow) {
		for (int up = 1; up <= 3; up++) {
			BlockPos a = cell.above(up);
			BlockState st = level.getBlockState(a);
			if (st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES)) {
				SiteWork.removeTreeAt(level, a, Set.of());
			} else if (!st.isAir() && (st.canBeReplaced() || SiteWork.natural(st))) {
				level.setBlock(a, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
			}
		}
		int h = Math.floorMod(cell.getX() * 31 + cell.getZ() * 17, 7);
		BlockState block;
		if (raised) {
			if (fillBelow) {
				support(level, cell);
			}
			block = (h == 0 ? Blocks.CRACKED_STONE_BRICKS : Blocks.STONE_BRICKS).defaultBlockState();
		} else if (tier >= 2) {
			block = (w == 0 ? (h < 2 ? Blocks.COBBLESTONE : Blocks.GRAVEL) : h == 0 ? Blocks.MOSSY_COBBLESTONE : Blocks.COBBLESTONE).defaultBlockState();
		} else {
			block = (w == 0 || h < 4 ? Blocks.DIRT_PATH : Blocks.COARSE_DIRT).defaultBlockState();
		}
		BlockState now = level.getBlockState(cell);
		if (now.getBlock() instanceof LiquidBlock || now.isAir() || SiteWork.natural(now) || SiteWork.earthwork(now) || now.is(Blocks.DIRT_PATH) || now.is(Blocks.GRAVEL)
				|| now.is(Blocks.COBBLESTONE) || now.is(Blocks.MOSSY_COBBLESTONE) || now.is(Blocks.SPRUCE_PLANKS) || now.is(Blocks.STONE_BRICKS)) {
			level.setBlock(cell, block, Block.UPDATE_CLIENTS);
			BlockPos below = cell.below();
			if (block.is(Blocks.GRAVEL) && (level.getBlockState(below).isAir() || level.getBlockState(below).getBlock() instanceof LiquidBlock)) {
				level.setBlock(below, Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_CLIENTS); // gravel must not fall
			}
		}
	}

	private static void post(ServerLevel level, BlockPos cell) {
		int top = SiteWork.groundTop(level, cell.getX(), cell.getZ(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cell.getX(), cell.getZ()));
		BlockPos base = new BlockPos(cell.getX(), top + 1, cell.getZ());
		if (!level.getBlockState(base).isAir() && !level.getBlockState(base).canBeReplaced()) {
			return;
		}
		level.setBlock(base, Blocks.SPRUCE_FENCE.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(base.above(), Blocks.SPRUCE_FENCE.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(base.above(2), Blocks.LANTERN.defaultBlockState(), Block.UPDATE_ALL);
	}

	// ---- the way out of the gate ----

	private static final int OUT_RADIUS = 210;
	/** Where the last way-out road of a settlement bends most (its middle), for the field test's views. */
	static final Map<UUID, BlockPos> WAY_OUT = new HashMap<>();
	static final String WAY_OUT_KEY = "wayout";

	/** The columns of the settlement's way-out road (3 wide, and one more each side for its banks), empty if it has none. */
	static Set<Long> wayOutColumns(Settlement s) {
		Set<Long> out = new java.util.HashSet<>();
		for (Map.Entry<String, String> e : s.names().entrySet()) {
			// the way out of the gate, and the roads to other places near the settlement (user, 2026-11-18: a castle
			// level made terraces right over the road to a village and left the gate with no way in)
			if (!e.getKey().equals(WAY_OUT_KEY) && !e.getKey().startsWith(LINE_PREFIX) || e.getValue().isEmpty()) {
				continue;
			}
			for (String cell : e.getValue().split(";")) {
				String[] p = cell.split(",");
				int x = Integer.parseInt(p[0]), z = Integer.parseInt(p[2]);
				for (int dx = -2; dx <= 2; dx++) {
					for (int dz = -2; dz <= 2; dz++) {
						out.add(BlockPos.asLong(x + dx, 0, z + dz));
					}
				}
			}
		}
		return out;
	}

	/** Names key of a road's stored line near its settlement: "line|<link key>" = "x,y,z;..." (land cells, road height). */
	static final String LINE_PREFIX = "line|";
	/** How far from the heart a road's line is stored and kept free of later land work. */
	private static final int LINE_RADIUS = 110;

	/** Stores the part of a finished road near its settlement, so later builds leave it alone and it can be laid again. */
	private static void storeLine(MinecraftServer server, Job job) {
		Settlement s = null;
		for (Settlement o : Settlements.all(server)) {
			if (o.id().toString().equals(job.settlement())) {
				s = o;
			}
		}
		if (s == null) {
			return;
		}
		StringBuilder enc = new StringBuilder();
		for (int k = 0; k < job.line().size(); k++) {
			BlockPos p = job.line().get(k);
			if (job.water()[k] || Math.abs(p.getX() - s.heart().getX()) > LINE_RADIUS || Math.abs(p.getZ() - s.heart().getZ()) > LINE_RADIUS) {
				continue;
			}
			int y = job.height()[k] / 2 - 1; // the full block the road lies on (a slab on it is left out)
			enc.append(enc.length() == 0 ? "" : ";").append(p.getX()).append(',').append(y).append(',').append(p.getZ());
		}
		Settlements.putState(server, s.withName(LINE_PREFIX + job.key(), enc.toString()));
	}

	/** The stored lines of the settlement's roads to other places (land cells near it). */
	static List<List<BlockPos>> storedLinks(Settlement s) {
		List<List<BlockPos>> out = new ArrayList<>();
		for (Map.Entry<String, String> e : s.names().entrySet()) {
			if (!e.getKey().startsWith(LINE_PREFIX) || e.getValue().isEmpty()) {
				continue;
			}
			List<BlockPos> line = new ArrayList<>();
			for (String cell : e.getValue().split(";")) {
				String[] p = cell.split(",");
				line.add(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])));
			}
			out.add(line);
		}
		return out;
	}

	/** Settlements whose owner was told this session that no road out could be laid (said once). */
	private static final Set<UUID> TOLD = new java.util.HashSet<>();

	/**
	 * Makes sure one can walk from the gate out into the open land. A wall closes the ring, so a gate that opens onto a
	 * pocket (sea on one side, cliffs or the wall on the others; field test: windswept hills) would leave the castle
	 * with no way out on foot. The land around (150 blocks) is read as columns: the part from which one can walk to
	 * the edge of that area is "open land". If the gate's landing isn't part of it, the cheapest route to it is found
	 * (a long way round is cheaper than earthwork, water costs a causeway) and a country road of the settlement's
	 * tier is laid along it, cut and filled so it never climbs more than one block per block. Built blocks, the ring
	 * with its moat and anything not natural (other people's buildings) are never touched.
	 */
	static void wayOut(MinecraftServer server, Settlement s, Set<Long> built) {
		ServerLevel level = server.overworld();
		BlockPos heart = s.heart();
		BlockPos start = gateExit(s);
		int n = OUT_RADIUS * 2 + 1;
		int x0 = heart.getX() - OUT_RADIUS, z0 = heart.getZ() - OUT_RADIUS;
		if (!level.isLoaded(start)) {
			return;
		}
		Rotation turn = Rotation.values()[s.rotation()];
		BlockPos cornerA = heart.offset(Template.rotate(new BlockPos(io.github.sotadevop.kingdomquest.building.Layout.ring(Settlements.round(s))[0] - 9, 0, io.github.sotadevop.kingdomquest.building.Layout.ring(Settlements.round(s))[1] - 9), turn));
		BlockPos cornerB = heart.offset(Template.rotate(new BlockPos(io.github.sotadevop.kingdomquest.building.Layout.ring(Settlements.round(s))[2] + 9, 0, io.github.sotadevop.kingdomquest.building.Layout.ring(Settlements.round(s))[3] + 9), turn));
		int bx0 = Math.min(cornerA.getX(), cornerB.getX()), bx1 = Math.max(cornerA.getX(), cornerB.getX());
		int bz0 = Math.min(cornerA.getZ(), cornerB.getZ()), bz1 = Math.max(cornerA.getZ(), cornerB.getZ());
		Set<Long> builtCols = new java.util.HashSet<>();
		for (long b : built) {
			builtCols.add(BlockPos.asLong(BlockPos.getX(b), 0, BlockPos.getZ(b)));
		}
		// every column: the height one walks on (built floors, roads and landings count), water, a fence or wall top
		// one can't step onto, and whether a road may be dug there (natural, outside the ring and its moat, not built)
		int[] h = new int[n * n];
		boolean[] water = new boolean[n * n];
		boolean[] wall = new boolean[n * n];
		boolean[] unloaded = new boolean[n * n];
		boolean[] inRing = new boolean[n * n];
		boolean[] dig = new boolean[n * n];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < n; i++) {
			for (int k = 0; k < n; k++) {
				int x = x0 + i, z = z0 + k, c = i * n + k;
				if (!level.isLoaded(pos.set(x, 0, z))) {
					unloaded[c] = true;
					continue;
				}
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
				BlockState st = level.getBlockState(pos.set(x, y, z));
				while (y > level.getMinY() && st.getFluidState().isEmpty() && (st.is(BlockTags.LOGS) || st.canBeReplaced() || !st.isSolid())) {
					st = level.getBlockState(pos.set(x, --y, z));
				}
				h[c] = y;
				water[c] = !st.getFluidState().isEmpty();
				net.minecraft.world.phys.shapes.VoxelShape shape = st.getCollisionShape(level, pos);
				wall[c] = !water[c] && !shape.isEmpty() && shape.max(Direction.Axis.Y) > 1.0;
				inRing[c] = x >= bx0 && x <= bx1 && z >= bz0 && z <= bz1;
				// natural land, and the settlement's own old terrace masonry outside the walls (no one's building)
				dig[c] = !inRing[c] && !builtCols.contains(BlockPos.asLong(x, 0, z)) && (water[c] || SiteWork.natural(st) || SiteWork.earthwork(st));
			}
		}
		int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		// can one walk out of the settlement (from the heart, the same walk as the field test: one up, three down,
		// through doors and under trees, no swimming)? Then nothing is needed.
		Set<Long> reach = new java.util.HashSet<>();
		if (walksOut(level, heart, reach)) {
			return;
		}
		// a road laid before that a later build covered or cut (a slope, a bank): laid again where it was. The roads to
		// other places too: a building that had to stand on one is the only thing that still cuts it (user: builders
		// keep off roads, laying them again is the fallback)
		List<BlockPos> before = storedLine(s);
		List<List<BlockPos>> links = storedLinks(s);
		if (!before.isEmpty() || !links.isEmpty()) {
			java.util.function.LongPredicate free = col -> !builtCols.contains(col) && !(BlockPos.getX(col) >= bx0 && BlockPos.getX(col) <= bx1
					&& BlockPos.getZ(col) >= bz0 && BlockPos.getZ(col) <= bz1);
			if (!before.isEmpty()) {
				lay(level, before, free, built, SiteWork.surface(level, heart), tier(s));
			}
			for (List<BlockPos> link : links) {
				lay(level, link, free, built, SiteWork.surface(level, heart), tier(s));
			}
			reach.clear();
			if (walksOut(level, heart, reach)) {
				io.github.sotadevop.kingdomquest.KingdomQuest.LOG.info("Way out of {}: the road was covered by a build and is laid again", s.name());
				return;
			}
		}
		// the road starts where one can get to outside the walls, as near the gate's exit as there is
		long best = Long.MAX_VALUE;
		int startCell = -1, startFeet = 0;
		for (long cell : reach) {
			int x = BlockPos.getX(cell), z = BlockPos.getZ(cell);
			int ci = x - x0, ck = z - z0;
			if (ci < 0 || ck < 0 || ci >= n || ck >= n || x >= bx0 && x <= bx1 && z >= bz0 && z <= bz1 || unloaded[ci * n + ck]) {
				continue;
			}
			long d = (long) (x - start.getX()) * (x - start.getX()) + (long) (z - start.getZ()) * (z - start.getZ());
			if (d < best) {
				best = d;
				startCell = ci * n + ck;
				startFeet = BlockPos.getY(cell);
			}
		}
		if (startCell < 0) {
			io.github.sotadevop.kingdomquest.KingdomQuest.LOG.info("Way out of {}: nothing outside the walls can be walked to", s.name());
			return;
		}
		start = new BlockPos(x0 + startCell / n, startFeet - 1, z0 + startCell % n);
		h[startCell] = startFeet - 1;
		water[startCell] = false;
		wall[startCell] = false;
		// open land: every column from which one can walk out to 100 blocks away
		List<Long> edge = new ArrayList<>();
		int r = FieldTest.R - 1;
		for (int i = -r; i <= r; i += 2) {
			for (int[] e2 : new int[][] {{i, -r}, {i, r}, {-r, i}, {r, i}}) {
				int x = heart.getX() + e2[0], z = heart.getZ() + e2[1];
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				if (!Double.isNaN(FieldTest.feet(level, x, y, z, false))) {
					edge.add(BlockPos.asLong(x, y, z));
				}
			}
		}
		boolean[] open = new boolean[n * n];
		for (long cell : FieldTest.reverse(level, heart, edge, 1_200_000)) {
			int ci = BlockPos.getX(cell) - x0, ck = BlockPos.getZ(cell) - z0;
			if (ci >= 0 && ck >= 0 && ci < n && ck < n) {
				int c = ci * n + ck;
				int feetY = BlockPos.getY(cell);
				open[c] |= feetY >= h[c] && feetY <= h[c] + 2; // standing on the column's top, not in a cave below it
			}
		}
		// the old road (laid again above) still leads out from where it was cut off: a short link to it is enough
		for (BlockPos b : before) {
			int ci = b.getX() - x0, ck = b.getZ() - z0;
			if (ci >= 0 && ck >= 0 && ci < n && ck < n) {
				int c = ci * n + ck;
				h[c] = b.getY();
				water[c] = false;
				open[c] = true;
			}
		}
		// the cheapest road from the gate to the open land. The search runs over (column, road height): the road
		// rises or falls at most one block per block and lies at most DEPTH above or below the land (deeper cuts and
		// higher banks aren't built), so a steep hillside is crossed on a long slanting road instead of a stair.
		// Cost per column: the earth moved there, water costs a causeway.
		final int depth = 16, kk = depth * 2 + 1;
		int[] land = new int[n * n];
		int[] floor = new int[n * n];
		for (int c = 0; c < n * n; c++) {
			land[c] = water[c] ? h[c] + 1 : h[c];
			floor[c] = land[c] - depth;
			int ci = c / n, ck = c % n;
			for (int[] d : dirs) { // beside water the road stays above it, so the cut can't flood
				int ni = ci + d[0], nk = ck + d[1];
				if (ni >= 0 && nk >= 0 && ni < n && nk < n && water[ni * n + nk]) {
					floor[c] = Math.max(floor[c], h[ni * n + nk] + 1);
				}
			}
		}
		int states = n * n * kk;
		int[] dist = new int[states];
		int[] from = new int[states];
		java.util.Arrays.fill(dist, Integer.MAX_VALUE);
		LongHeap heap = new LongHeap();
		int s0 = startCell * kk + depth; // on the gate's landing
		dist[s0] = 0;
		from[s0] = -1;
		heap.add(s0);
		int goal = -1;
		int popped = 0;
		while (heap.size() > 0) {
			long e = heap.poll();
			popped++;
			int st = (int) e, cost0 = (int) (e >>> 32);
			if (cost0 > dist[st]) {
				continue;
			}
			int a = st / kk, ra = land[a] + st % kk - depth;
			if (open[a] && ra == land[a] && a != startCell) { // ends on the open land itself, not on a bank over it
				goal = st;
				break;
			}
			if (cost0 > 6000) {
				break; // no reasonable road: leave the land as it is
			}
			int ai = a / n, ak = a % n;
			// the road can't come back over or beside itself (it is 3 wide): a column within 2 of where the road ran
			// more than 3 steps ago is not entered, so a way down a cliff needs real room, not a spiral
			int[] recent = new int[40];
			int nr = 0;
			for (int t = from[st]; t >= 0 && nr < recent.length; t = from[t]) {
				recent[nr++] = t / kk;
			}
			for (int[] d : dirs) {
				int bi = ai + d[0], bk = ak + d[1];
				if (bi < 0 || bk < 0 || bi >= n || bk >= n) {
					continue;
				}
				int b = bi * n + bk;
				// built ground outside the ring (a landing, a road) can be walked over as it is, at its own height
				boolean over = !dig[b] && !unloaded[b] && !inRing[b] && !water[b] && !wall[b];
				if (!dig[b] && !over) {
					continue;
				}
				boolean again = false;
				for (int t = 0; t < nr && !again; t++) {
					int ri = recent[t] / n, rk = recent[t] % n;
					again = t < 3 ? recent[t] == b : Math.abs(ri - bi) <= 2 && Math.abs(rk - bk) <= 2;
				}
				if (again) {
					continue;
				}
				for (int rb = ra - 1; rb <= ra + 1; rb++) {
					int off = rb - land[b];
					if (off < -depth || off > depth || rb < floor[b] || over && off != 0) {
						continue;
					}
					int nb = b * kk + off + depth;
					int c2 = cost0 + 2 + Math.abs(off) + (water[b] ? 8 : 0);
					if (c2 < dist[nb]) {
						dist[nb] = c2;
						from[nb] = st;
						heap.add(((long) c2 << 32) | nb);
					}
				}
			}
		}
		if (goal < 0) {
			ServerPlayer owner = server.getPlayerList().getPlayer(s.owner());
			// said once, and only when the gate is finished: before that the land may still change
			if (owner != null && s.level(Progression.GATE) >= Progression.GATE_STEPS.size()
					&& s.level(Settlement.MAIN) >= Progression.steps(Settlement.MAIN).size() && TOLD.add(s.id())) {
				People.say(owner, "Architect", "Outside the gate the land is closed in by water and cliffs, and no road can be laid down from it. "
						+ "Make a way out by hand, or found the castle facing open land.");
			}
			int openCount = 0;
			for (boolean o : open) {
				openCount += o ? 1 : 0;
			}
			io.github.sotadevop.kingdomquest.KingdomQuest.LOG.info("Way out of {}: no road possible from {} (land {}, dig {}, {} open columns, {} states tried)",
					s.name(), start, h[startCell] - heart.getY(), dig[startCell], openCount, popped);
			return;
		}
		List<Integer> path = new ArrayList<>();
		List<Integer> heights = new ArrayList<>();
		int wetCols = 0;
		for (int st = goal; st >= 0; st = from[st]) {
			int c = st / kk;
			path.add(0, c);
			heights.add(0, land[c] + st % kk - depth);
			wetCols += water[c] ? 1 : 0;
		}
		if (wetCols > 24) {
			return; // a long causeway over the sea would make the island something else: it stays an island
		}
		int len = path.size();
		int[] road = new int[len];
		for (int i = 0; i < len; i++) {
			road[i] = heights.get(i);
		}
		List<BlockPos> line = new ArrayList<>();
		for (int i = 0; i < len; i++) {
			line.add(new BlockPos(x0 + path.get(i) / n, road[i], z0 + path.get(i) % n));
		}
		lay(level, line, col -> {
			int ci = BlockPos.getX(col) - x0, ck = BlockPos.getZ(col) - z0;
			return ci >= 0 && ck >= 0 && ci < n && ck < n && dig[ci * n + ck] && !builtCols.contains(col);
		}, built, SiteWork.surface(level, heart), tier(s));
		StringBuilder trace = new StringBuilder();
		for (int i = 0; i < len; i++) {
			int c = path.get(i);
			trace.append(String.format(" (%d,%d) land %d road %d%s", x0 + c / n - heart.getX(), z0 + c % n - heart.getZ(), land[c] - heart.getY(),
					road[i] - heart.getY(), water[c] ? " water" : ""));
		}
		io.github.sotadevop.kingdomquest.KingdomQuest.LOG.debug("Way out path:{}", trace);
		{ // check: can one walk out now?
			Set<Long> again = new java.util.HashSet<>();
			io.github.sotadevop.kingdomquest.KingdomQuest.LOG.info("Way out check: out {}", walksOut(level, heart, again));
		}
		// stored with the settlement, so site grading, smoothing and later builds leave the road alone (also after a
		// restart); the old road stays part of it (the new one may only link to it)
		List<BlockPos> keepAll = new ArrayList<>(line);
		for (BlockPos b : before) {
			if (!keepAll.contains(b)) {
				keepAll.add(b);
			}
		}
		StringBuilder enc = new StringBuilder();
		for (BlockPos b : keepAll) {
			enc.append(enc.length() == 0 ? "" : ";").append(b.getX()).append(',').append(b.getY()).append(',').append(b.getZ());
		}
		Settlement current = Settlements.byHeart(server, heart);
		if (current != null) {
			Settlements.putState(server, current.withName(WAY_OUT_KEY, enc.toString()));
		}
		int mid = path.get(len / 2);
		WAY_OUT.put(s.id(), new BlockPos(x0 + mid / n, road[len / 2], z0 + mid % n));
		ServerPlayer owner = server.getPlayerList().getPlayer(s.owner());
		if (owner != null) {
			People.say(owner, "Architect", "Outside the gate the land was closed in, so my workers laid a road from the gate out to the open land (" + len
					+ " blocks).");
		}
		io.github.sotadevop.kingdomquest.KingdomQuest.LOG.info("Way out of {}: road of {} blocks from {} to {}", s.name(), len, start,
				new BlockPos(x0 + goal / kk / n, road[len - 1], z0 + goal / kk % n));
	}

	/** The field test's walk from the heart; true if it gets 100 blocks out. The cells walked go into {@code reach}. */
	private static boolean walksOut(ServerLevel level, BlockPos heart, Set<Long> reach) {
		java.util.ArrayDeque<long[]> queue = new java.util.ArrayDeque<>();
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				for (int dy = -2; dy <= 3; dy++) {
					long key = BlockPos.asLong(heart.getX() + dx, heart.getY() + dy, heart.getZ() + dz);
					if (!Double.isNaN(FieldTest.feet(level, heart.getX() + dx, heart.getY() + dy, heart.getZ() + dz, false)) && reach.add(key)) {
						queue.add(new long[] {key});
					}
				}
			}
		}
		return FieldTest.spread(level, heart, reach, queue, Set.of(), 1_600_000);
	}

	/** The centre line of the settlement's way-out road as stored, empty if it has none. */
	private static List<BlockPos> storedLine(Settlement s) {
		String v = s.names().get(WAY_OUT_KEY);
		List<BlockPos> out = new ArrayList<>();
		if (v != null && !v.isEmpty()) {
			for (String cell : v.split(";")) {
				String[] p = cell.split(",");
				out.add(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])));
			}
		}
		return out;
	}

	/**
	 * Lays a road 3 wide along {@code line} (centre cells at road height): the land above goes (trees whole, headroom
	 * 3), earth is filled under it down to the land, stone through water, then the road surface. Only columns
	 * {@code mayDig} accepts (keys x,0,z) are touched.
	 */
	private static void lay(ServerLevel level, List<BlockPos> line, java.util.function.LongPredicate mayDig, Set<Long> built, BlockState surface, int tier) {
		BlockState fill = SiteWork.subsoil(surface);
		int len = line.size();
		for (int i = 0; i < len; i++) {
			BlockPos p = line.get(i);
			BlockPos prev = line.get(Math.max(0, i - 1)), next = line.get(Math.min(len - 1, i + 1));
			boolean alongX = Math.abs(next.getX() - prev.getX()) >= Math.abs(next.getZ() - prev.getZ());
			Direction side = alongX ? Direction.SOUTH : Direction.EAST;
			for (int w = -1; w <= 1; w++) {
				BlockPos cell = p.relative(side, w);
				if (!mayDig.test(BlockPos.asLong(cell.getX(), 0, cell.getZ()))) {
					continue;
				}
				int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, cell.getX(), cell.getZ());
				for (int y = Math.max(top, cell.getY() + 3); y > cell.getY(); y--) {
					BlockPos a = new BlockPos(cell.getX(), y, cell.getZ());
					BlockState st = level.getBlockState(a);
					if (st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES)) {
						SiteWork.removeTreeAt(level, a, built);
					} else if (!st.isAir() && (st.canBeReplaced() || SiteWork.natural(st) || SiteWork.earthwork(st)) && st.getFluidState().isEmpty()) {
						level.setBlock(a, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
					}
				}
				for (int y = cell.getY() - 1, d = 0; d < 48 && y > level.getMinY(); y--, d++) {
					BlockPos b = new BlockPos(cell.getX(), y, cell.getZ());
					BlockState st = level.getBlockState(b);
					boolean wet = !st.getFluidState().isEmpty();
					if (!(st.isAir() || st.canBeReplaced() || wet || st.is(BlockTags.LEAVES))) {
						break;
					}
					level.setBlock(b, wet ? Blocks.COBBLESTONE.defaultBlockState() : fill, Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
				}
				pave(level, cell, w, tier, false);
			}
			if (tier >= 2 && i % 24 == 12) {
				post(level, p.relative(side, 2));
			}
		}
		finish(level, line, mayDig, surface);
	}

	/**
	 * The look of a road through hilly land (agreed with the user): where it is cut 3 or more into the hill, the
	 * sides of the cut are stone retaining walls; where it runs 2 or more above the land, its sides are stone down to
	 * the land with a low wall on top as a railing; every 1-block step becomes a stair, so it is walked without jumping.
	 * Stone matches the settlement's walls (sandstone in the desert, cobblestone in the snow, stone bricks elsewhere).
	 */
	private static void finish(ServerLevel level, List<BlockPos> line, java.util.function.LongPredicate mayDig, BlockState surface) {
		BlockState wall = SiteWork.wallFor(surface);
		BlockState rail = wall.is(Blocks.SMOOTH_SANDSTONE) ? Blocks.SANDSTONE_WALL.defaultBlockState()
				: wall.is(Blocks.CUT_RED_SANDSTONE) ? Blocks.RED_SANDSTONE_WALL.defaultBlockState()
				: wall.is(Blocks.COBBLESTONE) ? Blocks.COBBLESTONE_WALL.defaultBlockState() : Blocks.STONE_BRICK_WALL.defaultBlockState();
		BlockState stair = wall.is(Blocks.SMOOTH_SANDSTONE) ? Blocks.SANDSTONE_STAIRS.defaultBlockState()
				: wall.is(Blocks.CUT_RED_SANDSTONE) ? Blocks.RED_SANDSTONE_STAIRS.defaultBlockState()
				: wall.is(Blocks.COBBLESTONE) ? Blocks.COBBLESTONE_STAIRS.defaultBlockState() : Blocks.STONE_BRICK_STAIRS.defaultBlockState();
		int len = line.size();
		// the road's own columns (3 wide): nothing of the finish goes there
		Set<Long> onRoad = new java.util.HashSet<>();
		Direction[] sides = new Direction[len];
		for (int i = 0; i < len; i++) {
			BlockPos p = line.get(i);
			BlockPos prev = line.get(Math.max(0, i - 1)), next = line.get(Math.min(len - 1, i + 1));
			sides[i] = Math.abs(next.getX() - prev.getX()) >= Math.abs(next.getZ() - prev.getZ()) ? Direction.SOUTH : Direction.EAST;
			for (int w = -1; w <= 1; w++) {
				BlockPos c = p.relative(sides[i], w);
				onRoad.add(BlockPos.asLong(c.getX(), 0, c.getZ()));
			}
		}
		BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
		for (int i = 0; i < len; i++) {
			BlockPos p = line.get(i);
			int road = p.getY();
			for (int w : new int[] {-2, 2}) {
				BlockPos edge = p.relative(sides[i], w);
				long col = BlockPos.asLong(edge.getX(), 0, edge.getZ());
				if (onRoad.contains(col) || !mayDig.test(col)) {
					continue;
				}
				int top = SiteWork.groundTop(level, edge.getX(), edge.getZ(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, edge.getX(), edge.getZ()));
				if (top >= road + 3) {
					// a cutting: the side that stands over the road is faced with stone (at most 10 up)
					for (int y = road + 1; y <= Math.min(top, road + 10); y++) {
						BlockState st = level.getBlockState(q.set(edge.getX(), y, edge.getZ()));
						if (SiteWork.terrain(st) || SiteWork.earthwork(st)) {
							level.setBlock(q, Math.floorMod(edge.getX() * 31 + y * 7 + edge.getZ() * 13, 5) == 0 ? mossyOf(wall) : wall, Block.UPDATE_CLIENTS);
						}
					}
				} else if (top <= road - 2) {
					// raised: a stone side down to the land and a low wall on top
					for (int y = road; y > top && y > road - 40; y--) {
						BlockState st = level.getBlockState(q.set(edge.getX(), y, edge.getZ()));
						if (!(st.isAir() || st.canBeReplaced() || st.getBlock() instanceof LiquidBlock || SiteWork.natural(st))) {
							break;
						}
						level.setBlock(q, Math.floorMod(edge.getX() * 31 + y * 7 + edge.getZ() * 13, 5) == 0 ? mossyOf(wall) : wall, Block.UPDATE_CLIENTS);
					}
					BlockState above = level.getBlockState(q.set(edge.getX(), road + 1, edge.getZ()));
					if (above.isAir() || above.canBeReplaced()) {
						level.setBlock(q, Block.updateFromNeighbourShapes(rail, level, q), Block.UPDATE_ALL);
					}
				}
			}
		}
		// steps: a stair on the lower cell, facing up the road, over the road's width
		for (int i = 0; i < len; i++) {
			BlockPos p = line.get(i);
			for (int j : new int[] {i + 1, i - 1}) {
				if (j < 0 || j >= len || line.get(j).getY() != p.getY() + 1) {
					continue;
				}
				BlockPos up = line.get(j);
				Direction toward = up.getX() > p.getX() ? Direction.EAST : up.getX() < p.getX() ? Direction.WEST : up.getZ() > p.getZ() ? Direction.SOUTH : Direction.NORTH;
				for (int w = -1; w <= 1; w++) {
					BlockPos cell = p.relative(sides[i], w).above();
					if (!mayDig.test(BlockPos.asLong(cell.getX(), 0, cell.getZ())) && w != 0) {
						continue;
					}
					BlockState st = level.getBlockState(cell);
					if (st.isAir() || st.canBeReplaced()) {
						level.setBlock(cell, stair.setValue(StairBlock.FACING, toward), Block.UPDATE_ALL);
					}
				}
				break; // one stair per cell
			}
		}
	}

	private static BlockState mossyOf(BlockState wall) {
		return wall.is(Blocks.STONE_BRICKS) ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState()
				: wall.is(Blocks.COBBLESTONE) ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : wall;
	}

	/** A min-heap of longs (cost in the high half, state in the low half) without boxing. */
	private static final class LongHeap {
		private long[] a = new long[1024];
		private int size;

		int size() {
			return size;
		}

		void add(long v) {
			if (size == a.length) {
				a = java.util.Arrays.copyOf(a, size * 2);
			}
			int i = size++;
			while (i > 0 && a[(i - 1) / 2] > v) {
				a[i] = a[(i - 1) / 2];
				i = (i - 1) / 2;
			}
			a[i] = v;
		}

		long poll() {
			long top = a[0];
			long v = a[--size];
			int i = 0;
			while (true) {
				int c = 2 * i + 1;
				if (c >= size) {
					break;
				}
				if (c + 1 < size && a[c + 1] < a[c]) {
					c++;
				}
				if (a[c] >= v) {
					break;
				}
				a[i] = a[c];
				i = c;
			}
			a[i] = v;
			return top;
		}
	}
}
