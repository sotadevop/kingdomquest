package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.KingdomQuest;
import io.github.sotadevop.kingdomquest.building.Template;
import io.github.sotadevop.kingdomquest.charter.CharterData;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** All settlements of the world: founding, building over time, upgrades, protection and the kingdom menu. */
public final class Settlements {
	public static final AttachmentType<List<Settlement>> DATA = AttachmentRegistry.<List<Settlement>>builder()
			.persistent(Settlement.CODEC.listOf())
			.initializer(List::of)
			.buildAndRegister(Identifier.fromNamespaceAndPath(KingdomQuest.MOD_ID, "settlements"));

	/** Minimum distance between two hearts. */
	private static final int SPACING = 48;
	private static final int CONFIRM_TICKS = 200;
	private static final Map<UUID, Pending> PENDING = new HashMap<>();
	private static final Map<String, List<Template.Placement>> PLACEMENTS = new HashMap<>();

	private record Pending(BlockPos heart, long tick) {
	}

	private Settlements() {
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(KingdomInfoPayload.TYPE, KingdomInfoPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(UpgradePayload.TYPE, UpgradePayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(UpgradePayload.TYPE, (payload, context) -> upgrade(context.player(), payload.heart()));
		ServerTickEvents.END_SERVER_TICK.register(Settlements::tick);
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
				!(level instanceof ServerLevel server) || !(player instanceof ServerPlayer sp) || allowBreak(server, sp, pos));
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (hand != InteractionHand.MAIN_HAND || !player.getMainHandItem().isEmpty() || !(player instanceof ServerPlayer sp)) {
				return InteractionResult.PASS;
			}
			Settlement s = byHeart(sp.level().getServer(), hit.getBlockPos());
			if (s == null || level.dimension() != Level.OVERWORLD) {
				return InteractionResult.PASS;
			}
			sendInfo(sp, s);
			return InteractionResult.SUCCESS;
		});
	}

	// ---- data ----

	public static List<Settlement> all(MinecraftServer server) {
		return server.overworld().getAttachedOrCreate(DATA);
	}

	private static void put(MinecraftServer server, Settlement s) {
		List<Settlement> list = new ArrayList<>(all(server));
		list.removeIf(o -> o.id().equals(s.id()));
		list.add(s);
		server.overworld().setAttached(DATA, List.copyOf(list));
	}

	public static Settlement byHeart(MinecraftServer server, BlockPos pos) {
		for (Settlement s : all(server)) {
			if (s.heart().equals(pos)) {
				return s;
			}
		}
		return null;
	}

	public static Settlement byOwner(MinecraftServer server, UUID owner) {
		for (Settlement s : all(server)) {
			if (s.owner().equals(owner)) {
				return s;
			}
		}
		return null;
	}

	public static String dye(int color) {
		return DyeColor.byId(color).getName();
	}

	public static Rotation rotationFor(Direction facing) {
		return switch (facing) {
			case EAST -> Rotation.CLOCKWISE_90;
			case SOUTH -> Rotation.CLOCKWISE_180;
			case WEST -> Rotation.COUNTERCLOCKWISE_90;
			default -> Rotation.NONE;
		};
	}

	// ---- founding ----

	/**
	 * Called when a sealed charter is used on the top of a block. First use asks for confirmation, a second
	 * use on the same spot within 10 seconds founds the settlement. Returns true if the charter was used up.
	 */
	public static boolean tryFound(ServerPlayer player, CharterData data, BlockPos clicked) {
		ServerLevel level = (ServerLevel) player.level();
		MinecraftServer server = level.getServer();
		if (level.dimension() != Level.OVERWORLD) {
			player.sendOverlayMessage(Component.translatable("kingdomquest.found.overworld"));
			return false;
		}
		if (byOwner(server, player.getUUID()) != null) {
			player.sendOverlayMessage(Component.translatable("kingdomquest.found.one"));
			return false;
		}
		BlockPos heart = clicked.above();
		for (Settlement s : all(server)) {
			if (s.heart().closerThan(heart, SPACING)) {
				player.sendOverlayMessage(Component.translatable("kingdomquest.found.too_close", s.name()));
				return false;
			}
		}
		long now = server.getTickCount();
		Pending p = PENDING.get(player.getUUID());
		if (p == null || !p.heart().equals(heart) || now - p.tick() > CONFIRM_TICKS) {
			PENDING.put(player.getUUID(), new Pending(heart, now));
			player.sendOverlayMessage(Component.translatable("kingdomquest.found.confirm", data.name()));
			return false;
		}
		PENDING.remove(player.getUUID());
		String style = Style.forBiome(level.getBiome(heart).getRegisteredName());
		Rotation rotation = rotationFor(player.getDirection());
		Settlement s = new Settlement(UUID.randomUUID(), player.getUUID(), data.name(), data.color(), data.logo(), style, heart,
				rotation.ordinal(), 1, style + "/tent_1", 0, List.of());
		Template t = Template.get(s.job());
		if (t == null) {
			player.sendOverlayMessage(Component.literal("Missing building " + s.job()));
			return false;
		}
		prepareSite(level, heart, t, rotation);
		put(server, s);
		level.getServer().getPlayerList().broadcastSystemMessage(Component.translatable("kingdomquest.found.done", player.getDisplayName(), data.name()), false);
		KingdomQuest.LOG.info("Settlement {} founded at {} ({})", data.name(), heart, style);
		return true;
	}

	/** Clears the plot above the heart's layer and fills holes below it, so nothing is buried or floating. */
	static void prepareSite(ServerLevel level, BlockPos heart, Template t, Rotation rotation) {
		for (int x = 0; x < t.sizeX; x++) {
			for (int z = 0; z < t.sizeZ; z++) {
				BlockPos rel = Template.rotate(new BlockPos(x - t.heart.getX(), 0, z - t.heart.getZ()), rotation);
				BlockPos column = heart.offset(rel.getX(), 0, rel.getZ());
				for (int y = 0; y < 10; y++) {
					BlockPos p = column.above(y);
					if (!level.getBlockState(p).isAir()) {
						level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
					}
				}
				for (int y = 1; y <= 4; y++) {
					BlockPos p = column.below(y);
					BlockState state = level.getBlockState(p);
					if (state.isAir() || state.canBeReplaced() || !state.getFluidState().isEmpty()) {
						level.setBlock(p, (y == 1 ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState(), Block.UPDATE_ALL);
					}
				}
			}
		}
	}

	// ---- upgrades ----

	static void upgrade(ServerPlayer player, BlockPos heart) {
		MinecraftServer server = player.level().getServer();
		Settlement s = byHeart(server, heart);
		if (s == null || !s.owner().equals(player.getUUID()) || !s.job().isEmpty() || player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(heart)) > 64 * 64) {
			return;
		}
		Progression.Step step = Progression.next(s.level());
		if (step == null || !Progression.requirementMet(player, step) || !Progression.canPay(player, step)) {
			return;
		}
		Progression.pay(player, step);
		String job = s.style() + "/" + step.building();
		Template t = Template.get(job);
		if (t == null) {
			return;
		}
		// blocks of the old level that the new one doesn't use are taken down (they drop nothing)
		ServerLevel level = server.overworld();
		Set<Long> next = new HashSet<>();
		for (Template.Placement p : placements(s, job)) {
			next.add(s.heart().offset(p.offset()).asLong());
		}
		List<Long> kept = new ArrayList<>();
		for (long pos : s.built()) {
			if (next.contains(pos)) {
				kept.add(pos);
			} else {
				level.setBlock(BlockPos.of(pos), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
		put(server, s.withJob(job, 0, step.level()).withBuilt(List.copyOf(kept)));
		player.sendOverlayMessage(Component.translatable("kingdomquest.upgrade.started", Component.translatable(step.titleKey())));
	}

	private static List<Template.Placement> placements(Settlement s, String job) {
		String key = job + "|" + s.color() + "|" + s.rotation();
		return PLACEMENTS.computeIfAbsent(key, k -> {
			Template t = Template.get(job);
			return t == null ? List.of() : t.placements(dye(s.color()), Rotation.values()[s.rotation()]);
		});
	}

	// ---- building over time ----

	static void tick(MinecraftServer server) {
		for (Settlement s : all(server)) {
			if (!s.job().isEmpty()) {
				step(server, s);
			}
		}
	}

	private static void step(MinecraftServer server, Settlement s) {
		ServerLevel level = server.overworld();
		if (!level.isLoaded(s.heart())) {
			return; // paused while nobody is near
		}
		Template t = Template.get(s.job());
		List<Template.Placement> list = placements(s, s.job());
		if (t == null || list.isEmpty()) {
			put(server, s.withJob("", 0, s.level()));
			return;
		}
		int perTick = Math.max(1, (int) Math.ceil(list.size() / (t.seconds * 20.0)));
		int index = s.jobIndex();
		List<Long> built = new ArrayList<>(s.built());
		for (int n = 0; n < perTick && index < list.size(); n++, index++) {
			Template.Placement p = list.get(index);
			BlockPos pos = s.heart().offset(p.offset());
			if (!level.getBlockState(pos).equals(p.state())) {
				level.setBlock(pos, p.state(), Block.UPDATE_ALL);
				if (n == 0) {
					SoundType sound = p.state().getSoundType();
					level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1) / 2, sound.getPitch() * 0.8f);
					level.levelEvent(2001, pos, Block.getId(p.state()));
				}
			}
			if (p.offset().getY() >= 0 && !built.contains(pos.asLong())) {
				built.add(pos.asLong());
			}
		}
		if (index >= list.size()) {
			put(server, s.withJob("", 0, s.level()).withBuilt(List.copyOf(built)));
			ServerPlayer owner = server.getPlayerList().getPlayer(s.owner());
			if (owner != null) {
				owner.sendSystemMessage(Component.translatable("kingdomquest.build.done", s.name()));
			}
			return;
		}
		put(server, s.withJob(s.job(), index, s.level()).withBuilt(List.copyOf(built)));
		ServerPlayer owner = server.getPlayerList().getPlayer(s.owner());
		if (owner != null && server.getTickCount() % 20 == 0 && owner.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(s.heart())) < 48 * 48) {
			owner.sendOverlayMessage(Component.translatable("kingdomquest.build.progress", s.name(), index * 100 / list.size()));
		}
	}

	// ---- protection ----

	/** Built blocks drop nothing; the heart can't be broken at all (creative players break normally). */
	static boolean allowBreak(ServerLevel level, ServerPlayer player, BlockPos pos) {
		if (player.isCreative() || level.dimension() != Level.OVERWORLD) {
			return true;
		}
		MinecraftServer server = level.getServer();
		for (Settlement s : all(server)) {
			if (s.heart().equals(pos)) {
				player.sendOverlayMessage(Component.translatable("kingdomquest.heart.protected", s.name()));
				return false;
			}
			if (s.built().contains(pos.asLong())) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				List<Long> built = new ArrayList<>(s.built());
				built.remove(pos.asLong());
				put(server, s.withBuilt(List.copyOf(built)));
				return false;
			}
		}
		return true;
	}

	// ---- menu ----

	public static void sendInfo(ServerPlayer player, Settlement s) {
		int percent = -1;
		if (!s.job().isEmpty()) {
			int total = placements(s, s.job()).size();
			percent = total == 0 ? 100 : s.jobIndex() * 100 / total;
		}
		Progression.Step next = Progression.next(s.level());
		boolean mine = s.owner().equals(player.getUUID());
		ServerPlayNetworking.send(player, new KingdomInfoPayload(s.heart(), s.name(), s.color(), s.logo(), s.level(), percent,
				next == null ? "" : next.titleKey(),
				next == null || next.requirementKey() == null ? "" : next.requirementKey(),
				next != null && mine && Progression.requirementMet(player, next),
				next == null ? "" : Progression.costText(next),
				next != null && mine && Progression.canPay(player, next)));
	}

	/** Builds every level of every style at once (used by the CI self-test); returns the number of blocks. */
	public static int buildAllForTest(ServerLevel level, BlockPos start) {
		int count = 0;
		int i = 0;
		for (String style : List.of(Style.TEMPERATE, Style.FROZEN)) {
			for (Progression.Step step : Progression.TENT) {
				Template t = Template.get(style + "/" + step.building());
				if (t == null) {
					continue;
				}
				BlockPos heart = start.offset(i * 20, 0, 0);
				prepareSite(level, heart, t, Rotation.NONE);
				for (Template.Placement p : t.placements("blue", Rotation.NONE)) {
					level.setBlock(heart.offset(p.offset()), p.state(), Block.UPDATE_ALL);
					count++;
				}
				i++;
			}
		}
		return count;
	}
}
