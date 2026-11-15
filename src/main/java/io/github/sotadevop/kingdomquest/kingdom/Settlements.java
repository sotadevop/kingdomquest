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

	/** The player's own settlement closest to them (a player may rule several, one per charter). */
	public static Settlement nearestOwned(ServerPlayer player) {
		Settlement best = null;
		double bestDist = Double.MAX_VALUE;
		for (Settlement s : all(player.level().getServer())) {
			double d = player.distanceToSqr(Vec3.atCenterOf(s.heart()));
			if (s.owner().equals(player.getUUID()) && d < bestDist) {
				best = s;
				bestDist = d;
			}
		}
		return best;
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
		BlockPos heart = clicked.above();
		if (!SiteWork.isGround(level.getBlockState(clicked))) {
			player.sendOverlayMessage(Component.translatable("kingdomquest.found.ground"));
			return false;
		}
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
						level.setBlock(p, y == 1 ? SiteWork.surface(level, heart) : Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
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
				BlockPos old = BlockPos.of(pos);
				// ground blocks (old paths) become the area's ground again, not holes
				level.setBlock(old, old.getY() < s.heart().getY() ? SiteWork.surface(level, s.heart()) : Blocks.AIR.defaultBlockState(),
						Block.UPDATE_ALL);
			}
		}
		put(server, s.withJob(job, track0, 0).withLevel(track0, step.level()).withBuilt(track0, kept));
	}

	// ---- test tools (/kingdomquest test ...) ----

	/** Finishes the running build of the player's settlement at once. Returns null if there is no settlement. */
	public static Settlement testFinish(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		Settlement s = nearestOwned(player);
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
		Settlement s = nearestOwned(player);
		if (s != null) {
			Production.run(player.level().getServer().overworld(), s);
		}
		return s != null;
	}

	/** Removes the player's settlement and every block its builders placed. */
	public static boolean testReset(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		Settlement s = nearestOwned(player);
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
	 * The blocks of a job for this settlement. Road and lamp segments only appear once a building they lead to
	 * exists (the template's segment rules against the settlement's levels).
	 */
	private static List<Template.Placement> placements(Settlement s, String job) {
		Template t = Template.get(job);
		if (t == null) {
			return List.of();
		}
		StringBuilder mask = new StringBuilder();
		for (Template.Segment seg : t.segments) {
			boolean on = seg.any().stream().anyMatch(r -> s.level(r.getKey()) >= r.getValue());
			mask.append(on ? '1' : '0');
		}
		String key = job + "|" + s.color() + "|" + s.rotation() + "|" + mask;
		return PLACEMENTS.computeIfAbsent(key, k -> {
			List<Template.Placement> all = t.placements(dye(s.color()), Rotation.values()[s.rotation()]);
			if (t.segments.isEmpty()) {
				return all;
			}
			List<Template.Placement> out = new ArrayList<>();
			for (Template.Placement p : all) {
				if (p.segment() < 0 || mask.charAt(p.segment()) == '1') {
					out.add(p);
				}
			}
			return List.copyOf(out);
		});
	}

	/**
	 * After a building is finished, roads and lamps that already exist grow to it: their new segments are
	 * placed at once at the current road/lamp level.
	 */
	private static void extendInfrastructure(MinecraftServer server, Settlement s) {
		ServerLevel level = server.overworld();
		BlockState surface = SiteWork.surface(level, s.heart());
		for (String track : List.of(Progression.ROADS, Progression.LAMPS)) {
			Progression.Step current = Progression.current(s, track);
			if (current == null) {
				continue;
			}
			String job = s.style() + "/" + current.building();
			List<Long> built = new ArrayList<>(s.built().getOrDefault(track, List.of()));
			Set<Long> have = new HashSet<>(built);
			Set<Long> all = new HashSet<>();
			s.built().values().forEach(all::addAll);
			for (Template.Placement p : placements(s, job)) {
				BlockPos pos = s.heart().offset(p.offset());
				if (have.contains(pos.asLong())) {
					continue;
				}
				if (p.offset().getY() < 0) {
					SiteWork.clear(level, s.heart(), new SiteWork.Column(p.offset().getX(), p.offset().getZ(), 0), all, surface);
				}
				level.setBlock(pos, neighbourShaped(p.state(), level, pos), Block.UPDATE_ALL);
				built.add(pos.asLong());
				have.add(pos.asLong());
			}
			s = s.withBuilt(track, built);
		}
		put(server, s);
	}

	private static BlockState neighbourShaped(BlockState state, ServerLevel level, BlockPos pos) {
		if (state.getBlock() instanceof CrossCollisionBlock || state.getBlock() instanceof WallBlock) {
			return Block.updateFromNeighbourShapes(state, level, pos); // fences, panes and walls join their neighbours
		}
		return state;
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
			SiteWork.clear(level, s.heart(), columns.get(index), allBuilt, SiteWork.surface(level, s.heart()));
		}
		// phase 2: place the blocks
		int perTick = instant ? list.size() : Math.max(1, (int) Math.ceil(list.size() / (t.seconds * 12.0)));
		List<Long> built = new ArrayList<>(s.built().getOrDefault(s.jobTrack(), List.of()));
		Set<Long> builtSet = new HashSet<>(built);
		if (index >= cols) {
			for (int n = 0; n < perTick && index < cols + list.size(); n++, index++) {
				Template.Placement p = list.get(index - cols);
				BlockPos pos = s.heart().offset(p.offset());
				BlockState state = neighbourShaped(p.state(), level, pos);
				if (!level.getBlockState(pos).equals(state)) {
					level.setBlock(pos, state, Block.UPDATE_ALL);
					if (n == 0) {
						SoundType sound = state.getSoundType();
						level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1) / 2, sound.getPitch() * 0.8f);
						level.levelEvent(2001, pos, Block.getId(state));
					}
				}
				// crops are harvested normally, so they are not protected
				// ground blocks (roads, farmland, water) are tracked too, so later levelling never touches them
				if (!(state.getBlock() instanceof CropBlock) && builtSet.add(pos.asLong())) {
					built.add(pos.asLong());
				}
			}
		}
		if (index >= cols + list.size()) {
			Settlement done = s.withJob("", "", 0).withBuilt(s.jobTrack(), built);
			put(server, done);
			spawnAnimals(level, s, t);
			if (!Progression.ROADS.equals(s.jobTrack()) && !Progression.LAMPS.equals(s.jobTrack())) {
				extendInfrastructure(server, done.withLevel(s.jobTrack(), done.level(s.jobTrack())));
			}
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
			for (int up = 0; up < 12 && !(level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()); up++) {
				pos = pos.above(); // never inside a block
			}
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
		List<Progression.Step> mainSteps = Progression.steps(Settlement.MAIN);
		for (String track : Progression.ORDER) {
			List<Progression.Step> steps = Progression.steps(track);
			int lvl = s.level(track);
			Progression.Step next = lvl < steps.size() ? steps.get(lvl) : null;
			String lockedBy = next != null && s.level(Settlement.MAIN) < next.minMain() ? mainSteps.get(next.minMain() - 1).titleKey() : "";
			options.add(new KingdomInfoPayload.Option(track, lvl, steps.stream().map(Progression.Step::titleKey).toList(),
					next == null || next.requirementKey() == null ? "" : next.requirementKey(),
					next != null && mine && Progression.requirementMet(player, next), next == null ? "" : Progression.costText(next),
					next != null && mine && Progression.canPay(player, next), lockedBy));
		}
		Progression.Step current = Progression.current(s, Settlement.MAIN);
		ServerPlayNetworking.send(player, new KingdomInfoPayload(s.heart(), s.name(), s.color(), s.logo(),
				current == null ? "" : current.titleKey(), percent, options));
	}

	/**
	 * CI check of the ground work: builds a hill with ponds over a farm area in the sky, levels it and checks
	 * that nothing is left above the ground and no water is left in the foundation.
	 */
	public static List<String> siteTest(ServerLevel level) {
		List<String> errors = new ArrayList<>();
		BlockPos heart = new BlockPos(0, 150, 400);
		String job = "temperate/farm_2";
		Template t = Template.get(job);
		if (t == null) {
			return List.of("missing " + job);
		}
		List<SiteWork.Column> columns = SiteWork.columns(job, t, Rotation.NONE);
		int i = 0;
		for (SiteWork.Column c : columns) {
			BlockPos base = heart.offset(c.dx(), 0, c.dz());
			level.setBlock(base.below(3), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
			boolean pond = i++ % 7 == 0;
			level.setBlock(base.below(2), (pond ? Blocks.WATER : Blocks.DIRT).defaultBlockState(), Block.UPDATE_CLIENTS);
			level.setBlock(base.below(1), (pond ? Blocks.WATER : Blocks.STONE).defaultBlockState(), Block.UPDATE_CLIENTS);
			for (int y = 0; y < 4; y++) {
				level.setBlock(base.above(y), (y == 3 ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState(), Block.UPDATE_CLIENTS);
			}
		}
		BlockState surface = Blocks.GRASS_BLOCK.defaultBlockState();
		for (SiteWork.Column c : columns) {
			SiteWork.clear(level, heart, c, Set.of(), surface);
		}
		int left = 0;
		Map<String, Integer> kinds = new HashMap<>();
		int water = 0;
		int bare = 0;
		for (SiteWork.Column c : columns) {
			BlockPos base = heart.offset(c.dx(), 0, c.dz());
			for (int y = 0; y < 4; y++) {
				BlockState st = level.getBlockState(base.above(y));
				if (!st.isAir()) {
					left++;
					kinds.merge(st.getBlock().getDescriptionId() + "@y" + y, 1, Integer::sum);
				}
			}
			if (!level.getBlockState(base.below(1)).getFluidState().isEmpty() || !level.getBlockState(base.below(2)).getFluidState().isEmpty()) {
				water++;
			}
			if (!level.getBlockState(base.below(1)).is(Blocks.GRASS_BLOCK)) {
				bare++;
			}
		}
		if (left > 0) {
			errors.add(left + " blocks left above the ground after levelling: " + kinds);
		}
		if (water > 0) {
			errors.add(water + " columns still have water under the foundation");
		}
		if (bare > 0) {
			errors.add(bare + " columns without the surface block on top");
		}
		KingdomQuest.LOG.info("Site test: {} columns levelled, {} left, {} wet, {} bare", columns.size(), left, water, bare);
		return errors;
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
