package io.github.sotadevop.kingdomquest.kingdom;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.sotadevop.kingdomquest.npc.KingdomNpc;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The realm: all of a lord's settlements under one empire name, the villages he knows and the roads between
 * them, plus a register of one settlement's buildings, people and animals. Names live in the settlements'
 * {@code names} map: "empire" (on each settlement), "village|x,z" (villages found from that settlement),
 * "link|target" (roads, see {@link RoadLink}).
 */
final class Realm {
	private Realm() {
	}

	private static List<Settlement> owned(MinecraftServer server, UUID owner) {
		return Settlements.all(server).stream().filter(o -> o.owner().equals(owner)).toList();
	}

	static String empire(MinecraftServer server, ServerPlayer player) {
		for (Settlement o : owned(server, player.getUUID())) {
			String e = o.names().get("empire");
			if (e != null) {
				return e;
			}
		}
		return player.getName().getString() + "'s Realm";
	}

	static void send(ServerPlayer player, Settlement here) {
		MinecraftServer server = player.level().getServer();
		ServerLevel level = server.overworld();
		JsonObject j = new JsonObject();
		j.addProperty("empire", empire(server, player));
		j.addProperty("here", here.id().toString());
		j.addProperty("hx", here.heart().getX());
		j.addProperty("hz", here.heart().getZ());
		j.addProperty("px", (int) player.getX());
		j.addProperty("pz", (int) player.getZ());
		JsonArray settlements = new JsonArray();
		JsonArray villages = new JsonArray();
		JsonArray links = new JsonArray();
		for (Settlement o : owned(server, player.getUUID())) {
			JsonObject e = new JsonObject();
			e.addProperty("id", o.id().toString());
			e.addProperty("name", o.name());
			e.addProperty("x", o.heart().getX());
			e.addProperty("z", o.heart().getZ());
			e.addProperty("color", o.color());
			e.addProperty("level", o.level(Settlement.MAIN));
			settlements.add(e);
			for (Map.Entry<String, String> n : o.names().entrySet()) {
				if (n.getKey().startsWith("village|")) {
					String[] p = n.getKey().substring(8).split(",");
					JsonObject v = new JsonObject();
					v.addProperty("key", "v:" + p[0] + "," + p[1]);
					v.addProperty("name", n.getValue());
					v.addProperty("x", Integer.parseInt(p[0]));
					v.addProperty("z", Integer.parseInt(p[1]));
					villages.add(v);
				} else if (n.getKey().startsWith(RoadLink.PREFIX)) {
					JsonObject l = new JsonObject();
					l.addProperty("from", o.id().toString());
					l.addProperty("to", n.getKey().substring(RoadLink.PREFIX.length()));
					String[] p = n.getValue().split(",");
					l.addProperty("x", Integer.parseInt(p[0]));
					l.addProperty("z", Integer.parseInt(p[2]));
					links.add(l);
				}
			}
		}
		j.add("settlements", settlements);
		j.add("villages", villages);
		j.add("links", links);
		// the land under it all: everything the realm reaches, with room around it
		int minX = Math.min(here.heart().getX(), (int) player.getX());
		int maxX = Math.max(here.heart().getX(), (int) player.getX());
		int minZ = Math.min(here.heart().getZ(), (int) player.getZ());
		int maxZ = Math.max(here.heart().getZ(), (int) player.getZ());
		for (JsonArray list : List.of(settlements, villages, links)) {
			for (com.google.gson.JsonElement e : list) {
				int x = e.getAsJsonObject().get("x").getAsInt();
				int z = e.getAsJsonObject().get("z").getAsInt();
				minX = Math.min(minX, x);
				maxX = Math.max(maxX, x);
				minZ = Math.min(minZ, z);
				maxZ = Math.max(maxZ, z);
			}
		}
		WorldMap.refresh(level, here.heart(), 7);
		j.add("map", WorldMap.layer(server, minX - 320, minZ - 320, maxX + 320, maxZ + 320));
		// buildings of this settlement
		JsonArray buildings = new JsonArray();
		String last = here.names().getOrDefault("last_built", "");
		for (String track : Progression.ORDER) {
			Progression.Step step = Progression.current(here, track);
			int[] b = Landmarks.bounds(here, track);
			if (step == null || b == null) {
				continue;
			}
			JsonObject e = new JsonObject();
			e.addProperty("track", track);
			e.addProperty("title", step.titleKey());
			e.addProperty("level", step.level());
			e.addProperty("x0", b[0]);
			e.addProperty("z0", b[1]);
			e.addProperty("x1", b[2]);
			e.addProperty("z1", b[3]);
			e.addProperty("last", track.equals(last));
			e.addProperty("area", !Settlements.LIFTED.contains(track) && !Progression.LAIR.equals(track) && !Progression.DOCK.equals(track));
			buildings.add(e);
		}
		j.add("buildings", buildings);
		int bx0 = here.heart().getX() - 40;
		int bx1 = here.heart().getX() + 40;
		int bz0 = here.heart().getZ() - 40;
		int bz1 = here.heart().getZ() + 40;
		for (com.google.gson.JsonElement e : buildings) {
			JsonObject b = e.getAsJsonObject();
			bx0 = Math.min(bx0, b.get("x0").getAsInt());
			bz0 = Math.min(bz0, b.get("z0").getAsInt());
			bx1 = Math.max(bx1, b.get("x1").getAsInt());
			bz1 = Math.max(bz1, b.get("z1").getAsInt());
		}
		int cxm = (bx0 + bx1) / 2;
		int czm = (bz0 + bz1) / 2;
		j.add("plan", WorldMap.plan(level, Math.max(bx0 - 12, cxm - 99), Math.max(bz0 - 12, czm - 99), Math.min(bx1 + 12, cxm + 99), Math.min(bz1 + 12, czm + 99)));
		// people and animals around it
		JsonArray people = new JsonArray();
		JsonArray animals = new JsonArray();
		String id = here.id().toString();
		AABB box = new AABB(here.heart()).inflate(140, 64, 140);
		for (KingdomNpc npc : level.getEntitiesOfClass(KingdomNpc.class, box, Entity::isAlive)) {
			String role = People.role(npc);
			boolean ours = role.contains(id) || player.getUUID().equals(npc.lord()) && !npc.role().equals("raider");
			if (!ours || role.startsWith("raider")) {
				continue;
			}
			JsonObject e = new JsonObject();
			e.addProperty("uuid", npc.getUUID().toString());
			e.addProperty("name", npc.getCustomName() == null ? npc.role() : npc.getCustomName().getString());
			e.addProperty("role", roleName(npc, role));
			e.addProperty("skin", npc.skin());
			e.addProperty("x", npc.getBlockX());
			e.addProperty("y", npc.getBlockY());
			e.addProperty("z", npc.getBlockZ());
			people.add(e);
		}
		for (Mob m : level.getEntitiesOfClass(Mob.class, box, e -> e.isAlive() && !(e instanceof KingdomNpc))) {
			String role = People.role(m);
			boolean tamedHere = m instanceof net.minecraft.world.entity.TamableAnimal t && t.isTame() && t.isOwnedBy(player);
			if (!role.contains(id) && !tamedHere) {
				continue;
			}
			JsonObject e = new JsonObject();
			e.addProperty("name", m.getCustomName() == null ? m.getType().getDescription().getString() : m.getCustomName().getString());
			e.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).getPath());
			e.addProperty("x", m.getBlockX());
			e.addProperty("y", m.getBlockY());
			e.addProperty("z", m.getBlockZ());
			animals.add(e);
		}
		j.add("people", people);
		j.add("animals", animals);
		ServerPlayNetworking.send(player, new RealmPayload(j.toString()));
	}

	private static String roleName(KingdomNpc npc, String role) {
		if (role.startsWith("worker|")) {
			int bar = role.lastIndexOf('|');
			return bar > 7 && role.indexOf('|', 7) > 0 ? role.substring(bar + 1) : "Builder";
		}
		return switch (npc.role()) {
			case "architect" -> "Architect";
			case "guard" -> "Guard";
			case "sword" -> "Swordfighter";
			case "archer" -> "Archer";
			case "mage" -> "Mage";
			case "companion" -> "Companion";
			default -> npc.role();
		};
	}

	/** Realm actions from the screen ("realm_..."); the screen is refreshed afterwards. */
	static void handle(ServerPlayer player, Settlement here, String action) {
		MinecraftServer server = player.level().getServer();
		String[] a = action.split("\\|", 4);
		switch (a[0]) {
			case "realm_empire" -> {
				String name = clean(a.length > 1 ? a[1] : "");
				if (!name.isEmpty()) {
					for (Settlement o : owned(server, player.getUUID())) {
						Settlements.putState(server, o.withName("empire", name));
					}
				}
			}
			case "realm_rename" -> {
				Settlement o = byId(server, player, a.length > 1 ? a[1] : "");
				String name = clean(a.length > 2 ? a[2] : "");
				if (o != null && !name.isEmpty()) {
					Settlements.putState(server, o.renamed(name));
				}
			}
			case "realm_vname" -> {
				String name = clean(a.length > 2 ? a[2] : "");
				for (Settlement o : owned(server, player.getUUID())) {
					if (!name.isEmpty() && o.names().containsKey("village|" + a[1])) {
						Settlements.putState(server, o.withName("village|" + a[1], name));
					}
				}
			}
			case "realm_find" -> findVillages(server, player);
			case "realm_link" -> {
				Settlement from = byId(server, player, a.length > 1 ? a[1] : "");
				String to = a.length > 2 ? a[2] : "";
				if (from == null) {
					break;
				}
				if (to.startsWith("v:")) {
					String[] p = to.substring(2).split(",");
					int x = Integer.parseInt(p[0]);
					int z = Integer.parseInt(p[1]);
					int y = server.overworld().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
					String name = "the village";
					for (Settlement o : owned(server, player.getUUID())) {
						name = o.names().getOrDefault("village|" + x + "," + z, name);
					}
					RoadLink.link(server, player, from, to, new BlockPos(x, y, z), name);
				} else {
					Settlement target = byId(server, player, to);
					if (target != null && !target.id().equals(from.id())) {
						RoadLink.link(server, player, from, to, RoadLink.gateExit(target), target.name());
					}
				}
			}
			case "realm_skin" -> {
				Entity e = server.overworld().getEntity(UUID.fromString(a[1]));
				if (e instanceof KingdomNpc npc && (player.getUUID().equals(npc.lord()) || People.role(npc).contains(here.id().toString()))) {
					npc.setSkin(Integer.parseInt(a[2]));
				}
			}
			default -> {
			}
		}
		Settlement fresh = Settlements.byHeart(server, here.heart());
		send(player, fresh == null ? here : fresh);
	}

	private static String clean(String s) {
		s = s.strip();
		return s.length() > 32 ? s.substring(0, 32) : s;
	}

	private static Settlement byId(MinecraftServer server, ServerPlayer player, String id) {
		for (Settlement o : owned(server, player.getUUID())) {
			if (o.id().toString().equals(id)) {
				return o;
			}
		}
		return null;
	}

	/** Looks for villages around every settlement of the realm (here and 500 blocks out in each direction). */
	private static void findVillages(MinecraftServer server, ServerPlayer player) {
		ServerLevel level = server.overworld();
		List<BlockPos> known = new ArrayList<>();
		List<Settlement> mine = owned(server, player.getUUID());
		for (Settlement o : mine) {
			for (String k : o.names().keySet()) {
				if (k.startsWith("village|")) {
					String[] p = k.substring(8).split(",");
					known.add(new BlockPos(Integer.parseInt(p[0]), 0, Integer.parseInt(p[1])));
				}
			}
		}
		int found = 0;
		for (Settlement o : mine) {
			Settlement cur = o;
			for (int[] d : new int[][] {{0, 0}, {500, 0}, {-500, 0}, {0, 500}, {0, -500}}) {
				BlockPos v = level.findNearestMapStructure(StructureTags.VILLAGE, o.heart().offset(d[0], 0, d[1]), 40, false);
				if (v == null || v.distSqr(o.heart()) > 1200 * 1200 || known.stream().anyMatch(k -> Math.abs(k.getX() - v.getX()) < 96
						&& Math.abs(k.getZ() - v.getZ()) < 96)) {
					continue;
				}
				known.add(new BlockPos(v.getX(), 0, v.getZ()));
				found++;
				cur = cur.withName("village|" + v.getX() + "," + v.getZ(), "Village " + known.size());
			}
			Settlements.putState(server, cur);
		}
		People.say(player, "Cartographer", found == 0 ? "No new villages within reach." : "I found " + found + (found == 1 ? " new village." : " new villages."));
	}
}
