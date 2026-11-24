package io.github.sotadevop.kingdomquest.kingdom;

import com.mojang.datafixers.util.Pair;
import io.github.sotadevop.kingdomquest.KingdomQuest;
import io.github.sotadevop.kingdomquest.building.Layout;
import io.github.sotadevop.kingdomquest.building.Template;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Field test (CI): founds settlements in real generated terrain (one per biome situation), builds every track up to
 * its last level the way a player would unlock them, and measures the criteria of claude/kingdom-test-criteria.md
 * after every tier. Writes fieldtest/report.md and a before/after map per site into the server folder.
 */
public final class FieldTest {
	private FieldTest() {
	}

	record Scenario(String name, Set<String> biomes, String kind) {
	}

	static final List<Scenario> SCENARIOS = List.of(
			new Scenario("plains", Set.of("plains", "sunflower_plains"), "land"),
			new Scenario("forest", Set.of("forest", "birch_forest"), "land"),
			new Scenario("dark_forest", Set.of("dark_forest"), "land"),
			new Scenario("high_meadow", Set.of("cherry_grove", "meadow"), "land"),
			new Scenario("snowy_plains", Set.of("snowy_plains"), "land"),
			new Scenario("snowy_slopes", Set.of("snowy_slopes", "grove"), "land"),
			new Scenario("taiga", Set.of("taiga", "old_growth_spruce_taiga", "old_growth_pine_taiga"), "land"),
			new Scenario("windswept_hills", Set.of("windswept_hills", "windswept_gravelly_hills", "windswept_forest"), "land"),
			new Scenario("stony_peaks", Set.of("stony_peaks"), "land"),
			new Scenario("desert", Set.of("desert"), "land"),
			new Scenario("badlands", Set.of("badlands", "eroded_badlands", "wooded_badlands"), "land"),
			new Scenario("savanna_plateau", Set.of("savanna_plateau", "windswept_savanna"), "land"),
			new Scenario("jungle", Set.of("jungle", "bamboo_jungle", "sparse_jungle"), "land"),
			new Scenario("swamp", Set.of("swamp"), "land"),
			new Scenario("mangrove", Set.of("mangrove_swamp"), "land"),
			new Scenario("beach", Set.of("beach", "snowy_beach"), "shore"),
			new Scenario("river_bank", Set.of("river"), "river"),
			new Scenario("flower_forest", Set.of("flower_forest"), "land"),
			new Scenario("old_birch", Set.of("old_growth_birch_forest"), "land"),
			new Scenario("pale_garden", Set.of("pale_garden"), "land"),
			new Scenario("cherry_grove", Set.of("cherry_grove"), "land"),
			new Scenario("ice_spikes", Set.of("ice_spikes"), "land"),
			new Scenario("jagged_peaks", Set.of("jagged_peaks", "frozen_peaks"), "land"),
			new Scenario("stony_shore", Set.of("stony_shore"), "shore"),
			new Scenario("snowy_beach", Set.of("snowy_beach"), "shore"),
			new Scenario("eroded_badlands", Set.of("eroded_badlands", "wooded_badlands"), "land"),
			new Scenario("bamboo_jungle", Set.of("bamboo_jungle"), "land"),
			new Scenario("mushroom_fields", Set.of("mushroom_fields"), "land"),
			new Scenario("frozen_river", Set.of("frozen_river"), "river"),
			// terrain shapes (user, 2026-11-19): hills of different sizes, valleys, ridges, cliff edges, found from the
			// generator's height noise; the biomes are preferred (one per style), any land biome is taken otherwise
			new Scenario("knoll", Set.of("plains", "sunflower_plains", "forest", "flower_forest", "birch_forest", "meadow"), "shape"),
			new Scenario("hill", Set.of("taiga", "snowy_taiga", "snowy_plains", "grove", "old_growth_spruce_taiga", "old_growth_pine_taiga"), "shape"),
			new Scenario("big_hill", Set.of("windswept_hills", "windswept_forest", "windswept_gravelly_hills", "meadow", "cherry_grove"), "shape"),
			new Scenario("hillside_up", Set.of("jungle", "bamboo_jungle", "sparse_jungle"), "shape"),
			new Scenario("hillside_down", Set.of("forest", "birch_forest", "dark_forest", "plains", "old_growth_birch_forest"), "shape"),
			new Scenario("ridge", Set.of("snowy_slopes", "grove", "taiga", "snowy_taiga"), "shape"),
			new Scenario("valley", Set.of("forest", "dark_forest", "birch_forest", "taiga", "meadow", "plains"), "shape"),
			new Scenario("basin", Set.of(), "shape"),
			new Scenario("cliff_away", Set.of("badlands", "wooded_badlands", "eroded_badlands", "savanna_plateau", "savanna"), "shape"),
			new Scenario("cliff_toward", Set.of("windswept_hills", "stony_peaks", "meadow", "windswept_savanna"), "shape"),
			new Scenario("mesa", Set.of("wooded_badlands", "badlands", "savanna_plateau"), "shape"),
			new Scenario("rolling", Set.of("dark_forest", "old_growth_birch_forest", "forest", "savanna", "sparse_jungle"), "shape"),
			// the Fealty Ceremony at a real village's bell (see FealtyTest); CI runs it when named: [fieldtest fealty]
			new Scenario("fealty", Set.of(), "village"));

	private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000f1e1");
	/** Half size of the inspected square around the heart: the wall ring, the moat, the beach and a margin. */
	static final int R = 190;
	private static final Set<String> IMAGE_STAGES = Set.of("tent_1", "hamlet_1", "village_1", "castle_1", "final");

	private static final StringBuilder REPORT = new StringBuilder();
	/** Whether the untouched land let one walk from the heart out of the inspected area. */
	private static boolean OUT_BEFORE = true;
	/** Per door (lower half): the ground 1 and 2 blocks in front of it after each build, when it changed. */
	private static final Map<Long, List<String>> DOOR_GROUND = new HashMap<>();
	private static final List<String> SUMMARY = new ArrayList<>();
	/** Road top per column at the last check of the current site. */
	private static final Map<Long, Integer> ROAD_LAST = new HashMap<>();

	/** Runs the given scenarios (all when empty). Returns the number of sites with problems. */
	public static int run(MinecraftServer server, Set<String> only) {
		ServerLevel level = server.overworld();
		Path dir = Path.of("fieldtest");
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			KingdomQuest.LOG.error("Field test: no folder", e);
		}
		REPORT.setLength(0);
		SUMMARY.clear();
		REPORT.append("# KingdomQuest field test\n\nSeed ").append(level.getSeed()).append(". Positions in details are relative to the heart (x, y, z).\n\n");
		List<BlockPos> used = new ArrayList<>();
		int bad = 0;
		int i = 0;
		// a site may ask for a look of the founding menu: "knoll@hill" (hill castle), "knoll@town" (hill town)
		Map<String, Integer> looks = new HashMap<>();
		for (String o : only) {
			String[] p = o.split("@");
			looks.put(p[0], p.length < 2 ? Grade.FLAT : p[1].startsWith("town") ? Grade.HILL_TOWN : p[1].startsWith("hill") ? Grade.HILL_CASTLE : Grade.FLAT);
		}
		for (Scenario sc : SCENARIOS) {
			if (!only.isEmpty() && !looks.containsKey(sc.name())) {
				i++;
				continue;
			}
			long t0 = System.nanoTime();
			try {
				bad += ("village".equals(sc.kind()) ? FealtyTest.run(server, REPORT, SUMMARY, dir)
						: runSite(server, level, sc, i, used, dir, looks.getOrDefault(sc.name(), Grade.FLAT))) ? 0 : 1;
			} catch (Throwable e) {
				KingdomQuest.LOG.error("Field test {} crashed", sc.name(), e);
				REPORT.append("## ").append(sc.name()).append("\n\nCRASH: ").append(e).append(" at ").append(top(e)).append("\n\n");
				SUMMARY.add("| " + sc.name() + " | CRASH " + e.getClass().getSimpleName() + " |");
				bad++;
			}
			KingdomQuest.LOG.info("Field test {} done in {} s", sc.name(), (System.nanoTime() - t0) / 1_000_000_000L);
			write(dir);
			i++;
		}
		write(dir);
		return bad;
	}

	private static String top(Throwable e) {
		StackTraceElement[] st = e.getStackTrace();
		StringBuilder b = new StringBuilder();
		for (int k = 0; k < Math.min(6, st.length); k++) {
			b.append(st[k].getClassName().replaceAll(".*\\.", "")).append('.').append(st[k].getMethodName()).append(':').append(st[k].getLineNumber()).append(' ');
		}
		return b.toString().trim();
	}

	private static void write(Path dir) {
		StringBuilder all = new StringBuilder();
		all.append("## Summary\n\n| site | result |\n|---|---|\n");
		SUMMARY.forEach(l -> all.append(l).append('\n'));
		all.append('\n').append(REPORT);
		try {
			Files.writeString(dir.resolve("report.md"), all.toString());
		} catch (IOException e) {
			KingdomQuest.LOG.error("Field test: report not written", e);
		}
	}

	// ---- one site ----

	private static boolean runSite(MinecraftServer server, ServerLevel level, Scenario sc, int index, List<BlockPos> used, Path dir, int look) {
		BlockPos origin = new BlockPos((index % 6) * 2500 - 6000, 80, (index / 6) * 2500 - 2500);
		KingdomQuest.LOG.info("Field test {}: looking for a site", sc.name());
		SHAPE_TURN = null;
		SHAPE_NOTE = "";
		BlockPos heart = "shape".equals(sc.kind()) ? locateShape(level, sc, origin, used) : locate(level, sc, origin, used);
		KingdomQuest.LOG.info("Field test {}: site {}", sc.name(), heart);
		if (heart == null) {
			REPORT.append("## ").append(sc.name()).append("\n\nNo site found near ").append(origin.toShortString()).append(".\n\n");
			SUMMARY.add("| " + sc.name() + " | not found |");
			return true;
		}
		used.add(heart);
		// the inspected square, and the grading plan's reach with a chunk to spare (its sections are shaped when their
		// neighbouring chunks are loaded)
		int load = Math.max(R, Grade.PAD + Grade.MARGIN + Math.max(Math.max(-Layout.ring()[0], Layout.ring()[2]), Math.max(-Layout.ring()[1], Layout.ring()[3]))) + 32;
		int cx0 = (heart.getX() - load) >> 4, cx1 = (heart.getX() + load) >> 4;
		int cz0 = (heart.getZ() - load) >> 4, cz1 = (heart.getZ() + load) >> 4;
		for (int cx = cx0; cx <= cx1; cx++) {
			for (int cz = cz0; cz <= cz1; cz++) {
				level.setChunkForced(cx, cz, true);
				level.getChunk(cx, cz);
			}
		}
		KingdomQuest.LOG.info("Field test {}: chunks loaded", sc.name());
		// the gate: shape sites that test a side keep it (the lord can set any side in the menu), the rest use Auto
		Rotation rot = SHAPE_TURN != null ? SHAPE_TURN : GateSite.best(level, heart, Rotation.values()[index % 4]);
		StringBuilder costs = new StringBuilder();
		for (Rotation r : Rotation.values()) {
			costs.append(GateSite.side(r)).append(' ').append(Math.round(GateSite.cost(level, heart, r))).append(r == rot ? "*" : "").append(", ");
		}
		SHAPE_NOTE += " Gate approach cost per side (* = used): " + costs.substring(0, costs.length() - 2) + ".";
		String biome = level.getBiome(heart).getRegisteredName();
		Snapshot base = Snapshot.take(level, heart, Set.of());
		{
			// could one walk from the heart out of the area before anything was built? (a mesa top may have no way down)
			Set<Long> seen = new HashSet<>();
			ArrayDeque<long[]> q0 = new ArrayDeque<>();
			for (int dy = -2; dy <= 3; dy++) {
				if (!Double.isNaN(feet(level, heart.getX(), heart.getY() + dy, heart.getZ(), false)) && seen.add(heart.above(dy).asLong())) {
					q0.add(new long[] {heart.above(dy).asLong()});
				}
			}
			OUT_BEFORE = spread(level, heart, seen, q0, Set.of(), 1_600_000);
		}
		{
			// what already hangs in the untouched land (cave ceilings over water, tree branches) is not the mod's doing
			Settlement probe = new Settlement(UUID.randomUUID(), OWNER, "probe", 0, Identifier.withDefaultNamespace("stone"), "temperate", heart, 0,
					Map.of(), "", "", 0, Map.of(), Map.of());
			floating(level, probe, Map.of(), new HashMap<>(), new ArrayList<>(), base.floatingLand(), null);
		}
		int relief = Settlements.relief(level, heart);
		REPORT.append("## ").append(sc.name()).append("\n\n").append(biome).append(" at ").append(heart.toShortString())
				.append(", facing ").append(rot).append(", relief ").append(relief).append(", walkable out before founding: ").append(OUT_BEFORE).append(", style ")
				.append(Style.forBiome(biome)).append(", ground ").append(SiteWork.surface(level, heart).getBlock().getDescriptionId().replace("block.minecraft.", ""))
				.append(".").append(SHAPE_NOTE).append("\n\n| stage | ms | float built | float land | floating feet | buried | destroyed | doors unreached | walk out | breach | wet inside | wet moat | new cliffs | tall walls | pits | road gaps | road steps | wall gaps | cut trees | foreign ground | snow lost | road steep | stumps | road high | road bank | road moved |\n|")
				.append("---|".repeat(26)).append('\n');
		Settlement s = new Settlement(UUID.randomUUID(), OWNER, "FT " + sc.name(), 11, Identifier.withDefaultNamespace("stone_bricks"),
				Style.forBiome(biome), heart, rot.ordinal(), Map.of(Settlement.MAIN, 1), Style.forBiome(biome) + "/tent_1", Settlement.MAIN, 0, Map.of(),
				Map.of());
		long tf = System.nanoTime();
		s = Settlements.found(level, s, look, 0, true);
		heart = s.heart();
		REPORT.append("Founded as ").append(new String[] {"flat castle", "hill castle", "hill town"}[look]).append(": plateau at ")
				.append(Grade.of(server, s).plateau - heart.getY() + 1).append(" (relative to the heart), site shaped in ")
				.append((System.nanoTime() - tf) / 1_000_000).append(" ms.\n\n");
		DOOR_GROUND.clear();
		ROAD_LAST.clear();
		List<String> problems = new ArrayList<>();
		Map<String, Integer> destroyedBefore = new HashMap<>();
		long t = System.nanoTime();
		s = finish(server, s);
		check(level, s, base, "tent_1", (System.nanoTime() - t) / 1_000_000, problems, dir, sc.name(), destroyedBefore);
		s = countryRoad(server, level, s);
		boolean lordChanged = false;
		Set<String> skipped = new HashSet<>();
		int guard = 0;
		while (guard++ < 400) {
			boolean progressed = false;
			for (String track : Progression.ORDER) {
				if (Settlement.MAIN.equals(track) || skipped.contains(track)) {
					continue;
				}
				Progression.Step step = Progression.next(s, track);
				if (step == null || Template.get(s.style() + "/" + step.building()) == null) {
					continue;
				}
				int before = s.level(track);
				long t1 = System.nanoTime();
				try {
					Settlements.start(server, s, track, step);
					s = finish(server, Settlements.byHeart(server, heart));
				} catch (Throwable e) {
					problems.add(track + " " + step.level() + " crashed: " + e + " at " + top(e));
					s = Settlements.byHeart(server, heart);
					skipped.add(track);
					continue;
				}
				long ms = (System.nanoTime() - t1) / 1_000_000;
				if (ms > 4000) {
					problems.add(track + " " + step.level() + " took " + ms + " ms");
				}
				if (s.level(track) <= before) {
					skipped.add(track);
					REPORT.append("| ").append(track).append(" ").append(step.level()).append(" not built |\n");
					continue;
				}
				progressed = true;
				blame(level, s, track + "_" + step.level(), destroyedBefore, problems);
				watchDoors(level, s, track + "_" + step.level());
			}
			if (progressed) {
				continue;
			}
			Progression.Step main = Progression.next(s, Settlement.MAIN);
			if (main == null) {
				break;
			}
			long t2 = System.nanoTime();
			long[] lordTest = lordChanged ? null : lordChange(level, s, main);
			lordChanged = true;
			try {
				Settlements.start(server, s, Settlement.MAIN, main);
				s = finish(server, Settlements.byHeart(server, heart));
				if (lordTest != null) {
					lordCheck(level, s, main, lordTest, problems);
				}
			} catch (Throwable e) {
				problems.add("main " + main.building() + " crashed: " + e + " at " + top(e));
				break;
			}
			blame(level, s, main.building(), destroyedBefore, problems);
			watchDoors(level, s, main.building());
			check(level, s, base, main.building(), (System.nanoTime() - t2) / 1_000_000, problems, dir, sc.name(), destroyedBefore);
			KingdomQuest.LOG.info("Field test {}: {} built and checked", sc.name(), main.building());
			write(dir);
		}
		check(level, s, base, "final", 0, problems, dir, sc.name(), destroyedBefore);
		roadIntact(level, s, problems);
		gateReport(level, s, base);
		problems.addAll(Storehouse.fieldTest(level, Settlements.byHeart(server, heart), REPORT));
		signalReport(level, s, problems);
		StringBuilder levels = new StringBuilder();
		for (String track : Progression.ORDER) {
			List<Progression.Step> steps = Progression.steps(track);
			if (steps != null && s.level(track) < steps.size()) {
				levels.append(track).append(' ').append(s.level(track)).append('/').append(steps.size()).append(", ");
			}
		}
		if (!levels.isEmpty()) {
			problems.add("not at the last level: " + levels);
		}
		REPORT.append('\n');
		for (String p : problems) {
			REPORT.append("- ").append(p).append('\n');
		}
		REPORT.append("\n![").append(sc.name()).append("](").append(sc.name()).append("_final.png)\n\n");
		for (int cx = cx0; cx <= cx1; cx++) {
			for (int cz = cz0; cz <= cz1; cz++) {
				level.setChunkForced(cx, cz, false);
			}
		}
		SUMMARY.add("| " + sc.name() + " | " + (problems.isEmpty() ? "ok" : problems.size() + " findings") + " |");
		return problems.isEmpty();
	}

	/** Runs the settlement's current job (and any job it starts) to the end at once. */
	private static Settlement finish(MinecraftServer server, Settlement s) {
		for (int n = 0; n < 20 && s != null && !s.job().isEmpty(); n++) {
			Settlements.step(server, s, true);
			s = Settlements.byHeart(server, s.heart());
		}
		return s;
	}

	/** Blocks of earlier builds that are gone after a build: which build destroyed them. */
	private static void blame(ServerLevel level, Settlement s, String by, Map<String, Integer> before, List<String> problems) {
		Map<String, Integer> now = destroyed(level, s, null);
		for (Map.Entry<String, Integer> e : now.entrySet()) {
			int was = before.getOrDefault(e.getKey(), 0);
			if (e.getValue() > was && !e.getKey().equals(Settlements.builtTrack(by.replaceAll("_\\d+$", "")))) {
				List<String> where = new ArrayList<>();
				destroyed(level, s, where);
				problems.add(by + " destroyed " + (e.getValue() - was) + " blocks of " + e.getKey() + " " + where.stream().filter(w -> w.startsWith(e.getKey()))
						.limit(4).toList());
			}
		}
		before.clear();
		before.putAll(now);
	}

	/** Notes the ground in front of every door (feet height 1 and 2 blocks out, relative to the door) when it changes. */
	private static void watchDoors(ServerLevel level, Settlement s, String by) {
		for (Map.Entry<String, List<Long>> e : s.built().entrySet()) {
			for (long p : e.getValue()) {
				BlockPos d = BlockPos.of(p);
				BlockState st = level.getBlockState(d);
				if (!(st.getBlock() instanceof DoorBlock) || st.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
					continue;
				}
				Direction f = st.getValue(DoorBlock.FACING);
				StringBuilder now = new StringBuilder();
				for (Direction g : new Direction[] {f, f.getOpposite()}) {
					BlockPos q = d.relative(g, 2);
					BlockPos t = new BlockPos(q.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, q.getX(), q.getZ()) - 1, q.getZ());
					String who = "";
					for (Map.Entry<String, List<Long>> o : s.built().entrySet()) {
						if (o.getValue().contains(t.asLong())) {
							who = "@" + o.getKey();
						}
					}
					now.append(name(level.getBlockState(t))).append(who).append(' ');
				}
				for (Direction g : new Direction[] {f, f.getOpposite()}) { // doors face in or out: both sides
					for (int k = 1; k <= 3; k++) {
						BlockPos q = d.relative(g, k);
						int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, q.getX(), q.getZ());
						now.append(top - d.getY()).append(k < 3 ? "/" : g == f ? " | " : "");
					}
				}
				List<String> hist = DOOR_GROUND.computeIfAbsent(p, k -> new ArrayList<>());
				String last = hist.isEmpty() ? "" : hist.get(hist.size() - 1);
				if (!last.endsWith(" " + now)) {
					hist.add(by + " " + now);
				}
			}
		}
	}

	/** Per track: blocks its builders placed that are now different in the world. */
	private static Map<String, Integer> destroyed(ServerLevel level, Settlement s, List<String> where) {
		Map<String, Integer> out = new TreeMap<>();
		for (String track : Progression.ORDER) {
			Progression.Step step = Progression.current(s, track);
			if (step == null) {
				continue;
			}
			Set<Long> mine = new HashSet<>(s.built().getOrDefault(track, List.of()));
			for (Template.Placement p : Settlements.placements(s, s.style() + "/" + step.building())) {
				if (p.state().isAir() || p.state().getBlock() instanceof CropBlock || !p.state().getFluidState().isEmpty()) {
					continue;
				}
				BlockPos pos = s.heart().offset(p.offset());
				if (!mine.contains(pos.asLong()) || Drawbridge.moving(s, pos)) {
					continue;
				}
				BlockState w = level.getBlockState(pos);
				if (w.getBlock() != p.state().getBlock()) {
					out.merge(track, 1, Integer::sum);
					if (where != null && where.size() < 200) {
						where.add(track + " " + rel(s, pos) + " " + name(p.state()) + "->" + name(w));
					}
				}
			}
		}
		return out;
	}

	// ---- finding a site ----

	private static String path(Holder<Biome> h) {
		String n = h.getRegisteredName();
		return n.startsWith("minecraft:") ? n.substring(10) : n;
	}

	/**
	 * A road to a place 130 blocks away, built right after the tent, before the castle grows around it (user,
	 * 2026-11-18: later land work cut such a road and a bridge crossed a river at a slant). The place lies across
	 * water when there is water around: the direction whose line crosses the most water and ends on land.
	 */
	private static Settlement countryRoad(MinecraftServer server, ServerLevel level, Settlement s) {
		BlockPos start = RoadLink.gateExit(s);
		BlockPos target = null;
		int bestWet = -1;
		for (int a = 0; a < 16; a++) {
			double ang = a * Math.PI / 8;
			int wet = 0;
			BlockPos end = null;
			for (int r = 8; r <= 130; r += 2) {
				int x = start.getX() + (int) Math.round(Math.cos(ang) * r), z = start.getZ() + (int) Math.round(Math.sin(ang) * r);
				int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
				boolean water = !level.getBlockState(new BlockPos(x, y, z)).getFluidState().isEmpty();
				wet += water ? 1 : 0;
				end = water ? null : new BlockPos(x, y + 1, z);
			}
			if (end != null && wet > bestWet) {
				bestWet = wet;
				target = end;
			}
		}
		if (target == null) {
			return s;
		}
		RoadLink.link(server, null, s, "ft", target, "the test place");
		RoadLink.runAll(server);
		REPORT.append("Country road to ").append(target.toShortString()).append(" (").append(bestWet * 2).append(" blocks of water on the way).\n\n");
		return Settlements.byHeart(server, s.heart());
	}

	/**
	 * The lord's own changes (user, 2026-11-19): before the first main upgrade, the lord breaks two blocks of the
	 * current level, one where the next level's plan has the same block (it must stay broken: repair and upgrade leave
	 * it) and one where the plan changes (the upgrade must win). Returns {kept, replaced} positions, -1 if none found.
	 */
	private static long[] lordChange(ServerLevel level, Settlement s, Progression.Step next) {
		Map<Long, BlockState> nextPlan = new HashMap<>();
		for (Template.Placement p : Settlements.placements(s, s.style() + "/" + next.building())) {
			nextPlan.put(s.heart().offset(p.offset()).asLong(), p.state());
		}
		long kept = -1, replaced = -1;
		for (long pos : s.built().getOrDefault(Settlement.MAIN, List.of())) {
			BlockState here = level.getBlockState(BlockPos.of(pos));
			if (here.isAir() || !here.isCollisionShapeFullBlock(level, BlockPos.of(pos)) || BlockPos.of(pos).getY() < s.heart().getY()) {
				continue;
			}
			BlockState planned = nextPlan.get(pos);
			if (kept < 0 && planned != null && planned.getBlock() == here.getBlock()) {
				kept = pos;
			} else if (replaced < 0 && planned != null && !planned.isAir() && planned.getBlock() != here.getBlock()) {
				replaced = pos;
			}
		}
		for (long pos : new long[] {kept, replaced}) {
			if (pos != -1) {
				level.setBlock(BlockPos.of(pos), Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
				LordChanges.record(level.getServer(), s, pos, Blocks.AIR.defaultBlockState());
			}
		}
		boolean repairs = false;
		for (Template.Placement p : Settlements.damaged(level, s)) {
			long pos = s.heart().offset(p.offset()).asLong();
			repairs |= pos == kept || pos == replaced;
		}
		REPORT.append("Lord's changes: broke 2 blocks before ").append(next.building()).append("; repair ")
				.append(repairs ? "WOULD PUT THEM BACK" : "leaves them").append(".\n\n");
		return new long[] {kept, replaced, repairs ? 1 : 0};
	}

	private static void lordCheck(ServerLevel level, Settlement s, Progression.Step next, long[] t, List<String> problems) {
		boolean keptOk = t[0] == -1 || level.getBlockState(BlockPos.of(t[0])).isAir();
		boolean replacedOk = t[1] == -1 || !level.getBlockState(BlockPos.of(t[1])).isAir();
		Map<Long, BlockState> left = LordChanges.of(level.getServer(), s);
		REPORT.append("Lord's changes after ").append(next.building()).append(": unchanged spot ")
				.append(t[0] == -1 ? "none found" : keptOk ? "kept broken" : "REBUILT").append(", changed spot ")
				.append(t[1] == -1 ? "none found" : replacedOk ? "rebuilt by the new plan" : "STILL BROKEN").append(", ")
				.append(left.size()).append(" change(s) remembered.\n\n");
		if (!keptOk || !replacedOk || t[2] == 1) {
			problems.add("lord's changes: kept " + keptOk + ", replaced " + replacedOk + ", repair skips " + (t[2] == 0));
		}
	}

	/** Whether the country road built after the tent is still there at the end: cells near the castle without road. */
	private static void roadIntact(ServerLevel level, Settlement s, List<String> problems) {
		int cells = 0, gone = 0;
		List<String> where = new ArrayList<>();
		for (List<BlockPos> line : RoadLink.storedLinks(s)) {
			for (BlockPos p : line) {
				cells++;
				boolean road = false;
				for (int dy = -1; dy <= 2 && !road; dy++) {
					BlockState st = level.getBlockState(p.above(dy));
					road = st.is(Blocks.DIRT_PATH) || st.is(Blocks.GRAVEL) || st.is(Blocks.COBBLESTONE) || st.is(Blocks.MOSSY_COBBLESTONE)
							|| st.is(Blocks.STONE_BRICKS) || st.is(Blocks.CRACKED_STONE_BRICKS) || st.is(Blocks.STONE_BRICK_SLAB) || st.is(Blocks.COARSE_DIRT);
				}
				if (!road) {
					gone++;
					if (where.size() < 5) {
						where.add(p.subtract(s.heart()).toShortString());
					}
				}
			}
		}
		REPORT.append("Country road at the end: ").append(cells - gone).append(" of ").append(cells).append(" cells near the castle intact")
				.append(gone > 0 ? ", cut at " + where : "").append(".\n\n");
		if (gone > 0) {
			problems.add("country road cut in " + gone + " cells, e.g. " + where);
		}
	}

	private static BlockPos locate(ServerLevel level, Scenario sc, BlockPos origin, List<BlockPos> used) {
		for (BlockPos from : List.of(origin, origin.offset(1200, 0, 1200), origin.offset(-1500, 0, 900))) {
			Pair<BlockPos, Holder<Biome>> found = level.findClosestBiome3d(h -> sc.biomes().contains(path(h)), from, 4000, 32, 64);
			if (found == null) {
				continue;
			}
			BlockPos best = null;
			int bestScore = Integer.MIN_VALUE;
			BlockPos c = found.getFirst();
			for (int r = 0; r <= 96; r += 8) {
				for (int dx = -r; dx <= r; dx += 8) {
					for (int dz = -r; dz <= r; dz += 8) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
							continue;
						}
						BlockPos h = surface(level, c.getX() + dx, c.getZ() + dz);
						if (h == null || !sc.biomes().contains(path(level.getBiome(h)))) {
							continue;
						}
						boolean near = false;
						for (BlockPos u : used) {
							near |= u.distSqr(h) < 400 * 400;
						}
						if (near) {
							continue;
						}
						int score = score(level, sc, h);
						if (score > bestScore) {
							bestScore = score;
							best = h;
						}
					}
				}
				if (best != null && bestScore >= 3) {
					return best;
				}
			}
			if (best != null) {
				return best;
			}
		}
		return null;
	}

	/** A heart position on the ground at x/z (air above natural ground, no tree, no water), or null. */
	private static BlockPos surface(ServerLevel level, int x, int z) {
		level.getChunk(x >> 4, z >> 4);
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		BlockPos heart = new BlockPos(x, y, z);
		if (!level.getFluidState(heart).isEmpty() || !SiteWork.isGround(level.getBlockState(heart.below()))) {
			return null;
		}
		return heart;
	}

	/** How well a heart fits its scenario: the biome all around it; for shores water close by. */
	private static int score(ServerLevel level, Scenario sc, BlockPos h) {
		int score = 0;
		for (int[] d : new int[][] {{40, 0}, {-40, 0}, {0, 40}, {0, -40}}) {
			BlockPos p = h.offset(d[0], 0, d[1]);
			level.getChunk(p.getX() >> 4, p.getZ() >> 4);
			if (sc.biomes().contains(path(level.getBiome(p))) || !"land".equals(sc.kind())) {
				score++;
			}
		}
		if (!"land".equals(sc.kind())) {
			int water = 0;
			for (int dx = -32; dx <= 32; dx += 4) {
				for (int dz = -32; dz <= 32; dz += 4) {
					level.getChunk((h.getX() + dx) >> 4, (h.getZ() + dz) >> 4);
					int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, h.getX() + dx, h.getZ() + dz) - 1;
					if (level.getFluidState(new BlockPos(h.getX() + dx, y, h.getZ() + dz)).is(Fluids.WATER)) {
						water++;
					}
				}
			}
			score = water >= 20 ? score : score - 4; // a real shore: at least a fifth of the area is water
		}
		return score;
	}

	// ---- terrain shapes ----

	/** Rotation a shape site asks for (the gate up or down a slope, along a valley), or null for the usual cycle. */
	private static Rotation SHAPE_TURN;
	/** The shape's numbers for the report. */
	private static String SHAPE_NOTE = "";
	private static final int[] RINGS = {24, 48, 72, 96};
	private static final double[][] DIRS = new double[8][];

	static {
		for (int k = 0; k < 8; k++) {
			DIRS[k] = new double[] {Math.sin(k * Math.PI / 4), Math.cos(k * Math.PI / 4)}; // k 0 = +z (south), 2 = +x (east)
		}
	}

	/** Land heights from the generator's noise on an 8-block grid, without generating chunks. */
	private static final class Heights {
		final ServerLevel level;
		final net.minecraft.world.level.chunk.ChunkGenerator gen;
		final net.minecraft.world.level.levelgen.RandomState random;
		final Map<Long, int[]> cache = new HashMap<>();

		Heights(ServerLevel level) {
			this.level = level;
			this.gen = level.getChunkSource().getGenerator();
			this.random = level.getChunkSource().randomState();
		}

		/** {land height, water surface height} at the grid point nearest x/z. */
		int[] at(int x, int z) {
			int gx = Math.round(x / 8f) * 8, gz = Math.round(z / 8f) * 8;
			return cache.computeIfAbsent(BlockPos.asLong(gx, 0, gz), key -> new int[] {
					gen.getBaseHeight(gx, gz, Heightmap.Types.OCEAN_FLOOR_WG, level, random),
					gen.getBaseHeight(gx, gz, Heightmap.Types.WORLD_SURFACE_WG, level, random)});
		}

		int land(int x, int z) {
			return at(x, z)[0];
		}

		boolean wet(int x, int z) {
			int[] h = at(x, z);
			return h[1] > h[0];
		}
	}

	/**
	 * Finds a heart whose land around it has the scenario's shape. The land is read at rings 24/48/72/96 blocks out in
	 * eight directions (heights relative to the heart's own); each shape scores how well the rings fit it. The castle's
	 * ring reaches about 60 blocks out, so a "knoll" is smaller than the castle, a "hill" about its size and a "big hill"
	 * larger.
	 */
	private static BlockPos locateShape(ServerLevel level, Scenario sc, BlockPos origin, List<BlockPos> used) {
		Heights hs = new Heights(level);
		double bestScore = -1;
		int bx = 0, bz = 0, bDir = -1;
		int[][] bRing = null;
		for (int cx = origin.getX() - 1600; cx <= origin.getX() + 1600; cx += 32) {
			for (int cz = origin.getZ() - 1600; cz <= origin.getZ() + 1600; cz += 32) {
				if (hs.wet(cx, cz) || hs.wet(cx + 8, cz) || hs.wet(cx, cz + 8)) {
					continue;
				}
				boolean near = false;
				for (BlockPos u : used) {
					near |= u.distSqr(new BlockPos(cx, u.getY(), cz)) < 400 * 400;
				}
				if (near) {
					continue;
				}
				int h0 = (hs.land(cx, cz) * 2 + hs.land(cx + 8, cz) + hs.land(cx - 8, cz) + hs.land(cx, cz + 8) + hs.land(cx, cz - 8)) / 6;
				int[][] ring = new int[RINGS.length][8];
				for (int r = 0; r < RINGS.length; r++) {
					for (int k = 0; k < 8; k++) {
						ring[r][k] = hs.land(cx + (int) Math.round(DIRS[k][0] * RINGS[r]), cz + (int) Math.round(DIRS[k][1] * RINGS[r])) - h0;
					}
				}
				int[] dir = {-1};
				double score = sc.name().equals("rolling") ? rolling(hs, cx, cz, h0) : shapeScore(sc.name(), ring, dir);
				if (score < 0) {
					continue;
				}
				String biome = path(level.getBiome(new BlockPos(cx, h0, cz)));
				if (biome.contains("ocean") || biome.contains("river") || biome.contains("beach") || biome.contains("shore")) {
					continue;
				}
				if (!sc.biomes().isEmpty() && !sc.biomes().contains(biome)) {
					score -= 4; // any biome will do, the preferred ones first
				}
				if (score > bestScore) {
					bestScore = score;
					bx = cx;
					bz = cz;
					bDir = dir[0];
					bRing = ring;
				}
			}
		}
		if (bestScore < 0) {
			return null;
		}
		BlockPos heart = null;
		for (int r = 0; r <= 16 && heart == null; r += 4) {
			for (int dx = -r; dx <= r && heart == null; dx += 4) {
				for (int dz = -r; dz <= r && heart == null; dz += 4) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) == r) {
						heart = surface(level, bx + dx, bz + dz);
					}
				}
			}
		}
		if (heart == null) {
			return null;
		}
		if (bDir >= 0) {
			// the gate (template z +40) is turned to face the asked direction
			double best = -2;
			for (Rotation r : Rotation.values()) {
				BlockPos g = Template.rotate(new BlockPos(0, 0, 40), r);
				double dot = (g.getX() * DIRS[bDir][0] + g.getZ() * DIRS[bDir][1]) / 40.0; // only the direction matters
				if (dot > best) {
					best = dot;
					SHAPE_TURN = r;
				}
			}
		}
		StringBuilder note = new StringBuilder(" Shape ").append(sc.name()).append(" (score ").append(Math.round(bestScore * 10) / 10.0).append(")");
		if (bRing != null) {
			note.append(", land relative to the heart, directions S SE E NE N NW W SW");
			String[] names = {"S", "SE", "E", "NE", "N", "NW", "W", "SW"};
			if (bDir >= 0) {
				note.append(", gate toward ").append(names[bDir]);
			}
			for (int r = 0; r < RINGS.length; r++) {
				note.append(r == 0 ? ": " : "; ").append(RINGS[r]).append(" out ").append(java.util.Arrays.toString(bRing[r]));
			}
		}
		SHAPE_NOTE = note.append('.').toString();
		return heart;
	}

	private static double mean(int[] v) {
		double t = 0;
		for (int x : v) {
			t += x;
		}
		return t / v.length;
	}

	private static int min(int[] v) {
		return java.util.Arrays.stream(v).min().orElse(0);
	}

	private static int max(int[] v) {
		return java.util.Arrays.stream(v).max().orElse(0);
	}

	/** How well the rings (relative heights at 24/48/72/96 in 8 directions) fit a shape; negative = not this shape. */
	static double shapeScore(String shape, int[][] d, int[] dir) {
		int[] r24 = d[0], r48 = d[1], r72 = d[2], r96 = d[3];
		double m24 = mean(r24), m48 = mean(r48), m72 = mean(r72), m96 = mean(r96);
		switch (shape) {
			case "knoll": // a hill smaller than the castle: the land drops within 24, then stays
				if (max(r24) > -3 || m24 < -14 || m24 > -4 || Math.abs(m48 - m24) > 5 || Math.abs(m96 - m24) > 8) {
					return -1;
				}
				return 10 - Math.abs(m24 + 8) - Math.abs(m48 - m24);
			case "hill": // about the castle's size: down 10-24 by 48, flat beyond
				if (m24 > -2 || m24 < -12 || max(r48) > -5 || m48 > -9 || m48 < -26 || m96 < m48 - 8) {
					return -1;
				}
				return 10 - Math.abs(m48 + 15) / 2 - Math.abs(m96 - m48) / 2;
			case "big_hill": // the top of a hill much larger than the castle: gentle near, far down at 96
				if (m24 < -6 || m24 > 1 || min(r24) < -10 || max(r24) > 4 || m48 > -3 || m48 < -16 || max(r96) > -10 || m96 > -22) {
					return -1;
				}
				return Math.min(10, -m96 / 4) - Math.abs(m48 + 8) / 3;
			case "basin": // a bowl: higher land all round
				if (min(r24) < 0 || min(r48) < 6 || m48 < 12) {
					return -1;
				}
				return Math.min(10, m48 / 3) + min(r48) / 4.0;
			case "mesa": // a flat top with steep sides all round
				if (max(r24) > 4 || min(r24) < -4 || max(r72) > -8 || m72 > -14) {
					return -1;
				}
				return Math.min(10, -m72 / 4);
			default:
				break;
		}
		double best = -1;
		for (int k = 0; k < 8; k++) {
			int o = (k + 4) % 8, p1 = (k + 2) % 8, p2 = (k + 6) % 8;
			double score = -1;
			int toward = k;
			switch (shape) {
				case "hillside_up", "hillside_down" -> { // k uphill, o downhill, gentle across
					if (r48[k] >= 12 && r48[o] <= -12 && r24[k] >= 4 && r24[o] <= -4 && Math.abs(r48[p1]) <= 8 && Math.abs(r48[p2]) <= 8) {
						score = Math.min(r48[k], -r48[o]) / 4.0 - (Math.abs(r48[p1]) + Math.abs(r48[p2])) / 4.0;
						toward = shape.equals("hillside_up") ? k : o;
					}
				}
				case "ridge" -> { // down on both sides k and o, level along the ridge (the gate runs along it)
					if (k < 4 && r48[k] <= -10 && r48[o] <= -10 && r48[p1] >= -5 && r48[p1] <= 8 && r48[p2] >= -5 && r48[p2] <= 8) {
						score = Math.min(-r48[k], -r48[o]) / 4.0 - (Math.abs(r48[p1]) + Math.abs(r48[p2])) / 4.0;
						toward = p1;
					}
				}
				case "valley" -> { // up on both sides k and o, level along the valley (the gate runs along it)
					if (k < 4 && r48[k] >= 12 && r48[o] >= 12 && r24[k] >= 3 && r24[o] >= 3 && Math.abs(r48[p1]) <= 6 && Math.abs(r48[p2]) <= 6) {
						score = Math.min(r48[k], r48[o]) / 4.0 - (Math.abs(r48[p1]) + Math.abs(r48[p2])) / 4.0;
						toward = p1;
					}
				}
				case "cliff_away", "cliff_toward" -> { // flat near the heart, a drop of 18+ beyond the wall line on side k
					if (max(r24) <= 3 && min(r24) >= -3 && r72[k] <= -18 && r48[k] <= -6 && r72[o] >= -6) {
						score = Math.min(10, -r72[k] / 3.0) - Math.abs(r72[o]) / 4.0;
						toward = shape.equals("cliff_toward") ? k : o;
					}
				}
				default -> {
					return -1;
				}
			}
			if (score > best) {
				best = score;
				dir[0] = toward;
			}
		}
		return best;
	}

	/** Rolling land: bumps and dips all over the castle's area, but no overall slope. */
	private static double rolling(Heights hs, int cx, int cz, int h0) {
		List<Integer> v = new ArrayList<>();
		double east = 0, west = 0, south = 0, north = 0;
		for (int dx = -48; dx <= 48; dx += 16) {
			for (int dz = -48; dz <= 48; dz += 16) {
				int y = hs.land(cx + dx, cz + dz) - h0;
				if (hs.wet(cx + dx, cz + dz)) {
					return -1;
				}
				v.add(y);
				east += dx > 0 ? y : 0;
				west += dx < 0 ? y : 0;
				south += dz > 0 ? y : 0;
				north += dz < 0 ? y : 0;
			}
		}
		double m = v.stream().mapToInt(Integer::intValue).average().orElse(0);
		double sd = Math.sqrt(v.stream().mapToDouble(y -> (y - m) * (y - m)).average().orElse(0));
		int range = v.stream().mapToInt(Integer::intValue).max().orElse(0) - v.stream().mapToInt(Integer::intValue).min().orElse(0);
		if (sd < 4.5 || range > 24 || Math.abs(east - west) / 21 > 5 || Math.abs(south - north) / 21 > 5) {
			return -1;
		}
		return sd;
	}

	/** The gate's surroundings at the end: land in front of it before and after, the moat per side, the road out. */
	private static void gateReport(ServerLevel level, Settlement s, Snapshot base) {
		Rotation r = Rotation.values()[s.rotation()];
		BlockPos h = s.heart();
		Set<Long> built = new HashSet<>();
		s.built().values().forEach(built::addAll);
		int n = 2 * R + 1;
		StringBuilder b = new StringBuilder("Gate (land relative to the heart, before -> after) at 0/5/10/15/20/30/40 blocks out of the gate:");
		for (int out : new int[] {0, 5, 10, 15, 20, 30, 40}) {
			BlockPos p = h.offset(Template.rotate(new BlockPos(0, 0, 40 + out), r));
			int i = p.getX() - base.x0(), k = p.getZ() - base.z0();
			String was = i >= 0 && k >= 0 && i < n && k < n ? String.valueOf(base.ground()[i * n + k] - h.getY()) : "?";
			b.append(' ').append(was).append("->").append(SiteWork.groundDeep(level, p.getX(), p.getZ(), h.getY(), built) - h.getY());
		}
		Map<String, Integer> sides = new java.util.TreeMap<>();
		Rotation back = switch (r) {
			case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
			case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
			default -> r;
		};
		for (long col : Settlements.moatColumns(s)) {
			BlockPos t = Template.rotate(new BlockPos(BlockPos.getX(col) - h.getX(), 0, BlockPos.getZ(col) - h.getZ()), back);
			String side = t.getZ() > 40 ? "gate" : t.getZ() < -56 ? "back" : t.getX() < -63 ? "left" : t.getX() > 69 ? "right" : "corner";
			sides.merge(side, 1, Integer::sum);
		}
		b.append(". Moat columns per side ").append(sides.isEmpty() ? "none" : sides.toString());
		String way = s.names().getOrDefault("wayout", "");
		b.append(". Way-out road ").append(way.isEmpty() ? "none" : way.split(";").length + " blocks").append(".\n\n");
		REPORT.append(b);
	}

	/** The signal fires: one on every tower top, they light and go out, and the tower guards still find their platforms. */
	private static void signalReport(ServerLevel level, Settlement s, List<String> problems) {
		int walls = s.level(Progression.WALLS);
		int towers = walls >= 4 ? 13 : walls == 2 ? 6 : 0; // tower_spots in tools/buildings.py
		List<BlockPos> fires = SignalFires.fires(s).stream().filter(p -> level.getBlockState(p).is(Blocks.CAMPFIRE)).toList();
		int lit = 0;
		int out = 0;
		for (BlockPos p : fires) {
			lit += SignalFires.set(level, p, true) && level.getBlockState(p).getValue(net.minecraft.world.level.block.CampfireBlock.LIT) ? 1 : 0;
		}
		for (BlockPos p : fires) {
			out += SignalFires.set(level, p, false) ? 1 : 0;
		}
		long posts = Defense.posts(level, s).stream().filter(Defense.Post::tower).count();
		REPORT.append("Signal fires: ").append(fires.size()).append(" on ").append(towers).append(" towers, lit ").append(lit).append(", put out ")
				.append(out).append("; tower guard posts ").append(posts).append(".\n\n");
		if (fires.size() < towers || lit < fires.size() || out < fires.size()) {
			problems.add("signal fires: " + fires.size() + " of " + towers + " towers, " + lit + " lit, " + out + " put out");
		}
	}

	// ---- measuring ----

	/** The land around a heart before anything was built: ground height and top block per column, leaves about to decay, map colours. */
	record Snapshot(BlockPos heart, int x0, int z0, int[] ground, String[] top, int decaying, int[] colours, Set<Long> floatingLand, BitSet snow) {
		static Snapshot take(ServerLevel level, BlockPos heart, Set<Long> built) {
			int n = 2 * R + 1;
			int[] ground = new int[n * n];
			String[] top = new String[n * n];
			int[] colours = new int[n * n];
			BitSet snow = new BitSet(n * n);
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			for (int i = 0; i < n; i++) {
				for (int k = 0; k < n; k++) {
					int x = heart.getX() - R + i;
					int z = heart.getZ() - R + k;
					int g = SiteWork.groundDeep(level, x, z, heart.getY(), built, 90, 140); // deep: the whole site, mountains and valleys
					ground[i * n + k] = g;
					top[i * n + k] = name(level.getBlockState(pos.set(x, g, z)));
					BlockState over = level.getBlockState(pos.set(x, g + 1, z));
					if (over.is(Blocks.SNOW) || level.getBlockState(pos.set(x, g, z)).is(Blocks.SNOW_BLOCK)) {
						snow.set(i * n + k);
					}
					int ty = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
					BlockState st = level.getBlockState(pos.set(x, ty, z));
					colours[i * n + k] = (st.getMapColor(level, pos).col & 0xFFFFFF) | (ty & 0xFF) << 24;
				}
			}
			return new Snapshot(heart, heart.getX() - R, heart.getZ() - R, ground, top, FieldTest.decaying(level, heart), colours, new HashSet<>(), snow);
		}
	}

	private static int decaying(ServerLevel level, BlockPos heart) {
		int count = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = heart.getX() - R; x <= heart.getX() + R; x++) {
			for (int z = heart.getZ() - R; z <= heart.getZ() + R; z++) {
				int ty = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
				for (int y = ty; y >= ty - 30; y--) {
					BlockState st = level.getBlockState(pos.set(x, y, z));
					if (st.getBlock() instanceof LeavesBlock && st.hasProperty(LeavesBlock.DISTANCE) && st.getValue(LeavesBlock.DISTANCE) >= 7
							&& !st.getValue(LeavesBlock.PERSISTENT)) {
						count++;
					}
				}
			}
		}
		return count;
	}

	static String name(BlockState st) {
		return st.getBlock().getDescriptionId().replace("block.minecraft.", "");
	}

	static String rel(Settlement s, BlockPos p) {
		return "(" + (p.getX() - s.heart().getX()) + "," + (p.getY() - s.heart().getY()) + "," + (p.getZ() - s.heart().getZ()) + ")";
	}

	/** Marks for the map: x, z, colour. */
	private record Mark(int x, int z, int rgb) {
	}

	private static void check(ServerLevel level, Settlement s, Snapshot base, String stage, long ms, List<String> problems, Path dir, String site,
			Map<String, Integer> destroyedBefore) {
		BlockPos h = s.heart();
		Map<Long, String> owner = new HashMap<>();
		for (Map.Entry<String, List<Long>> e : s.built().entrySet()) {
			for (long p : e.getValue()) {
				owner.putIfAbsent(p, e.getKey());
			}
		}
		Set<Long> builtCols = new HashSet<>();
		for (long p : owner.keySet()) {
			BlockPos b = BlockPos.of(p);
			builtCols.add(BlockPos.asLong(b.getX(), 0, b.getZ()));
		}
		Set<Long> moat = Settlements.moatColumns(s);
		List<Mark> marks = new ArrayList<>();
		Map<String, List<String>> samples = new LinkedHashMap<>();
		Map<String, Integer> c = new LinkedHashMap<>();

		// 1 floating: solid blocks not connected to the ground; buildings whose ground course has nothing under it
		int[] fl = floating(level, s, owner, samples, marks, null, base.floatingLand());
		c.put("float built", fl[0]);
		c.put("float land", fl[1]);
		int feet = 0;
		Map<Long, BlockPos> lowest = new HashMap<>();
		for (Map.Entry<Long, String> e : owner.entrySet()) {
			String tr = e.getValue();
			if (!Settlement.MAIN.equals(tr) && !Settlements.LIFTED.contains(tr)) {
				continue;
			}
			BlockPos b = BlockPos.of(e.getKey());
			lowest.merge(BlockPos.asLong(b.getX(), 0, b.getZ()), b, (a, x) -> a.getY() <= x.getY() ? a : x);
		}
		for (BlockPos b : lowest.values()) {
			BlockState st = level.getBlockState(b);
			BlockState below = level.getBlockState(b.below());
			String tr = owner.get(b.asLong());
			int baseY = h.getY() + Settlements.lift(s, s.style() + "/" + tr + "_1");
			if (b.getY() > baseY || !st.isCollisionShapeFullBlock(level, b)) {
				continue; // eaves, porches and roofs overhang by design; only the ground course must stand on something
			}
			if (!st.isAir() && !owner.containsKey(b.below().asLong()) && (below.isAir() || !below.getFluidState().isEmpty() || below.canBeReplaced())) {
				feet++;
				sample(samples, "floating feet", owner.get(b.asLong()) + " " + rel(s, b) + " " + name(st) + " over " + name(below));
				marks.add(new Mark(b.getX(), b.getZ(), 0xFF2020));
			}
		}
		c.put("floating feet", feet);

		// 2 buried: template air filled with land; 12 destroyed
		int buried = 0;
		for (String track : Progression.ORDER) {
			Progression.Step step = Progression.current(s, track);
			if (step == null) {
				continue;
			}
			for (Template.Placement p : Settlements.placements(s, s.style() + "/" + step.building())) {
				if (!p.state().isAir()) {
					continue;
				}
				BlockPos pos = h.offset(p.offset());
				BlockState w = level.getBlockState(pos);
				if (!owner.containsKey(pos.asLong()) && SiteWork.terrain(w)) {
					buried++;
					sample(samples, "buried", track + " " + rel(s, pos) + " " + name(w));
					marks.add(new Mark(pos.getX(), pos.getZ(), 0xFF00FF));
				}
			}
		}
		c.put("buried", buried);
		List<String> where = new ArrayList<>();
		int destroyed = destroyed(level, s, where).values().stream().mapToInt(Integer::intValue).sum();
		c.put("destroyed", destroyed);
		where.stream().limit(6).forEach(w -> sample(samples, "destroyed", w));

		// 3 walking
		Walk walk = walk(level, s, owner, marks, samples);
		c.put("doors unreached", walk.doorsMissed);
		c.put("walk out", walk.out ? 1 : 0);
		c.put("breach", walk.breach);

		// 4 water
		Rotation r = Rotation.values()[s.rotation()];
		// the wall ring relative to the heart (tools/buildings.py WALL, layout.json)
		BlockPos ra = h.offset(Template.rotate(new BlockPos(Layout.ring()[0], 0, Layout.ring()[1]), r));
		BlockPos rb = h.offset(Template.rotate(new BlockPos(Layout.ring()[2], 0, Layout.ring()[3]), r));
		int wet = 0;
		int wetMoat = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = Math.min(ra.getX(), rb.getX()) - 20; x <= Math.max(ra.getX(), rb.getX()) + 20; x++) {
			for (int z = Math.min(ra.getZ(), rb.getZ()) - 20; z <= Math.max(ra.getZ(), rb.getZ()) + 20; z++) {
				long col = BlockPos.asLong(x, 0, z);
				boolean inside = x > Math.min(ra.getX(), rb.getX()) && x < Math.max(ra.getX(), rb.getX()) && z > Math.min(ra.getZ(), rb.getZ())
						&& z < Math.max(ra.getZ(), rb.getZ());
				if (!inside && !moat.contains(col)) {
					continue;
				}
				int ty = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
				FluidState f = level.getFluidState(pos.set(x, ty, z));
				if (!f.is(Fluids.WATER)) {
					continue;
				}
				if (moat.contains(col)) {
					wetMoat++;
					marks.add(new Mark(x, z, 0x00FFFF));
				} else if (s.level(Progression.WALLS) >= 1 && !owner.containsKey(pos.asLong())) {
					wet++;
					marks.add(new Mark(x, z, 0x0040FF));
					sample(samples, "wet inside", rel(s, pos.immutable()));
				}
			}
		}
		c.put("wet inside", wet);
		c.put("wet moat", wetMoat);

		// 5 land: new cliffs and pits between columns nothing was built on
		int n = 2 * R + 1;
		int[] now = new int[n * n];
		boolean[] open = new boolean[n * n];
		Set<Long> builtAll = owner.keySet();
		for (int i = 0; i < n; i++) {
			for (int k = 0; k < n; k++) {
				int x = base.x0() + i, z = base.z0() + k;
				long col = BlockPos.asLong(x, 0, z);
				open[i * n + k] = !builtCols.contains(col) && !moat.contains(col);
				now[i * n + k] = SiteWork.groundDeep(level, x, z, h.getY(), builtAll, 90, 140);
			}
		}
		int cliffs = 0;
		int pits = 0;
		int tall = 0;
		Map<String, Integer> tallBy = new java.util.TreeMap<>();
		Set<String> masonry = Set.of("stone_bricks", "mossy_stone_bricks", "cobblestone", "mossy_cobblestone", "smooth_sandstone", "cut_red_sandstone");
		for (int i = 1; i < n - 1; i++) {
			for (int k = 1; k < n - 1; k++) {
				int a = i * n + k;
				if (!open[a]) {
					continue;
				}
				for (int[] d : new int[][] {{1, 0}, {0, 1}}) {
					int b = (i + d[0]) * n + k + d[1];
					if (open[b] && Math.abs(now[a] - now[b]) >= 3 && Math.abs(base.ground()[a] - base.ground()[b]) < 3) {
						int hi = now[a] > now[b] ? a : b;
						String cap = name(level.getBlockState(pos.set(base.x0() + hi / n, now[hi], base.z0() + hi % n)));
						if (masonry.contains(cap)) { // a terrace's retaining wall: meant, unless it towers
							if (Math.abs(now[a] - now[b]) >= 7) {
								tall++;
								marks.add(new Mark(base.x0() + i, base.z0() + k, 0xFFFFFF));
								BlockPos capPos = new BlockPos(base.x0() + hi / n, now[hi], base.z0() + hi % n);
								String who = owner.getOrDefault(capPos.asLong(), "unowned");
								tallBy.merge(who + "/" + owner.getOrDefault(capPos.below(2).asLong(), "unowned"), 1, Integer::sum);
								BlockPos under = capPos.below(Math.min(6, Math.abs(now[a] - now[b]) - 1));
								sample(samples, "tall walls", rel(s, new BlockPos(base.x0() + i, now[a], base.z0() + k)) + " " + (now[b] - now[a]) + " cap " + who
										+ " low " + owner.getOrDefault(under.asLong(), "unowned") + ":" + name(level.getBlockState(under)));
							}
							continue;
						}
						cliffs++;
						marks.add(new Mark(base.x0() + i, base.z0() + k, 0xFFD000));
						sample(samples, "new cliffs", rel(s, new BlockPos(base.x0() + i, now[a], base.z0() + k)) + " to " + (now[b] - now[a]));
					}
				}
				int lower = 0, higher = 0, opens = 0;
				for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
					int b = (i + d[0]) * n + k + d[1];
					if (open[b]) {
						opens++;
						lower += now[b] - now[a] >= 2 ? 1 : 0;
						higher += now[a] - now[b] >= 2 ? 1 : 0;
					}
				}
				boolean wasPit = false;
				int lb = 0, hb = 0;
				for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
					int b = (i + d[0]) * n + k + d[1];
					lb += base.ground()[b] - base.ground()[a] >= 2 ? 1 : 0;
					hb += base.ground()[a] - base.ground()[b] >= 2 ? 1 : 0;
				}
				wasPit = lb == 4 || hb == 4;
				if (opens == 4 && (lower == 4 || higher == 4) && !wasPit) {
					pits++;
					marks.add(new Mark(base.x0() + i, base.z0() + k, 0xFF8000));
					sample(samples, "pits", rel(s, new BlockPos(base.x0() + i, now[a], base.z0() + k)) + (lower == 4 ? " hole" : " spike"));
				}
			}
		}
		c.put("new cliffs", cliffs);
		c.put("tall walls", tall);
		if (!tallBy.isEmpty()) {
			sample(samples, "tall by", tallBy.toString());
		}
		c.put("pits", pits);

		// 7 roads, 8 walls
		int roadGaps = 0, roadSteps = 0;
		Map<Long, Integer> roadTop = new HashMap<>();
		for (long p : s.built().getOrDefault(Progression.ROADS, List.of())) {
			BlockPos b = BlockPos.of(p);
			BlockState st = level.getBlockState(b);
			if (st.isAir()) {
				continue;
			}
			roadTop.merge(BlockPos.asLong(b.getX(), 0, b.getZ()), b.getY(), Math::max);
			BlockState below = level.getBlockState(b.below());
			if ((below.isAir() || below.canBeReplaced() && below.getFluidState().isEmpty()) && !owner.containsKey(b.below().asLong())) {
				roadGaps++;
				sample(samples, "road gaps", rel(s, b) + " " + name(st) + " over " + name(below));
				marks.add(new Mark(b.getX(), b.getZ(), 0xA0A0FF));
			}
			if (!level.getFluidState(b.above()).isEmpty()) {
				roadGaps++;
				sample(samples, "road gaps", rel(s, b) + " under water");
			}
		}
		for (Map.Entry<Long, Integer> e : roadTop.entrySet()) {
			int x = BlockPos.getX(e.getKey()), z = BlockPos.getZ(e.getKey());
			for (int[] d : new int[][] {{1, 0}, {0, 1}}) {
				Integer o = roadTop.get(BlockPos.asLong(x + d[0], 0, z + d[1]));
				if (o != null && Math.abs(o - e.getValue()) >= 2) {
					roadSteps++;
					sample(samples, "road steps", rel(s, new BlockPos(x, e.getValue(), z)) + " to " + (o - e.getValue()));
					marks.add(new Mark(x, z, 0x8080FF));
				}
			}
		}
		// a road climbing 3 or more within 3 blocks reads as a staircase, not a road (smooth roads, user 2026-11-18)
		int roadSteep = 0;
		for (Map.Entry<Long, Integer> e : roadTop.entrySet()) {
			int x = BlockPos.getX(e.getKey()), z = BlockPos.getZ(e.getKey());
			for (int[] d : new int[][] {{3, 0}, {0, 3}}) {
				Integer o = roadTop.get(BlockPos.asLong(x + d[0], 0, z + d[1]));
				if (o != null && Math.abs(o - e.getValue()) >= 3 && roadTop.containsKey(BlockPos.asLong(x + d[0] / 3, 0, z + d[1] / 3))
						&& roadTop.containsKey(BlockPos.asLong(x + 2 * d[0] / 3, 0, z + 2 * d[1] / 3))) {
					roadSteep++;
					sample(samples, "road steep", rel(s, new BlockPos(x, e.getValue(), z)) + " to " + (o - e.getValue()) + " in 3");
					marks.add(new Mark(x, z, 0x6060FF));
				}
			}
		}
		// roads higher than the buildings they lead to (user, 2026-11-19: "weirdly high, higher than the stairs of the
		// buildings"): a road cell beside a building's bottom-half stair stands above that stair's top, or beside a door
		// above its sill; and roads riding a bank, land 2+ lower on both sides across the road
		int roadHigh = 0, roadBank = 0;
		for (Map.Entry<Long, Integer> e : roadTop.entrySet()) {
			int x = BlockPos.getX(e.getKey()), z = BlockPos.getZ(e.getKey());
			BlockPos roadAt = new BlockPos(x, e.getValue(), z);
			double rf = e.getValue() + (level.getBlockState(roadAt).getBlock() instanceof net.minecraft.world.level.block.SlabBlock ? 0.5 : 1.0);
			boolean high = false;
			for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
				for (int dy = -4; dy <= 2 && !high; dy++) {
					BlockPos q = new BlockPos(x + d[0], e.getValue() + dy, z + d[1]);
					String tr = owner.get(q.asLong());
					if (tr == null || Progression.ROADS.equals(tr) || Progression.LAMPS.equals(tr)) {
						continue;
					}
					BlockState qs = level.getBlockState(q);
					if (qs.getBlock() instanceof net.minecraft.world.level.block.StairBlock
							&& qs.getValue(net.minecraft.world.level.block.StairBlock.HALF) == net.minecraft.world.level.block.state.properties.Half.BOTTOM
							&& level.getBlockState(q.above()).isAir() && rf > q.getY() + 1.01) {
						high = true;
						sample(samples, "road high", rel(s, roadAt) + " above " + tr + " step " + rel(s, q));
					} else if (qs.getBlock() instanceof DoorBlock && qs.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER && rf > q.getY() + 0.51) {
						high = true;
						sample(samples, "road high", rel(s, roadAt) + " above " + tr + " door " + rel(s, q));
					}
				}
			}
			if (high) {
				roadHigh++;
				marks.add(new Mark(x, z, 0xFF40FF));
			}
			for (int[] d : new int[][] {{1, 0}, {0, 1}}) {
				long a1 = BlockPos.asLong(x + d[0], 0, z + d[1]), a2 = BlockPos.asLong(x - d[0], 0, z - d[1]);
				if (roadTop.containsKey(a1) || roadTop.containsKey(a2) || builtCols.contains(a1) || builtCols.contains(a2)) {
					continue;
				}
				int g1 = SiteWork.groundDeep(level, x + d[0], z + d[1], h.getY(), owner.keySet());
				int g2 = SiteWork.groundDeep(level, x - d[0], z - d[1], h.getY(), owner.keySet());
				if (e.getValue() - g1 >= 2 && e.getValue() - g2 >= 2) {
					roadBank++;
					sample(samples, "road bank", rel(s, roadAt) + " over " + (e.getValue() - g1) + "/" + (e.getValue() - g2));
					break;
				}
			}
		}
		// streets keep their height once laid (road first, 2026-11-19): road columns whose top moved since the last check
		int roadMoved = 0;
		Map<Long, Integer> roadBase = new HashMap<>(); // the road block itself: slabs and stairs come with the paving level
		for (long p : s.built().getOrDefault(Progression.ROADS, List.of())) {
			BlockPos b = BlockPos.of(p);
			Block blk = level.getBlockState(b).getBlock();
			if (!level.getBlockState(b).isAir() && !(blk instanceof net.minecraft.world.level.block.SlabBlock) && !(blk instanceof net.minecraft.world.level.block.StairBlock)) {
				roadBase.merge(BlockPos.asLong(b.getX(), 0, b.getZ()), b.getY(), Math::max);
			}
		}
		for (Map.Entry<Long, Integer> e : roadBase.entrySet()) {
			Integer was = ROAD_LAST.get(e.getKey());
			if (was != null && !was.equals(e.getValue())) {
				roadMoved++;
				sample(samples, "road moved", rel(s, new BlockPos(BlockPos.getX(e.getKey()), e.getValue(), BlockPos.getZ(e.getKey()))) + " from " + (was - h.getY()));
			}
		}
		ROAD_LAST.putAll(roadBase);
		c.put("road gaps", roadGaps);
		c.put("road steps", roadSteps);
		int wallGaps = 0;
		Map<Long, BlockPos> wallLow = new HashMap<>();
		Map<Long, Integer> wallCount = new HashMap<>();
		for (long p : s.built().getOrDefault(Progression.WALLS, List.of())) {
			BlockPos b = BlockPos.of(p);
			if (!level.getBlockState(b).isAir()) {
				wallLow.merge(BlockPos.asLong(b.getX(), 0, b.getZ()), b, (a, x) -> a.getY() <= x.getY() ? a : x);
				wallCount.merge(BlockPos.asLong(b.getX(), 0, b.getZ()), 1, Integer::sum);
			}
		}
		Set<Long> wallSet = new HashSet<>(s.built().getOrDefault(Progression.WALLS, List.of()));
		for (BlockPos b : wallLow.values()) {
			if (wallCount.getOrDefault(BlockPos.asLong(b.getX(), 0, b.getZ()), 0) < 3) {
				continue; // a walkway board on brackets, not a wall body
			}
			if (!wallSet.contains(b.above().asLong()) || !wallSet.contains(b.above(2).asLong()) || level.getBlockState(b.above()).isAir()
					|| level.getBlockState(b.above(2)).isAir()) {
				continue; // a corbel or a hoarding's eave sticking out over the ditch: the body above it is not solid from here
			}
			BlockState below = level.getBlockState(b.below());
			if ((below.isAir() || !below.getFluidState().isEmpty() || below.canBeReplaced()) && !owner.containsKey(b.below().asLong())) {
				wallGaps++;
				sample(samples, "wall gaps", rel(s, b) + " over " + name(below));
				marks.add(new Mark(b.getX(), b.getZ(), 0xFF6060));
			}
		}
		c.put("wall gaps", wallGaps);

		// 6 cut trees, 10 foreign ground
		int cut = Math.max(0, decaying(level, h) - base.decaying());
		c.put("cut trees", cut);
		Map<String, Integer> before = new HashMap<>();
		Map<String, Integer> after = new HashMap<>();
		int cols = 0;
		for (int i = 0; i < n; i++) {
			for (int k = 0; k < n; k++) {
				int x = base.x0() + i, z = base.z0() + k;
				if (Math.abs(x - h.getX()) > R - 20 || Math.abs(z - h.getZ()) > R - 20 || !open[i * n + k]) {
					continue;
				}
				cols++;
				before.merge(base.top()[i * n + k], 1, Integer::sum);
				after.merge(name(level.getBlockState(pos.set(x, now[i * n + k], z))), 1, Integer::sum);
			}
		}
		int foreign = 0;
		for (Map.Entry<String, Integer> e : after.entrySet()) {
			int was = before.getOrDefault(e.getKey(), 0);
			if (Set.of("stone_bricks", "mossy_stone_bricks", "cobblestone", "mossy_cobblestone", "smooth_sandstone", "cut_red_sandstone").contains(e.getKey())) {
				continue; // the stone terrace walls over a real drop: built that way on purpose
			}
			if (cols > 0 && e.getValue() * 50 > cols && was * 200 < cols) {
				foreign += e.getValue();
				sample(samples, "foreign ground", e.getKey() + " " + e.getValue() + " of " + cols + " columns (was " + was + ")");
			}
		}
		c.put("foreign ground", foreign);
		// snow: open columns that were white before and are bare now (frozen biomes)
		int bare = 0;
		Snapshot now2 = Snapshot.take(level, h, builtAll);
		for (int i = 0; i < n; i++) {
			for (int k = 0; k < n; k++) {
				int x = base.x0() + i, z = base.z0() + k;
				if (Math.abs(x - h.getX()) <= R - 20 && Math.abs(z - h.getZ()) <= R - 20 && open[i * n + k] && base.snow().get(i * n + k) && !now2.snow().get(i * n + k)) {
					bare++;
					if (bare % 50 == 1) {
						marks.add(new Mark(x, z, 0xFFFFFF));
						int ty = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
						BlockPos tp = new BlockPos(x, ty, z);
						sample(samples, "snow lost", rel(s, tp) + " on " + name(level.getBlockState(tp.below())) + " at " + name(level.getBlockState(tp))
								+ " light " + level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, tp) + (owner.containsKey(tp.below().asLong()) ? " built" : ""));
					}
				}
			}
		}
		c.put("snow lost", bare);
		c.put("road steep", roadSteep);
		// bamboo, cactus or sugar cane cut off in a floor (user, 2026-11-19: bamboo left in the ground around a jungle camp)
		int stumps = 0;
		for (int x = h.getX() - 70; x <= h.getX() + 70; x++) {
			for (int z = h.getZ() - 70; z <= h.getZ() + 70; z++) {
				int ty = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
				for (int y = ty; y >= ty - 8; y--) {
					pos.set(x, y, z);
					if (SiteWork.stalk(level.getBlockState(pos)) && SiteWork.stumpTop(level, pos.immutable(), builtAll)) {
						stumps++;
						sample(samples, "stumps", rel(s, pos.immutable()) + " " + name(level.getBlockState(pos)) + " under " + name(level.getBlockState(pos.above())));
						marks.add(new Mark(x, z, 0x80FF00));
					}
				}
			}
		}
		c.put("stumps", stumps);
		c.put("road high", roadHigh);
		c.put("road bank", roadBank);
		c.put("road moved", roadMoved);

		StringBuilder row = new StringBuilder("| ").append(stage).append(" | ").append(ms);
		for (int v : c.values()) {
			row.append(" | ").append(v);
		}
		REPORT.append(row).append(" |\n");
		boolean any = false;
		for (String extra : new String[] {"door gaps", "way out", "tall by"}) {
			if (samples.containsKey(extra) && "final".equals(stage)) {
				problems.add(stage + ": " + extra + " " + samples.get(extra));
			}
		}
		for (Map.Entry<String, Integer> e : c.entrySet()) {
			boolean fail = e.getKey().equals("walk out") ? e.getValue() == 0 && OUT_BEFORE : e.getValue() > 0;
			if (fail) {
				any = true;
				problems.add(stage + ": " + e.getKey() + " " + (e.getKey().equals("walk out") ? "no way out of the settlement" : e.getValue()) + " "
						+ samples.getOrDefault(e.getKey(), List.of()));
			}
		}
		if (IMAGE_STAGES.contains(stage) || any && "final".equals(stage)) {
			image(level, s, base, marks, dir.resolve(site + "_" + stage + ".png"));
		}
		if ("final".equals(stage)) {
			views(level, s, samples, dir, site);
			roads(level, s, base, owner, dir, site);
		}
	}

	private static void sample(Map<String, List<String>> samples, String key, String text) {
		List<String> l = samples.computeIfAbsent(key, k -> new ArrayList<>());
		if (l.size() < 5) {
			l.add(text);
		}
	}

	/** Solid blocks in the inspected box that hang in the air (not connected to the bottom or the sides). [built, land] */
	private static int[] floating(ServerLevel level, Settlement s, Map<Long, String> owner, Map<String, List<String>> samples, List<Mark> marks,
			Set<Long> record, Set<Long> ignore) {
		BlockPos h = s.heart();
		int x0 = h.getX() - R, z0 = h.getZ() - R, n = 2 * R + 1;
		int ylo = Integer.MAX_VALUE, yhi = Integer.MIN_VALUE;
		for (int i = 0; i < n; i += 4) {
			for (int k = 0; k < n; k += 4) {
				ylo = Math.min(ylo, level.getHeight(Heightmap.Types.OCEAN_FLOOR, x0 + i, z0 + k));
				yhi = Math.max(yhi, level.getHeight(Heightmap.Types.WORLD_SURFACE, x0 + i, z0 + k));
			}
		}
		ylo = Math.max(level.getMinY(), Math.min(ylo, h.getY() - 20) - 10);
		yhi = Math.min(yhi + 4, h.getY() + 90);
		int hgt = yhi - ylo + 1;
		BitSet solid = new BitSet(n * n * hgt);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < n; i++) {
			for (int k = 0; k < n; k++) {
				for (int y = 0; y < hgt; y++) {
					BlockState st = level.getBlockState(pos.set(x0 + i, ylo + y, z0 + k));
					boolean leaves = st.getBlock() instanceof LeavesBlock && !(st.hasProperty(LeavesBlock.PERSISTENT) && st.getValue(LeavesBlock.PERSISTENT));
					if (!st.isAir() && !(st.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) && !leaves) { // waterlogged roots hold
						solid.set((i * n + k) * hgt + y);
					}
				}
			}
		}
		BitSet seen = new BitSet(n * n * hgt);
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		for (int i = 0; i < n; i++) {
			for (int k = 0; k < n; k++) {
				for (int y = 0; y < hgt; y++) {
					boolean edge = y == 0 || i == 0 || k == 0 || i == n - 1 || k == n - 1;
					int idx = (i * n + k) * hgt + y;
					if (edge && solid.get(idx) && !seen.get(idx)) {
						seen.set(idx);
						queue.add(idx);
					}
				}
			}
		}
		while (!queue.isEmpty()) {
			int idx = queue.poll();
			int y = idx % hgt;
			int col = idx / hgt;
			int i = col / n, k = col % n;
			// faces and edges: stairs of a stepped roof only touch along an edge, and that holds in game
			for (int[] q : new int[][] {{i + 1, k, y}, {i - 1, k, y}, {i, k + 1, y}, {i, k - 1, y}, {i, k, y + 1}, {i, k, y - 1},
					{i + 1, k, y + 1}, {i - 1, k, y + 1}, {i, k + 1, y + 1}, {i, k - 1, y + 1}, {i + 1, k, y - 1}, {i - 1, k, y - 1},
					{i, k + 1, y - 1}, {i, k - 1, y - 1}, {i + 1, k + 1, y}, {i + 1, k - 1, y}, {i - 1, k + 1, y}, {i - 1, k - 1, y}}) {
				if (q[0] < 0 || q[1] < 0 || q[0] >= n || q[1] >= n || q[2] < 0 || q[2] >= hgt) {
					continue;
				}
				int j = (q[0] * n + q[1]) * hgt + q[2];
				if (solid.get(j) && !seen.get(j)) {
					seen.set(j);
					queue.add(j);
				}
			}
		}
		int built = 0, land = 0;
		for (int idx = solid.nextSetBit(0); idx >= 0; idx = solid.nextSetBit(idx + 1)) {
			if (seen.get(idx)) {
				continue;
			}
			int y = idx % hgt;
			int col = idx / hgt;
			BlockPos p = new BlockPos(x0 + col / n, ylo + y, z0 + col % n);
			BlockState st = level.getBlockState(p);
			if (owner.containsKey(p.asLong())) {
				built++;
				sample(samples, "float built", owner.get(p.asLong()) + " " + rel(s, p) + " " + name(st));
				marks.add(new Mark(p.getX(), p.getZ(), 0xFF0000));
			} else if (SiteWork.terrain(st) || st.is(BlockTags.LOGS)) {
				if (record != null) {
					record.add(p.asLong());
					continue;
				}
				if (ignore != null && ignore.contains(p.asLong())) {
					continue;
				}
				land++;
				sample(samples, "float land", rel(s, p) + " " + name(st));
				marks.add(new Mark(p.getX(), p.getZ(), 0xC00000));
			}
		}
		return new int[] {built, land};
	}

	// ---- walking ----

	private static final class Walk {
		int doorsMissed;
		boolean out;
		int breach;
	}

	private static boolean passable(BlockState st, ServerLevel level, BlockPos p) {
		if (st.getBlock() instanceof DoorBlock || st.getBlock() instanceof FenceGateBlock) {
			return true;
		}
		if (st.getFluidState().is(Fluids.LAVA) || st.is(Blocks.FIRE)) {
			return false;
		}
		return st.getCollisionShape(level, p).isEmpty();
	}

	private static double top(BlockState st, ServerLevel level, BlockPos p) {
		VoxelShape shape = st.getCollisionShape(level, p);
		return shape.isEmpty() ? 0 : shape.max(Direction.Axis.Y);
	}

	/** Feet height when standing in cell x/y/z, NaN if one can't stand there. Swimming counts only when {@code wet}. */
	static double feet(ServerLevel level, int x, int y, int z, boolean wet) {
		BlockPos p = new BlockPos(x, y, z);
		BlockState at = level.getBlockState(p);
		if (!passable(at, level, p)) {
			double t = top(at, level, p);
			if (t > 0 && t <= 0.5625 && passable(level.getBlockState(p.above()), level, p.above())
					&& passable(level.getBlockState(p.above(2)), level, p.above(2))) {
				return y + t;
			}
			return Double.NaN;
		}
		if (!passable(level.getBlockState(p.above()), level, p.above())) {
			return Double.NaN;
		}
		if (!at.getFluidState().isEmpty()) {
			return wet ? y : Double.NaN;
		}
		BlockPos b = p.below();
		BlockState below = level.getBlockState(b);
		if (!below.getFluidState().isEmpty() && !wet) {
			return Double.NaN;
		}
		double t = top(below, level, b);
		return t <= 0 ? Double.NaN : y - 1 + Math.min(t, 1.5);
	}

	private static Walk walk(ServerLevel level, Settlement s, Map<Long, String> owner, List<Mark> marks, Map<String, List<String>> samples) {
		Walk w = new Walk();
		BlockPos h = s.heart();
		Set<Long> reach = new HashSet<>();
		ArrayDeque<long[]> queue = new ArrayDeque<>();
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				for (int dy = -2; dy <= 3; dy++) {
					double f = feet(level, h.getX() + dx, h.getY() + dy, h.getZ() + dz, false);
					long key = BlockPos.asLong(h.getX() + dx, h.getY() + dy, h.getZ() + dz);
					if (!Double.isNaN(f) && reach.add(key)) {
						queue.add(new long[] {key});
					}
				}
			}
		}
		w.out = spread(level, h, reach, queue, Set.of(), 1_600_000);
		if (!w.out && reach.size() >= 1_600_000) {
			sample(samples, "way out", "walk budget used up (" + reach.size() + " cells)");
		}
		if (!w.out) {
			List<Long> edge = new ArrayList<>();
			for (int i = -R + 1; i < R; i += 2) {
				for (int[] e2 : new int[][] {{i, -R + 1}, {i, R - 1}, {-R + 1, i}, {R - 1, i}}) {
					int x = h.getX() + e2[0], z = h.getZ() + e2[1];
					int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
					if (!Double.isNaN(feet(level, x, y, z, false))) {
						edge.add(BlockPos.asLong(x, y, z));
					}
				}
			}
			Set<Long> back = reverse(level, h, edge, 1_600_000);
			sample(samples, "way out", gap(level, s, reach, back));
			// where the two walks end: is the gate reached from inside, its outside reached from the edge?
			Rotation turn = Rotation.values()[s.rotation()];
			StringBuilder at = new StringBuilder("walked " + reach.size() + " inside, " + back.size() + " from " + edge.size() + " edge cells;");
			for (int gz : new int[] {34, 38, 42, 46, 52, 60}) {
				BlockPos c = h.offset(Template.rotate(new BlockPos(0, 0, gz), turn));
				boolean in = false, out = false;
				for (long k : reach) {
					in |= Math.abs(BlockPos.getX(k) - c.getX()) <= 2 && Math.abs(BlockPos.getZ(k) - c.getZ()) <= 2;
				}
				for (long k : back) {
					out |= Math.abs(BlockPos.getX(k) - c.getX()) <= 2 && Math.abs(BlockPos.getZ(k) - c.getZ()) <= 2;
				}
				at.append(" z").append(gz).append(in ? " in" : "").append(out ? " out" : "").append(in || out ? "" : " -")
						.append("@").append(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ()) - h.getY());
			}
			sample(samples, "way out", at.toString());
		}
		int doors = 0;
		for (Map.Entry<Long, String> e : owner.entrySet()) {
			BlockPos p = BlockPos.of(e.getKey());
			BlockState st = level.getBlockState(p);
			if (st.getBlock() instanceof DoorBlock && st.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
				doors++;
				if (!reach.contains(e.getKey())) {
					w.doorsMissed++;
					sample(samples, "door gaps", e.getValue() + " " + rel(s, p) + " " + gap(level, s, reach, reverse(level, h, List.of(e.getKey()), 60_000)));
					sample(samples, "doors unreached", e.getValue() + " " + rel(s, p) + " front " + DOOR_GROUND.getOrDefault(e.getKey(), List.of()));
					marks.add(new Mark(p.getX(), p.getZ(), 0xFF00A0));
				}
			}
		}
		if (s.level(Progression.WALLS) >= 1) {
			// from outside, with the gate building closed: anything that still reaches the square is a gap in the ring
			Set<Long> gate = new HashSet<>();
			for (long p : s.built().getOrDefault(Progression.GATE, List.of())) {
				BlockPos b = BlockPos.of(p);
				gate.add(BlockPos.asLong(b.getX(), 0, b.getZ()));
			}
			Set<Long> outside = new HashSet<>();
			ArrayDeque<long[]> q2 = new ArrayDeque<>();
			for (int i = -R; i <= R; i += 4) {
				for (int[] e : new int[][] {{i, -R}, {i, R}, {-R, i}, {R, i}}) {
					int x = h.getX() + e[0], z = h.getZ() + e[1];
					int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
					if (!Double.isNaN(feet(level, x, y, z, false)) && outside.add(BlockPos.asLong(x, y, z))) {
						q2.add(new long[] {BlockPos.asLong(x, y, z)});
					}
				}
			}
			spread(level, h, outside, q2, gate, 400_000);
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					for (int dy = -2; dy <= 3; dy++) {
						if (outside.contains(BlockPos.asLong(h.getX() + dx, h.getY() + dy, h.getZ() + dz))) {
							w.breach = 1;
						}
					}
				}
			}
			if (w.breach > 0) {
				sample(samples, "breach", "the square can be reached from outside without passing the gate");
			}
		}
		return w;
	}

	/**
	 * Highest step one walks up or down. A walk counts only if it also works back (user, 2026-11-18: 3-block steps in
	 * front of a gate let one jump out of the castle but never back in, and the old test, down 3.5, called that a way out).
	 */
	static final double STEP = 1.25;

	/** Walks from the queued cells (up and down {@link #STEP}, dry); true if it reached the edge of the inspected box. */
	/** True in a cave: the column's top is natural ground (not a roof) more than 6 blocks over the feet. */
	private static boolean underground(ServerLevel level, int x, int y, int z) {
		int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		return top > y + 6 && SiteWork.terrain(level.getBlockState(new BlockPos(x, top, z)));
	}

	static boolean spread(ServerLevel level, BlockPos h, Set<Long> reach, ArrayDeque<long[]> queue, Set<Long> blockedCols, int cap) {
		boolean edge = false;
		// surface first: cells in caves (natural rock overhead) wait until the surface is walked out, so a cave
		// system under a hill can't use up the budget before the way out is found (windswept hills: "no contact")
		ArrayDeque<long[]> caves = new ArrayDeque<>();
		while ((!queue.isEmpty() || !caves.isEmpty()) && reach.size() < cap) {
			long key = (queue.isEmpty() ? caves : queue).poll()[0];
			int x = BlockPos.getX(key), y = BlockPos.getY(key), z = BlockPos.getZ(key);
			if (Math.abs(x - h.getX()) >= R || Math.abs(z - h.getZ()) >= R) {
				edge = true;
				continue;
			}
			double f = feet(level, x, y, z, false);
			for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
				int nx = x + d[0], nz = z + d[1];
				if (blockedCols.contains(BlockPos.asLong(nx, 0, nz))) {
					continue;
				}
				for (int dy = 2; dy >= -4; dy--) {
					long nk = BlockPos.asLong(nx, y + dy, nz);
					if (reach.contains(nk)) {
						continue;
					}
					double g = feet(level, nx, y + dy, nz, false);
					if (!Double.isNaN(g) && g - f <= STEP && f - g <= STEP) {
						reach.add(nk);
						(underground(level, nx, y + dy, nz) ? caves : queue).add(new long[] {nk});
					}
				}
			}
		}
		return edge;
	}

	// ---- 3D views (rendered with tools/render.py) ----

	/** Boxes of blocks around the gate, a corner of the ring and the first finding of each kind, as JSON for the renderer. */
	private static void views(ServerLevel level, Settlement s, Map<String, List<String>> samples, Path dir, String site) {
		BlockPos h = s.heart();
		Rotation r = Rotation.values()[s.rotation()];
		Map<String, BlockPos> centres = new LinkedHashMap<>();
		int[] ring = Layout.ring();
		centres.put("gate", h.offset(Template.rotate(new BlockPos(0, 0, ring[3]), r)));
		centres.put("corner", h.offset(Template.rotate(new BlockPos(ring[0] + 4, 0, ring[1] + 4), r)));
		centres.put("square", h);
		// the town's four quarters, for looking at the roads inside the walls as a whole (user, 2026-11-19)
		centres.put("town_a", h.offset(Template.rotate(new BlockPos(ring[0] / 2, 0, ring[1] / 2), r)));
		centres.put("town_b", h.offset(Template.rotate(new BlockPos(ring[2] / 2, 0, ring[1] / 2), r)));
		centres.put("town_c", h.offset(Template.rotate(new BlockPos(ring[0] / 2, 0, ring[3] / 2), r)));
		centres.put("town_d", h.offset(Template.rotate(new BlockPos(ring[2] / 2, 0, ring[3] / 2), r)));
		if (RoadLink.WAY_OUT.containsKey(s.id())) {
			centres.put("way_out", RoadLink.WAY_OUT.get(s.id()));
		}
		if (RoadLink.BRIDGE.containsKey(s.id().toString())) {
			centres.put("bridge", RoadLink.BRIDGE.get(s.id().toString()));
		}
		java.util.regex.Pattern rel = java.util.regex.Pattern.compile("\\((-?\\d+),(-?\\d+),(-?\\d+)\\)");
		int doorViews = 0;
		for (String text : samples.getOrDefault("doors unreached", List.of())) {
			java.util.regex.Matcher m = rel.matcher(text);
			if (m.find() && doorViews < 3) {
				centres.put("door" + (++doorViews) + "_" + text.substring(0, text.indexOf(' ')),
						h.offset(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))));
			}
		}
		for (Map.Entry<String, List<String>> e : samples.entrySet()) {
			for (String text : e.getValue()) {
				java.util.regex.Matcher m = rel.matcher(text);
				if (m.find()) {
					centres.putIfAbsent(e.getKey().replace(' ', '_'), h.offset(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))));
					break;
				}
			}
		}
		for (Map.Entry<String, BlockPos> e : centres.entrySet()) {
			BlockPos c = e.getValue();
			int half = 16;
			int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
			for (int x = c.getX() - half; x <= c.getX() + half; x += 2) {
				for (int z = c.getZ() - half; z <= c.getZ() + half; z += 2) {
					int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
					lo = Math.min(lo, level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 3);
					hi = Math.max(hi, top + 1);
				}
			}
			lo = Math.max(lo, c.getY() - 24);
			hi = Math.min(hi, c.getY() + 30);
			StringBuilder b = new StringBuilder("{\"size\":[").append(2 * half + 1).append(',').append(hi - lo + 1).append(',').append(2 * half + 1)
					.append("],\"centre\":\"").append(rel(s, c)).append("\",\"palette\":[");
			Map<BlockState, Integer> pal = new LinkedHashMap<>();
			StringBuilder blocks = new StringBuilder();
			BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
			for (int x = -half; x <= half; x++) {
				for (int z = -half; z <= half; z++) {
					for (int y = lo; y <= hi; y++) {
						BlockState st = level.getBlockState(p.set(c.getX() + x, y, c.getZ() + z));
						if (st.isAir() || hidden(level, p)) {
							continue;
						}
						int idx = pal.computeIfAbsent(st, k -> pal.size());
						if (!blocks.isEmpty()) {
							blocks.append(',');
						}
						blocks.append('[').append(x + half).append(',').append(y - lo).append(',').append(z + half).append(',').append(idx).append(']');
					}
				}
			}
			boolean first = true;
			for (BlockState st : pal.keySet()) {
				b.append(first ? "" : ",").append('"').append(st.toString().replace("\"", "")).append('"');
				first = false;
			}
			b.append("],\"blocks\":[").append(blocks).append("]}");
			try {
				Files.writeString(dir.resolve(site + "_view_" + e.getKey() + ".json"), b.toString());
			} catch (IOException ex) {
				KingdomQuest.LOG.error("Field test: view not written", ex);
			}
		}
	}

	/**
	 * Every column within 70 of the heart for the road analysis: natural ground before founding, the shaped land now
	 * (looking through everything built), the road's top block if any, the highest built block and its track; and every
	 * door with its facing. Heights relative to the heart. Written as fieldtest/<site>_roads.tsv.
	 */
	private static void roads(ServerLevel level, Settlement s, Snapshot base, Map<Long, String> owner, Path dir, String site) {
		BlockPos h = s.heart();
		int n = 2 * R + 1;
		Map<Long, int[]> roadTop = new HashMap<>();
		for (long p : s.built().getOrDefault(Progression.ROADS, List.of())) {
			BlockPos b = BlockPos.of(p);
			if (!level.getBlockState(b).isAir()) {
				roadTop.merge(BlockPos.asLong(b.getX(), 0, b.getZ()), new int[] {b.getY()}, (a, c) -> a[0] >= c[0] ? a : c);
			}
		}
		Map<Long, BlockPos> builtTop = new HashMap<>();
		for (long p : owner.keySet()) {
			BlockPos b = BlockPos.of(p);
			String tr = owner.get(p);
			if (Progression.ROADS.equals(tr) || Progression.LAMPS.equals(tr) || level.getBlockState(b).isAir()) {
				continue;
			}
			builtTop.merge(BlockPos.asLong(b.getX(), 0, b.getZ()), b, (a, c) -> a.getY() >= c.getY() ? a : c);
		}
		StreetPlan plan = StreetPlan.of(level.getServer(), s);
		StringBuilder out = new StringBuilder("# x z base land road road_block built_top built_track plan_half steep (relative to the heart; - = none)\n");
		for (int dx = -70; dx <= 70; dx++) {
			for (int dz = -70; dz <= 70; dz++) {
				int x = h.getX() + dx, z = h.getZ() + dz;
				int i = x - base.x0(), k = z - base.z0();
				if (i < 0 || k < 0 || i >= n || k >= n) {
					continue;
				}
				long col = BlockPos.asLong(x, 0, z);
				int land = SiteWork.groundDeep(level, x, z, h.getY(), owner.keySet());
				int[] rt = roadTop.get(col);
				BlockPos bt = builtTop.get(col);
				out.append(dx).append('\t').append(dz).append('\t').append(base.ground()[i * n + k] - h.getY()).append('\t').append(land - h.getY()).append('\t')
						.append(rt == null ? "-" : String.valueOf(rt[0] - h.getY())).append('\t')
						.append(rt == null ? "-" : name(level.getBlockState(new BlockPos(x, rt[0], z)))).append('\t')
						.append(bt == null ? "-" : String.valueOf(bt.getY() - h.getY())).append('\t').append(bt == null ? "-" : owner.get(bt.asLong())).append('\t')
						.append(plan == null || plan.halfAt(col) == null ? "-" : String.valueOf(plan.halfAt(col))).append('\t')
						.append(plan != null && plan.steep(col) ? "1" : "0").append('\n');
			}
		}
		out.append("# doors: track x y z facing\n");
		for (Map.Entry<Long, String> e : owner.entrySet()) {
			BlockPos b = BlockPos.of(e.getKey());
			BlockState st = level.getBlockState(b);
			if (st.getBlock() instanceof DoorBlock && st.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
				out.append("door\t").append(e.getValue()).append('\t').append(b.getX() - h.getX()).append('\t').append(b.getY() - h.getY()).append('\t')
						.append(b.getZ() - h.getZ()).append('\t').append(st.getValue(DoorBlock.FACING)).append('\n');
			}
		}
		try {
			Files.writeString(dir.resolve(site + "_roads.tsv"), out.toString());
		} catch (IOException ex) {
			KingdomQuest.LOG.error("Field test: roads not written", ex);
		}
	}

	/** A block nobody can see: all six neighbours are full opaque blocks. */
	private static boolean hidden(ServerLevel level, BlockPos p) {
		for (Direction d : Direction.values()) {
			BlockPos q = p.relative(d);
			BlockState st = level.getBlockState(q);
			if (!st.canOcclude() || !st.isCollisionShapeFullBlock(level, q)) {
				return false;
			}
		}
		return true;
	}

	/** Every cell from which {@code seeds} can be walked to (moves taken backwards, {@link #STEP} up and down). */
	static Set<Long> reverse(ServerLevel level, BlockPos h, java.util.Collection<Long> seeds, int cap) {
		Set<Long> out = new HashSet<>(seeds);
		ArrayDeque<long[]> queue = new ArrayDeque<>();
		seeds.forEach(k -> queue.add(new long[] {k}));
		ArrayDeque<long[]> caves = new ArrayDeque<>();
		while ((!queue.isEmpty() || !caves.isEmpty()) && out.size() < cap) {
			long key = (queue.isEmpty() ? caves : queue).poll()[0];
			int x = BlockPos.getX(key), y = BlockPos.getY(key), z = BlockPos.getZ(key);
			if (Math.abs(x - h.getX()) >= R || Math.abs(z - h.getZ()) >= R) {
				continue;
			}
			double f = feet(level, x, y, z, false);
			for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
				for (int dy = 4; dy >= -2; dy--) {
					long nk = BlockPos.asLong(x + d[0], y + dy, z + d[1]);
					if (out.contains(nk)) {
						continue;
					}
					double g = feet(level, x + d[0], y + dy, z + d[1], false);
					if (!Double.isNaN(g) && f - g <= STEP && g - f <= STEP) {
						out.add(nk);
						(underground(level, x + d[0], y + dy, z + d[1]) ? caves : queue).add(new long[] {nk});
					}
				}
			}
		}
		return out;
	}

	/** The smallest climb from the walked area {@code from} into {@code to} (neighbouring columns), and where. */
	private static String gap(ServerLevel level, Settlement s, Set<Long> from, Set<Long> to) {
		double best = Double.MAX_VALUE;
		String where = "";
		for (long b : to) {
			int x = BlockPos.getX(b), y = BlockPos.getY(b), z = BlockPos.getZ(b);
			double fb = feet(level, x, y, z, false);
			for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
				for (int dy = -8; dy <= 8; dy++) {
					long a = BlockPos.asLong(x + d[0], y + dy, z + d[1]);
					if (!from.contains(a)) {
						continue;
					}
					double climb = fb - feet(level, x + d[0], y + dy, z + d[1], false);
					if (climb < best) {
						best = climb;
						BlockPos pa = BlockPos.of(a), pb = BlockPos.of(b);
						where = String.format("climb %.1f from %s to %s (%s under, %s beside)", climb, rel(s, pa), rel(s, pb),
								name(level.getBlockState(pb.below())), name(level.getBlockState(pb.offset(-d[0], 0, -d[1]))));
					}
				}
			}
		}
		return to.size() < 12 ? "enclosed (" + to.size() + " cells) " + where : where.isEmpty() ? "no contact" : where;
	}

	// ---- map ----

	private static void image(ServerLevel level, Settlement s, Snapshot base, List<Mark> marks, Path file) {
		int n = 2 * R + 1;
		int scale = 3;
		BufferedImage img = new BufferedImage(n * 3 * scale + 16, n * scale, BufferedImage.TYPE_INT_RGB);
		Snapshot now = Snapshot.take(level, s.heart(), Set.of());
		for (int half = 0; half < 3; half++) {
			int[] col = half == 0 ? base.colours() : now.colours();
			for (int i = 0; i < n; i++) {
				for (int k = 0; k < n; k++) {
					int v = col[i * n + k];
					int y = v >>> 24;
					int north = k > 0 ? col[i * n + k - 1] >>> 24 : y;
					double shade = y > north ? 1.15 : y < north ? 0.78 : 1.0;
					int rgb = v & 0xFFFFFF;
					int rr = Math.min(255, (int) (((rgb >> 16) & 255) * shade));
					int gg = Math.min(255, (int) (((rgb >> 8) & 255) * shade));
					int bb = Math.min(255, (int) ((rgb & 255) * shade));
					if (half == 2) { // the third panel: grey map with the findings on it
						int grey = (rr * 3 + gg * 5 + bb * 2) / 10 / 2 + 60;
						rr = gg = bb = grey;
					}
					for (int a = 0; a < scale; a++) {
						for (int b = 0; b < scale; b++) {
							img.setRGB(half * (n * scale + 8) + i * scale + a, k * scale + b, rr << 16 | gg << 8 | bb);
						}
					}
				}
			}
		}
		for (Mark m : marks) {
			int i = m.x() - base.x0(), k = m.z() - base.z0();
			if (i < 0 || k < 0 || i >= n || k >= n) {
				continue;
			}
			for (int a = 0; a < scale; a++) {
				for (int b = 0; b < scale; b++) {
					if (a == 1 || b == 1) {
						img.setRGB(2 * (n * scale + 8) + i * scale + a, k * scale + b, m.rgb());
					}
				}
			}
		}
		try {
			javax.imageio.ImageIO.write(img, "png", file.toFile());
		} catch (IOException e) {
			KingdomQuest.LOG.error("Field test: map not written", e);
		}
	}
}
