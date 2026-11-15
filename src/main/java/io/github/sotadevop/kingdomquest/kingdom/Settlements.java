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
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;

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
	private static final int SPACING = 160;
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
		ServerPlayNetworking.registerGlobalReceiver(UpgradePayload.TYPE, (payload, context) -> upgrade(context.player(), payload.heart(), payload.track()));
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
				rotation.ordinal(), Map.of(Settlement.MAIN, 1), style + "/tent_1", Settlement.MAIN, 0, Map.of());
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

	static void upgrade(ServerPlayer player, BlockPos heart, String track) {
		MinecraftServer server = player.level().getServer();
		Settlement s = byHeart(server, heart);
		if (s == null || !s.owner().equals(player.getUUID()) || player.distanceToSqr(Vec3.atCenterOf(heart)) > 64 * 64) {
			return;
		}
		if (!s.job().isEmpty()) {
			if (!player.isCreative()) {
				return;
			}
			step(server, s, true); // creative: finish the running build at once
			s = byHeart(server, heart);
		}
		Progression.Step step = Progression.next(s, track);
		if (step == null || !Progression.requirementMet(player, step) || !Progression.canPay(player, step)) {
			return;
		}
		if (Template.get(s.style() + "/" + step.building()) == null) {
			return;
		}
		Progression.pay(player, step);
		start(server, s, track, step);
		player.sendOverlayMessage(Component.translatable("kingdomquest.upgrade.started", Component.translatable(step.titleKey())));
	}

	/** Starts building {@code step} on {@code track}: takes down the track's blocks the new level doesn't use, clears the ground. */
	private static void start(MinecraftServer server, Settlement s, String track, Progression.Step step) {
		String job = s.style() + "/" + step.building();
		String track0 = track;
		ServerLevel level = server.overworld();
		List<Template.Placement> list = placements(s, job);
		Set<Long> next = new HashSet<>();
		for (Template.Placement p : list) {
			next.add(s.heart().offset(p.offset()).asLong());
		}
		// blocks of this track's old level that the new one doesn't use are taken down (they drop nothing)
		List<Long> kept = new ArrayList<>();
		for (long pos : s.built().getOrDefault(track, List.of())) {
			if (next.contains(pos)) {
				kept.add(pos);
			} else {
				level.setBlock(BlockPos.of(pos), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
		put(server, s.withJob(job, track0, 0).withLevel(track0, step.level()).withBuilt(track0, kept));
	}

	// ---- test tools (/kingdomquest test ...) ----

	/** Finishes the running build of the player's settlement at once. Returns null if there is no settlement. */
	public static Settlement testFinish(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		Settlement s = byOwner(server, player.getUUID());
		if (s != null && !s.job().isEmpty()) {
			step(server, s, true);
			s = byHeart(server, s.heart());
		}
		return s;
	}

	/** Builds the given level of a track at once, skipping requirements and costs (also downgrades). */
	public static boolean testSet(ServerPlayer player, String track, int lvl) {
		Settlement s = testFinish(player);
		List<Progression.Step> steps = Progression.steps(track);
		if (s == null || steps == null || lvl < 1 || lvl > steps.size()) {
			return false;
		}
		MinecraftServer server = player.level().getServer();
		start(server, s, track, steps.get(lvl - 1));
		testFinish(player);
		return true;
	}

	/** Every track to its highest level. */
	public static boolean testMax(ServerPlayer player) {
		boolean ok = true;
		for (String track : Progression.ORDER) {
			ok &= testSet(player, track, Progression.steps(track).size());
		}
		return ok;
	}

	public static boolean testProduce(ServerPlayer player) {
		Settlement s = byOwner(player.level().getServer(), player.getUUID());
		if (s != null) {
			Production.run(player.level().getServer().overworld(), s);
		}
		return s != null;
	}

	/** Removes the player's settlement and every block its builders placed. */
	public static boolean testReset(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		Settlement s = byOwner(server, player.getUUID());
		if (s == null) {
			return false;
		}
		ServerLevel level = server.overworld();
		for (List<Long> list : s.built().values()) {
			for (long pos : list) {
				level.setBlock(BlockPos.of(pos), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
		level.setBlock(s.heart(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		List<Settlement> rest = new ArrayList<>(all(server));
		rest.removeIf(o -> o.id().equals(s.id()));
		server.overworld().setAttached(DATA, List.copyOf(rest));
		return true;
	}

	// ---- test tools (/kingdomquest test ...) ----

	/** Finishes the running build of the player's settlement at once. Returns null if there is no settlement. */
	public static Settlement testFinish(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		Settlement s = byOwner(server, player.getUUID());
		if (s != null && !s.job().isEmpty()) {
			step(server, s, true);
			s = byHeart(server, s.heart());
		}
		return s;
	}

	/** Builds the given level of a track at once, skipping requirements and costs (also downgrades). */
	public static boolean testSet(ServerPlayer player, String track, int lvl) {
		Settlement s = testFinish(player);
		List<Progression.Step> steps = Progression.steps(track);
		if (s == null || steps == null || lvl < 1 || lvl > steps.size()) {
			return false;
		}
		MinecraftServer server = player.level().getServer();
		start(server, s, track, steps.get(lvl - 1));
		testFinish(player);
		return true;
	}

	/** Every track to its highest level. */
	public static boolean testMax(ServerPlayer player) {
		boolean ok = true;
		for (String track : Progression.ORDER) {
			ok &= testSet(player, track, Progression.steps(track).size());
		}
		return ok;
	}

	public static boolean testProduce(ServerPlayer player) {
		Settlement s = byOwner(player.level().getServer(), player.getUUID());
		if (s != null) {
			Production.run(player.level().getServer().overworld(), s);
		}
		return s != null;
	}

	/** Removes the player's settlement and every block its builders placed. */
	public static boolean testReset(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		Settlement s = byOwner(server, player.getUUID());
		if (s == null) {
			return false;
		}
		ServerLevel level = server.overworld();
		for (List<Long> list : s.built().values()) {
			for (long pos : list) {
				level.setBlock(BlockPos.of(pos), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
		level.setBlock(s.heart(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		List<Settlement> rest = new ArrayList<>(all(server));
		rest.removeIf(o -> o.id().equals(s.id()));
		server.overworld().setAttached(DATA, List.copyOf(rest));
		return true;
	}

	/**
	 * Makes room for a new building: in every column it uses, natural blocks up to 10 above the ground are removed
	 * (blocks of other buildings stay) and holes under it are filled.
	 */
	private static void clearFootprint(ServerLevel level, Settlement s, List<Template.Placement> list) {
		Set<Long> columns = new HashSet<>();
		for (Template.Placement p : list) {
			BlockPos column = s.heart().offset(p.offset().getX(), 0, p.offset().getZ());
			if (!columns.add(column.asLong())) {
				continue;
			}
			for (int y = 0; y < 10; y++) {
				BlockPos pos = column.above(y);
				if (!level.getBlockState(pos).isAir() && !s.isBuilt(pos.asLong()) && !pos.equals(s.heart())) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				}
			}
			for (int y = 1; y <= 3; y++) {
				BlockPos pos = column.below(y);
				BlockState state = level.getBlockState(pos);
				if (state.isAir() || state.canBeReplaced()) {
					level.setBlock(pos, (y == 1 ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState(), Block.UPDATE_ALL);
				}
			}
		}
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
		boolean produce = server.getTickCount() % Production.INTERVAL == 0;
		for (Settlement s : all(server)) {
			if (!s.job().isEmpty()) {
				step(server, s, false);
			}
			if (produce) {
				Production.run(server.overworld(), s);
			}
		}
	}

	/** Total work units of a job: columns to level first, then blocks to place. */
	private static int total(Settlement s, String job) {
		Template t = Template.get(job);
		return t == null ? 0 : SiteWork.columns(job, t, Rotation.values()[s.rotation()]).size() + placements(s, job).size();
	}

	private static void step(MinecraftServer server, Settlement s, boolean instant) {
		ServerLevel level = server.overworld();
		if (!level.isLoaded(s.heart())) {
			return; // paused while nobody is near
		}
		Template t = Template.get(s.job());
		List<Template.Placement> list = placements(s, s.job());
		if (t == null || list.isEmpty()) {
			put(server, s.withJob("", "", 0));
			return;
		}
		List<SiteWork.Column> columns = SiteWork.columns(s.job(), t, Rotation.values()[s.rotation()]);
		int cols = columns.size();
		int index = s.jobIndex();
		Set<Long> allBuilt = new HashSet<>();
		s.built().values().forEach(allBuilt::addAll);
		// phase 1: level the ground
		int colsPerTick = instant ? cols : Math.max(4, (int) Math.ceil(cols / (t.seconds * 8.0)));
		for (int n = 0; n < colsPerTick && index < cols; n++, index++) {
			SiteWork.clear(level, s.heart(), columns.get(index), allBuilt);
		}
		// phase 2: place the blocks
		int perTick = instant ? list.size() : Math.max(1, (int) Math.ceil(list.size() / (t.seconds * 12.0)));
		List<Long> built = new ArrayList<>(s.built().getOrDefault(s.jobTrack(), List.of()));
		Set<Long> builtSet = new HashSet<>(built);
		if (index >= cols) {
			for (int n = 0; n < perTick && index < cols + list.size(); n++, index++) {
				Template.Placement p = list.get(index - cols);
				BlockPos pos = s.heart().offset(p.offset());
				BlockState state = p.state();
				if (state.getBlock() instanceof CrossCollisionBlock || state.getBlock() instanceof WallBlock) {
					state = Block.updateFromNeighbourShapes(state, level, pos); // fences, panes and walls join their neighbours
				}
				if (!level.getBlockState(pos).equals(state)) {
					level.setBlock(pos, state, Block.UPDATE_ALL);
					if (n == 0) {
						SoundType sound = state.getSoundType();
						level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1) / 2, sound.getPitch() * 0.8f);
						level.levelEvent(2001, pos, Block.getId(state));
					}
				}
				// crops are harvested normally, so they are not protected
				if (p.offset().getY() >= 0 && !(state.getBlock() instanceof CropBlock) && builtSet.add(pos.asLong())) {
					built.add(pos.asLong());
				}
			}
		}
		if (index >= cols + list.size()) {
			put(server, s.withJob("", "", 0).withBuilt(s.jobTrack(), built));
			spawnAnimals(level, s, t);
			ServerPlayer owner = server.getPlayerList().getPlayer(s.owner());
			if (owner != null) {
				owner.sendSystemMessage(Component.translatable("kingdomquest.build.done", s.name()));
			}
			return;
		}
		put(server, s.withJob(s.job(), s.jobTrack(), index).withBuilt(s.jobTrack(), built));
		ServerPlayer owner = server.getPlayerList().getPlayer(s.owner());
		if (owner != null && server.getTickCount() % 20 == 0 && owner.distanceToSqr(Vec3.atCenterOf(s.heart())) < 64 * 64) {
			owner.sendOverlayMessage(Component.translatable(index < cols ? "kingdomquest.build.clearing" : "kingdomquest.build.progress", s.name(),
					index * 100 / (cols + list.size())));
		}
	}

	/** Spawns the template's animals once its building is finished. */
	private static void spawnAnimals(ServerLevel level, Settlement s, Template t) {
		for (Template.Animal a : t.animals) {
			EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(a.type());
			Entity e = type.create(level, EntitySpawnReason.MOB_SUMMONED);
			if (e == null) {
				continue;
			}
			BlockPos pos = s.heart().offset(t.offset(a.pos(), Rotation.values()[s.rotation()]));
			e.snapTo(Vec3.atBottomCenterOf(pos), level.getRandom().nextFloat() * 360, 0);
			level.addFreshEntity(e);
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
			for (Map.Entry<String, List<Long>> e : s.built().entrySet()) {
				if (e.getValue().contains(pos.asLong())) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
					List<Long> built = new ArrayList<>(e.getValue());
					built.remove(pos.asLong());
					put(server, s.withBuilt(e.getKey(), built));
					return false;
				}
			}
		}
		return true;
	}

	// ---- menu ----

	public static void sendInfo(ServerPlayer player, Settlement s) {
		int percent = -1;
		if (!s.job().isEmpty()) {
			int total = total(s, s.job());
			percent = total == 0 ? 100 : s.jobIndex() * 100 / total;
		}
		boolean mine = s.owner().equals(player.getUUID());
		List<KingdomInfoPayload.Option> options = new ArrayList<>();
		for (String track : Progression.ORDER) {
			Progression.Step next = Progression.next(s, track);
			if (next != null) {
				options.add(new KingdomInfoPayload.Option(track, next.titleKey(), next.requirementKey() == null ? "" : next.requirementKey(),
						mine && Progression.requirementMet(player, next), Progression.costText(next), mine && Progression.canPay(player, next)));
			}
		}
		Progression.Step current = Progression.current(s, Settlement.MAIN);
		ServerPlayNetworking.send(player, new KingdomInfoPayload(s.heart(), s.name(), s.color(), s.logo(),
				current == null ? "" : current.titleKey(), percent, options));
	}

	/** Builds every level of every style at once (used by the CI self-test); returns the number of blocks. */
	public static int buildAllForTest(ServerLevel level, BlockPos start) {
		int count = 0;
		int i = 0;
		for (String style : List.of(Style.TEMPERATE, Style.FROZEN)) {
			for (Progression.Step step : Progression.ORDER.stream().flatMap(t -> Progression.steps(t).stream()).toList()) {
				Template t = Template.get(style + "/" + step.building());
				if (t == null) {
					continue;
				}
				BlockPos heart = start.offset(i * 40, 0, 0);
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
