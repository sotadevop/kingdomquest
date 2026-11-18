package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.building.Template;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
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
 * height and holes are filled. Around it (the template's "apron") the land is graded the way a hillside looks:
 * a rounded flat pad with an uneven edge, higher ground cut back at a gentle slope that steepens further out,
 * small dips filled with earth. Where the land falls away, the pad stands on a solid stone-faced foundation that
 * goes down to the ground (a terrace wall): nothing is ever left on stilts or hanging.
 * Only natural blocks are removed (terrain, plants, trees, water); the player's own blocks and the settlement's
 * buildings stay.
 */
public final class SiteWork {
	/** Block updates without item drops: replaced banners, beds and the like just vanish. */
	public static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS;
	/** Flat strip outside a wall before the slope starts. */
	private static final int FLAT = 4;

	/**
	 * @param floor how far above the pad the land may stay in this column (0 on the pad itself)
	 * @param edge  the outermost ring of the flat pad, where a terrace gets its retaining wall
	 * @param drop  how far below the pad a bank of earth ends in this column (0 on the pad itself)
	 * @param out   how many blocks this column lies outside the flat pad (0 on the pad itself)
	 */
	public record Column(int dx, int dz, int floor, boolean edge, int drop, int out) {
		public Column(int dx, int dz, int floor) {
			this(dx, dz, floor, false, floor, floor);
		}

		/** On the flat pad itself (not the graded land around it). */
		public boolean pad() {
			return this.drop == 0;
		}
	}

	/**
	 * How far around a building the land is shaped: cut slopes and banks run on until they meet the land, and over a
	 * deep drop the terraces step down to it, instead of stopping at the template's apron in a cliff.
	 */
	private static final int REACH = 14;
	/** Over a deep drop the made ground steps down in terraces: a stone face this high, then a bench of earth. */
	static final int TERRACE_STEP = 4;
	/** Width of each terrace bench (the flat strip of earth between two stone faces). */
	static final int BENCH = 3;

	/** How deep a pad's stone foundation goes to find the ground. */
	private static final int PLINTH = 48;

	/** Flowers, saplings, mushrooms and other small plants: not ground, and no reason to leave a hole when land is filled. */
	static boolean plant(BlockState st) {
		return !st.isAir() && !st.isSolid() && !(st.getBlock() instanceof LiquidBlock) && !st.is(BlockTags.LOGS) && !st.is(BlockTags.LEAVES)
				&& !st.hasBlockEntity() && natural(st);
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
			Blocks.COBWEB, Blocks.MANGROVE_ROOTS, Blocks.MUDDY_MANGROVE_ROOTS, Blocks.LILY_PAD, Blocks.KELP, Blocks.KELP_PLANT, Blocks.SEAGRASS, Blocks.TALL_SEAGRASS);

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
			if (t.follow || t.anchored) {
				return List.of(); // terrain-following blocks are placed on the ground as it is
			}
			int a = t.apron;
			int flat = Math.min(3, Math.max(1, a / 2));
			int reach = a == 0 ? 0 : Math.max(a, REACH);
			// distance from the footprint in tenths of a block, diagonal steps cost 1.4: pads get round corners
			Map<Long, Integer> dist = new HashMap<>();
			java.util.PriorityQueue<long[]> queue = new java.util.PriorityQueue<long[]>(java.util.Comparator.<long[]>comparingLong(c -> c[2]));
			for (Template.Entry e : t.entries) {
				if (e.y() < -1 || e.name().endsWith(":snow") || e.name().endsWith(":air")) {
					continue;
				}
				long key = BlockPos.asLong(e.x(), 0, e.z());
				if (dist.putIfAbsent(key, 0) == null) {
					queue.add(new long[] {e.x(), e.z(), 0});
				}
			}
			for (int[] r : t.pads) { // the plot of the track's later levels is levelled from the start
				for (int x = r[0]; x <= r[2]; x++) {
					for (int z = r[1]; z <= r[3]; z++) {
						if (dist.putIfAbsent(BlockPos.asLong(x, 0, z), 0) == null) {
							queue.add(new long[] {x, z, 0});
						}
					}
				}
			}
			while (!queue.isEmpty()) {
				long[] c = queue.poll();
				if (c[2] > dist.get(BlockPos.asLong((int) c[0], 0, (int) c[1]))) {
					continue;
				}
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (dx == 0 && dz == 0) {
							continue;
						}
						int d = (int) c[2] + (dx != 0 && dz != 0 ? 14 : 10);
						long key = BlockPos.asLong((int) c[0] + dx, 0, (int) c[1] + dz);
						if (d <= reach * 10 && d < dist.getOrDefault(key, Integer.MAX_VALUE)) {
							dist.put(key, d);
							queue.add(new long[] {c[0] + dx, c[1] + dz, d});
						}
					}
				}
			}
			Map<Long, int[]> shape = new HashMap<>(); // column -> {floor, drop, out}
			for (Map.Entry<Long, Integer> e : dist.entrySet()) {
				int x = BlockPos.getX(e.getKey());
				int z = BlockPos.getZ(e.getKey());
				double d = e.getValue() / 10.0;
				// the pad is a block wider in places (the same for a few blocks at a time), so its edge is no ruler line
				double out = d - flat - (a >= 6 ? wobble(x, z) : 0);
				// cut: one block up for every two out over the first four blocks, then one for one, like a hill's foot
				int floor = out <= 0 ? 0 : (int) Math.floor(out <= 4 ? out * 0.5 + 0.5 : out - 1.5);
				// made ground falls away the same way: gently at first, then one block per block
				int drop = out <= 0 ? 0 : (int) Math.ceil(out <= 4 ? out * 0.5 : out - 2);
				shape.put(e.getKey(), new int[] {e.getValue() == 0 ? 0 : floor, e.getValue() == 0 ? 0 : drop,
						e.getValue() == 0 || out <= 0 ? 0 : (int) Math.ceil(out)});
			}
			List<Column> list = new ArrayList<>();
			for (Map.Entry<Long, int[]> e : shape.entrySet()) {
				int x = BlockPos.getX(e.getKey());
				int z = BlockPos.getZ(e.getKey());
				boolean edge = false;
				if (e.getValue()[1] == 0) {
					for (int[] n : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
						int[] other = shape.get(BlockPos.asLong(x + n[0], 0, z + n[1]));
						edge |= other == null || other[1] > 0;
					}
				}
				BlockPos rel = Template.rotate(new BlockPos(x - t.heart.getX(), 0, z - t.heart.getZ()), rotation);
				list.add(new Column(rel.getX(), rel.getZ(), e.getValue()[0], edge, e.getValue()[1], e.getValue()[2]));
			}
			list.sort(java.util.Comparator.comparingInt(Column::dx).thenComparingInt(Column::dz));
			return List.copyOf(list);
		});
	}

	/** 0 or 1, constant over patches of four by four blocks. */
	private static int wobble(int x, int z) {
		long h = (long) Math.floorDiv(x, 4) * 73856093L ^ (long) Math.floorDiv(z + 2, 4) * 19349663L;
		return (int) Math.floorMod(h * 2654435761L >> 7, 3L) == 0 ? 1 : 0;
	}

	/** Terrain only (no plants or trees): what the slope zone outside the flat margin may cut. */
	public static boolean terrain(BlockState state) {
		return !state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.REPLACEABLE) && !state.is(BlockTags.FLOWERS)
				&& !state.is(BlockTags.SAPLINGS) && natural(state) && state.isSolid();
	}

	public static boolean natural(BlockState state) {
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
			for (int dx = -40; dx <= 40; dx += 4) {
				for (int dz = -40; dz <= 40; dz += 4) {
					// the top of the land in this column, wherever it lies: on a hillside the heart's own level says little
					int y = groundDeep(level, heart.getX() + dx, heart.getZ() + dz, heart.getY());
					BlockState st = level.getBlockState(new BlockPos(heart.getX() + dx, y, heart.getZ() + dz));
					if (surfaceLike(st)) {
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

	/** Top soil, or the terracotta of the badlands: what the land is covered with. */
	static boolean surfaceLike(BlockState st) {
		return isTopSoil(st) || st.is(BlockTags.TERRACOTTA);
	}

	/** What lies under the surface block where land is built up: earth under grass, the same stone in badlands and deserts. */
	static BlockState subsoil(BlockState surface) {
		if (surface.is(BlockTags.TERRACOTTA)) {
			return surface;
		}
		return surface.is(Blocks.SAND) ? Blocks.SANDSTONE.defaultBlockState()
				: surface.is(Blocks.RED_SAND) ? Blocks.RED_SANDSTONE.defaultBlockState() : Blocks.DIRT.defaultBlockState();
	}

	/** Dirt or stone that only shows because a hill was cut away; it gets the surface block on top. */
	private static boolean exposedInside(BlockState st) {
		return st.is(Blocks.DIRT) || st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(Blocks.GRAVEL) || st.is(Blocks.SANDSTONE)
				|| st.is(Blocks.RED_SANDSTONE) || BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath().endsWith("_ore");
	}

	/**
	 * Clears one column down to its floor, puts the area's surface block on cut ground, and inside the area itself
	 * fills holes and water under the ground layer down to solid ground (at most {@link #FOUNDATION} deep).
	 * {@code loose}: built blocks that simply lie on the land (roads, lamps) and are laid again after the land changed.
	 */
	public static void clear(ServerLevel level, BlockPos heart, Column c, Set<Long> built, BlockState surface, Set<Long> loose) {
		clear(level, heart, c, built, surface, loose, false, Set.of());
	}

	/**
	 * The most common top block of the land that borders water in the band around the ring: what the natural shore
	 * is made of here. Turf-like tops (grass, podzol, snow...) give the land's own surface.
	 */
	private static BlockState naturalShore(ServerLevel level, int x0, int z0, int x1, int z1, int reach, Set<Long> built, BlockState surface) {
		Map<Block, Integer> count = new HashMap<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = x0 - reach; x <= x1 + reach; x += 2) {
			for (int z = z0 - reach; z <= z1 + reach; z += 2) {
				if (x > x0 && x < x1 && z > z0 && z < z1 || !level.isLoaded(pos.set(x, 0, z))) {
					continue;
				}
				int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
				BlockState st = level.getBlockState(pos.set(x, top, z));
				if (!st.getFluidState().isEmpty() || built.contains(pos.asLong()) || !terrain(st)) {
					continue;
				}
				boolean wet = false;
				for (Direction d : Direction.Plane.HORIZONTAL) {
					wet |= level.getFluidState(pos.set(x + d.getStepX() * 2, top, z + d.getStepZ() * 2)).is(net.minecraft.tags.FluidTags.WATER);
				}
				if (wet) {
					count.merge(level.getBlockState(pos.set(x, top, z)).getBlock(), 1, Integer::sum);
				}
			}
		}
		Block best = count.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(Blocks.SAND);
		BlockState b = best.defaultBlockState();
		if (b.is(Blocks.GRASS_BLOCK) || b.is(Blocks.DIRT) || b.is(Blocks.PODZOL) || b.is(Blocks.MYCELIUM) || b.is(Blocks.SNOW_BLOCK) || b.is(Blocks.COARSE_DIRT)) {
			return surface;
		}
		return b;
	}

	/** The bed under the water off a shore of {@code shore}: turf can't lie under water, so it becomes its earth. */
	private static BlockState bedFor(BlockState shore, BlockState surface) {
		return shore.is(Blocks.GRASS_BLOCK) || shore.is(Blocks.MYCELIUM) || shore.is(Blocks.PODZOL) || shore.is(Blocks.SNOW_BLOCK) ? subsoil(surface) : shore;
	}

	/**
	 * Natural pieces left hanging after a build cut the land (an overhang whose rock below was taken, the stump of a
	 * cut tree): everything natural in the box that is no longer connected to the ground or the box's sides is
	 * removed. Built blocks are never touched and count as support.
	 */
	public static void dropFloating(ServerLevel level, int x0, int z0, int x1, int z1, Set<Long> built) {
		int nx = x1 - x0 + 1, nz = z1 - z0 + 1;
		if (nx <= 2 || nz <= 2 || (long) nx * nz > 90_000) {
			return;
		}
		int ylo = Integer.MAX_VALUE, yhi = Integer.MIN_VALUE;
		for (int x = x0; x <= x1; x += 3) {
			for (int z = z0; z <= z1; z += 3) {
				ylo = Math.min(ylo, level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z));
				yhi = Math.max(yhi, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
			}
		}
		ylo = Math.max(level.getMinY(), ylo - 6);
		yhi = yhi + 2;
		int ny = yhi - ylo + 1;
		if (ny <= 2 || (long) nx * nz * ny > 4_000_000) {
			return;
		}
		java.util.BitSet solid = new java.util.BitSet(nx * nz * ny);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < nx; i++) {
			for (int k = 0; k < nz; k++) {
				for (int j = 0; j < ny; j++) {
					BlockState st = level.getBlockState(pos.set(x0 + i, ylo + j, z0 + k));
					boolean leaves = st.getBlock() instanceof LeavesBlock && !(st.hasProperty(LeavesBlock.PERSISTENT) && st.getValue(LeavesBlock.PERSISTENT));
					if (!st.isAir() && st.getFluidState().isEmpty() && !leaves) {
						solid.set((i * nz + k) * ny + j);
					}
				}
			}
		}
		java.util.BitSet seen = new java.util.BitSet(nx * nz * ny);
		java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
		for (int i = 0; i < nx; i++) {
			for (int k = 0; k < nz; k++) {
				for (int j = 0; j < ny; j++) {
					int idx = (i * nz + k) * ny + j;
					if ((j == 0 || i == 0 || k == 0 || i == nx - 1 || k == nz - 1) && solid.get(idx) && !seen.get(idx)) {
						seen.set(idx);
						queue.add(idx);
					}
				}
			}
		}
		int[][] nb = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}, {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1}, {1, -1, 0},
				{-1, -1, 0}, {0, -1, 1}, {0, -1, -1}, {1, 0, 1}, {1, 0, -1}, {-1, 0, 1}, {-1, 0, -1}};
		while (!queue.isEmpty()) {
			int idx = queue.poll();
			int j = idx % ny, col = idx / ny, i = col / nz, k = col % nz;
			for (int[] d : nb) {
				int a = i + d[0], c = k + d[2], b = j + d[1];
				if (a < 0 || c < 0 || b < 0 || a >= nx || c >= nz || b >= ny) {
					continue;
				}
				int n = (a * nz + c) * ny + b;
				if (solid.get(n) && !seen.get(n)) {
					seen.set(n);
					queue.add(n);
				}
			}
		}
		for (int idx = solid.nextSetBit(0); idx >= 0; idx = solid.nextSetBit(idx + 1)) {
			if (seen.get(idx)) {
				continue;
			}
			int j = idx % ny, col = idx / ny;
			pos.set(x0 + col / nz, ylo + j, z0 + col % nz);
			BlockState st = level.getBlockState(pos);
			if (!built.contains(pos.asLong()) && (terrain(st) || trunk(st) || earthwork(st))) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
			}
		}
	}

	/**
	 * A dug moat must stay dry: water standing in the ditch (ground water, a lake edge) is filled with earth, and
	 * water right beside the ditch's open cells is walled off with earth, so nothing runs in later.
	 */
	public static void sealDitch(ServerLevel level, Set<Long> ditch, Set<Long> built, BlockState surface) {
		BlockState earth = subsoil(surface);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (long col : ditch) {
			int x = BlockPos.getX(col), z = BlockPos.getZ(col);
			int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
			for (int y = top; y > top - 30; y--) {
				BlockState st = level.getBlockState(pos.set(x, y, z));
				if (!st.getFluidState().isEmpty() && !built.contains(pos.asLong())) {
					level.setBlock(pos, earth, FLAGS);
				} else if (!st.isAir()) {
					break;
				}
			}
			int floor = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
			for (int y = floor; y <= floor + 12; y++) {
				if (!level.getBlockState(pos.set(x, y, z)).isAir()) {
					continue;
				}
				for (Direction d : Direction.Plane.HORIZONTAL) {
					BlockPos n = pos.relative(d);
					if (!ditch.contains(BlockPos.asLong(n.getX(), 0, n.getZ())) && !level.getFluidState(n).isEmpty() && !built.contains(n.asLong())) {
						level.setBlock(n, earth, FLAGS);
					}
				}
			}
		}
	}

	/** Earth and sand that can show as the side of made ground. */
	private static boolean earthy(BlockState st) {
		return st.is(Blocks.DIRT) || st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.COARSE_DIRT) || st.is(Blocks.ROOTED_DIRT) || st.is(Blocks.PODZOL)
				|| st.is(Blocks.SAND) || st.is(Blocks.RED_SAND) || st.is(Blocks.GRAVEL) || st.is(Blocks.SANDSTONE) || st.is(Blocks.RED_SANDSTONE)
				|| st.is(Blocks.MUD) || st.is(Blocks.SNOW_BLOCK) || st.is(BlockTags.TERRACOTTA);
	}

	/**
	 * Made ground (columns a build filled up) whose side stands open 3 or more blocks high gets a masonry face there,
	 * the top block stays turf: the same look as the terrace walls, never a tall bare wall of earth.
	 */
	public static void dressFaces(ServerLevel level, Set<Long> filled, Set<Long> built, BlockState surface) {
		BlockState wall = wallFor(surface);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (long col : filled) {
			int x = BlockPos.getX(col), z = BlockPos.getZ(col);
			int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
			int low = Integer.MAX_VALUE;
			for (Direction d : Direction.Plane.HORIZONTAL) {
				low = Math.min(low, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + d.getStepX(), z + d.getStepZ()) - 1);
			}
			if (top - low < 3) {
				continue; // no side of it stands 3 or more above its lowest neighbour
			}
			for (int y = top - 1; y > low; y--) {
				pos.set(x, y, z);
				BlockState st = level.getBlockState(pos);
				if (built.contains(pos.asLong()) || !earthy(st) || !openSide(level, pos, built)) {
					continue;
				}
				level.setBlock(pos, Math.floorMod(x * 31 + y * 7 + z * 13, 5) == 0 ? mossy(wall) : wall, FLAGS);
			}
		}
	}

	/** Deepest drop under a pad's rim that is still made up with a natural bank of earth; beyond it the pad gets a stone terrace wall. */
	public static final int BANK_LIMIT = 9;

	/**
	 * How far the land lies below a building's pad at the lowest point of its rim (water counts as open): decides
	 * between a natural bank ({@link #BANK_LIMIT}) and a terrace wall.
	 */
	public static int rimDrop(ServerLevel level, BlockPos heart, List<Column> columns) {
		int worst = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (Column c : columns) {
			if (!c.pad() || !c.edge()) {
				continue;
			}
			int depth = 0;
			for (int y = heart.getY() - 1; depth < PLINTH && y > level.getMinY(); y--, depth++) {
				BlockState st = level.getBlockState(pos.set(heart.getX() + c.dx(), y, heart.getZ() + c.dz()));
				if (!(st.isAir() || st.canBeReplaced() || !st.getFluidState().isEmpty() || st.is(BlockTags.LEAVES) || plant(st))) {
					break;
				}
			}
			worst = Math.max(worst, depth);
		}
		return worst;
	}

	/**
	 * {@code bank}: the pad lies only a little above the land around it, so it is made up like a natural rise: earth
	 * instead of masonry, and around it a bank that falls away to the land (also under water: a shore).
	 * {@code roads}: road blocks, which a bank may cover (they are laid again on top of it).
	 */
	public static boolean clear(ServerLevel level, BlockPos heart, Column c, Set<Long> built, BlockState surface, Set<Long> loose, boolean bank,
			Set<Long> roads) {
		int x = heart.getX() + c.dx();
		int z = heart.getZ() + c.dz();
		int floor = heart.getY() + c.floor();
		boolean margin = c.pad();
		// the heightmap alone missed blocks (CI site test); scan at least 64 blocks above the floor as well
		int top = Math.min(level.getMaxY(), Math.max(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), floor + 64));
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = top; y >= floor; y--) {
			pos.set(x, y, z);
			if (built.contains(pos.asLong()) || pos.equals(heart)) {
				// something built that rests on what is below it (a floor, a wall, a foundation): the land under it
				// stays, or the neighbour's house would be left hanging. An eave or a lantern over open air is no reason.
				BlockState under = level.getBlockState(pos.below());
				if (y > floor && !loose.contains(pos.asLong()) && !level.getBlockState(pos).isAir() && !under.isAir() && !under.canBeReplaced()
						&& !(under.getBlock() instanceof LiquidBlock)) {
					break;
				}
				pos.set(x, y, z);
				continue;
			}
			BlockState st = level.getBlockState(pos);
			if (trunk(st) || st.is(BlockTags.LEAVES)) {
				// any part of a tree in the way takes the whole tree: no half trees or floating crowns
				// on the plot every tree goes, roots and all; on the slopes around it only a trunk standing in the way
				// (mangrove roots there stay: felling whole swamp trees from the slope tore up the land around plots)
				if (margin || st.is(BlockTags.LOGS) && y <= floor + 1) {
					removeTreeAt(level, pos.immutable(), built);
				}
				continue;
			}
			// in the flat margin everything natural goes; further out only the land is shaped. Old terrace masonry
			// nobody owns is land too
			if (margin ? natural(st) || earthwork(st) : terrain(st) || earthwork(st)) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
				if (trunk(level.getBlockState(pos.above())) && !built.contains(pos.above().asLong())) {
					removeTreeAt(level, pos.above(), built); // the ground under a tree was cut: the tree goes too
				}
			}
		}
		// the new ground layer: cut stone, dirt, leaves or logs at ground level become the area's surface block
		pos.set(x, floor - 1, z);
		BlockState ground = level.getBlockState(pos);
		if (!built.contains(pos.asLong()) && level.getBlockState(pos.above()).isAir() && !surfaceLike(ground)
				&& (exposedInside(ground) || margin && (ground.is(BlockTags.LEAVES) || ground.is(BlockTags.LOGS)))) {
			level.setBlock(pos, surface, FLAGS);
		}
		// foundation: fill holes, water and leaves below the ground layer
		int fillTop = heart.getY() - 1 - c.drop();
		// over a deep drop (no bank) the land around the pad steps down in terraces: benches BENCH wide, each
		// TERRACE_STEP lower than the last, made up to the land below; the outer row of each bench is its stone face
		boolean terrace = !bank && !margin && c.out() > 0;
		int bench = terrace ? (c.out() + BENCH - 1) / BENCH : 0;
		if (terrace) {
			fillTop = heart.getY() - 1 - bench * TERRACE_STEP;
		}
		int reach = margin || terrace ? PLINTH : FOUNDATION;
		int depth = 0;
		boolean blocked = false;
		for (int y = fillTop; y >= fillTop - reach && y > level.getMinY(); y--) {
			pos.set(x, y, z);
			if (built.contains(pos.asLong())) {
				if (y >= fillTop - 1 && !level.getBlockState(pos).isAir() || bank && roads.contains(pos.asLong())) {
					depth++; // a floor or a road already lies here: the foundation goes on below it
					continue;
				}
				blocked = true;
				break; // a cellar, the moat, a neighbour's roof: never filled
			}
			BlockState state = level.getBlockState(pos);
			if (margin && state.is(BlockTags.LOGS)) {
				removeTreeAt(level, pos.immutable(), built); // a tree growing up from below the pad
				state = level.getBlockState(pos);
			}
			if (!(state.isAir() || state.canBeReplaced() || state.getBlock() instanceof LiquidBlock || state.is(BlockTags.LEAVES) || plant(state))) {
				break;
			}
			depth++;
		}
		if (depth == 0 || !margin && !terrace && depth > 2 && (!bank || blocked) || terrace && blocked) {
			return false; // outside the pad only dips are filled; a real drop stays natural and the pad ends in its wall
		}
		// a pad over lower land is built up solid: earth on top, stone below, and its rim is a masonry wall
		BlockState wall = wallFor(surface);
		// a terrace step of up to 2 blocks is a grassy bank; only a real step (3 and more) gets a stone face
		boolean high = terrace && openBelow(level, x, fillTop, z, built) >= 3;
		for (int y = fillTop; y > fillTop - depth; y--) {
			pos.set(x, y, z);
			if (built.contains(pos.asLong()) && !(bank && roads.contains(pos.asLong()))) {
				continue;
			}
			// a terrace block whose side is open is part of a retaining wall: masonry. Where the next bench later
			// covers it, the stone is simply hidden; every face that stays in sight is stone, never bare earth
			boolean face = high && depth > 1 && y < fillTop && openSide(level, pos, built);
			boolean stone = !bank && margin && depth > 2 && (c.edge() || y < fillTop - 2) || face;
			BlockState fill = stone ? (Math.floorMod(x * 31 + y * 7 + z * 13, 5) == 0 ? mossy(wall) : wall)
					: y == fillTop ? surface : subsoil(surface);
			level.setBlock(pos, fill, FLAGS);
		}
		return true;
	}

	/** How far the land beside a column lies below {@code top} at its lowest side (open blocks counted, at most 3). */
	private static int openBelow(ServerLevel level, int x, int top, int z, Set<Long> built) {
		int most = 0;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (Direction d : Direction.Plane.HORIZONTAL) {
			int n = 0;
			while (n < 3) {
				p.set(x + d.getStepX(), top - n, z + d.getStepZ());
				BlockState st = level.getBlockState(p);
				if (built.contains(p.asLong()) || !(st.isAir() || st.canBeReplaced() || !st.getFluidState().isEmpty())) {
					break;
				}
				n++;
			}
			most = Math.max(most, n);
		}
		return most;
	}

	/** Air, water or a plant beside this block on any side (not counting built blocks): it would show. */
	private static boolean openSide(ServerLevel level, BlockPos pos, Set<Long> built) {
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos n = pos.relative(d);
			BlockState st = level.getBlockState(n);
			if (!built.contains(n.asLong()) && (st.isAir() || st.canBeReplaced() || !st.getFluidState().isEmpty())) {
				return true;
			}
		}
		return false;
	}

	/** Stone for retaining walls, matching the ground: sandstone in deserts, cobblestone in the snow, stone bricks elsewhere. */
	static BlockState wallFor(BlockState surface) {
		if (surface.is(Blocks.SAND)) {
			return Blocks.SMOOTH_SANDSTONE.defaultBlockState();
		}
		if (surface.is(Blocks.RED_SAND) || surface.is(BlockTags.TERRACOTTA)) {
			return Blocks.CUT_RED_SANDSTONE.defaultBlockState();
		}
		return surface.is(Blocks.SNOW_BLOCK) ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState();
	}

	private static BlockState mossy(BlockState wall) {
		return wall.is(Blocks.STONE_BRICKS) ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState()
				: wall.is(Blocks.COBBLESTONE) ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : wall;
	}

	/**
	 * After a building is finished: wherever a block of its ground course ({@code baseY} or lower) has nothing under
	 * it, masonry is carried down to the ground. Whatever happened to the land before, nothing is left on stilts.
	 * Cellars, the moat and anything else the settlement dug stay open.
	 */
	public static void underpin(ServerLevel level, List<Long> mine, int baseY, Set<Long> built, BlockState surface) {
		Map<Long, Integer> low = new HashMap<>();
		for (long l : mine) {
			int y = BlockPos.getY(l);
			if (y <= baseY) {
				low.merge(BlockPos.asLong(BlockPos.getX(l), 0, BlockPos.getZ(l)), y, Math::min);
			}
		}
		BlockState wall = wallFor(surface);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (Map.Entry<Long, Integer> e : low.entrySet()) {
			int x = BlockPos.getX(e.getKey());
			int z = BlockPos.getZ(e.getKey());
			pos.set(x, e.getValue(), z);
			BlockState foot = level.getBlockState(pos);
			if (foot.isAir() || !foot.isSolid()) {
				continue; // dug out or a plant: nothing that needs carrying
			}
			int depth = 0;
			boolean ground = false;
			for (int y = e.getValue() - 1; y >= e.getValue() - PLINTH && y > level.getMinY(); y--) {
				pos.set(x, y, z);
				if (built.contains(pos.asLong())) {
					break;
				}
				BlockState st = level.getBlockState(pos);
				if (!(st.isAir() || st.canBeReplaced() || st.getBlock() instanceof LiquidBlock || st.is(BlockTags.LEAVES) || plant(st))) {
					ground = true;
					break;
				}
				depth++;
			}
			if (!ground || depth == 0) {
				continue;
			}
			for (int y = e.getValue() - 1; y >= e.getValue() - depth; y--) {
				pos.set(x, y, z);
				level.setBlock(pos, Math.floorMod(x * 31 + y * 7 + z * 13, 5) == 0 ? mossy(wall) : wall, FLAGS);
			}
		}
	}

	/** A log or leaf block of a tree is in the way: every tree it belongs to goes, whole. */
	/** Logs, and mangrove roots: they belong to their tree and go with it (cut one by one, the rest would hang). */
	static boolean trunk(BlockState st) {
		return st.is(BlockTags.LOGS) || st.is(Blocks.MANGROVE_ROOTS) || st.is(Blocks.MUDDY_MANGROVE_ROOTS);
	}

	/**
	 * Masonry the settlement's own land work left (terrace faces, pad rims, dressed faces) where no building owns
	 * it: a later cut removes it like the land, or it would stand on in a new plot (a door walled in by an old
	 * terrace face, field test).
	 */
	static boolean earthwork(BlockState st) {
		return st.is(Blocks.STONE_BRICKS) || st.is(Blocks.MOSSY_STONE_BRICKS) || st.is(Blocks.COBBLESTONE) || st.is(Blocks.MOSSY_COBBLESTONE)
				|| st.is(Blocks.SMOOTH_SANDSTONE) || st.is(Blocks.CUT_RED_SANDSTONE);
	}

	public static void removeTreeAt(ServerLevel level, BlockPos pos, Set<Long> built) {
		BlockState st = level.getBlockState(pos);
		if (trunk(st)) {
			fellTree(level, pos, built);
			return;
		}
		if (!st.is(BlockTags.LEAVES)) {
			return;
		}
		boolean any = false;
		for (BlockPos p : BlockPos.betweenClosed(pos.offset(-5, -6, -5), pos.offset(5, 3, 5))) {
			if (level.getBlockState(p).is(BlockTags.LOGS) && !built.contains(p.asLong())) {
				fellTree(level, p.immutable(), built);
				any = true;
			}
		}
		if (!any && !built.contains(pos.asLong())) {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS); // stray leaves
		}
	}

	/**
	 * Shapes the land of a region toward the site plan before something is built in it: earth is added or taken
	 * away column by column, with the land's own surface on top. Only open natural ground moves: a column with
	 * anything built on it or beside it stays (except roads, which are taken up and laid again afterwards), and so
	 * do trees, water (see {@link #reclaim}) and the moat. Toward the edge of the region the change fades out, so
	 * it never ends in a step; the next building nearby carries it on.
	 *
	 * @param plan world column -> planned height of the ground top relative to the block under the heart, or MIN_VALUE
	 */
	public static void grade(ServerLevel level, BlockPos heart, int x0, int z0, int x1, int z1, java.util.function.IntBinaryOperator plan,
			Set<Long> built, Set<Long> roads, Set<Long> keep, BlockState surface) {
		final int fade = 8;
		int w = x1 - x0 + 1;
		int d = z1 - z0 + 1;
		int[] h = new int[w * d];
		boolean[] open = new boolean[w * d];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < w; i++) {
			for (int k = 0; k < d; k++) {
				int y = groundDeep(level, x0 + i, z0 + k, heart.getY());
				h[i * d + k] = y;
				pos.set(x0 + i, y, z0 + k);
				BlockState top = level.getBlockState(pos);
				boolean road = roads.contains(pos.asLong());
				open[i * d + k] = level.isLoaded(pos) && (road || !built.contains(pos.asLong()) && terrain(top))
						&& !built.contains(pos.above().asLong()) && !built.contains(pos.above(2).asLong())
						&& !level.getBlockState(pos.above()).is(BlockTags.LOGS) && !keep.contains(BlockPos.asLong(x0 + i, 0, z0 + k));
			}
		}
		for (int i = 1; i < w - 1; i++) {
			for (int k = 1; k < d - 1; k++) {
				int c = i * d + k;
				boolean free = true;
				for (int di = -1; di <= 1 && free; di++) {
					for (int dk = -1; dk <= 1 && free; dk++) {
						free = open[c + di * d + dk];
					}
				}
				int x = x0 + i;
				int z = z0 + k;
				int want = plan.applyAsInt(x, z);
				if (!free || want == Integer.MIN_VALUE) {
					continue;
				}
				double f = Math.min(1.0, Math.min(Math.min(i, w - 1 - i), Math.min(k, d - 1 - k)) / (double) fade);
				int cur = h[c];
				int target = cur + (int) Math.round(f * (heart.getY() - 1 + want - cur));
				target = Math.max(cur - 10, Math.min(cur + 10, target));
				if (target > cur) {
					int reached = cur;
					for (int y = cur + 1; y <= target; y++) {
						BlockState there = level.getBlockState(pos.set(x, y, z));
						if (built.contains(pos.asLong()) || !(there.isAir() || there.canBeReplaced() || plant(there)) || !there.getFluidState().isEmpty()) {
							break;
						}
						level.setBlock(pos, y == target ? surface : subsoil(surface), FLAGS);
						reached = y;
					}
					if (reached > cur) {
						if (reached < target) {
							level.setBlock(pos.set(x, reached, z), surface, FLAGS);
						}
						pos.set(x, cur, z);
						if (roads.contains(pos.asLong()) || isTopSoil(level.getBlockState(pos))) {
							level.setBlock(pos, subsoil(surface), FLAGS); // buried turf and old road become earth
						}
					}
				} else if (target < cur) {
					BlockState top = level.getBlockState(pos.set(x, cur, z));
					for (int y = cur + 1; y <= cur + 2; y++) {
						BlockState above = level.getBlockState(pos.set(x, y, z));
						if (!built.contains(pos.asLong()) && natural(above) && !terrain(above) && !above.is(BlockTags.LOGS)) {
							level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
						}
					}
					int reached = cur;
					for (int y = cur; y > target; y--) {
						pos.set(x, y, z);
						if (!roads.contains(pos.asLong()) && (built.contains(pos.asLong()) || !terrain(level.getBlockState(pos)))) {
							break;
						}
						level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
						reached = y - 1;
					}
					pos.set(x, reached, z);
					if (reached < cur && !built.contains(pos.asLong()) && exposedInside(level.getBlockState(pos))) {
						level.setBlock(pos, surfaceLike(top) ? top : surface, FLAGS);
					}
				}
			}
		}
	}

	/**
	 * A castle by or in the water makes its own land: every water column inside the ring (x0..x1, z0..z1) is filled
	 * from the bed up to the water line, a strip of beach runs around the walls, and beyond it the new land slopes
	 * down under water like a natural shore. Deeper than {@link #PLINTH} nothing is done. Ice counts as water.
	 */
	public static void reclaim(ServerLevel level, int x0, int z0, int x1, int z1, Set<Long> built, Set<Long> keep, BlockState surface) {
		final int berm = 6;
		final int reach = 16;
		BlockState shore = naturalShore(level, x0, z0, x1, z1, reach, built, surface);
		boolean grass = surface.is(Blocks.GRASS_BLOCK) || surface.is(Blocks.PODZOL) || surface.is(Blocks.MYCELIUM) || surface.is(Blocks.COARSE_DIRT);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = x0 - reach; x <= x1 + reach; x++) {
			for (int z = z0 - reach; z <= z1 + reach; z++) {
				if (keep.contains(BlockPos.asLong(x, 0, z)) || !level.isLoaded(pos.set(x, 0, z))) {
					continue;
				}
				int ox = Math.max(0, Math.max(x0 - x, x - x1));
				int oz = Math.max(0, Math.max(z0 - z, z - z1));
				int o = (int) Math.round(Math.sqrt(ox * ox + oz * oz)); // round corners
				if (o > reach) {
					continue;
				}
				int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
				BlockState st = level.getBlockState(pos.set(x, top, z));
				boolean wet = !st.getFluidState().isEmpty() && st.getFluidState().is(net.minecraft.tags.FluidTags.WATER) || st.is(Blocks.ICE);
				if (!wet || built.contains(pos.asLong())) {
					continue;
				}
				long h = Math.floorMod((long) x * 73856093L ^ (long) z * 19349663L, 12L);
				// the shore: flat to the edge of the beach, then down one block per block, a little uneven
				int target = top - Math.max(0, o - berm) - (o > berm && h % 3 == 0 ? 1 : 0);
				int bottom = top;
				boolean stop = false;
				while (bottom > top - PLINTH && bottom > level.getMinY()) {
					BlockState below = level.getBlockState(pos.set(x, bottom, z));
					if (built.contains(pos.asLong())) {
						stop = true;
						break;
					}
					if (!(below.isAir() || below.canBeReplaced() || !below.getFluidState().isEmpty() || below.is(Blocks.ICE) || plant(below))) {
						break;
					}
					bottom--;
				}
				if (stop || bottom <= top - PLINTH || target <= bottom) {
					continue; // something built below, an abyss, or the bed is already high enough
				}
				for (int y = bottom + 1; y <= target; y++) {
					BlockState fill;
					if (y < target) {
						fill = subsoil(surface);
					} else if (target < top) {
						// the bed off the shore: what the natural shore around is made of (sand, gravel, stone, mud...)
						fill = shore.is(Blocks.SAND) ? (h % 4 == 0 ? Blocks.GRAVEL : Blocks.SAND).defaultBlockState() : bedFor(shore, surface);
					} else {
						// the new shore outside the walls looks like the natural shore here: a sand beach only where the
						// shore is sand; stone, gravel, mycelium, snow or turf elsewhere (field test: sand beaches on a
						// stony shore, in mushroom fields and on snowy plains)
						fill = o >= 3 ? shore : surface;
					}
					level.setBlock(pos.set(x, y, z), fill, FLAGS);
				}
				if (target == top) {
					BlockState over = level.getBlockState(pos.set(x, top + 1, z));
					if (plant(over) || over.is(Blocks.LILY_PAD)) {
						level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
					}
					if (o == 0 && surface.is(Blocks.GRASS_BLOCK) && h < 2 && level.getBlockState(pos).isAir()) {
						level.setBlock(pos, Blocks.SHORT_GRASS.defaultBlockState(), Block.UPDATE_CLIENTS);
					}
				}
			}
		}
	}

	/** Everything natural above a dug block goes (earth, overhangs, trees, water): the moat lies open to the sky. */
	public static void openSky(ServerLevel level, BlockPos from, Set<Long> built) {
		int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, from.getX(), from.getZ());
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = from.getY() + 1; y <= top; y++) {
			pos.set(from.getX(), y, from.getZ());
			if (built.contains(pos.asLong())) {
				return;
			}
			BlockState st = level.getBlockState(pos);
			if (st.isAir()) {
				continue;
			}
			if (st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES)) {
				removeTreeAt(level, pos.immutable(), built);
			} else if (natural(st)) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
			} else {
				return; // something a player built
			}
		}
	}

	/** Clears what stands on a ground block that a road or path now covers: plants go, trees go whole. */
	public static void clearAbove(ServerLevel level, BlockPos ground, Set<Long> built) {
		for (int up = 1; up <= 3; up++) {
			BlockPos p = ground.above(up);
			if (built.contains(p.asLong())) {
				return;
			}
			BlockState st = level.getBlockState(p);
			if (st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES)) {
				removeTreeAt(level, p, built);
			} else if (natural(st) && !terrain(st)) {
				level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS);
			} else {
				return;
			}
		}
	}

	/**
	 * Removes a whole tree: every log connected to {@code start}, then the leaves that belonged to it (within four
	 * blocks of its logs and not closer to another tree), so nothing is left floating.
	 */
	static void fellTree(ServerLevel level, BlockPos start, Set<Long> built) {
		java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
		Set<Long> seen = new java.util.HashSet<>();
		List<BlockPos> logs = new ArrayList<>();
		queue.add(start);
		seen.add(start.asLong());
		while (!queue.isEmpty() && logs.size() < 900) {
			BlockPos p = queue.poll();
			if (!trunk(level.getBlockState(p))) {
				continue;
			}
			logs.add(p);
			for (BlockPos n : BlockPos.betweenClosed(p.offset(-1, -1, -1), p.offset(1, 1, 1))) {
				if (!built.contains(n.asLong()) && seen.add(n.asLong()) && trunk(level.getBlockState(n))) {
					queue.add(n.immutable());
				}
			}
		}
		if (logs.isEmpty()) {
			return;
		}
		int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
		for (BlockPos p : logs) {
			level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS);
			x0 = Math.min(x0, p.getX()); y0 = Math.min(y0, p.getY()); z0 = Math.min(z0, p.getZ());
			x1 = Math.max(x1, p.getX()); y1 = Math.max(y1, p.getY()); z1 = Math.max(z1, p.getZ());
		}
		for (BlockPos p : BlockPos.betweenClosed(x0 - 4, y0 - 1, z0 - 4, x1 + 4, y1 + 4, z1 + 4)) {
			if (built.contains(p.asLong()) || !level.getBlockState(p).is(BlockTags.LEAVES)) {
				continue;
			}
			int mine = Integer.MAX_VALUE;
			for (BlockPos l : logs) {
				mine = Math.min(mine, Math.max(Math.abs(l.getX() - p.getX()), Math.max(Math.abs(l.getY() - p.getY()), Math.abs(l.getZ() - p.getZ()))));
			}
			if (mine <= 4 && !otherLogNear(level, p, Math.min(mine - 1, 2))) {
				level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS);
			}
		}
	}

	private static boolean otherLogNear(ServerLevel level, BlockPos p, int r) {
		if (r < 1) {
			return false;
		}
		for (BlockPos n : BlockPos.betweenClosed(p.offset(-r, -r, -r), p.offset(r, r, r))) {
			if (level.getBlockState(n).is(BlockTags.LOGS)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Evens out the ground inside a settlement after a build: single-block holes are filled and single-block
	 * bumps cut, walls of earth one block thick and trenches one block wide are levelled (terraces stay), and the
	 * banks along roads are matched to the road so paths don't run in trenches. Only natural ground is touched;
	 * flowers and other small plants never stop a fill.
	 */
	public static void smooth(ServerLevel level, BlockPos heart, int x0, int z0, int x1, int z1, Set<Long> built, Set<Long> roads,
			BlockState surface, Set<Long> keep) {
		int w = x1 - x0 + 1;
		int d = z1 - z0 + 1;
		int[] h = new int[w * d];
		boolean[] free = new boolean[w * d];
		boolean[] land = new boolean[w * d]; // the top of the column is natural ground, not a roof or a wall
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < w; i++) {
			for (int k = 0; k < d; k++) {
				int x = x0 + i;
				int z = z0 + k;
				int y = groundTop(level, x, z, heart.getY());
				h[i * d + k] = y;
				pos.set(x, y, z);
				land[i * d + k] = !built.contains(pos.asLong()) && natural(level.getBlockState(pos));
				free[i * d + k] = land[i * d + k] && !built.contains(pos.above().asLong()) && !keep.contains(BlockPos.asLong(x, 0, z))
						&& !level.getBlockState(pos.above()).is(BlockTags.LOGS); // never undercut a tree
			}
		}
		int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		for (int pass = 0; pass < 4; pass++) {
			for (int i = 1; i < w - 1; i++) {
				for (int k = 1; k < d - 1; k++) {
					int c = i * d + k;
					if (!free[c]) {
						continue;
					}
					int lo = Integer.MAX_VALUE;
					int hi = Integer.MIN_VALUE;
					int roadY = Integer.MIN_VALUE;
					int[] hn = new int[4]; // E, W, S, N: a building beside the column says nothing about the land
					for (int q = 0; q < 4; q++) {
						int[] dd = dirs[q];
						int n = (i + dd[0]) * d + k + dd[1];
						pos.set(x0 + i + dd[0], h[n], z0 + k + dd[1]);
						boolean road = roads.contains(pos.asLong());
						hn[q] = land[n] || road ? h[n] : h[c];
						lo = Math.min(lo, hn[q]);
						hi = Math.max(hi, hn[q]);
						if (road) {
							roadY = Math.max(roadY, h[n]);
						}
					}
					int x = x0 + i;
					int z = z0 + k;
					int target = h[c];
					if (roadY != Integer.MIN_VALUE && Math.abs(h[c] - roadY) == 1) {
						target = roadY; // road banks: level with the road
					} else if (h[c] < lo && lo - h[c] <= 2) {
						target = lo; // hole
					} else if (h[c] > hi && h[c] - hi <= 2) {
						target = hi; // bump
					} else {
						int we = Math.max(hn[0], hn[1]);
						int ns = Math.max(hn[2], hn[3]);
						int weLow = Math.min(hn[0], hn[1]);
						int nsLow = Math.min(hn[2], hn[3]);
						boolean ridgeX = h[c] - we >= 2;
						boolean ridgeZ = h[c] - ns >= 2;
						boolean trenchX = weLow - h[c] >= 2;
						boolean trenchZ = nsLow - h[c] >= 2;
						if (ridgeX || ridgeZ) {
							target = ridgeX && ridgeZ ? Math.max(we, ns) : ridgeX ? we : ns; // a wall of earth one block thick
						} else if (trenchX || trenchZ) {
							target = trenchX && trenchZ ? Math.min(weLow, nsLow) : trenchX ? weLow : nsLow; // a slot one block wide
						}
					}
					if (target > h[c] && level.getBlockState(pos.set(x, h[c] + 1, z)).getBlock() instanceof LiquidBlock) {
						target = h[c]; // a stream or pond stays
					}
					if (target > h[c]) {
						for (int y = h[c] + 1; y <= target; y++) {
							pos.set(x, y, z);
							BlockState there = level.getBlockState(pos);
							if (built.contains(pos.asLong()) || !(there.canBeReplaced() || plant(there))) {
								break;
							}
							level.setBlock(pos, y == target ? surface : subsoil(surface), FLAGS);
						}
						pos.set(x, h[c], z);
						if (isTopSoil(level.getBlockState(pos))) {
							level.setBlock(pos, Blocks.DIRT.defaultBlockState(), FLAGS);
						}
						h[c] = target;
					} else if (target < h[c]) {
						BlockState top = level.getBlockState(pos.set(x, h[c], z));
						for (int y = h[c] + 1; y <= h[c] + 2; y++) {
							pos.set(x, y, z);
							BlockState above = level.getBlockState(pos);
							if (!built.contains(pos.asLong()) && natural(above) && !terrain(above) && !above.is(BlockTags.LOGS)) {
								level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
							}
						}
						for (int y = h[c]; y > target; y--) {
							pos.set(x, y, z);
							if (!terrain(level.getBlockState(pos))) {
								break;
							}
							level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
						}
						pos.set(x, target, z);
						if (exposedInside(level.getBlockState(pos))) {
							level.setBlock(pos, surfaceLike(top) ? top : surface, FLAGS);
						}
						h[c] = target;
					}
				}
			}
		}
	}

	/**
	 * Height of the ground at a column near {@code heartY}: the top full block that isn't part of a tree or a
	 * plant (roads and farmland count). Used by terrain-following templates.
	 */
	/** Like {@link #groundTop}, but searches 30 blocks up and 48 down: mountains and valleys. */
	public static int groundDeep(ServerLevel level, int x, int z, int heartY) {
		return groundDeep(level, x, z, heartY, Set.of());
	}

	/** {@code skip}: blocks to look through (what the settlement built: a road under a gatehouse lies on the ground, not on its roof). */
	public static int groundDeep(ServerLevel level, int x, int z, int heartY, Set<Long> skip) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = heartY + 30; y >= heartY - 48; y--) {
			pos.set(x, y, z);
			if (!skip.isEmpty() && skip.contains(pos.asLong())) {
				continue;
			}
			BlockState st = level.getBlockState(pos);
			if (st.isAir() || st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES) || st.canBeReplaced() || st.getBlock() instanceof LiquidBlock) {
				if (st.getBlock() instanceof LiquidBlock) {
					return y; // the water surface counts as ground for a wall
				}
				continue;
			}
			if (st.is(Blocks.DIRT_PATH) || st.is(Blocks.FARMLAND) || st.isCollisionShapeFullBlock(level, pos)) {
				return y;
			}
		}
		return heartY - 1;
	}

	public static int groundTop(ServerLevel level, int x, int z, int heartY) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = heartY + 12; y >= heartY - 12; y--) {
			pos.set(x, y, z);
			BlockState st = level.getBlockState(pos);
			if (st.isAir() || st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES) || st.canBeReplaced()) {
				continue;
			}
			if (st.is(Blocks.DIRT_PATH) || st.is(Blocks.FARMLAND) || st.isCollisionShapeFullBlock(level, pos)) {
				return y;
			}
		}
		return heartY - 1;
	}

	private static final Block[] GRASSY = {Blocks.SHORT_GRASS, Blocks.SHORT_GRASS, Blocks.SHORT_GRASS, Blocks.FERN, Blocks.DANDELION,
			Blocks.POPPY, Blocks.OXEYE_DAISY, Blocks.CORNFLOWER, Blocks.AZURE_BLUET};

	/**
	 * Makes cleared but unused ground look natural: grass tufts and flowers on some of the grass blocks of the area.
	 * They are natural blocks, so a later building simply clears them again.
	 */
	/**
	 * Where it snows, open ground the builders touched gets its snow back (cut, filled and regraded land would stay
	 * green in a white land until the next snowfall). Built columns, roads included, stay clear.
	 */
	public static void snowCover(ServerLevel level, int x0, int z0, int x1, int z1, Set<Long> built) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
				pos.set(x, y - 1, z);
				BlockState ground = level.getBlockState(pos);
				if (built.contains(pos.asLong()) || !terrain(ground) || !ground.isCollisionShapeFullBlock(level, pos)) {
					continue;
				}
				pos.set(x, y, z);
				BlockState at = level.getBlockState(pos);
				if (built.contains(pos.asLong()) || !(at.isAir() || plant(at) && at.canBeReplaced()) || at.is(Blocks.SNOW)) {
					continue;
				}
				if (level.getBiome(pos).value().shouldSnow(level, pos)) {
					level.setBlock(pos, Blocks.SNOW.defaultBlockState(), Block.UPDATE_ALL);
				}
			}
		}
	}

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
