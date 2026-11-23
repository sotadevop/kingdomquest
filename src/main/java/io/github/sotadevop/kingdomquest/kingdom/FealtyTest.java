package io.github.sotadevop.kingdomquest.kingdom;

import com.google.gson.JsonObject;
import io.github.sotadevop.kingdomquest.KingdomQuest;
import io.github.sotadevop.kingdomquest.building.Template;
import io.github.sotadevop.kingdomquest.npc.KingdomNpc;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Field test site "fealty" ({@code [fieldtest fealty]}): a real generated village and a kingdom founded 240 blocks
 * away. The Fealty Ceremony runs at the village's bell with the world ticking (so the villagers really walk), then
 * the road is built and the result is measured: villagers at the bell per ring, the banner on its pole, the two
 * guards at their posts, the oath and the chronicle entry in the data, the road, the border stones. Views of the bell
 * and of every border stone are written for tools/render_view.py.
 */
final class FealtyTest {
	private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000fea1");

	private FealtyTest() {
	}

	static boolean run(MinecraftServer server, StringBuilder report, List<String> summary, Path dir) {
		ServerLevel level = server.overworld();
		report.append("## fealty\n\n");
		BlockPos found = level.findNearestMapStructure(StructureTags.VILLAGE, new BlockPos(1500, 80, -1500), 100, false);
		if (found == null) {
			report.append("No village found.\n\n");
			summary.add("| fealty | not found |");
			return true;
		}
		for (int cx = (found.getX() - 112) >> 4; cx <= (found.getX() + 112) >> 4; cx++) {
			for (int cz = (found.getZ() - 112) >> 4; cz <= (found.getZ() + 112) >> 4; cz++) {
				level.setChunkForced(cx, cz, true);
				level.getChunk(cx, cz);
			}
		}
		List<String> problems = new ArrayList<>();
		BlockPos bell = null;
		for (int x = found.getX() - 96; x <= found.getX() + 96; x++) {
			for (int z = found.getZ() - 96; z <= found.getZ() + 96; z++) {
				int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
				for (int y = top - 8; y <= top + 1; y++) {
					BlockPos p = new BlockPos(x, y, z);
					if (level.getBlockState(p).is(Blocks.BELL) && (bell == null || p.distSqr(found) < bell.distSqr(found))) {
						bell = p;
					}
				}
			}
		}
		StructureStart village = bell == null ? null : Fealty.villageAt(level, bell);
		if (village == null) {
			report.append("Village at ").append(found.toShortString()).append(" has no bell in a village structure.\n\n");
			summary.add("| fealty | no bell |");
			return false;
		}
		String biome = level.getBiome(bell).getRegisteredName();
		String style = Style.forBiome(biome);
		// the kingdom: on dry land 240 blocks away, a tent like a newly founded one
		BlockPos heart = null;
		for (int a = 0; a < 16 && heart == null; a++) {
			int x = bell.getX() + (int) Math.round(Math.cos(a * Math.PI / 8) * 240);
			int z = bell.getZ() + (int) Math.round(Math.sin(a * Math.PI / 8) * 240);
			level.getChunk(x >> 4, z >> 4); // heights of chunks not loaded read as the bottom of the world
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			BlockState top = level.getBlockState(new BlockPos(x, y - 1, z));
			if (top.getFluidState().isEmpty() && SiteWork.isGround(top)) {
				heart = new BlockPos(x, y, z);
			}
		}
		if (heart == null) {
			report.append("No dry land for the kingdom around the village.\n\n");
			summary.add("| fealty | no kingdom site |");
			return false;
		}
		String kStyle = Style.forBiome(level.getBiome(heart).getRegisteredName());
		Settlement s = new Settlement(UUID.randomUUID(), OWNER, "FT Fealty", 11, Identifier.withDefaultNamespace("stone_bricks"), kStyle, heart, 0,
				Map.of(Settlement.MAIN, 1), kStyle + "/tent_1", Settlement.MAIN, 0, Map.of(), Map.of());
		Settlements.prepareSite(level, heart, Template.get(s.job()), Rotation.NONE);
		Settlements.put(server, s);
		for (int n = 0; n < 20 && s != null && !s.job().isEmpty(); n++) {
			Settlements.step(server, s, true);
			s = Settlements.byHeart(server, heart);
		}
		JsonObject info = Fealty.info(level, null, s, village);
		String name = info.get("name").getAsString();
		int villagers = info.get("villagers").getAsInt();
		report.append(biome).append(" village (style ").append(style).append(") with its bell at ").append(bell.toShortString()).append(", ")
				.append(villagers).append(" villagers. Kingdom at ").append(heart.toShortString()).append(". Menu: name suggested \"").append(name)
				.append("\".\n\n");
		if (villagers == 0) {
			problems.add("no villagers counted");
		}
		List<net.minecraft.world.entity.npc.villager.Villager> people = Fealty.villagers(level, village);
		BlockPos at = bell;
		StringBuilder before = new StringBuilder();
		people.forEach(v -> before.append(Math.round(Math.sqrt(v.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(at))))).append(' '));
		long t0 = System.nanoTime();
		Fealty.Ceremony c = Fealty.begin(server, s, null, village, bell, name);
		boolean ticking = true;
		int ticks = 0;
		for (; ticks < Fealty.GATHER_MAX + Fealty.END + 10 && Fealty.CEREMONIES.containsKey(bell.asLong()); ticks++) {
			if (ticking) {
				try {
					level.tick(() -> true); // the world moves on, so the villagers walk to the bell
				} catch (Throwable e) {
					problems.add("world tick failed (villagers can't walk in this test): " + e);
					ticking = false;
				}
			}
			Fealty.ceremonies(server);
		}
		long ms = (System.nanoTime() - t0) / 1_000_000;
		StringBuilder after = new StringBuilder();
		people.forEach(v -> after.append(Math.round(Math.sqrt(v.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(at))))).append(' '));
		report.append("Villagers' distance to the bell before: ").append(before.toString().trim()).append("; at the end: ").append(after.toString().trim())
				.append(". Gathering took ").append(c.ringAt).append(" ticks.\n\n");
		RoadLink.runAll(server);
		Fealty.upkeep(server);
		s = Settlements.byHeart(server, heart);
		// measure
		String key = null;
		for (Fealty.Vassal v : Fealty.vassals(server)) {
			if (v.liege().id().equals(s.id())) {
				key = v.key();
			}
		}
		if (key == null) {
			problems.add("the oath was not saved");
			key = c.key;
		}
		boolean chronicle = s.names().entrySet().stream().anyMatch(e -> e.getKey().startsWith(Fealty.CHRONICLE) && e.getValue().contains("|fealty|"));
		if (!chronicle) {
			problems.add("no chronicle entry");
		}
		if (c.rung != Fealty.RINGS) {
			problems.add("the bell rang " + c.rung + " times");
		}
		if (c.gathered[Fealty.RINGS - 1] < Math.max(1, villagers / 2)) {
			problems.add("only " + c.gathered[Fealty.RINGS - 1] + " of " + villagers + " villagers at the bell at the last ring");
		}
		String banner = "none";
		if (c.pole == null) {
			problems.add("no spot for the banner pole");
		} else {
			BlockState top = level.getBlockState(c.pole.above(4));
			banner = BuiltInRegistries.BLOCK.getKey(top.getBlock()).getPath() + " on " + BuiltInRegistries.BLOCK.getKey(level.getBlockState(c.pole.above(3))
					.getBlock()).getPath() + " at " + c.pole.above(4).toShortString();
			if (!(top.getBlock() instanceof BannerBlock) || !BuiltInRegistries.BLOCK.getKey(top.getBlock()).getPath().equals(Settlements.dye(s.color()) + "_banner")) {
				problems.add("no kingdom banner on the pole: " + banner);
			}
		}
		String tag = Fealty.GUARD + "|" + s.id() + "|" + key;
		List<KingdomNpc> guards = level.getEntitiesOfClass(KingdomNpc.class, new AABB(bell).inflate(96, 48, 96), g -> g.isAlive() && tag.equals(People.role(g)));
		StringBuilder posted = new StringBuilder();
		for (KingdomNpc g : guards) {
			BlockPos post = BlockPos.of(g.post());
			double off = Math.sqrt(g.distanceToSqr(post.getX() + 0.5, post.getY(), post.getZ() + 0.5));
			posted.append(String.format("%.1f", off)).append(' ');
			if (off > 4) {
				problems.add("a guard stands " + String.format("%.1f", off) + " blocks from his post");
			}
		}
		if (guards.size() != 2) {
			problems.add(guards.size() + " guards at the village");
		}
		boolean road = s.names().containsKey(RoadLink.PREFIX + "v:" + key);
		int pathNear = 0;
		for (BlockPos p : BlockPos.betweenClosed(c.road.offset(-2, -3, -2), c.road.offset(2, 3, 2))) {
			BlockState st = level.getBlockState(p);
			if (st.is(Blocks.DIRT_PATH) || st.is(Blocks.COARSE_DIRT) || st.is(Blocks.GRAVEL) || st.is(Blocks.COBBLESTONE)) {
				pathNear++;
			}
		}
		if (!road || pathNear == 0) {
			problems.add("no road to the village (stored " + road + ", road blocks at its end " + pathNear + ")");
		}
		List<Long> blocks = Fealty.blocks(s, key);
		List<BlockPos> stones = new ArrayList<>();
		for (long l : blocks) {
			BlockPos p = BlockPos.of(l);
			if (level.getBlockState(p).getBlock() instanceof WallBannerBlock) {
				stones.add(p);
			}
		}
		if (stones.isEmpty()) {
			problems.add("no border stones");
		}
		report.append("| ticks | ms | villagers at the bell per ring | banner | guards (blocks off their post) | street ends | road end | border stones |\n")
				.append("|---|---|---|---|---|---|---|---|\n| ").append(ticks).append(" | ").append(ms).append(" | ").append(c.gathered[0]).append(", ")
				.append(c.gathered[1]).append(", ").append(c.gathered[2]).append(" of ").append(villagers).append(" | ").append(banner).append(" | ")
				.append(guards.size()).append(" (").append(posted.toString().trim()).append(") | ").append(c.ends.size()).append(" | ")
				.append(c.road.toShortString()).append(" | ").append(stones.size()).append(" |\n\n");
		Map<String, BlockPos> views = new LinkedHashMap<>();
		views.put("bell", bell);
		for (int i = 0; i < stones.size(); i++) {
			views.put("stone" + (i + 1), stones.get(i));
		}
		for (Map.Entry<String, BlockPos> e : views.entrySet()) {
			view(level, e.getValue(), e.getKey().equals("bell") ? 12 : 6, dir.resolve("fealty_view_" + e.getKey() + ".json"));
			report.append("- view ").append(e.getKey()).append(" at ").append(e.getValue().toShortString()).append('\n');
		}
		report.append('\n');
		for (String p : problems) {
			report.append("- ").append(p).append('\n');
		}
		report.append('\n');
		KingdomQuest.LOG.info("Field test fealty: {} villagers, at the bell {}/{}/{}, guards {}, stones {}, problems {}", villagers, c.gathered[0], c.gathered[1],
				c.gathered[2], guards.size(), stones.size(), problems.size());
		summary.add("| fealty | " + (problems.isEmpty() ? "ok" : problems.size() + " findings") + " |");
		return problems.isEmpty();
	}

	/** The blocks around a point as a view for tools/render_view.py (same format as FieldTest's views). */
	private static void view(ServerLevel level, BlockPos c, int half, Path file) {
		int lo = c.getY() - 6;
		int hi = c.getY() + 10;
		Map<BlockState, Integer> pal = new LinkedHashMap<>();
		StringBuilder blocks = new StringBuilder();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int x = -half; x <= half; x++) {
			for (int z = -half; z <= half; z++) {
				for (int y = lo; y <= hi; y++) {
					BlockState st = level.getBlockState(p.set(c.getX() + x, y, c.getZ() + z));
					if (st.isAir()) {
						continue;
					}
					int idx = pal.computeIfAbsent(st, k -> pal.size());
					blocks.append(blocks.isEmpty() ? "" : ",").append('[').append(x + half).append(',').append(y - lo).append(',').append(z + half).append(',')
							.append(idx).append(']');
				}
			}
		}
		StringBuilder b = new StringBuilder("{\"size\":[").append(2 * half + 1).append(',').append(hi - lo + 1).append(',').append(2 * half + 1)
				.append("],\"centre\":\"").append(c.toShortString()).append("\",\"palette\":[");
		boolean first = true;
		for (BlockState st : pal.keySet()) {
			b.append(first ? "" : ",").append('"').append(st.toString().replace("\"", "")).append('"');
			first = false;
		}
		b.append("],\"blocks\":[").append(blocks).append("]}");
		try {
			Files.writeString(file, b.toString());
		} catch (IOException e) {
			KingdomQuest.LOG.error("Field test: view not written", e);
		}
	}
}
