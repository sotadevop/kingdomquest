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
	 */
	public record Column(int dx, int dz, int floor, boolean edge, int drop) {
		public Column(int dx, int dz, int floor) {
			this(dx, dz, floor, false, floor);
		}

		/** On the flat pad itself (not the graded land around it). */
		public boolean pad() {
			return this.drop == 0;
		}
	}

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
			if (t.follow || t.anchored) {
				return List.of(); // terrain-following blocks are placed on the ground as it is
			}
			int a = t.apron;
			int flat = Math.min(3, Math.max(1, a / 2));
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
						if (d <= a * 10 && d < dist.getOrDefault(key, Integer.MAX_VALUE)) {
							dist.put(key, d);
							queue.add(new long[] {c[0] + dx, c[1] + dz, d});
						}
					}
				}
			}
			Map<Long, int[]> shape = new HashMap<>(); // column -> {floor, drop}
			for (Map.Entry<Long, Integer> e : dist.entrySet()) {
				int x = BlockPos.getX(e.getKey());
				int z = BlockPos.getZ(e.getKey());
				double d = e.getValue() / 10.0;
				// the pad is a block wider in places (the same for a few blocks at a time), so its edge is no ruler line
				double out = d - flat - (a >= 6 ? wobble(x, z) : 0);
				// cut: one block up for every two out over the first four blocks, then one for one, like a hill's foot
				int floor = out <= 0 ? 0 : (int) Math.floor(out <= 4 ? out * 0.5 + 0.5 : out - 1.5);
				int drop = out <= 0 ? 0 : (int) Math.ceil(out);
				shape.put(e.getKey(), new int[] {e.getValue() == 0 ? 0 : floor, e.getValue() == 0 ? 0 : drop});
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
				list.add(new Column(rel.getX(), rel.getZ(), e.getValue()[0], edge, e.getValue()[1]));
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
		return st.is(Blocks.DIRT) || st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(Blocks.GRAVEL)
				|| BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath().endsWith("_ore");
	}

	/**
	 * Clears one column down to its floor, puts the area's surface block on cut ground, and inside the area itself
	 * fills holes and water under the ground layer down to solid ground (at most {@link #FOUNDATION} deep).
	 * {@code loose}: built blocks that simply lie on the land (roads, lamps) and are laid again after the land changed.
	 */
	public static void clear(ServerLevel level, BlockPos heart, Column c, Set<Long> built, BlockState surface, Set<Long> loose) {
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
			if (st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES)) {
				// any part of a tree in the way takes the whole tree: no half trees or floating crowns
				if (margin || st.is(BlockTags.LOGS) && y <= floor + 1) {
					removeTreeAt(level, pos.immutable(), built);
				}
				continue;
			}
			// in the flat margin everything natural goes; further out only the land is shaped
			if (margin ? natural(st) : terrain(st)) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
				if (level.getBlockState(pos.above()).is(BlockTags.LOGS) && !built.contains(pos.above().asLong())) {
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
		int reach = margin ? PLINTH : FOUNDATION;
		int depth = 0;
		for (int y = fillTop; y >= fillTop - reach && y > level.getMinY(); y--) {
			pos.set(x, y, z);
			if (built.contains(pos.asLong())) {
				if (y >= fillTop - 1 && !level.getBlockState(pos).isAir()) {
					depth++; // a floor or a road already lies here: the foundation goes on below it
					continue;
				}
				break; // a cellar or the moat: never filled
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
		if (depth == 0 || !margin && depth > 2) {
			return; // outside the pad only dips are filled; a real drop stays natural and the pad ends in its wall
		}
		// a pad over lower land is built up solid: earth on top, stone below, and its rim is a masonry wall
		BlockState wall = wallFor(surface);
		for (int y = fillTop; y > fillTop - depth; y--) {
			pos.set(x, y, z);
			if (built.contains(pos.asLong())) {
				continue;
			}
			boolean stone = margin && depth > 2 && (c.edge() || y < fillTop - 2);
			BlockState fill = stone ? (Math.floorMod(x * 31 + y * 7 + z * 13, 5) == 0 ? mossy(wall) : wall)
					: y == fillTop ? surface : subsoil(surface);
			level.setBlock(pos, fill, FLAGS);
		}
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
	public static void removeTreeAt(ServerLevel level, BlockPos pos, Set<Long> built) {
		BlockState st = level.getBlockState(pos);
		if (st.is(BlockTags.LOGS)) {
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
		while (!queue.isEmpty() && logs.size() < 400) {
			BlockPos p = queue.poll();
			if (!level.getBlockState(p).is(BlockTags.LOGS)) {
				continue;
			}
			logs.add(p);
			for (BlockPos n : BlockPos.betweenClosed(p.offset(-1, -1, -1), p.offset(1, 1, 1))) {
				if (!built.contains(n.asLong()) && seen.add(n.asLong()) && level.getBlockState(n).is(BlockTags.LOGS)) {
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
