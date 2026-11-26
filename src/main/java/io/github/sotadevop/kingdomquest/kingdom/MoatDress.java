package io.github.sotadevop.kingdomquest.kingdom;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Makes the dry moat look grown over rather than dug (user, 2026-11-26: "not natural enough", straight steps and bare
 * stone faces, the berm at the wall's foot bare): the ditch, its banks, the berm and a band of land beyond.
 * <ul>
 * <li>bare natural stone in the faces and on top becomes earth and turf;</li>
 * <li>the straight edges of the steps are broken up: here and there a lower step is raised to the next one, so the
 * slope's contour lines wander like a natural hillside's;</li>
 * <li>grass, ferns, flowers, the odd bush, on every free patch of turf (sand gets dead bushes; nothing in the snow).</li>
 * </ul>
 * Only natural blocks and air are touched: what the builders laid stays as it is, so repairs never fight this.
 */
final class MoatDress {
	/** How far the band reaches out from the ditch (and in over the berm). */
	private static final int BAND = 7;
	private static final Block[] FLOWERS = {Blocks.DANDELION, Blocks.POPPY, Blocks.OXEYE_DAISY, Blocks.CORNFLOWER, Blocks.AZURE_BLUET,
			Blocks.ALLIUM};

	private MoatDress() {
	}

	private static int hash(int x, int z, int salt) {
		return (int) Math.floorMod(((long) x * 73856093L) ^ ((long) z * 19349663L) ^ ((long) salt * 83492791L), 1000L);
	}

	static void dress(ServerLevel level, Settlement s, Set<Long> moat, Set<Long> built) {
		if (moat.isEmpty()) {
			return;
		}
		// the band: the ditch and every column within BAND of it whose top is no one's building (walls, roads and
		// houses stop it, so it doesn't reach into the town)
		Map<Long, Integer> band = new HashMap<>();
		ArrayDeque<Long> queue = new ArrayDeque<>();
		for (long c : moat) {
			band.put(c, 0);
			queue.add(c);
		}
		while (!queue.isEmpty()) {
			long c = queue.poll();
			int d = band.get(c);
			if (d >= BAND) {
				continue;
			}
			int x = BlockPos.getX(c), z = BlockPos.getZ(c);
			for (Direction dir : Direction.Plane.HORIZONTAL) {
				int nx = x + dir.getStepX(), nz = z + dir.getStepZ();
				long n = BlockPos.asLong(nx, 0, nz);
				if (band.containsKey(n) || !level.isLoaded(new BlockPos(nx, 0, nz))) {
					continue;
				}
				int top = top(level, nx, nz);
				BlockPos tp = new BlockPos(nx, top, nz);
				BlockState st = level.getBlockState(tp);
				boolean builtTop = built.contains(tp.asLong()) && !turf(st);
				if (builtTop || !st.getFluidState().isEmpty()) {
					continue; // a wall, a road, a house, water: the band ends there
				}
				band.put(n, d + 1);
				queue.add(n);
			}
		}
		// 1. bare stone becomes earth and turf
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		BlockState surface = SiteWork.surface(level, s.heart());
		boolean sandy = surface.is(Blocks.SAND) || surface.is(Blocks.RED_SAND);
		BlockState turfBlock = sandy ? surface : Blocks.GRASS_BLOCK.defaultBlockState();
		for (long c : band.keySet()) {
			int x = BlockPos.getX(c), z = BlockPos.getZ(c);
			int top = top(level, x, z);
			for (int y = top; y > top - 8; y--) {
				pos.set(x, y, z);
				BlockState st = level.getBlockState(pos);
				if (built.contains(pos.asLong()) || !stone(st)) {
					continue;
				}
				boolean open = y == top;
				for (Direction dir : Direction.Plane.HORIZONTAL) {
					open |= level.getBlockState(pos.relative(dir)).isAir();
				}
				if (open) {
					level.setBlock(pos, y == top ? turfBlock : hash(x, z, y) < 250 ? Blocks.COARSE_DIRT.defaultBlockState()
							: SiteWork.subsoil(surface), Block.UPDATE_CLIENTS);
				}
			}
		}
		// 2. the step edges wander: a lower tread beside a riser one block high is raised to the step above now and then
		// (never where a gate's bridge comes down)
		java.util.List<BlockPos> gates = new java.util.ArrayList<>();
		for (BlockPos[] e : Settlements.exits(s)) {
			gates.add(e[0]);
		}
		for (long c : band.keySet()) {
			int x = BlockPos.getX(c), z = BlockPos.getZ(c);
			if (hash(x, z, 7) >= 330 || gates.stream().anyMatch(g -> Math.abs(g.getX() - x) <= 8 && Math.abs(g.getZ() - z) <= 8)) {
				continue;
			}
			int top = top(level, x, z);
			boolean riser = false;
			for (Direction dir : Direction.Plane.HORIZONTAL) {
				long n = BlockPos.asLong(x + dir.getStepX(), 0, z + dir.getStepZ());
				riser |= band.containsKey(n) && top(level, x + dir.getStepX(), z + dir.getStepZ()) == top + 1;
			}
			pos.set(x, top + 1, z);
			if (riser && level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir() && turf(level.getBlockState(pos.below()))) {
				level.setBlock(pos.below(), SiteWork.subsoil(surface), Block.UPDATE_CLIENTS);
				level.setBlock(pos, turfBlock, Block.UPDATE_CLIENTS);
			}
		}
		// 3. plants on the turf
		for (long c : band.keySet()) {
			int x = BlockPos.getX(c), z = BlockPos.getZ(c);
			int top = top(level, x, z);
			pos.set(x, top + 1, z);
			if (!level.getBlockState(pos).isAir() || level.getBiome(pos).value().coldEnoughToSnow(pos, level.getSeaLevel())) {
				continue;
			}
			BlockState ground = level.getBlockState(pos.below());
			int h = hash(x, z, 3);
			if (ground.is(Blocks.SAND) || ground.is(Blocks.RED_SAND)) {
				if (h < 50) {
					level.setBlock(pos, Blocks.DEAD_BUSH.defaultBlockState(), Block.UPDATE_CLIENTS);
				}
				continue;
			}
			if (!ground.is(Blocks.GRASS_BLOCK) && !ground.is(Blocks.PODZOL) && !ground.is(Blocks.COARSE_DIRT) && !ground.is(Blocks.DIRT)) {
				continue;
			}
			if (!ground.is(Blocks.GRASS_BLOCK)) {
				if (h < 120) {
					level.setBlock(pos, Blocks.SHORT_GRASS.defaultBlockState(), Block.UPDATE_CLIENTS);
				}
				continue;
			}
			if (h < 420) {
				level.setBlock(pos, Blocks.SHORT_GRASS.defaultBlockState(), Block.UPDATE_CLIENTS);
			} else if (h < 520 && level.getBlockState(pos.above()).isAir()) {
				DoublePlantBlock.placeAt(level, Blocks.TALL_GRASS.defaultBlockState(), pos.immutable(), Block.UPDATE_CLIENTS);
			} else if (h < 590) {
				level.setBlock(pos, Blocks.FERN.defaultBlockState(), Block.UPDATE_CLIENTS);
			} else if (h < 680) {
				level.setBlock(pos, FLOWERS[hash(x, z, 11) % FLOWERS.length].defaultBlockState(), Block.UPDATE_CLIENTS);
			} else if (h < 700 && band.get(c) > 0) {
				// a low bush (never in the ditch's bottom, where the drawbridge comes down)
				level.setBlock(pos, (s.style().equals(Style.FROZEN) || s.style().equals(Style.HIGHLAND) ? Blocks.SPRUCE_LEAVES : Blocks.OAK_LEAVES)
						.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true), Block.UPDATE_CLIENTS);
			}
		}
	}

	private static int top(ServerLevel level, int x, int z) {
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, y, z);
		// plants and bushes on top are not the ground
		while (y > level.getMinY() && (level.getBlockState(p).canBeReplaced() || level.getBlockState(p).is(BlockTags.LEAVES))
				&& level.getBlockState(p).getFluidState().isEmpty()) {
			p.setY(--y);
		}
		return y;
	}

	private static boolean turf(BlockState st) {
		return st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.DIRT) || st.is(Blocks.COARSE_DIRT) || st.is(Blocks.PODZOL) || st.is(Blocks.SAND)
				|| st.is(Blocks.RED_SAND) || st.is(Blocks.SNOW_BLOCK) || st.is(Blocks.MUD) || st.is(Blocks.GRAVEL);
	}

	private static boolean stone(BlockState st) {
		return st.is(Blocks.STONE) || st.is(Blocks.ANDESITE) || st.is(Blocks.DIORITE) || st.is(Blocks.GRANITE) || st.is(Blocks.TUFF)
				|| st.is(Blocks.DEEPSLATE) || st.is(Blocks.CALCITE);
	}
}
