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
		return s.heart().offset(Template.rotate(new BlockPos(0, 0, 51), Rotation.values()[s.rotation()]));
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
	}

	/** Builds (or rebuilds at the current tier) a road from {@code from}'s gate to {@code end} and records it. */
	static void link(MinecraftServer server, ServerPlayer player, Settlement from, String key, BlockPos end, String name) {
		String id = from.id() + "|" + key;
		if (JOBS.containsKey(id)) {
			return;
		}
		BlockPos start = gateExit(from);
		if (Math.sqrt(end.distSqr(start)) > MAX_LENGTH) {
			if (player != null) {
				player.sendOverlayMessage(Component.translatable("kingdomquest.link.too_far"));
			}
			return;
		}
		Settlements.putState(server, from.withName(PREFIX + key, end.getX() + "," + end.getY() + "," + end.getZ()));
		List<BlockPos> line = new ArrayList<>();
		int dx = end.getX() - start.getX();
		int dz = end.getZ() - start.getZ();
		int steps = Math.max(1, Math.max(Math.abs(dx), Math.abs(dz)));
		for (int i = 0; i <= steps; i++) {
			line.add(new BlockPos(start.getX() + Math.round((float) dx * i / steps), from.heart().getY(), start.getZ() + Math.round((float) dz * i / steps)));
		}
		JOBS.put(id, new Job(from.owner(), from.id().toString(), key, line, new int[] {0}, tier(from), null, null));
		ServerPlayer p = player != null ? player : server.getPlayerList().getPlayer(from.owner());
		if (p != null) {
			People.say(p, "Architect", "My workers will build a road to " + name + ". It's " + steps + " blocks long.");
		}
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

	/** Ground and water along the line, then ramps up to the bridge decks. */
	private static void profile(ServerLevel level, Job job) {
		int n = job.line().size();
		int[] h = new int[n];
		boolean[] w = new boolean[n];
		for (int i = 0; i < n; i++) {
			BlockPos c = job.line().get(i);
			int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, c.getX(), c.getZ());
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(c.getX(), top, c.getZ());
			while (pos.getY() > level.getMinY()) {
				BlockState st = level.getBlockState(pos);
				if (st.getBlock() instanceof LiquidBlock) {
					w[i] = true;
					break;
				}
				if (!st.isAir() && !st.canBeReplaced() && !st.is(BlockTags.LEAVES) && !st.is(BlockTags.LOGS) && st.isSolid()) {
					break;
				}
				pos.move(0, -1, 0);
			}
			h[i] = w[i] ? pos.getY() + 2 : pos.getY(); // bridge deck two blocks above the water
		}
		// a hole in the ground (a cave mouth, a ravine): where the land drops 4 or more in one step and comes back up
		// within 48 blocks, the road keeps its height and crosses on a bridge, the same as over water
		for (int i = 1; i < n; i++) {
			if (w[i] || w[i - 1] || h[i - 1] - h[i] < 4) {
				continue;
			}
			int j = i;
			while (j < n && j < i + 48 && !w[j] && h[j] < h[i - 1] - 2) {
				j++;
			}
			if (j >= n || j >= i + 48 || w[j]) {
				continue; // no far side: a slope, not a hole
			}
			int deck = Math.max(h[i - 1], h[j]);
			for (int k = i; k < j; k++) {
				h[k] = deck;
				w[k] = true;
			}
			i = j;
		}
		// one deck height per crossing (the highest), ramps of one block per block on both banks
		for (int i = 0; i < n; ) {
			if (!w[i]) {
				i++;
				continue;
			}
			int j = i;
			int deck = h[i];
			while (j < n && w[j]) {
				deck = Math.max(deck, h[j]);
				j++;
			}
			for (int k = i; k < j; k++) {
				h[k] = deck;
			}
			for (int k = 1; k < 12; k++) {
				if (i - k >= 0 && !w[i - k]) {
					h[i - k] = Math.max(h[i - k], deck - k);
				}
				if (j - 1 + k < n && !w[j - 1 + k]) {
					h[j - 1 + k] = Math.max(h[j - 1 + k], deck - k);
				}
			}
			i = j;
		}
		Job full = new Job(job.player(), job.settlement(), job.key(), job.line(), job.index(), job.tier(), h, w);
		JOBS.put(job.settlement() + "|" + job.key(), full);
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
				int y = job.height()[k];
				boolean bridge = job.water()[k];
				int ground = groundAt(level, p);
				boolean raised = bridge || y > ground + 1;
				for (int w = -1; w <= 1; w++) {
					BlockPos cell = new BlockPos(p.getX(), y, p.getZ()).relative(side, w);
					cell = new BlockPos(cell.getX(), raised ? y : groundAt(level, cell), cell.getZ());
					pave(level, cell, w, job.tier(), raised);
				}
				if (raised) {
					for (int w : new int[] {-2, 2}) {
						BlockPos rail = new BlockPos(p.getX(), y, p.getZ()).relative(side, w);
						support(level, rail);
						set(level, rail, Blocks.STONE_BRICKS.defaultBlockState());
						set(level, rail.above(), (k % 6 == 0 ? Blocks.STONE_BRICKS : Blocks.STONE_BRICK_WALL).defaultBlockState());
						if (k % 6 == 0) {
							set(level, rail.above(2), Blocks.LANTERN.defaultBlockState());
						}
					}
					if (bridge && k % 6 == 0) {
						for (int w = -2; w <= 2; w++) {
							support(level, new BlockPos(p.getX(), y, p.getZ()).relative(side, w)); // pillar to the riverbed
						}
					} else if (bridge && (k % 6 == 1 || k % 6 == 5)) {
						Direction toPillar = k % 6 == 1 ? forward.getOpposite() : forward;
						for (int w = -1; w <= 1; w++) {
							BlockPos under = new BlockPos(p.getX(), y - 1, p.getZ()).relative(side, w);
							set(level, under, Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, toPillar)
									.setValue(StairBlock.HALF, Half.TOP)); // the arch springs from the pillar
						}
					}
				} else if (job.tier() >= 2 && k % 24 == 12) {
					post(level, p.relative(side, 2));
				}
			}
			if (i[0] >= n) {
				JOBS.remove(id);
				ServerPlayer pl = server.getPlayerList().getPlayer(job.player());
				if (pl != null) {
					People.say(pl, "Architect", "The road is finished.");
				}
			}
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
			support(level, cell);
			block = (h == 0 ? Blocks.CRACKED_STONE_BRICKS : Blocks.STONE_BRICKS).defaultBlockState();
		} else if (tier >= 2) {
			block = (w == 0 ? (h < 2 ? Blocks.COBBLESTONE : Blocks.GRAVEL) : h == 0 ? Blocks.MOSSY_COBBLESTONE : Blocks.COBBLESTONE).defaultBlockState();
		} else {
			block = (w == 0 || h < 4 ? Blocks.DIRT_PATH : Blocks.COARSE_DIRT).defaultBlockState();
		}
		BlockState now = level.getBlockState(cell);
		if (now.getBlock() instanceof LiquidBlock || now.isAir() || SiteWork.natural(now) || now.is(Blocks.DIRT_PATH) || now.is(Blocks.GRAVEL)
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

	private static final int OUT_RADIUS = 150;
	/** Where the last way-out road of a settlement bends most (its middle), for the field test's views. */
	static final Map<UUID, BlockPos> WAY_OUT = new HashMap<>();
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
		BlockPos cornerA = heart.offset(Template.rotate(new BlockPos(-63 - 9, 0, -56 - 9), turn));
		BlockPos cornerB = heart.offset(Template.rotate(new BlockPos(69 + 9, 0, 40 + 9), turn));
		int bx0 = Math.min(cornerA.getX(), cornerB.getX()), bx1 = Math.max(cornerA.getX(), cornerB.getX());
		int bz0 = Math.min(cornerA.getZ(), cornerB.getZ()), bz1 = Math.max(cornerA.getZ(), cornerB.getZ());
		Set<Long> builtCols = new java.util.HashSet<>();
		for (long b : built) {
			builtCols.add(BlockPos.asLong(BlockPos.getX(b), 0, BlockPos.getZ(b)));
		}
		int[] h = new int[n * n];
		boolean[] water = new boolean[n * n];
		boolean[] blocked = new boolean[n * n];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < n; i++) {
			for (int k = 0; k < n; k++) {
				int x = x0 + i, z = z0 + k, c = i * n + k;
				if (!level.isLoaded(pos.set(x, 0, z)) || x >= bx0 && x <= bx1 && z >= bz0 && z <= bz1 || builtCols.contains(BlockPos.asLong(x, 0, z))) {
					blocked[c] = true;
					continue;
				}
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
				BlockState st = level.getBlockState(pos.set(x, y, z));
				while (y > level.getMinY() && st.getFluidState().isEmpty() && (st.is(BlockTags.LOGS) || st.canBeReplaced() || !st.isSolid())) {
					st = level.getBlockState(pos.set(x, --y, z));
				}
				h[c] = y;
				water[c] = !st.getFluidState().isEmpty();
				blocked[c] = !water[c] && !SiteWork.natural(st); // someone's floor or roof: not ours to dig
			}
		}
		int si = start.getX() - x0, sk = start.getZ() - z0;
		if (si < 0 || sk < 0 || si >= n || sk >= n) {
			return;
		}
		int startCell = si * n + sk;
		blocked[startCell] = false;
		{ // the road starts on whatever is at the gate's exit now: the landing the gate built, or the land
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, start.getX(), start.getZ()) - 1;
			BlockState st = level.getBlockState(pos.set(start.getX(), y, start.getZ()));
			while (y > level.getMinY() && st.getFluidState().isEmpty() && (st.is(BlockTags.LOGS) || st.canBeReplaced() || !st.isSolid())) {
				st = level.getBlockState(pos.set(start.getX(), --y, start.getZ()));
			}
			h[startCell] = y;
			water[startCell] = !st.getFluidState().isEmpty();
		}
		int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		// open land: every column from which the edge of the area can be walked to (one up, three down, no swimming)
		boolean[] open = new boolean[n * n];
		java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
		for (int i = 0; i < n; i++) {
			for (int c : new int[] {i * n, i * n + n - 1, i, (n - 1) * n + i}) {
				if (!blocked[c] && !water[c] && !open[c]) {
					open[c] = true;
					queue.add(c);
				}
			}
		}
		while (!queue.isEmpty()) {
			int b = queue.poll();
			int bi = b / n, bk = b % n;
			for (int[] d : dirs) {
				int ai = bi + d[0], ak = bk + d[1];
				if (ai < 0 || ak < 0 || ai >= n || ak >= n) {
					continue;
				}
				int a = ai * n + ak;
				if (!open[a] && !blocked[a] && !water[a] && h[b] - h[a] <= 1 && h[a] - h[b] <= 3) {
					open[a] = true;
					queue.add(a);
				}
			}
		}
		if (open[startCell]) {
			return; // the gate already opens onto land that leads away
		}
		// the cheapest road from the gate to the open land. The search runs over (column, road height): the road
		// rises or falls at most one block per block and lies at most DEPTH above or below the land (deeper cuts and
		// higher banks aren't built), so a steep hillside is crossed on a long slanting road instead of a stair.
		// Cost per column: the earth moved there, water costs a causeway.
		final int depth = 10, kk = depth * 2 + 1;
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
		while (heap.size() > 0) {
			long e = heap.poll();
			int st = (int) e, cost0 = (int) (e >>> 32);
			if (cost0 > dist[st]) {
				continue;
			}
			int a = st / kk, ra = land[a] + st % kk - depth;
			if (open[a] && ra - land[a] <= 3 && land[a] - ra <= 1 && a != startCell) {
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
				if (bi < 0 || bk < 0 || bi >= n || bk >= n || blocked[bi * n + bk]) {
					continue;
				}
				int b = bi * n + bk;
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
					if (off < -depth || off > depth || rb < floor[b]) {
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
			if (owner != null && TOLD.add(s.id())) {
				People.say(owner, "Architect", "Outside the gate the land is closed in by water and cliffs, and no road can be laid down from it. "
						+ "Make a way out by hand, or found the castle facing open land.");
			}
			io.github.sotadevop.kingdomquest.KingdomQuest.LOG.info("Way out of {}: no road possible from {}", s.name(), start);
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
		BlockState fill = SiteWork.subsoil(SiteWork.surface(level, heart));
		int tier = tier(s);
		for (int i = 0; i < len; i++) {
			int c = path.get(i);
			BlockPos p = new BlockPos(x0 + c / n, road[i], z0 + c % n);
			int prev = path.get(Math.max(0, i - 1)), next = path.get(Math.min(len - 1, i + 1));
			boolean alongX = Math.abs(next / n - prev / n) >= Math.abs(next % n - prev % n);
			Direction side = alongX ? Direction.SOUTH : Direction.EAST;
			for (int w = -1; w <= 1; w++) {
				BlockPos cell = p.relative(side, w);
				int ci = cell.getX() - x0, ck = cell.getZ() - z0;
				if (ci < 0 || ck < 0 || ci >= n || ck >= n || (w != 0 && blocked[ci * n + ck]) || builtCols.contains(BlockPos.asLong(cell.getX(), 0, cell.getZ()))) {
					continue;
				}
				// cut: the land above the road goes (trees whole), headroom 3
				int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, cell.getX(), cell.getZ());
				for (int y = Math.max(top, cell.getY() + 3); y > cell.getY(); y--) {
					BlockPos a = new BlockPos(cell.getX(), y, cell.getZ());
					BlockState st = level.getBlockState(a);
					if (st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES)) {
						SiteWork.removeTreeAt(level, a, built);
					} else if (!st.isAir() && (st.canBeReplaced() || SiteWork.natural(st)) && st.getFluidState().isEmpty()) {
						level.setBlock(a, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
					}
				}
				// fill: earth under the road down to the land; stone through water to the bed
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
		StringBuilder trace = new StringBuilder();
		for (int i = 0; i < len; i++) {
			int c = path.get(i);
			trace.append(String.format(" (%d,%d) land %d road %d%s", x0 + c / n - heart.getX(), z0 + c % n - heart.getZ(), land[c] - heart.getY(),
					road[i] - heart.getY(), water[c] ? " water" : ""));
		}
		io.github.sotadevop.kingdomquest.KingdomQuest.LOG.info("Way out path:{}", trace);
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
