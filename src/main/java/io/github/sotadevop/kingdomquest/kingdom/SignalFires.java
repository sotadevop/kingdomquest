package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.building.Template;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Signal fires: the unlit campfire on top of every tower. When an army marches on one of a lord's kingdoms (or,
 * through {@link #alarm}, on one of his vassal villages) they light one after another across his whole realm,
 * nearest first, and go out once the attack is over. Nobody lights them by hand: a fire lit without an attack goes
 * out again. Any campfire a template places unlit is a signal fire, so later watchtowers only need to place one.
 */
final class SignalFires {
	/** Ticks between one fire and the next in the chain. */
	private static final int DELAY = 10;

	/** An attack under way: whose realm, where, and since when (game time). */
	private record Alarm(UUID owner, BlockPos origin, long start) {
	}

	/** Attacks under way: settlement id (kingdoms) or a caller's own key (vassal villages) -> alarm. */
	private static final Map<String, Alarm> ALARMS = new HashMap<>();
	/** Template and rotation -> the columns (relative to the heart) that hold a signal fire. */
	private static final Map<String, Set<Long>> COLUMNS = new HashMap<>();
	/** Settlement id -> {the settlement state it was worked out for, its signal fires}. */
	private static final Map<UUID, Object[]> FIRES = new HashMap<>();

	private SignalFires() {
	}

	/** An attack is coming at {@code name}: the lord is told and his fires light from {@code where} outwards. */
	static void alarm(MinecraftServer server, UUID owner, String key, String name, BlockPos where) {
		if (ALARMS.putIfAbsent(key, new Alarm(owner, where, server.overworld().getGameTime())) != null) {
			return;
		}
		ServerPlayer lord = server.getPlayerList().getPlayer(owner);
		if (lord != null) {
			lord.sendSystemMessage(Component.literal("Attack on " + name + "!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
		}
	}

	/** The attack on {@code key} is over: once no other attack is under way, his fires go out. */
	static void allClear(String key) {
		ALARMS.remove(key);
	}

	/** Where a settlement's signal fires stand (only a campfire found there in the world is lit or put out). */
	static List<BlockPos> fires(Settlement s) {
		Object[] cached = FIRES.get(s.id());
		if (cached != null && cached[0] == s) {
			@SuppressWarnings("unchecked")
			List<BlockPos> list = (List<BlockPos>) cached[1];
			return list;
		}
		List<BlockPos> out = new ArrayList<>();
		Rotation rot = Rotation.values()[s.rotation()];
		for (String track : Progression.ORDER) {
			Progression.Step step = Progression.current(s, track);
			Template t = step == null ? null : Template.get(Settlements.template(s, step.building()));
			if (t == null) {
				continue;
			}
			Set<Long> columns = COLUMNS.computeIfAbsent(t.id + "|" + rot, k -> {
				Set<Long> c = new HashSet<>();
				for (Template.Placement p : t.placements("white", rot)) {
					if (p.state().is(Blocks.CAMPFIRE) && !p.state().getValue(CampfireBlock.LIT)) {
						c.add(BlockPos.asLong(p.offset().getX(), 0, p.offset().getZ()));
					}
				}
				return c;
			});
			if (columns.isEmpty()) {
				continue;
			}
			// the fire crowns its column: the highest block the builders put there
			Map<Long, BlockPos> top = new HashMap<>();
			for (long l : s.built().getOrDefault(track, List.of())) {
				BlockPos p = BlockPos.of(l);
				long col = BlockPos.asLong(p.getX() - s.heart().getX(), 0, p.getZ() - s.heart().getZ());
				if (columns.contains(col)) {
					top.merge(col, p, (a, b) -> a.getY() >= b.getY() ? a : b);
				}
			}
			out.addAll(top.values());
		}
		out.addAll(Roadside.fires(s)); // waystations and watchtowers along the roads
		List<BlockPos> list = List.copyOf(out);
		FIRES.put(s.id(), new Object[] {s, list});
		return list;
	}

	/** Lights or puts out the fire at {@code pos}, if a campfire stands there in a loaded chunk. True if it changed. */
	static boolean set(ServerLevel level, BlockPos pos, boolean lit) {
		if (!level.isLoaded(pos)) {
			return false;
		}
		BlockState st = level.getBlockState(pos);
		if (!st.is(Blocks.CAMPFIRE) || st.getValue(CampfireBlock.LIT) == lit) {
			return false;
		}
		// a burning signal fire sends its smoke up high, as over a hay bale; a campfire never sets anything alight
		level.setBlock(pos, st.setValue(CampfireBlock.LIT, lit).setValue(CampfireBlock.SIGNAL_FIRE, lit), Block.UPDATE_ALL);
		level.playSound(null, pos, lit ? SoundEvents.FIRECHARGE_USE : SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, lit ? 1.5f : 0.8f, 1f);
		return true;
	}

	/** Twice a second: attacks on kingdoms are picked up from {@link Defense}; fires light along the chain or go out. */
	static void tick(MinecraftServer server) {
		if (server.getTickCount() % DELAY != 0) {
			return;
		}
		ServerLevel level = server.overworld();
		long now = level.getGameTime();
		List<Settlement> all = Settlements.all(server);
		for (Settlement s : all) {
			String key = s.id().toString();
			boolean running = s.level("attack_running") == 1; // Defense: from the Warchief's warning to the victory
			if (running) {
				alarm(server, s.owner(), key, s.name(), s.heart());
			} else {
				allClear(key);
			}
		}
		boolean sweep = server.getTickCount() % 100 == 0; // fires lit without an attack (by hand, or before a restart) go out
		Map<UUID, List<BlockPos>> realms = new HashMap<>();
		for (Settlement s : all) {
			realms.computeIfAbsent(s.owner(), k -> new ArrayList<>()).addAll(fires(s));
		}
		for (Map.Entry<UUID, List<BlockPos>> realm : realms.entrySet()) {
			List<Alarm> alarms = ALARMS.values().stream().filter(a -> a.owner().equals(realm.getKey())).toList();
			if (alarms.isEmpty()) {
				if (sweep) {
					realm.getValue().forEach(p -> set(level, p, false));
				}
				continue;
			}
			// one chain across the realm, from the attacked place outwards; a fire far away lights when it is due,
			// or as soon as its land is loaded after that
			List<BlockPos> chain = new ArrayList<>(realm.getValue());
			chain.sort(Comparator.comparingDouble((BlockPos p) -> alarms.stream().mapToDouble(a -> a.origin().distSqr(p)).min().orElse(0)));
			long start = alarms.stream().mapToLong(Alarm::start).min().orElse(now);
			for (int i = 0; i < chain.size() && now >= start + (long) i * DELAY; i++) {
				set(level, chain.get(i), true);
			}
		}
	}
}
