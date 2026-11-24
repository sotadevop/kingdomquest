package io.github.sotadevop.kingdomquest.kingdom;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.sotadevop.kingdomquest.KingdomQuest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The chronicler (19.11): the kingdom's history, written as it happens. Each entry is a game day, a text key
 * (kingdomquest.chronicle.*) with its arguments and whether it is a highlight (the founding, each tier, landmarks,
 * vassal villages, won battles, vanilla milestones, new kingdoms). Read in a book-style screen from the kingdom menu
 * or by talking to the Chronicler at the town hall.
 * <p>
 * Kept apart from the settlement record (many places hold and store a copy of that), one file per settlement:
 * kingdomquest_chronicle/&lt;id&gt;.json in the world folder.
 */
public final class Chronicle {
	private static final int MAX = 600;
	private static final Map<UUID, JsonArray> CACHE = new HashMap<>();

	private Chronicle() {
	}

	static void register() {
		PayloadTypeRegistry.clientboundPlay().register(ChroniclePayload.TYPE, ChroniclePayload.CODEC);
	}

	private static Path file(MinecraftServer server, UUID id) {
		return server.getWorldPath(LevelResource.ROOT).resolve("kingdomquest_chronicle").resolve(id + ".json");
	}

	private static JsonArray entries(MinecraftServer server, UUID id) {
		return CACHE.computeIfAbsent(id, k -> {
			Path f = file(server, k);
			try {
				if (Files.exists(f)) {
					return JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonArray();
				}
			} catch (Exception e) {
				KingdomQuest.LOG.warn("KingdomQuest: chronicle {} unreadable", f, e);
			}
			return new JsonArray();
		});
	}

	/** Writes an entry for today; a "once" key that is already in the chronicle is not written again. */
	static void add(MinecraftServer server, Settlement s, String key, boolean highlight, String once, String... args) {
		if (server == null || s == null) {
			return;
		}
		JsonArray all = entries(server, s.id());
		if (once != null) {
			for (int i = 0; i < all.size(); i++) {
				JsonObject e = all.get(i).getAsJsonObject();
				if (e.has("o") && e.get("o").getAsString().equals(once)) {
					return;
				}
			}
		}
		JsonObject e = new JsonObject();
		e.addProperty("d", (int) (server.overworld().getGameTime() / 24000L) + 1);
		e.addProperty("k", key);
		e.addProperty("h", highlight);
		if (once != null) {
			e.addProperty("o", once);
		}
		JsonArray a = new JsonArray();
		for (String arg : args) {
			a.add(arg == null ? "" : arg);
		}
		e.add("a", a);
		all.add(e);
		while (all.size() > MAX) {
			// the oldest everyday entries go first; the highlights stay
			int drop = 0;
			for (int i = 0; i < all.size(); i++) {
				if (!all.get(i).getAsJsonObject().get("h").getAsBoolean()) {
					drop = i;
					break;
				}
			}
			all.remove(drop);
		}
		try {
			Path f = file(server, s.id());
			Files.createDirectories(f.getParent());
			Files.writeString(f, all.toString(), StandardCharsets.UTF_8);
		} catch (Exception ex) {
			KingdomQuest.LOG.warn("KingdomQuest: chronicle of {} not written", s.name(), ex);
		}
	}

	private static String lord(MinecraftServer server, Settlement s) {
		ServerPlayer p = server.getPlayerList().getPlayer(s.owner());
		return p == null ? "" : p.getName().getString();
	}

	/** The founding, and a line in the chronicles of the lord's other kingdoms. */
	static void founded(MinecraftServer server, Settlement s) {
		add(server, s, "founded", true, "founded", s.name(), lord(server, s));
		for (Settlement o : Settlements.all(server)) {
			if (!o.id().equals(s.id()) && o.owner().equals(s.owner())) {
				add(server, o, "kingdom", true, "kingdom|" + s.id(), s.name());
			}
		}
	}

	/** A finished build: the new level of a track (each written once). Tiers and landmarks are highlights. */
	static void built(MinecraftServer server, Settlement s, String track) {
		if (s == null || track == null) {
			return;
		}
		Progression.Step step = Progression.current(s, track);
		if (step == null) {
			return;
		}
		boolean big = Settlement.MAIN.equals(track) || java.util.Set.of(Progression.CHURCH, Progression.TOWNHALL, Progression.GARDEN,
				Progression.MARKETHALL, Progression.BELFRY, Progression.MONASTERY, Progression.TRAM, Progression.GATE, Progression.WALLS).contains(track);
		String once = "built|" + track + "|" + step.level();
		if (Settlement.MAIN.equals(track)) {
			add(server, s, "tier", true, once, s.name(), step.titleKey());
		} else {
			add(server, s, "built", big, once, step.titleKey());
		}
	}

	static void vassal(MinecraftServer server, Settlement liege, String village) {
		add(server, liege, "vassal", true, "vassal|" + village, village);
	}

	static void battle(MinecraftServer server, Settlement s, int raiders) {
		add(server, s, "attack_won", true, null, String.valueOf(raiders));
	}

	static void milestone(MinecraftServer server, Settlement s, String name) {
		add(server, s, "ms." + name, true, "ms|" + name, lord(server, s));
	}

	/** Opens the chronicle for a player. */
	static void send(ServerPlayer player, Settlement s) {
		JsonObject o = new JsonObject();
		o.addProperty("name", s.name());
		o.add("entries", entries(player.level().getServer(), s.id()));
		ServerPlayNetworking.send(player, new ChroniclePayload(o.toString()));
	}
}
