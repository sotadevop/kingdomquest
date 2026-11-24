package io.github.sotadevop.kingdomquest.kingdom;

import com.mojang.datafixers.util.Pair;
import io.github.sotadevop.kingdomquest.KingdomQuest;
import io.github.sotadevop.kingdomquest.building.Template;
import io.github.sotadevop.kingdomquest.npc.KingdomNpc;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Field test sites "roads", "roads_frozen", "roads_arid", "roads_highland", "roads_wild" ({@code [fieldtest roads]}):
 * three young kingdoms in the region's land, A with roads to B (about 640 blocks away) and to C (about 360). The roads
 * are built, then A's road level is raised from 1 to 3; after each level the waystation and the road's extras are
 * measured: the station's blocks against its template, blocks hanging over nothing, steps in the land around it,
 * the walk from the road to the inn's bed and through the arch, the signs and what they say (also after B is
 * renamed), border stones, signposts and shrines at the crossroads, milestones, bridge and hill towers, the station's
 * guards and horses, and the signal fires. Views for tools/render_view.py are written for each part.
 */
final class RoadTest {
	private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000a0ad");

	private RoadTest() {
	}

	static boolean run(MinecraftServer server, FieldTest.Scenario sc, int index, StringBuilder report, List<String> summary, Path dir) {
		ServerLevel level = server.overworld();
		String name = sc.name();
		report.append("## ").append(name).append("\n\n");
		BlockPos a = site(level, sc, new BlockPos(-4000 + index * 900, 80, 6000));
		if (a == null) {
			report.append("No site found.\n\n");
			summary.add("| " + name + " | not found |");
			return true;
		}
		BlockPos b = null, c = null;
		for (int k = 0; k < 16 && (b == null || c == null); k++) {
			double ang = k * Math.PI / 8;
			if (b == null) {
				b = dry(level, a.offset((int) Math.round(Math.cos(ang) * 640), 0, (int) Math.round(Math.sin(ang) * 640)));
				if (b != null) {
					continue;
				}
			}
			if (b != null && c == null) {
				double bang = Math.atan2(b.getZ() - a.getZ(), b.getX() - a.getX());
				c = dry(level, a.offset((int) Math.round(Math.cos(bang + Math.PI / 2 + k * 0.2) * 360), 0, (int) Math.round(Math.sin(bang + Math.PI / 2 + k * 0.2) * 360)));
			}
		}
		if (b == null || c == null) {
			report.append("No dry land for the other kingdoms around ").append(a.toShortString()).append(".\n\n");
			summary.add("| " + name + " | no sites |");
			return true;
		}
		String biome = level.getBiome(a).getRegisteredName();
		Settlement sa = found(server, level, a, "FT Roads A");
		Settlement sb = found(server, level, b, "FT Roads B");
		Settlement sc2 = found(server, level, c, "FT Roads C");
		report.append(biome).append(", style ").append(Style.forBiome(biome)).append(". A at ").append(a.toShortString()).append(", B at ").append(b.toShortString())
				.append(", C at ").append(c.toShortString()).append(".\n\n");
		List<String> problems = new ArrayList<>();
		long t0 = System.nanoTime();
		for (Settlement to : List.of(sb, sc2)) {
			RoadLink.link(server, null, sa, to.id().toString(), RoadLink.gateExit(to), to.name());
			Roadside.planned(server, sa, to.id().toString(), RoadLink.gateExit(sa), RoadLink.gateExit(to));
		}
		RoadLink.runAll(server);
		long buildMs = (System.nanoTime() - t0) / 1_000_000;
		report.append("Roads built in ").append(buildMs).append(" ms: A-B ").append(Roadside.length(byId(server, sa.id()), sb.id().toString())).append(" blocks, A-C ")
				.append(Roadside.length(byId(server, sa.id()), sc2.id().toString())).append(" blocks.\n\n");
		report.append("| road level | stations | blocks as planned | hanging | steps around | walk: bed / arch | signs (named) | border stones | signposts | shrines | milestones | bridge towers | hill towers | guards | horses | signal fires |\n")
				.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
		Map<String, BlockPos> views = new LinkedHashMap<>();
		for (int lvl = 1; lvl <= 3; lvl++) {
			Settlement now = byId(server, sa.id());
			if (lvl > 1) {
				Settlements.putState(server, now.withLevel(Progression.ROADS, lvl));
				if (lvl == 3) {
					RoadLink.upgradeAll(server, byId(server, sa.id()));
					RoadLink.runAll(server);
				}
				Roadside.update(server, byId(server, sa.id()));
			}
			Roadside.upkeep(server);
			measure(server, level, byId(server, sa.id()), lvl, report, problems, views);
		}
		// a kingdom is renamed: its signs follow
		Settlements.putState(server, byId(server, sb.id()).renamed("Eastmarch"));
		Roadside.refreshSigns(server);
		int renamed = 0, signs = 0;
		for (Map.Entry<String, String> e : byId(server, sa.id()).names().entrySet()) {
			if (e.getKey().startsWith(Roadside.SIGN) && e.getValue().contains("s:" + sb.id())) {
				signs++;
				renamed += text(level, pos(e.getKey().substring(Roadside.SIGN.length()))).contains("Eastmarch") ? 1 : 0;
			}
		}
		report.append("\nAfter renaming B to Eastmarch: ").append(renamed).append(" of ").append(signs).append(" signs naming it show the new name.\n\n");
		if (signs == 0 || renamed < signs) {
			problems.add("renamed place: " + renamed + " of " + signs + " signs updated");
		}
		for (Map.Entry<String, BlockPos> e : views.entrySet()) {
			FealtyTest.view(level, e.getValue(), e.getKey().startsWith("station") ? 26 : 8, dir.resolve(name + "_view_" + e.getKey() + ".json"));
			report.append("- view ").append(e.getKey()).append(" at ").append(e.getValue().toShortString()).append('\n');
		}
		report.append('\n');
		for (String p : problems) {
			report.append("- ").append(p).append('\n');
		}
		report.append('\n');
		for (String line : report.substring(Math.max(0, report.lastIndexOf("## " + name))).split("\n")) {
			if (!line.isBlank()) {
				KingdomQuest.LOG.info("Field test {} | {}", name, line);
			}
		}
		summary.add("| " + name + " | " + (problems.isEmpty() ? "ok" : problems.size() + " findings") + " |");
		return problems.isEmpty();
	}

	private static BlockPos pos(String s) {
		String[] p = s.split(",");
		return new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
	}

	private static Settlement byId(MinecraftServer server, UUID id) {
		for (Settlement s : Settlements.all(server)) {
			if (s.id().equals(id)) {
				return s;
			}
		}
		return null;
	}

	/** Land of the scenario's biomes with dry ground. */
	private static BlockPos site(ServerLevel level, FieldTest.Scenario sc, BlockPos origin) {
		Pair<BlockPos, Holder<Biome>> f = level.findClosestBiome3d(h -> sc.biomes().contains(h.getRegisteredName().replace("minecraft:", "")), origin, 6000, 32,
				64);
		if (f == null) {
			return null;
		}
		for (int r = 0; r <= 96; r += 16) {
			for (int dx = -r; dx <= r; dx += 16) {
				for (int dz = -r; dz <= r; dz += 16) {
					BlockPos d = dry(level, f.getFirst().offset(dx, 0, dz));
					if (d != null) {
						return d;
					}
				}
			}
		}
		return null;
	}

	/** The ground position at x/z if it is dry natural ground, else null. */
	private static BlockPos dry(ServerLevel level, BlockPos p) {
		level.getChunk(p.getX() >> 4, p.getZ() >> 4);
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
		BlockState top = level.getBlockState(new BlockPos(p.getX(), y - 1, p.getZ()));
		return top.getFluidState().isEmpty() && SiteWork.isGround(top) ? new BlockPos(p.getX(), y, p.getZ()) : null;
	}

	private static Settlement found(MinecraftServer server, ServerLevel level, BlockPos heart, String name) {
		String style = Style.forBiome(level.getBiome(heart).getRegisteredName());
		Settlement s = new Settlement(UUID.randomUUID(), OWNER, name, 11, Identifier.withDefaultNamespace("stone_bricks"), style, heart, 0,
				Map.of(Settlement.MAIN, 1), style + "/tent_1", Settlement.MAIN, 0, Map.of(), Map.of());
		Settlements.prepareSite(level, heart, Template.get(s.job()), Rotation.NONE);
		Settlements.put(server, s);
		for (int n = 0; n < 20 && s != null && !s.job().isEmpty(); n++) {
			Settlements.step(server, s, true);
			s = Settlements.byHeart(server, heart);
		}
		return s;
	}

	private static String text(ServerLevel level, BlockPos at) {
		if (!(level.getBlockEntity(at) instanceof SignBlockEntity be)) {
			return "";
		}
		// SignText's accessors changed between versions: find the one that returns the lines (test code only)
		Object t = be.getText(net.minecraft.world.level.block.entity.SignTextSlot.values()[0]);
		StringBuilder seen = new StringBuilder();
		for (java.lang.reflect.Method m : t.getClass().getMethods()) {
			seen.append(m.getName()).append(':').append(m.getReturnType().getSimpleName()).append(' ');
			Object[] args = m.getParameterCount() == 0 ? new Object[0]
					: m.getParameterCount() == 1 && m.getParameterTypes()[0] == boolean.class ? new Object[] {false} : null;
			boolean array = m.getReturnType() == net.minecraft.network.chat.Component[].class;
			if (args == null || !(array || java.util.List.class.isAssignableFrom(m.getReturnType())) || m.getDeclaringClass() == Object.class) {
				continue;
			}
			try {
				Object r = m.invoke(t, args);
				List<?> lines = array ? java.util.Arrays.asList((Object[]) r) : (List<?>) r;
				if (lines.isEmpty() || !(lines.get(0) instanceof net.minecraft.network.chat.Component)) {
					continue;
				}
				StringBuilder b = new StringBuilder();
				for (Object line : lines) {
					b.append(((net.minecraft.network.chat.Component) line).getString()).append(" / ");
				}
				return b.toString();
			} catch (ReflectiveOperationException | ClassCastException e) {
				// try the next accessor
			}
		}
		KingdomQuest.LOG.warn("RoadTest: no sign line accessor on {}: {}", t.getClass().getName(), seen);
		return "";
	}

	private static void measure(MinecraftServer server, ServerLevel level, Settlement s, int lvl, StringBuilder report, List<String> problems,
			Map<String, BlockPos> views) {
		List<Roadside.Station> stations = Roadside.stations(s);
		int placed = 0, wrong = 0, hanging = 0, steps = 0;
		String walk = "";
		int guards = 0, horses = 0;
		for (Roadside.Station st : stations) {
			views.put("station" + st.n() + "_l" + lvl, st.origin());
			if (st.level() != lvl) {
				problems.add("road level " + lvl + ": station " + st.n() + " is at level " + st.level());
			}
			Template t = Template.get(st.style() + "/waystation_" + st.level());
			if (t == null) {
				continue;
			}
			Map<Long, Integer> low = new java.util.HashMap<>();
			BlockPos bed = null;
			for (Template.Placement p : t.placements(Settlements.dye(s.color()), st.rot())) {
				BlockPos at = st.origin().offset(p.offset());
				if (p.state().isAir()) {
					continue;
				}
				placed++;
				if (level.getBlockState(at).getBlock() != p.state().getBlock()) {
					wrong++;
				}
				low.merge(BlockPos.asLong(at.getX(), 0, at.getZ()), at.getY(), Math::min);
				if (p.state().getBlock() instanceof BedBlock) {
					bed = at;
				}
			}
			for (Map.Entry<Long, Integer> e : low.entrySet()) {
				BlockPos under = new BlockPos(BlockPos.getX(e.getKey()), e.getValue() - 1, BlockPos.getZ(e.getKey()));
				BlockState u = level.getBlockState(under);
				if (u.isAir() || !u.getFluidState().isEmpty()) {
					hanging++;
				}
			}
			// the land around: steps of 3 or more between neighbouring columns within 20 of the site
			for (int dx = -30; dx <= 30; dx++) {
				for (int dz = -30; dz <= 30; dz++) {
					int x = st.origin().getX() + dx, z = st.origin().getZ() + dz;
					int h0 = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
					int h1 = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + 1, z);
					int h2 = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z + 1);
					steps += Math.abs(h0 - h1) >= 3 || Math.abs(h0 - h2) >= 3 ? 1 : 0;
				}
			}
			// the walk from the road at the station's end of the straight stretch
			BlockPos start = st.origin().offset(Template.rotate(new BlockPos(0, 0, -Roadside.STRAIGHT + 1), st.rot()));
			Set<Long> reach = new HashSet<>();
			ArrayDeque<long[]> queue = new ArrayDeque<>();
			for (int dy = -2; dy <= 3; dy++) {
				if (!Double.isNaN(FieldTest.feet(level, start.getX(), start.getY() + dy, start.getZ(), false)) && reach.add(start.above(dy).asLong())) {
					queue.add(new long[] {start.above(dy).asLong()});
				}
			}
			FieldTest.spread(level, st.origin(), reach, queue, Set.of(), 120_000);
			boolean bedOk = bed == null || near(reach, bed, 2);
			BlockPos through = st.origin().offset(Template.rotate(new BlockPos(0, 0, Roadside.STRAIGHT - 1), st.rot()));
			boolean archOk = near(reach, through, 1);
			walk += (bed == null ? "-" : bedOk ? "yes" : "NO") + " / " + (archOk ? "yes" : "NO") + " ";
			if (!bedOk) {
				problems.add("road level " + lvl + ": the inn's bed at " + bed.toShortString() + " can't be walked to from the road");
			}
			if (!archOk) {
				problems.add("road level " + lvl + ": the road through the station at " + st.origin().toShortString() + " can't be walked");
			}
			AABB box = new AABB(st.origin()).inflate(40, 16, 40);
			guards += level.getEntitiesOfClass(KingdomNpc.class, box, g -> g.isAlive() && st.tag().equals(People.role(g))).size();
			String animal = "way_animal|" + st.owner().id() + "|" + st.key() + "|" + st.n();
			horses += level.getEntities((net.minecraft.world.entity.Entity) null, box, e -> e.isAlive() && animal.equals(People.role(e))).size();
		}
		int signs = 0, named = 0, stones = 0, posts = 0, shrines = 0, miles = 0, btowers = 0, wtowers = 0;
		for (Map.Entry<String, String> e : s.names().entrySet()) {
			if (e.getKey().startsWith(Roadside.SIGN)) {
				signs++;
				String txt = text(level, pos(e.getKey().substring(Roadside.SIGN.length())));
				named += txt.contains("FT Roads") ? 1 : 0;
				if (named == 1 && !views.containsKey("sign_l" + lvl)) {
					views.put("sign_l" + lvl, pos(e.getKey().substring(Roadside.SIGN.length())));
					KingdomQuest.LOG.info("Field test roads: sign at {} reads: {}", e.getKey(), txt);
				}
			}
			if (e.getKey().startsWith(Roadside.EXTRAS)) {
				for (String x : e.getValue().split(";")) {
					stones += x.startsWith("stone@") ? 1 : 0;
					miles += x.startsWith("mile@") ? 1 : 0;
					btowers += x.startsWith("btower@") ? 1 : 0;
					wtowers += x.startsWith("wtower@") ? 1 : 0;
					String[] p = x.split("@");
					if ((x.startsWith("btower@") || x.startsWith("wtower@")) && p.length > 2) {
						views.putIfAbsent(p[0] + "_l" + lvl, pos(p[1]));
					}
				}
			}
			if (e.getKey().startsWith(Roadside.JUNCTION)) {
				for (String x : e.getValue().split(";")) {
					String[] p = x.split("@");
					if (x.startsWith("post@")) {
						posts++;
						views.putIfAbsent("signpost", pos(p[1]));
					}
					if (x.startsWith("shrine@")) {
						shrines++;
						views.putIfAbsent("shrine", pos(p[1]));
					}
				}
			}
		}
		List<net.minecraft.core.BlockPos> fires = Roadside.fires(s);
		int lit = 0;
		for (BlockPos f : fires) {
			lit += level.getBlockState(f).is(net.minecraft.world.level.block.Blocks.CAMPFIRE) ? 1 : 0;
		}
		boolean chained = SignalFires.fires(s).containsAll(fires);
		report.append("| ").append(lvl).append(" | ").append(stations.size()).append(" | ").append(placed - wrong).append(" of ").append(placed).append(" | ")
				.append(hanging).append(" | ").append(steps).append(" | ").append(walk.trim()).append(" | ").append(signs).append(" (").append(named).append(") | ")
				.append(stones).append(" | ").append(posts).append(" | ").append(shrines).append(" | ").append(miles).append(" | ").append(btowers).append(" | ")
				.append(wtowers).append(" | ").append(guards).append(" | ").append(horses).append(" | ").append(lit).append(" of ").append(fires.size())
				.append(chained ? "" : " NOT IN CHAIN").append(" |\n");
		if (stations.isEmpty()) {
			problems.add("road level " + lvl + ": no waystation");
		}
		if (placed > 0 && wrong * 50 > placed) {
			problems.add("road level " + lvl + ": " + wrong + " of " + placed + " station blocks not as planned");
		}
		if (hanging > 0) {
			problems.add("road level " + lvl + ": " + hanging + " station columns hang over nothing");
		}
		if (signs == 0 || named < signs) {
			problems.add("road level " + lvl + ": " + named + " of " + signs + " signs name a place");
		}
		if (stones == 0) {
			problems.add("road level " + lvl + ": no border stones");
		}
		if (posts == 0) {
			problems.add("road level " + lvl + ": no signpost where the roads part at A's gate");
		}
		if (lvl >= 2 && (miles == 0 || shrines == 0)) {
			problems.add("road level " + lvl + ": milestones " + miles + ", shrines " + shrines);
		}
		if (lvl >= 2 && horses == 0 && !stations.isEmpty()) {
			problems.add("road level " + lvl + ": no horses at the station");
		}
		if (lvl >= 3 && (guards < 2 * stations.size() || fires.isEmpty() || lit < fires.size() || !chained)) {
			problems.add("road level 3: guards " + guards + ", signal fires " + lit + " of " + fires.size() + (chained ? "" : ", not in the chain"));
		}
	}

	private static boolean near(Set<Long> reach, BlockPos p, int r) {
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dy = -1; dy <= 2; dy++) {
					if (reach.contains(BlockPos.asLong(p.getX() + dx, p.getY() + dy, p.getZ() + dz))) {
						return true;
					}
				}
			}
		}
		return false;
	}
}
