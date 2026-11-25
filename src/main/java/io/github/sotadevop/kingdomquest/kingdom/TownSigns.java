package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.building.Layout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Signs in the town (user, 2026-11-25: signs inside the castle and at the buildings): every building's own sign (the
 * hanging sign by its door) shows its name, and once the streets are paved (roads level 2) a signpost stands at each
 * crossing of the town's streets with arrows to the gate, the square, the castle and the buildings that stand that way.
 */
final class TownSigns {
	private static final String POST = "townsign|";
	private static final int NO_DROPS = Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS;
	/** Tracks whose blocks are no single building: no name sign, never a signpost's destination. */
	private static final List<String> NOT_BUILDINGS = List.of(Settlement.MAIN, Progression.WALLS, Progression.GATE, Progression.ROADS,
			Progression.LAMPS, Progression.POSTERN, Progression.TRAM, Progression.DOCK, Progression.CELLAR, "tourney", "quarry", "lumbercamp", "minecamp");

	private TownSigns() {
	}

	/** A job is done: its signs get the building's name, and the town's signposts are put up or brought up to date. */
	static void built(MinecraftServer server, Settlement s, String track) {
		if (s == null) {
			return;
		}
		ServerLevel level = server.overworld();
		if (!NOT_BUILDINGS.contains(track)) {
			Component name = Component.translatable("kingdomquest.track." + track);
			for (long at : s.built().getOrDefault(track, List.of())) {
				BlockPos p = BlockPos.of(at);
				if (level.isLoaded(p) && level.getBlockState(p).getBlock() instanceof SignBlock) {
					write(level, p, List.of(Component.empty(), name, Component.empty(), Component.empty()));
				}
			}
		}
		if (s.level(Progression.ROADS) >= 2) {
			posts(server, s);
		}
		Greenery.grow(server, s, 48);
	}

	private record Place(Component name, int bx, int bz, int rank) {
	}

	/** Where a signpost can point: the gate, the square and the castle, and every building that stands. */
	private static List<Place> places(Settlement s) {
		List<Place> out = new ArrayList<>();
		int[] sq = Layout.square();
		int[] keep = Layout.keep();
		out.add(new Place(Component.translatable("kingdomquest.tram.stop.gate"), Layout.gateX(), Layout.gateZ(Settlements.round(s)) - 4, 0));
		out.add(new Place(Component.translatable("kingdomquest.tram.stop.square"), (sq[0] + sq[2]) / 2, (sq[1] + sq[3]) / 2, 0));
		out.add(new Place(Component.translatable("kingdomquest.tram.stop.castle"), (keep[0] + keep[2]) / 2, (keep[1] + keep[3]) / 2, 0));
		Map<String, int[]> plots = new java.util.HashMap<>(Layout.plots());
		Layout.reserved().forEach(plots::putIfAbsent);
		for (Map.Entry<String, int[]> e : plots.entrySet()) {
			String track = e.getKey();
			if (NOT_BUILDINGS.contains(track) || s.level(track) <= 0) {
				continue;
			}
			int[] p = e.getValue();
			int rank = List.of(Progression.CHURCH, Progression.TOWNHALL, Progression.MARKETHALL, "tavern", Progression.BARRACKS, Progression.STOREHOUSE)
					.contains(track) ? 1 : 2;
			out.add(new Place(Component.translatable("kingdomquest.track." + track), (p[0] + p[2]) / 2, (p[1] + p[3]) / 2, rank));
		}
		return out;
	}

	/** A signpost at every crossing of two streets, beside it: up to three places per side, nearest first. */
	private static void posts(MinecraftServer server, Settlement s) {
		ServerLevel level = server.overworld();
		StreetPlan streets = StreetPlan.of(server, s);
		if (streets == null) {
			return;
		}
		Rotation r = Rotation.values()[s.rotation()];
		List<int[]> all = Layout.streets(Settlements.round(s));
		List<Place> places = places(s);
		String style = Style.forBiome(level.getBiome(s.heart()).getRegisteredName());
		for (int[] a : all) {
			for (int[] b : all) {
				if (a[4] != 0 || b[4] != 1) {
					continue;
				}
				int x0 = Math.max(a[0], b[0]), x1 = Math.min(a[2], b[2]), z0 = Math.max(a[1], b[1]), z1 = Math.min(a[3], b[3]);
				if (x0 > x1 || z0 > z1) {
					continue;
				}
				int cx = (x0 + x1) / 2, cz = (z0 + z1) / 2;
				BlockPos col = s.heart().offset(Layout.rel(r, cx, 0, cz));
				Integer f = streets.feetAt(BlockPos.asLong(col.getX(), 0, col.getZ()));
				if (f == null || !level.isLoaded(col)) {
					continue;
				}
				BlockPos centre = col.atY(s.heart().getY() + f - 1);
				post(server, s, level, centre, cx, cz, places, style, r);
			}
		}
	}

	private static void post(MinecraftServer server, Settlement s, ServerLevel level, BlockPos centre, int cx, int cz, List<Place> places, String style,
			Rotation r) {
		String key = POST + cx + "," + cz;
		BlockPos base = null;
		String known = s.names().get(key);
		if (known != null) {
			String[] p = known.split(",");
			base = new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
			if (!level.getBlockState(base).is(Roadside.postWood(style))) {
				return; // taken down by the lord (or built over): left so
			}
		} else {
			for (Direction d : new Direction[] {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
				if (base == null) {
					BlockPos b = Roadside.besideRoad(level, centre, d, 4);
					base = b != null && s.built().values().stream().noneMatch(l -> l.contains(b.asLong())) ? b : null;
				}
			}
			if (base == null) {
				return;
			}
			BlockState fence = Roadside.postWood(style).defaultBlockState();
			level.setBlock(base, fence, NO_DROPS);
			level.setBlock(base.above(), fence, NO_DROPS);
			level.setBlock(base.above(2), (style.equals(Style.ARID) ? Blocks.STRIPPED_ACACIA_LOG : style.equals(Style.WILD) ? Blocks.STRIPPED_JUNGLE_LOG
					: Blocks.STRIPPED_SPRUCE_LOG).defaultBlockState(), NO_DROPS);
			level.setBlock(base.above(3), Blocks.LANTERN.defaultBlockState(), NO_DROPS);
			Settlement fresh = Settlements.byHeart(server, s.heart());
			if (fresh != null) {
				Settlements.putState(server, fresh.withName(key, base.getX() + "," + base.getY() + "," + base.getZ()));
			}
		}
		BlockPos head = base.above(2);
		for (Direction face : new Direction[] {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
			List<Place> shown = new ArrayList<>();
			for (Place p : places) {
				BlockPos w = s.heart().offset(Layout.rel(r, p.bx(), 0, p.bz()));
				int dx = w.getX() - head.getX(), dz = w.getZ() - head.getZ();
				if (dx * dx + dz * dz < 10 * 10 || dx * dx + dz * dz > 170 * 170 || Roadside.horizontalDir(dx, dz) != face) {
					continue;
				}
				shown.add(p);
			}
			shown.sort(java.util.Comparator.comparingInt((Place p) -> p.rank()).thenComparingDouble(p -> {
				BlockPos w = s.heart().offset(Layout.rel(r, p.bx(), 0, p.bz()));
				return w.distSqr(head.atY(w.getY()));
			}));
			BlockPos at = head.relative(face);
			BlockState there = level.getBlockState(at);
			if (shown.isEmpty()) {
				continue;
			}
			if (!(there.getBlock() instanceof WallSignBlock)) {
				if (!there.isAir() && !there.canBeReplaced()) {
					continue;
				}
				level.setBlock(at, Roadside.wallSign(style).defaultBlockState().setValue(WallSignBlock.FACING, face), NO_DROPS);
			}
			List<Component> lines = new ArrayList<>();
			for (Place p : shown.subList(0, Math.min(3, shown.size()))) {
				BlockPos w = s.heart().offset(Layout.rel(r, p.bx(), 0, p.bz()));
				lines.add(Component.literal(Roadside.arrow(face, w.getX() - head.getX(), w.getZ() - head.getZ()) + " ").append(p.name()));
			}
			while (lines.size() < 4) {
				lines.add(Component.empty());
			}
			write(level, at, lines);
		}
	}

	private static void write(ServerLevel level, BlockPos at, List<Component> lines) {
		if (!(level.getBlockEntity(at) instanceof SignBlockEntity be)) {
			return;
		}
		SignText t = new SignText(lines, lines, DyeColor.BLACK, false);
		for (net.minecraft.world.level.block.entity.SignTextSlot slot : net.minecraft.world.level.block.entity.SignTextSlot.values()) {
			be.setText(t, slot);
		}
		be.setWaxed(true);
		be.setChanged();
		BlockState st = level.getBlockState(at);
		level.sendBlockUpdated(at, st, st, Block.UPDATE_ALL);
	}
}
