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
}
