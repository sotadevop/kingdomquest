package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.building.Layout;
import java.util.Base64;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The town's own greenery (user, 2026-11-25: the empty grass inside the castle should get trees, flowers and bushes).
 * The site is cleared when it is shaped; the spots that no building, street or lane of any style ever takes
 * (layout.json "green", tools/buildings.py greenery) are planted once the land under them has its final height: a tree
 * of the biome, a patch of the biome's flowers and grass, or a bush. Each spot is planted once; what the lord cuts
 * down stays gone.
 */
final class Greenery {
	private static final String DONE = "green_done";

	private Greenery() {
	}

	static void grow(MinecraftServer server, Settlement s, int budget) {
		Grade g = Grade.of(server, s);
		if (g == null || s.level(Settlement.MAIN) < 2) {
			return;
		}
		int[][] spots = Layout.green(Settlements.round(s));
		if (spots.length == 0) {
			return;
		}
		ServerLevel level = server.overworld();
		String stored = s.names().get(DONE);
		BitSet done = stored == null ? new BitSet() : BitSet.valueOf(Base64.getDecoder().decode(stored));
		Set<Long> built = new HashSet<>();
		s.built().values().forEach(built::addAll);
		Rotation r = Rotation.values()[s.rotation()];
		String style = Style.forBiome(level.getBiome(s.heart()).getRegisteredName());
		boolean changed = false;
		for (int i = 0; i < spots.length && budget > 0; i++) {
			if (done.get(i)) {
				continue;
			}
			BlockPos col = s.heart().offset(Layout.rel(r, spots[i][0], 0, spots[i][1]));
			if (!level.isLoaded(col) || !g.settled(col.getX(), col.getZ())) {
				continue;
			}
			int y = g.topAt(col.getX(), col.getZ());
			BlockPos ground = col.atY(y);
			BlockState st = level.getBlockState(ground);
			boolean soil = st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.PODZOL) || st.is(Blocks.DIRT) || st.is(Blocks.SNOW_BLOCK) || st.is(Blocks.MOSS_BLOCK);
			boolean open = true;
			for (int h = 1; h <= 3 && open; h++) {
				BlockState a = level.getBlockState(ground.above(h));
				open = (a.isAir() || a.is(Blocks.SNOW)) && !built.contains(ground.above(h).asLong());
			}
			done.set(i);
			changed = true;
			if (!soil || !open || built.contains(ground.asLong())) {
				continue; // something stands there now (the lord's own building, a path): the spot stays as it is
			}
			budget--;
			switch (spots[i][2]) {
				case 0 -> Grade.place(level, level.getChunkSource().getGenerator(), level.getRandom(), ground.above(), true, s.heart(), false);
				case 1 -> Grade.place(level, level.getChunkSource().getGenerator(), level.getRandom(), ground.above(), false, s.heart(), true);
				default -> bush(level, ground.above(), style);
			}
		}
		if (changed) {
			Settlement fresh = Settlements.byHeart(server, s.heart());
			if (fresh != null) {
				Settlements.putState(server, fresh.withName(DONE, Base64.getEncoder().encodeToString(done.toByteArray())));
			}
		}
	}

	/** A low bush of the region's leaves: one block, two on some. */
	private static void bush(ServerLevel level, BlockPos at, String style) {
		Block leaves = switch (style) {
			case Style.FROZEN -> Blocks.SPRUCE_LEAVES;
			case Style.ARID -> Blocks.ACACIA_LEAVES;
			case Style.WILD -> Blocks.JUNGLE_LEAVES;
			case Style.HIGHLAND -> Blocks.DARK_OAK_LEAVES;
			default -> Blocks.AZALEA_LEAVES;
		};
		BlockState st = leaves.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
		level.setBlock(at, st, Block.UPDATE_CLIENTS);
		if (Math.floorMod(at.getX() * 31 + at.getZ() * 17, 3) == 0) {
			BlockPos side = at.east();
			if (level.getBlockState(side).isAir() && level.getBlockState(side.below()).isSolid()) {
				level.setBlock(side, st, Block.UPDATE_CLIENTS);
			}
		}
	}
}
