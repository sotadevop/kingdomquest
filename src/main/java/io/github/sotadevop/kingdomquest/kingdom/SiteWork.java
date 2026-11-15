package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.building.Template;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Levels the ground before a building goes up: every column of the template's area is cleared down to the heart's
 * height and holes are filled. Templates with an "apron" (walls) also grade the land outside: flat for 4 blocks,
 * then rising one block per block, so the wall stands free instead of running into a hillside.
 * Only natural blocks are removed (terrain, plants, trees, water); the player's own blocks and the settlement's
 * buildings stay.
 */
public final class SiteWork {
	/** Flat strip outside a wall before the slope starts. */
	private static final int FLAT = 4;

	public record Column(int dx, int dz, int floor) {
	}

	private static final Map<String, List<Column>> CACHE = new HashMap<>();

	@SuppressWarnings("unchecked")
	private static final TagKey<Block>[] NATURAL = new TagKey[] {BlockTags.DIRT, BlockTags.BASE_STONE_OVERWORLD, BlockTags.LOGS,
			BlockTags.LEAVES, BlockTags.SAND, BlockTags.SNOW, BlockTags.ICE, BlockTags.FLOWERS, BlockTags.SAPLINGS, BlockTags.TERRACOTTA,
			BlockTags.REPLACEABLE};
	private static final Set<Block> NATURAL_BLOCKS = Set.of(Blocks.GRAVEL, Blocks.CLAY, Blocks.MOSS_BLOCK, Blocks.PUMPKIN, Blocks.MELON,
			Blocks.SUGAR_CANE, Blocks.CACTUS, Blocks.BAMBOO, Blocks.SWEET_BERRY_BUSH, Blocks.SNOW_BLOCK, Blocks.POWDER_SNOW, Blocks.MUD,
			Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.POINTED_DRIPSTONE, Blocks.DRIPSTONE_BLOCK, Blocks.BROWN_MUSHROOM,
			Blocks.RED_MUSHROOM, Blocks.BROWN_MUSHROOM_BLOCK, Blocks.RED_MUSHROOM_BLOCK, Blocks.MUSHROOM_STEM, Blocks.BEE_NEST,
			Blocks.COBWEB, Blocks.LILY_PAD, Blocks.KELP, Blocks.KELP_PLANT, Blocks.SEAGRASS, Blocks.TALL_SEAGRASS);

	private SiteWork() {
	}

	public static List<Column> columns(String job, Template t, Rotation rotation) {
		return CACHE.computeIfAbsent(job + "|" + rotation.ordinal(), k -> {
			List<Column> list = new ArrayList<>();
			int a = t.apron;
			for (int x = -a; x < t.sizeX + a; x++) {
				for (int z = -a; z < t.sizeZ + a; z++) {
					int dx = x < 0 ? -x : Math.max(0, x - t.sizeX + 1);
					int dz = z < 0 ? -z : Math.max(0, z - t.sizeZ + 1);
					int d = Math.max(dx, dz);
					BlockPos rel = Template.rotate(new BlockPos(x - t.heart.getX(), 0, z - t.heart.getZ()), rotation);
					list.add(new Column(rel.getX(), rel.getZ(), d == 0 ? 0 : Math.max(0, d - FLAT)));
				}
			}
			return List.copyOf(list);
		});
	}

	static boolean natural(BlockState state) {
		if (state.isAir() || state.hasBlockEntity() && !state.is(Blocks.BEE_NEST)) {
			return false;
		}
		if (state.getBlock() instanceof LiquidBlock || NATURAL_BLOCKS.contains(state.getBlock())
				|| BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().endsWith("_ore")) {
			return true;
		}
		for (TagKey<Block> tag : NATURAL) {
			if (state.is(tag)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * True for natural ground a settlement can be founded on: soil, sand, stone, snow. Trees, leaves, water and
	 * anything built are refused, so the heart always sits on the real surface.
	 */
	public static boolean isGround(BlockState state) {
		if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || !state.getFluidState().isEmpty() || !state.isSolid()) {
			return false;
		}
		return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.TERRACOTTA)
				|| state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.MUD)
				|| state.is(Blocks.SANDSTONE) || state.is(Blocks.RED_SANDSTONE) || state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.PACKED_ICE);
	}

	/** Deepest a foundation is filled (over rivers and holes). */
	private static final int FOUNDATION = 16;
	private static final Map<Long, BlockState> SURFACE = new HashMap<>();

	/**
	 * The ground block of the area around a heart (grass, sand, snow, podzol...): the most common exposed block at
	 * ground level within 40 blocks. Cached per heart.
	 */
	public static BlockState surface(ServerLevel level, BlockPos heart) {
		return SURFACE.computeIfAbsent(heart.asLong(), k -> {
			Map<Block, Integer> count = new HashMap<>();
			int y = heart.getY() - 1;
			for (int dx = -40; dx <= 40; dx += 4) {
				for (int dz = -40; dz <= 40; dz += 4) {
					BlockPos p = new BlockPos(heart.getX() + dx, y, heart.getZ() + dz);
					BlockState st = level.getBlockState(p);
					BlockState above = level.getBlockState(p.above());
					if (isTopSoil(st) && !above.isSolid()) {
						count.merge(st.getBlock(), 1, Integer::sum);
					}
				}
			}
			Block best = count.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(Blocks.GRASS_BLOCK);
			return best.defaultBlockState();
		});
	}

	private static boolean isTopSoil(BlockState st) {
		return st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.SAND) || st.is(Blocks.RED_SAND) || st.is(Blocks.PODZOL) || st.is(Blocks.MYCELIUM)
				|| st.is(Blocks.SNOW_BLOCK) || st.is(Blocks.COARSE_DIRT) || st.is(Blocks.MUD) || st.is(Blocks.MOSS_BLOCK);
	}

	/** Dirt or stone that only shows because a hill was cut away; it gets the surface block on top. */
	private static boolean exposedInside(BlockState st) {
		return st.is(Blocks.DIRT) || st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(Blocks.GRAVEL)
				|| BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath().endsWith("_ore");
	}

	/**
	 * Clears one column down to its floor, puts the area's surface block on cut ground, and inside the area itself
	 * fills holes and water under the ground layer down to solid ground (at most {@link #FOUNDATION} deep).
	 */
	public static void clear(ServerLevel level, BlockPos heart, Column c, Set<Long> built, BlockState surface) {
		int x = heart.getX() + c.dx();
		int z = heart.getZ() + c.dz();
		int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
		int floor = heart.getY() + c.floor();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = top; y >= floor; y--) {
			pos.set(x, y, z);
			if (built.contains(pos.asLong()) || pos.equals(heart)) {
				continue;
			}
			if (natural(level.getBlockState(pos))) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
			}
		}
		pos.set(x, floor - 1, z);
		if (!built.contains(pos.asLong()) && exposedInside(level.getBlockState(pos)) && level.getBlockState(pos.above()).isAir()) {
			level.setBlock(pos, surface, Block.UPDATE_CLIENTS);
		}
		if (c.floor() > 0) {
			return;
		}
		for (int y = heart.getY() - 1; y >= heart.getY() - FOUNDATION; y--) {
			pos.set(x, y, z);
			if (built.contains(pos.asLong())) {
				continue;
			}
			BlockState state = level.getBlockState(pos);
			boolean hole = state.isAir() || state.canBeReplaced() || state.getBlock() instanceof LiquidBlock;
			if (!hole) {
				break; // solid ground reached
			}
			level.setBlock(pos, y == heart.getY() - 1 ? surface : Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
	}
}
