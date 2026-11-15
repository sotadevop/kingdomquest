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
	/** Block updates without item drops: replaced banners, beds and the like just vanish. */
	public static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS;
	/** Flat strip outside a wall before the slope starts. */
	private static final int FLAT = 4;

	public record Column(int dx, int dz, int floor) {
	}

	private static final Map<String, List<Column>> CACHE = new HashMap<>();

	@SuppressWarnings("unchecked")
	private static final TagKey<Block>[] NATURAL = new TagKey[] {BlockTags.DIRT, BlockTags.BASE_STONE_OVERWORLD, BlockTags.LOGS,
			BlockTags.LEAVES, BlockTags.SAND, BlockTags.SNOW, BlockTags.ICE, BlockTags.FLOWERS, BlockTags.SAPLINGS, BlockTags.TERRACOTTA,
			BlockTags.REPLACEABLE};
	// listed by hand too: in 26.3 grass blocks are not in the dirt tag (found by the CI site test)
	private static final Set<Block> NATURAL_BLOCKS = Set.of(Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT,
			Blocks.PODZOL, Blocks.MYCELIUM, Blocks.SAND, Blocks.RED_SAND, Blocks.STONE, Blocks.DEEPSLATE, Blocks.GRANITE, Blocks.DIORITE,
			Blocks.ANDESITE, Blocks.TUFF, Blocks.CALCITE, Blocks.SNOW, Blocks.ICE, Blocks.PACKED_ICE, Blocks.SHORT_GRASS, Blocks.TALL_GRASS,
			Blocks.FERN, Blocks.LARGE_FERN, Blocks.GRAVEL, Blocks.CLAY, Blocks.MOSS_BLOCK, Blocks.PUMPKIN, Blocks.MELON,
			Blocks.SUGAR_CANE, Blocks.CACTUS, Blocks.BAMBOO, Blocks.SWEET_BERRY_BUSH, Blocks.SNOW_BLOCK, Blocks.POWDER_SNOW, Blocks.MUD,
			Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.POINTED_DRIPSTONE, Blocks.DRIPSTONE_BLOCK, Blocks.BROWN_MUSHROOM,
			Blocks.RED_MUSHROOM, Blocks.BROWN_MUSHROOM_BLOCK, Blocks.RED_MUSHROOM_BLOCK, Blocks.MUSHROOM_STEM, Blocks.BEE_NEST,
			Blocks.COBWEB, Blocks.LILY_PAD, Blocks.KELP, Blocks.KELP_PLANT, Blocks.SEAGRASS, Blocks.TALL_SEAGRASS);

	private SiteWork() {
	}

	/**
	 * The ground a job works on: every column within {@code apron} of the template's footprint (its blocks,
	 * not its bounding box), so cleared land follows the buildings instead of being one big rectangle.
	 * Distance is Chebyshev with a little noise, so the edge is irregular; floor = 0 in the flat margin, then the
	 * allowed height rises gently with distance (natural slope, slightly uneven).
	 */
	public static List<Column> columns(String job, Template t, Rotation rotation) {
		return CACHE.computeIfAbsent(job + "|" + rotation.ordinal(), k -> {
			int a = t.apron;
			int flat = t.apron >= 12 ? FLAT : Math.max(1, a / 2);
			Map<Long, Integer> dist = new HashMap<>();
			java.util.ArrayDeque<long[]> queue = new java.util.ArrayDeque<>();
			for (Template.Entry e : t.entries) {
				if (e.y() < -1 || e.name().endsWith(":snow") || e.name().endsWith(":air")) {
					continue;
				}
				long key = BlockPos.asLong(e.x(), 0, e.z());
				if (dist.putIfAbsent(key, 0) == null) {
					queue.add(new long[] {e.x(), e.z(), 0});
				}
			}
			while (!queue.isEmpty()) {
				long[] c = queue.poll();
				if (c[2] >= a) {
					continue;
				}
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						long key = BlockPos.asLong((int) c[0] + dx, 0, (int) c[1] + dz);
						if (!dist.containsKey(key)) {
							dist.put(key, (int) c[2] + 1);
							queue.add(new long[] {c[0] + dx, c[1] + dz, c[2] + 1});
						}
					}
				}
			}
			List<Column> list = new ArrayList<>();
			for (Map.Entry<Long, Integer> e : dist.entrySet()) {
				int x = BlockPos.getX(e.getKey());
				int z = BlockPos.getZ(e.getKey());
				int d = e.getValue();
				int noise = (int) Math.floorMod((long) x * 73856093L ^ (long) z * 19349663L, 3L) - 1; // -1, 0 or 1
				if (d > 0 && d + noise > a) {
					continue; // ragged edge
				}
				int floor = d <= flat ? 0 : Math.max(1, (d - flat) * 3 / 4 + noise);
				BlockPos rel = Template.rotate(new BlockPos(x - t.heart.getX(), 0, z - t.heart.getZ()), rotation);
				list.add(new Column(rel.getX(), rel.getZ(), d == 0 ? 0 : floor));
			}
			list.sort(java.util.Comparator.comparingInt(Column::dx).thenComparingInt(Column::dz));
			return List.copyOf(list);
		});
	}

	/** Terrain only (no plants or trees): what the slope zone outside the flat margin may cut. */
	static boolean terrain(BlockState state) {
		return !state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.REPLACEABLE) && !state.is(BlockTags.FLOWERS)
				&& !state.is(BlockTags.SAPLINGS) && natural(state) && state.isSolid();
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
		// grass blocks are listed by hand: the 26.3 dirt tag doesn't contain them
		return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM) || state.is(Blocks.DIRT)
				|| state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.SAND) || state.is(Blocks.RED_SAND)
				|| state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.TERRACOTTA)
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

	static boolean isTopSoil(BlockState st) {
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
		int floor = heart.getY() + c.floor();
		boolean margin = c.floor() == 0;
		// the heightmap alone missed blocks (CI site test); scan at least 64 blocks above the floor as well
		int top = Math.min(level.getMaxY(), Math.max(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), floor + 64));
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = top; y >= floor; y--) {
			pos.set(x, y, z);
			if (built.contains(pos.asLong()) || pos.equals(heart)) {
				continue;
			}
			BlockState st = level.getBlockState(pos);
			// in the flat margin everything natural goes (trees too); further out only the land is shaped
			if (margin ? natural(st) : terrain(st)) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
			}
		}
		// the new ground layer: cut stone, dirt, leaves or logs at ground level become the area's surface block
		pos.set(x, floor - 1, z);
		BlockState ground = level.getBlockState(pos);
		if (!built.contains(pos.asLong()) && level.getBlockState(pos.above()).isAir() && !isTopSoil(ground)
				&& (exposedInside(ground) || margin && (ground.is(BlockTags.LEAVES) || ground.is(BlockTags.LOGS)))) {
			level.setBlock(pos, surface, FLAGS);
		}
		// foundation: fill holes, water and leaves below the ground layer; outside the building the fill slopes away
		int fillTop = heart.getY() - 1 - c.floor();
		for (int y = fillTop; y >= fillTop - FOUNDATION; y--) {
			pos.set(x, y, z);
			if (built.contains(pos.asLong())) {
				continue;
			}
			BlockState state = level.getBlockState(pos);
			boolean hole = state.isAir() || state.canBeReplaced() || state.getBlock() instanceof LiquidBlock || state.is(BlockTags.LEAVES);
			if (!hole) {
				break; // solid ground reached
			}
			level.setBlock(pos, y == fillTop ? surface : Blocks.DIRT.defaultBlockState(), FLAGS);
		}
	}

	private static final Block[] GRASSY = {Blocks.SHORT_GRASS, Blocks.SHORT_GRASS, Blocks.SHORT_GRASS, Blocks.FERN, Blocks.DANDELION,
			Blocks.POPPY, Blocks.OXEYE_DAISY, Blocks.CORNFLOWER, Blocks.AZURE_BLUET};

	/**
	 * Makes cleared but unused ground look natural: grass tufts and flowers on some of the grass blocks of the area.
	 * They are natural blocks, so a later building simply clears them again.
	 */
	public static void decorate(ServerLevel level, BlockPos heart, List<Column> columns, Set<Long> built) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (Column c : columns) {
			int x = heart.getX() + c.dx();
			int z = heart.getZ() + c.dz();
			int h = (int) Math.floorMod((long) x * 83492791L ^ (long) z * 2654435761L, 100L);
			if (h >= 22) {
				continue;
			}
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
			pos.set(x, y - 1, z);
			if (built.contains(pos.asLong()) || !level.getBlockState(pos).is(Blocks.GRASS_BLOCK)) {
				continue;
			}
			pos.set(x, y, z);
			if (!level.getBlockState(pos).isAir() || built.contains(pos.asLong())) {
				continue;
			}
			level.setBlock(pos, GRASSY[h % GRASSY.length].defaultBlockState(), Block.UPDATE_CLIENTS);
		}
	}
}
