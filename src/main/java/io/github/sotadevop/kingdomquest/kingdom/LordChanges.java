package io.github.sotadevop.kingdomquest.kingdom;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.sotadevop.kingdomquest.KingdomQuest;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The lord's own changes to his settlement (user, 2026-11-19, option A): every block he breaks or places there is
 * remembered as his. Repair leaves those spots alone and fixes only real damage (raiders, creepers, fire, other
 * players). An upgrade keeps them where the new plan has the same block as the old one at that spot and replaces them
 * where the plan changes there, telling him how many it replaced. "Undo my changes" in the book puts the plan back.
 */
public final class LordChanges {
	/** One changed spot: the position and the block the lord left there (air where he broke one). */
	public record Change(long pos, BlockState state) {
		static final Codec<Change> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.LONG.fieldOf("pos").forGetter(Change::pos),
				BlockState.CODEC.fieldOf("state").forGetter(Change::state)
		).apply(i, Change::new));
	}

	/** Settlement id -> its lord's changes. */
	public static final AttachmentType<Map<String, List<Change>>> DATA = AttachmentRegistry.<Map<String, List<Change>>>builder()
			.persistent(Codec.unboundedMap(Codec.STRING, Change.CODEC.listOf()))
			.initializer(Map::of)
			.buildAndRegister(Identifier.fromNamespaceAndPath(KingdomQuest.MOD_ID, "lord_changes"));

	/** Blocks placed right now: checked a tick later, when the placement has happened (or not). */
	private record Pending(ServerLevel level, java.util.UUID settlement, BlockPos pos, BlockState before, long at) {
	}

	private static final List<Pending> PENDING = new ArrayList<>();

	private LordChanges() {
	}

	static void register() {
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp) {
				noteBreak(sl, sp, pos);
			}
		});
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			Settlement s = level instanceof ServerLevel sl0 && player instanceof ServerPlayer && player.getItemInHand(hand).getItem() instanceof BlockItem
					? owned(sl0, player.getUUID(), hit.getBlockPos()) : null;
			if (s != null) {
				ServerLevel sl = (ServerLevel) level;
				// the block goes into the clicked spot (grass, snow) or next to it: both are looked at a tick later
				for (BlockPos p : new BlockPos[] {hit.getBlockPos(), hit.getBlockPos().relative(hit.getDirection())}) {
					PENDING.add(new Pending(sl, s.id(), p.immutable(), sl.getBlockState(p), sl.getServer().getTickCount()));
				}
			}
			return InteractionResult.PASS;
		});
		ServerTickEvents.END_SERVER_TICK.register(LordChanges::tick);
	}

	private static void tick(MinecraftServer server) {
		if (PENDING.isEmpty()) {
			return;
		}
		int now = server.getTickCount();
		List<Pending> later = new ArrayList<>();
		for (Pending p : PENDING) {
			if (p.at() >= now) {
				later.add(p);
				continue;
			}
			BlockState st = p.level().getBlockState(p.pos());
			if (st != p.before() && !st.isAir()) {
				Settlement s = null;
				for (Settlement o : Settlements.all(server)) {
					if (o.id().equals(p.settlement())) {
						s = o;
					}
				}
				if (s != null) {
					put(server, s, p.pos().asLong(), st);
				}
			}
		}
		PENDING.clear();
		PENDING.addAll(later);
	}

	/** A block the lord broke (also a builders' block in survival, which is taken away without drops). */
	static void noteBreak(ServerLevel level, ServerPlayer player, BlockPos pos) {
		Settlement s = owned(level, player.getUUID(), pos);
		if (s != null) {
			put(level.getServer(), s, pos.asLong(), Blocks.AIR.defaultBlockState());
		}
	}

	/** The settlement this lord owns that holds the position, or null. */
	private static Settlement owned(ServerLevel level, java.util.UUID player, BlockPos pos) {
		if (level.dimension() != Level.OVERWORLD) {
			return null;
		}
		for (Settlement s : Settlements.all(level.getServer())) {
			if (s.owner().equals(player) && inside(s, pos)) {
				return s;
			}
		}
		return null;
	}

	/** Within the settlement's ground (the same radius the builders protect). */
	private static boolean inside(Settlement s, BlockPos pos) {
		int main = s.level(Settlement.MAIN);
		int r = main >= 7 ? 80 : main >= 4 ? 40 : 20;
		return Math.abs(pos.getX() - s.heart().getX()) <= r && Math.abs(pos.getZ() - s.heart().getZ()) <= r
				&& Math.abs(pos.getY() - s.heart().getY()) < 48;
	}

	/** The lord's changes in a settlement: position -> the block he left there. */
	public static Map<Long, BlockState> of(MinecraftServer server, Settlement s) {
		List<Change> list = server.overworld().getAttachedOrCreate(DATA).getOrDefault(s.id().toString(), List.of());
		Map<Long, BlockState> out = new HashMap<>();
		for (Change c : list) {
			out.put(c.pos(), c.state());
		}
		return out;
	}

	/** Records a change of the lord's (also used by the field test). */
	static void record(MinecraftServer server, Settlement s, long pos, BlockState state) {
		put(server, s, pos, state);
	}

	private static void put(MinecraftServer server, Settlement s, long pos, BlockState state) {
		Map<Long, BlockState> m = of(server, s);
		m.put(pos, state);
		save(server, s, m);
	}

	/** Forgets changed spots (an upgrade replaced them, or the lord undid them). */
	static void forget(MinecraftServer server, Settlement s, java.util.Collection<Long> spots) {
		if (spots.isEmpty()) {
			return;
		}
		Map<Long, BlockState> m = of(server, s);
		spots.forEach(m::remove);
		save(server, s, m);
	}

	private static void save(MinecraftServer server, Settlement s, Map<Long, BlockState> m) {
		Map<String, List<Change>> all = new HashMap<>(server.overworld().getAttachedOrCreate(DATA));
		List<Change> list = new ArrayList<>();
		m.forEach((k, v) -> list.add(new Change(k, v)));
		if (list.isEmpty()) {
			all.remove(s.id().toString());
		} else {
			all.put(s.id().toString(), List.copyOf(list));
		}
		server.overworld().setAttached(DATA, Map.copyOf(all));
	}
}
