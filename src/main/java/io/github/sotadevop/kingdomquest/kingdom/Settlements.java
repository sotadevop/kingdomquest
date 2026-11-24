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

	/** Minimum distance between two hearts: two sites with their slopes never overlap (ring about 260 x 200, plan reach 62). */
	private static final int SPACING = 400;
	private static final int CONFIRM_TICKS = 200;
	/** Replaced or removed builder blocks never drop items (banners, beds, torches popping off...). */
	private static final int NO_DROPS = Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS;
	/** Creative builds take this long, whatever the size (survival uses the template's time). */
	private static final int CREATIVE_TICKS = 60;
	private static final Map<UUID, Pending> PENDING = new HashMap<>();
	private static final Map<String, List<Template.Placement>> PLACEMENTS = new HashMap<>();
	/** Terrain-following placements, sampled from the world when a job starts (per settlement and job). */
	private static final Map<String, List<Template.Placement>> FOLLOWED = new HashMap<>();
	/** Settlement id -> the columns of its moat (x, 0, z): never filled, levelled or smoothed. */
	private static final Map<UUID, Set<Long>> MOAT = new HashMap<>();
	/** Settlement id -> blocks a repair could not make stay (two plans want the same spot, a block that falls...): not counted again. */
	private static final Map<UUID, Set<Long>> UNFIXABLE = new HashMap<>();
	private static MinecraftServer server;

	private record Pending(BlockPos heart, long tick) {
	}

	private Settlements() {
	}

	/** The running server (null between worlds). */
	static MinecraftServer server() {
		return server;
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(KingdomInfoPayload.TYPE, KingdomInfoPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(UpgradePayload.TYPE, UpgradePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RecolorPayload.TYPE, RecolorPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ActionPayload.TYPE, ActionPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(ActionPayload.TYPE, (payload, context) -> action(context.player(), payload.heart(), payload.action()));
		ServerTickEvents.END_SERVER_TICK.register(RoadLink::tick);
		ServerPlayNetworking.registerGlobalReceiver(RecolorPayload.TYPE, (payload, context) -> recolor(context.player(), payload.heart(), payload.color()));
		ServerPlayNetworking.registerGlobalReceiver(UpgradePayload.TYPE, (payload, context) -> upgrade(context.player(), payload.heart(), payload.track()));
		ServerTickEvents.END_SERVER_TICK.register(Settlements::tick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(srv -> server = srv);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(srv -> {
			server = null;
			FOLLOWED.clear();
			MOAT.clear();
			Grade.forgetAll(srv);
			StreetPlan.forgetAll();
			UNFIXABLE.clear();
		});
		// no monsters spawn inside a settlement
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof net.minecraft.world.entity.monster.Enemy && !Defense.isRaider(entity) && level.dimension() == Level.OVERWORLD && insideSettlement(level.getServer(), entity.blockPosition())) {
				entity.discard();
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(Drawbridge::tick);
		ServerTickEvents.END_SERVER_TICK.register(SignalFires::tick);
		People.register();
		LordChanges.register();
		Guild.register();
		Companion.register();
		Fealty.register();
		Roadside.register();
		PayloadTypeRegistry.clientboundPlay().register(NamePayload.TYPE, NamePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RealmPayload.TYPE, RealmPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(NamePayload.TYPE, NamePayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(NamePayload.TYPE, (payload, context) -> rename(context.player(), payload));
		ServerTickEvents.END_SERVER_TICK.register(Traps::tick);
		Storehouse.register();
		Tram.register();
		Chronicle.register();
		MarketDay.register();
		Realm.registerMapTable();
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
				!(level instanceof ServerLevel server) || !(player instanceof ServerPlayer sp) || allowBreak(server, sp, pos));
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (player instanceof ServerPlayer sp && hand == InteractionHand.MAIN_HAND
					&& (level.getBlockState(hit.getBlockPos()).getBlock() instanceof net.minecraft.world.level.block.LeverBlock
					|| level.getBlockState(hit.getBlockPos()).getBlock() instanceof net.minecraft.world.level.block.ButtonBlock)) {
				if (!Traps.use(sp.level().getServer(), hit.getBlockPos())) {
					Drawbridge.toggle(sp.level().getServer(), hit.getBlockPos());
				}
				return InteractionResult.PASS; // the lever still flips
			}
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

	/** Protected area around a heart by tier: camp 20, hamlet 40, village and up the wall ring and its moat. */
	static boolean insideSettlement(MinecraftServer srv, BlockPos pos) {
		for (Settlement s : all(srv)) {
			int main = s.level(Settlement.MAIN);
			if (Math.abs(pos.getY() - s.heart().getY()) >= 48) {
				continue;
			}
			if (main >= 7) {
				int[] box = Grade.ringBox(s.heart(), Rotation.values()[s.rotation()], round(s));
				if (pos.getX() >= box[0] - 14 && pos.getX() <= box[2] + 14 && pos.getZ() >= box[1] - 14 && pos.getZ() <= box[3] + 14) {
					return true;
				}
				continue;
			}
			int r = main >= 4 ? 40 : 20;
			if (Math.abs(pos.getX() - s.heart().getX()) <= r && Math.abs(pos.getZ() - s.heart().getZ()) <= r) {
				return true;
			}
		}
		return false;
	}

	/** Items that just popped out while builders replaced blocks (attached banners, beds...) are removed. */
	private static void sweepDrops(ServerLevel level, BlockPos a, BlockPos b) {
		net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(Vec3.atLowerCornerOf(a), Vec3.atLowerCornerOf(b)).inflate(2);
		for (net.minecraft.world.entity.item.ItemEntity item : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box,
				i -> i.tickCount <= 2)) {
			item.discard();
		}
	}

	static void putState(MinecraftServer server, Settlement s) {
		put(server, s);
	}

	static void put(MinecraftServer server, Settlement s) {
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
		if (!level.getFluidState(heart).isEmpty()) {
			player.sendOverlayMessage(Component.translatable("kingdomquest.found.water"));
			return false;
		}
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
		// the gate: the side chosen in the charter's menu, or on Auto the gentlest way in (user, 2026-11-19)
		Rotation rotation = data.gate() == CharterData.AUTO ? GateSite.best(level, heart, rotationFor(player.getDirection()))
				: Rotation.values()[Math.clamp(data.gate(), 0, 3)];
		if (p == null || !p.heart().equals(heart) || now - p.tick() > CONFIRM_TICKS) {
			PENDING.put(player.getUUID(), new Pending(heart, now));
			player.sendOverlayMessage(Component.translatable(data.gate() == CharterData.AUTO ? "kingdomquest.found.confirm_auto" : "kingdomquest.found.confirm",
					data.name(), Component.translatable("kingdomquest.side." + GateSite.side(rotation))));
			double easiest = Double.MAX_VALUE;
			for (Rotation r : Rotation.values()) {
				easiest = Math.min(easiest, GateSite.cost(level, heart, r));
			}
			if (data.gate() != CharterData.AUTO) {
				// the lord set the side himself: say so when it is much harder than the side Auto would take
				Rotation auto = GateSite.best(level, heart, rotationFor(player.getDirection()));
				double mine = GateSite.cost(level, heart, rotation), best = GateSite.cost(level, heart, auto);
				if (auto != rotation && mine > 40 && mine > 2 * best) {
					player.sendSystemMessage(Component.translatable("kingdomquest.found.gate_hard", Component.translatable("kingdomquest.side." + GateSite.side(rotation)),
							Component.translatable("kingdomquest.side." + GateSite.side(auto))).withStyle(net.minecraft.ChatFormatting.GOLD));
				}
			}
			if (easiest > GateSite.STEEP_ALL_ROUND) {
				// a hollow or a peak: no side gives a gentle way in (field test: a castle founded 30 blocks down a pit)
				player.sendSystemMessage(Component.translatable("kingdomquest.found.hollow").withStyle(net.minecraft.ChatFormatting.GOLD));
			}
			int relief = relief(level, heart);
			if (relief > 20) {
				player.sendSystemMessage(Component.translatable("kingdomquest.found.steep", relief).withStyle(net.minecraft.ChatFormatting.GOLD));
			}
			return false;
		}
		PENDING.remove(player.getUUID());
		String style = Style.forBiome(level.getBiome(heart).getRegisteredName());
		Settlement s0 = new Settlement(UUID.randomUUID(), player.getUUID(), data.name(), data.color(), data.logo(), style, heart,
				rotation.ordinal(), Map.of(Settlement.MAIN, 1), style + "/tent_1", Settlement.MAIN, 0, Map.of(), Map.of());
		Template t = Template.get(s0.job());
		if (t == null) {
			player.sendOverlayMessage(Component.literal("Missing building " + s0.job()));
			return false;
		}
		Settlement s = found(level, s0, data.look(), data.shape(), false);
		player.sendSystemMessage(Component.translatable("kingdomquest.site.started"));
		level.getServer().getPlayerList().broadcastSystemMessage(Component.translatable("kingdomquest.found.done", player.getDisplayName(), data.name()), false);
		KingdomQuest.LOG.info("Settlement {} founded at {} ({})", data.name(), heart, style);
		return true;
	}

	/**
	 * Founds the settlement: plans its site (the grading plan for every tier, the look from the menu), moves the heart onto
	 * the plateau (or the motte), stores each plot's level and shapes the land around the heart at once, so the tent can
	 * go up; the rest of the site is shaped near the lord as he goes ({@link Grade#work}), or all of it now when
	 * {@code whole}. Returns the stored settlement.
	 */
	static Settlement found(ServerLevel level, Settlement s0, int look, int shape, boolean whole) {
		Rotation rotation = Rotation.values()[s0.rotation()];
		Grade.Planned plan = Grade.plan(level, s0.heart(), rotation, look, shape == 1);
		Settlement s = new Settlement(s0.id(), s0.owner(), s0.name(), s0.color(), s0.logo(), s0.style(), plan.heart(), s0.rotation(), s0.levels(),
				s0.job(), s0.jobTrack(), s0.jobIndex(), s0.built(), s0.names()).withLevel(Grade.LOOK, look).withLevel(Grade.SHAPE, shape);
		for (Map.Entry<String, Integer> e : plan.lifts().entrySet()) {
			if (e.getValue() != 0) {
				s = s.withLevel("lift_" + e.getKey(), e.getValue());
			}
		}
		plan.grade().adopt(level.getServer(), s.id());
		Chronicle.founded(level.getServer(), s);
		if (whole) {
			plan.grade().shapeAll(level, s);
		} else {
			BlockPos h = s.heart();
			plan.grade().ready(level, s, h.getX() - 24, h.getZ() - 24, h.getX() + 24, h.getZ() + 24, Integer.MAX_VALUE);
		}
		Template t = Template.get(s.job());
		if (t != null) {
			prepareSite(level, s.heart(), t, rotation);
		}
		put(level.getServer(), s);
		return s;
	}

	/** Height difference of the land within the future wall ring (sampled every 8 blocks). */
	static int relief(ServerLevel level, BlockPos heart) {
		int lo = Integer.MAX_VALUE;
		int hi = Integer.MIN_VALUE;
		int[] ring = io.github.sotadevop.kingdomquest.building.Layout.ring();
		for (int dx = ring[0]; dx <= ring[2]; dx += 8) {
			for (int dz = ring[1]; dz <= ring[3]; dz += 8) {
				int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, heart.getX() + dx, heart.getZ() + dz);
				lo = Math.min(lo, y);
				hi = Math.max(hi, y);
			}
		}
		return hi - lo;
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
						level.setBlock(p, Blocks.AIR.defaultBlockState(), NO_DROPS);
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
		if (Template.get(template(s, step.building())) == null) {
			return;
		}
		if (Progression.DOCK.equals(track) && s.level("dock_set") == 0) {
			if (!findDockSite(server, s)) {
				player.sendOverlayMessage(Component.translatable("kingdomquest.dock.no_water"));
				return;
			}
			s = byHeart(server, heart);
		}
		Progression.pay(player, step);
		start(server, s, track, step);
		player.sendOverlayMessage(Component.translatable("kingdomquest.upgrade.started", Component.translatable(step.titleKey())));
	}

	/** Add-ons that stand on their own terrace: at the height of the land where they are built. */
	static final Set<String> LIFTED = Set.of("farm", "smith", "wood", "library", "brewery", "stables", "barracks", "guild", "apiary", "kennel",
			"trophy", "tavern", "market", "cartographer", "music", "workshop", "armorer", "tannery", "archaeology", "mine", "nether", "royal",
			Progression.STOREHOUSE, Progression.CHURCH, Progression.TOWNHALL, Progression.GARDEN, Progression.MARKETHALL, Progression.BELFRY, Progression.MONASTERY,
			"mill", "bakery", "herbs", "weaver", "dyehouse", "carpenter", "sawmill", "smelter", "saddler");

	/** The track (key of {@link Settlement#built()}) a job's building belongs to. */
	static String builtTrack(String job) {
		String b = trackOf(job);
		return switch (b) {
			case "road" -> Progression.ROADS;
			case "lamp" -> Progression.LAMPS;
			case "wall" -> Progression.WALLS;
			case "tent", "hamlet", "village", "town", "castle", "citadel" -> Settlement.MAIN;
			default -> b;
		};
	}

	static String trackOf(String job) {
		String b = job.substring(job.indexOf('/') + 1);
		int i = b.lastIndexOf('_');
		return i < 0 ? b : b.substring(0, i);
	}

	/**
	 * How many blocks above (or below) the heart the job's building stands: its plot's level on the grading plan, stored
	 * at the founding (0 on a flat site). Walls, roads and lamps follow the graded land; the main building is the heart's.
	 */
	static int lift(Settlement s, String job) {
		return trackLift(s, builtTrack(job));
	}

	static int trackLift(Settlement s, String track) {
		return switch (track) {
			case Settlement.MAIN, Progression.ROADS, Progression.LAMPS, Progression.WALLS, Progression.DOCK, Progression.POSTERN, Progression.TRAM -> 0;
			default -> s.level("lift_" + track);
		};
	}

	/** The level of a track's spot (base frame x, z): the track's plot level. */
	static int liftAt(Settlement s, String track, int x, int z) {
		return trackLift(s, track);
	}

	/** The columns of the settlement's moat (empty before gate level 3). */
	static Set<Long> moatColumns(Settlement s) {
		Progression.Step gate = s.level(Progression.GATE) >= 3 ? Progression.current(s, Progression.GATE) : null;
		if (gate == null) {
			return Set.of();
		}
		placements(s, template(s, gate.building())); // works the moat out if it hasn't been yet
		return MOAT.getOrDefault(s.id(), Set.of());
	}

	/**
	 * Roads and lamps never hang: where the ground under a road block or a lamp post (or the block of land it stands on)
	 * was taken away by later site work, earth is put back under it down to solid ground (12 blocks at most, never in the
	 * moat or water; deeper than that it is a ravine and left as it is). Field test, 2026-11-18: roads over air and lamps
	 * on hanging turf after the room around buildings was cut.
	 */
	static void carryRoads(ServerLevel level, Settlement s, BlockState surface) {
		Set<Long> all = new HashSet<>();
		s.built().values().forEach(all::addAll);
		Map<Long, Integer> lowest = new HashMap<>();
		for (String track : List.of(Progression.ROADS, "lamps")) {
			for (long p : s.built().getOrDefault(track, List.of())) {
				BlockPos q = BlockPos.of(p);
				if (!level.getBlockState(q).isAir()) {
					lowest.merge(BlockPos.asLong(q.getX(), 0, q.getZ()), q.getY(), Math::min);
				}
			}
		}
		Set<Long> moat = moatColumns(s);
		BlockState fill = SiteWork.subsoil(surface);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (Map.Entry<Long, Integer> e : lowest.entrySet()) {
			if (moat.contains(e.getKey())) {
				continue;
			}
			int x = BlockPos.getX(e.getKey()), z = BlockPos.getZ(e.getKey());
			int y = e.getValue() - 1;
			int land = 0; // a block or two of land it stands on may itself hang
			while (land < 2 && y > e.getValue() - 4) {
				BlockState st = level.getBlockState(pos.set(x, y, z));
				if (st.isAir() || st.canBeReplaced() || !SiteWork.natural(st) || all.contains(pos.asLong())) {
					break;
				}
				land++;
				y--;
			}
			List<BlockPos> gap = new ArrayList<>();
			boolean held = false;
			for (int down = 0; down < 12; down++, y--) {
				BlockState st = level.getBlockState(pos.set(x, y, z));
				if (!st.getFluidState().isEmpty()) {
					break;
				}
				if (st.isAir() || st.canBeReplaced() && !all.contains(pos.asLong())) {
					gap.add(pos.immutable());
					continue;
				}
				held = true;
				break;
			}
			if (held) {
				for (BlockPos g : gap) {
					level.setBlock(g, fill, NO_DROPS);
				}
			}
		}
	}

	/**
	 * The ground just outside every door the settlement built (10 blocks straight out, 3 wide; 5 wide for the first
	 * 2): the step and the way up or down to it. Site grading and smoothing leave it alone, or a later upgrade of the main
	 * building would grade away the land in front of a raised house and leave its door hanging (field test).
	 */
	static Set<Long> doorApproaches(ServerLevel level, Settlement s) {
		return doorApproaches(level, s, "");
	}

	/** {@link #doorApproaches(ServerLevel, Settlement)} without the doors of track {@code except}. */
	static Set<Long> doorApproaches(ServerLevel level, Settlement s, String except) {
		Set<Long> out = new HashSet<>();
		for (Map.Entry<String, List<Long>> e : s.built().entrySet()) {
			if (e.getKey().equals(except)) {
				continue;
			}
			for (long p : e.getValue()) {
				BlockPos d = BlockPos.of(p);
				BlockState st = level.getBlockState(d);
				if (!(st.getBlock() instanceof net.minecraft.world.level.block.DoorBlock)
						|| st.getValue(net.minecraft.world.level.block.DoorBlock.HALF) != net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) {
					continue;
				}
				// both sides: templates have doors facing in and doors facing out; the inside is the building anyway
				net.minecraft.core.Direction f0 = st.getValue(net.minecraft.world.level.block.DoorBlock.FACING);
				for (net.minecraft.core.Direction f : new net.minecraft.core.Direction[] {f0, f0.getOpposite()}) {
					for (int k = 1; k <= 10; k++) { // the whole way up or down from the door, not just its step
						int wide = k <= 2 ? 2 : 1;
						for (int side = -wide; side <= wide; side++) {
							BlockPos q = d.relative(f, k).relative(f.getClockWise(), side);
							out.add(BlockPos.asLong(q.getX(), 0, q.getZ()));
						}
					}
				}
			}
		}
		return out;
	}

	/** Starts building {@code step} on {@code track}: takes down the track's blocks the new level doesn't use, clears the ground. */
	static void start(MinecraftServer server, Settlement first, String track, Progression.Step step) {
		Settlement s0 = first;
		String job = template(s0, step.building());
		String track0 = track;
		ServerLevel level = server.overworld();
		if (Progression.DOCK.equals(track) && s0.level("dock_set") == 0) {
			if (!findDockSite(server, s0)) {
				return; // no water near: no dock (it used to be built on the square in that case)
			}
			s0 = byHeart(server, s0.heart());
		}
		Settlement s = s0;
		FOLLOWED.keySet().removeIf(k -> k.startsWith(s.id() + "|" + job + "|")); // sample the ground again
		List<Template.Placement> list = placements(s, job);
		Set<Long> next = new HashSet<>();
		Map<Long, Integer> nextLow = new HashMap<>(); // column -> lowest block of the new plan
		for (Template.Placement p : list) {
			BlockPos at = s.heart().offset(p.offset());
			next.add(at.asLong());
			nextLow.merge(BlockPos.asLong(at.getX(), 0, at.getZ()), at.getY(), Math::min);
		}
		// blocks of this track's old level that the new one doesn't use are taken down (they drop nothing)
		List<Long> kept = new ArrayList<>();
		Set<Long> moat = Progression.GATE.equals(track) ? MOAT.getOrDefault(s.id(), Set.of()) : Set.of();
		BlockPos lo = s.heart().offset(-80, -20, -80);
		BlockPos hi = s.heart().offset(80, 30, 80);
		for (long pos : s.built().getOrDefault(track, List.of())) {
			if (next.contains(pos)) {
				kept.add(pos);
			} else {
				BlockPos old = BlockPos.of(pos);
				// ground blocks (old paths) become the area's ground again, not holes; the moat stays a moat
				long column = BlockPos.asLong(old.getX(), 0, old.getZ());
				boolean ground = old.getY() < s.heart().getY() + lift(s, job) && !moat.contains(column);
				// under a moat that got shallower the old ditch is filled with earth, not left as a hollow
				boolean under = moat.contains(column) && old.getY() < nextLow.getOrDefault(column, Integer.MIN_VALUE);
				BlockState surface = SiteWork.surface(level, s.heart());
				level.setBlock(old, under ? SiteWork.subsoil(surface) : ground ? surface : Blocks.AIR.defaultBlockState(), NO_DROPS);
			}
		}
		sweepDrops(level, lo, hi);
		{
			// the plan being replaced, to see which spots the upgrade changes (the lord's changes stay where it doesn't)
			Map<Long, BlockState> was = new HashMap<>();
			Progression.Step cur = Progression.current(s, track0);
			if (cur != null && !LordChanges.of(server, s).isEmpty()) {
				for (Template.Placement p : placements(s, template(s, cur.building()))) {
					was.put(s.heart().offset(p.offset()).asLong(), p.state());
				}
			}
			OLD_PLAN.put(s.id() + "|" + job, was);
		}
		put(server, s.withJob(job, track0, 0).withLevel(track0, step.level()).withBuilt(track0, kept));
		People.sendWorkers(level, s, list.size(), server.getPlayerList().getPlayer(s.owner()));
	}

	/** Menu actions: drawbridge, roads to the next kingdom or village. */
	static void action(ServerPlayer player, BlockPos heart, String action) {
		MinecraftServer srv = player.level().getServer();
		Settlement s = byHeart(srv, heart);
		if (s == null || !s.owner().equals(player.getUUID())) {
			return;
		}
		switch (action) {
			case "bridge" -> {
				if (Drawbridge.toggle(srv, Drawbridge.lever(s))) {
					player.sendOverlayMessage(Component.translatable(s.level(Drawbridge.STATE) == 1 ? "kingdomquest.bridge.lowering" : "kingdomquest.bridge.raising"));
				}
			}
			case "link_kingdom" -> RoadLink.connect(player, s, false);
			case "link_village" -> RoadLink.connect(player, s, true);
			case "repair" -> startRepair(player, s);
			case "undo_changes" -> undoChanges(player, s);
			case "rebuild" -> startRebuild(player, s);
			default -> {
				if (action.equals("chronicle")) {
					Chronicle.send(player, s);
				} else if (action.equals("realm")) {
					Realm.send(player, s);
				} else if (action.startsWith("realm")) {
					Realm.handle(player, s, action);
				}
			}
		}
	}

	/**
	 * Rebuilds everything that stands, track by track, from the mod's current plans: after an update to the
	 * buildings or the way land is shaped, an existing kingdom gets the new version. Nothing is paid again.
	 */
	private static void startRebuild(ServerPlayer player, Settlement s) {
		if (!s.job().isEmpty() || !s.names().getOrDefault("rebuild", "").isEmpty()) {
			player.sendOverlayMessage(Component.translatable("kingdomquest.rebuild.busy"));
			return;
		}
		List<String> queue = new ArrayList<>();
		for (String track : Progression.ORDER) {
			if (Progression.current(s, track) != null && !track.equals(Progression.DOCK)) {
				queue.add(track);
			}
		}
		putState(player.level().getServer(), s.withName("rebuild", String.join(",", queue)));
		player.sendSystemMessage(Component.translatable("kingdomquest.rebuild.start", queue.size()));
	}

	/** The next track of a running rebuild, once the builders are free. */
	private static void rebuildTick(MinecraftServer server, Settlement s) {
		String queue = s.names().getOrDefault("rebuild", "");
		if (queue.isEmpty() || !s.job().isEmpty() || !server.overworld().isLoaded(s.heart())) {
			return;
		}
		int cut = queue.indexOf(',');
		String track = cut < 0 ? queue : queue.substring(0, cut);
		Settlement rest = s.withName("rebuild", cut < 0 ? "" : queue.substring(cut + 1));
		Progression.Step step = Progression.current(rest, track);
		if (step == null || Template.get(template(rest, step.building())) == null) {
			put(server, rest);
			return;
		}
		put(server, rest);
		start(server, rest, track, step);
		if (cut < 0) {
			ServerPlayer owner = server.getPlayerList().getPlayer(s.owner());
			if (owner != null) {
				owner.sendSystemMessage(Component.translatable("kingdomquest.rebuild.last"));
			}
		}
	}

	/** Per job: how many of the lord's changes the new plan replaced (told when the job is done). */
	private static final Map<String, Integer> REPLACED = new HashMap<>();
	/** Per job: the plan of the level being replaced (position -> block), to see which spots an upgrade changes. */
	private static final Map<String, Map<Long, BlockState>> OLD_PLAN = new HashMap<>();

	/** What the current level of the job's track had at each spot (empty for a first build). */
	private static Map<Long, BlockState> oldPlan(Settlement s) {
		// kept from the job's start (the track's level is raised then); after a restart nothing is known: the
		// lord's changes are then all kept, the safe side
		return OLD_PLAN.getOrDefault(s.id() + "|" + s.job(), null);
	}

	/** Puts the plan back where the lord changed it: every changed spot of a built level gets its planned block again. */
	private static void undoChanges(ServerPlayer player, Settlement s) {
		MinecraftServer server = player.level().getServer();
		ServerLevel level = server.overworld();
		Map<Long, BlockState> changes = LordChanges.of(server, s);
		if (changes.isEmpty() || !s.job().isEmpty()) {
			return;
		}
		int restored = 0;
		Settlement now = s;
		for (String track : Progression.ORDER) {
			Progression.Step step = Progression.current(s, track);
			if (step == null) {
				continue;
			}
			List<Long> built = new ArrayList<>(now.built().getOrDefault(track, List.of()));
			Set<Long> builtSet = new HashSet<>(built);
			for (Template.Placement p : placements(s, template(s, step.building()))) {
				BlockPos pos = s.heart().offset(p.offset());
				if (!changes.containsKey(pos.asLong()) || p.state().isAir() || p.state().getBlock() instanceof CropBlock) {
					continue;
				}
				if (level.getBlockState(pos).getBlock() != p.state().getBlock()) {
					level.destroyBlock(pos, true); // what the lord put there drops for him
					level.setBlock(pos, neighbourShaped(p.state(), level, pos), NO_DROPS);
					restored++;
				}
				if (builtSet.add(pos.asLong())) {
					built.add(pos.asLong());
				}
			}
			now = now.withBuilt(track, built);
		}
		put(server, now);
		LordChanges.forget(server, s, changes.keySet());
		People.say(player, "Architect", restored == 0 ? "Everything is as you left it; I only forgot your changes."
				: "I put " + restored + (restored == 1 ? " block" : " blocks") + " back as planned. Blocks you added where the plan has nothing stay.");
	}

	private static final Map<UUID, List<Template.Placement>> REPAIRS = new HashMap<>();
	private static final Map<UUID, int[]> REPAIR_AT = new HashMap<>();

	/** Blocks of all built levels that are missing or wrong in the world (attacks, griefing). Doors etc. count by type. */
	static List<Template.Placement> damaged(ServerLevel level, Settlement s) {
		List<Template.Placement> out = new ArrayList<>();
		Set<Long> unfixable = new HashSet<>(UNFIXABLE.getOrDefault(s.id(), Set.of()));
		unfixable.addAll(LordChanges.of(level.getServer(), s).keySet()); // the lord's own changes are not damage
		for (String track : Progression.ORDER) {
			Progression.Step step = Progression.current(s, track);
			if (step == null) {
				continue;
			}
			Template t = Template.get(template(s, step.building()));
			if (t == null || track.equals(s.jobTrack())) {
				continue;
			}
			// every block the builders placed for this track (walls, roads, foundations, moat bridge...) is checked
			Set<Long> mine = new HashSet<>(s.built().getOrDefault(track, List.of()));
			for (Template.Placement p : placements(s, template(s, step.building()))) {
				if (p.state().isAir() || p.state().getBlock() instanceof CropBlock || !p.state().getFluidState().isEmpty()) {
					continue;
				}
				BlockPos pos = s.heart().offset(p.offset());
				if (!mine.contains(pos.asLong()) || Drawbridge.moving(s, pos) || unfixable.contains(pos.asLong())) {
					continue;
				}
				if (level.getBlockState(pos).getBlock() != p.state().getBlock()) {
					out.add(new Template.Placement(p.offset(), p.state(), p.segment(), p.anchor()));
				}
			}
		}
		return out;
	}

	private static void startRepair(ServerPlayer player, Settlement s) {
		ServerLevel level = player.level().getServer().overworld();
		List<Template.Placement> list = damaged(level, s);
		if (list.isEmpty() || REPAIRS.containsKey(s.id())) {
			return;
		}
		List<Template.Placement> ordered = new ArrayList<>(list);
		ordered.sort(java.util.Comparator.comparingInt((Template.Placement p) -> attached(p.state()) ? 1 : 0));
		REPAIRS.put(s.id(), ordered);
		REPAIR_AT.remove(s.id());
		People.sendWorkers(level, s, list.size(), null);
		People.say(player, "Architect", "My builders will repair " + list.size() + " blocks.");
	}

	private static void repairTick(MinecraftServer server, Settlement s) {
		List<Template.Placement> list = REPAIRS.get(s.id());
		if (list == null) {
			return;
		}
		ServerLevel level = server.overworld();
		ServerPlayer owner = server.getPlayerList().getPlayer(s.owner());
		int n = owner != null && owner.isCreative() ? 40 : 2;
		// in the order of the plan: what hangs on other blocks (torches, ladders, banners, doors) comes after them.
		// It used to run backwards, so those were set first, fell off again and the count never reached zero.
		int[] at = REPAIR_AT.computeIfAbsent(s.id(), k -> new int[1]);
		for (int i = 0; i < n && at[0] < list.size(); i++) {
			Template.Placement p = list.get(at[0]++);
			BlockPos pos = s.heart().offset(p.offset());
			level.setBlock(pos, neighbourShaped(p.state(), level, pos), NO_DROPS);
			if (i == 0) {
				level.levelEvent(2001, pos, Block.getId(p.state()));
				if (server.getTickCount() % 40 == 0) {
					People.herdWorkers(level, s, pos);
				}
			}
		}
		if (at[0] >= list.size()) {
			REPAIRS.remove(s.id());
			REPAIR_AT.remove(s.id());
			// whatever is still wrong right after being set can't be repaired (two plans share the spot, the block
			// can't stay there): it is left out from now on, so the count always comes down to zero
			Set<Long> tried = new HashSet<>();
			for (Template.Placement p : list) {
				tried.add(s.heart().offset(p.offset()).asLong());
			}
			for (Template.Placement p : damaged(level, s)) {
				long pos = s.heart().offset(p.offset()).asLong();
				if (tried.contains(pos)) {
					UNFIXABLE.computeIfAbsent(s.id(), k -> new HashSet<>()).add(pos);
				}
			}
			People.removeWorkers(level, s);
			if (owner != null) {
				People.say(owner, "Architect", s.name() + " is as good as new.");
			}
		}
	}

	/** The Steward tells the lord which upgrades are open and what they still need. */
	static void stewardAdvice(ServerPlayer player, Settlement s) {
		List<String> lines = new ArrayList<>();
		for (String track : Progression.ORDER) {
			Progression.Step next = Progression.next(s, track);
			if (next == null || lines.size() >= 3) {
				continue;
			}
			String title = Component.translatable(next.titleKey()).getString();
			if (!Progression.requirementMet(player, next)) {
				lines.add(title + ": first " + Component.translatable(next.requirementKey()).getString().toLowerCase(java.util.Locale.ROOT) + ".");
			} else if (!Progression.canPay(player, next)) {
				lines.add(title + ": bring " + Progression.costText(next) + ".");
			} else {
				lines.add(title + ": ready to build!");
			}
		}
		People.say(player, "Steward", lines.isEmpty() ? "Everything is built, my lord. The realm is complete." : "Here is what the realm needs next:");
		lines.forEach(l -> player.sendSystemMessage(Component.literal(" - " + l)));
	}

	/** The lord chose a name (pets). */
	static void rename(ServerPlayer player, NamePayload p) {
		MinecraftServer srv = player.level().getServer();
		Settlement s = byHeart(srv, p.heart());
		String name = p.name().strip();
		if (s == null || !s.owner().equals(player.getUUID()) || name.isEmpty() || name.length() > 24 || !p.key().startsWith("pet_")) {
			return;
		}
		put(srv, s.withName(p.key(), name));
		Collections.rename(srv, s, p.key().substring(4), name);
	}

	/** Swaps every kingdom-colored block (banners, beds, carpets, wool) of all built levels to a new color. */
	static void recolor(ServerPlayer player, BlockPos heart, int color) {
		MinecraftServer server = player.level().getServer();
		Settlement s = byHeart(server, heart);
		if (s == null || !s.owner().equals(player.getUUID()) || !s.job().isEmpty() || color < 0 || color > 15 || color == s.color()
				|| player.distanceToSqr(Vec3.atCenterOf(heart)) > 64 * 64) {
			return;
		}
		ServerLevel level = server.overworld();
		Settlement after = s.withColor(color);
		for (String track : Progression.ORDER) {
			Progression.Step step = Progression.current(s, track);
			if (step == null) {
				continue;
			}
			String job = template(s, step.building());
			List<Template.Placement> before = placements(s, job);
			List<Template.Placement> now = placements(after, job);
			for (int i = 0; i < Math.min(before.size(), now.size()); i++) {
				if (!before.get(i).state().equals(now.get(i).state())) {
					BlockPos pos = s.heart().offset(now.get(i).offset());
					if (level.getBlockState(pos).getBlock() == before.get(i).state().getBlock()) {
						// no shape updates: a bed half must not break while its other half still has the old color
						level.setBlock(pos, now.get(i).state(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
					}
				}
			}
		}
		put(server, after);
		player.sendOverlayMessage(Component.translatable("kingdomquest.recolored", s.name()));
		sendInfo(player, after);
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

	public static boolean testAttack(ServerPlayer player) {
		Settlement s = nearestOwned(player);
		if (s != null) {
			Defense.start(player.level().getServer(), s, player);
		}
		return s != null;
	}

	public static boolean testAttacksToggle(ServerPlayer player) {
		Settlement s = nearestOwned(player);
		if (s != null) {
			boolean off = s.level(Defense.OFF) == 0;
			put(player.level().getServer(), s.withLevel(Defense.OFF, off ? 1 : 0));
			player.sendSystemMessage(Component.literal(off ? "Attacks are off for " + s.name() : "Attacks are on for " + s.name()));
		}
		return s != null;
	}

	/** Runs production as if a game day had passed. */
	public static boolean testProduce(ServerPlayer player) {
		Settlement s = nearestOwned(player);
		if (s != null) {
			ServerLevel level = player.level().getServer().overworld();
			Storehouse.Stock stock = Storehouse.stock(level.getServer(), s);
			stock.last = (stock.last <= 0 ? level.getGameTime() : stock.last) - Production.DAY;
			stock.save(level.getServer(), s);
			Production.produce(level, s, level.getGameTime());
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
		People.removeAll(level, s);
		for (List<Long> list : s.built().values()) {
			for (long pos : list) {
				level.setBlock(BlockPos.of(pos), Blocks.AIR.defaultBlockState(), NO_DROPS);
			}
		}
		level.setBlock(s.heart(), Blocks.AIR.defaultBlockState(), NO_DROPS);
		List<Settlement> rest = new ArrayList<>(all(server));
		rest.removeIf(o -> o.id().equals(s.id()));
		server.overworld().setAttached(DATA, List.copyOf(rest));
		return true;
	}

	/**
	 * The blocks of a job for this settlement. Road and lamp segments only appear once a building they lead to
	 * exists (the template's segment rules against the settlement's levels).
	 */
	/** The dock's origin (first water block at the shore) relative to the heart, and its facing (toward the water). */
	static BlockPos dockOffset(Settlement s) {
		return new BlockPos(s.level("dock_x"), s.level("dock_y"), s.level("dock_z"));
	}

	static Rotation dockRotation(Settlement s) {
		return Rotation.values()[Math.floorMod(s.level("dock_r"), 4)];
	}

	/** A point in the dock's local frame (shore at z = 0, water toward +z) in world coordinates. */
	static BlockPos dockPoint(Settlement s, int x, int y, int z) {
		return s.heart().offset(dockOffset(s)).offset(Template.rotate(new BlockPos(x, y, z), dockRotation(s)));
	}

	/**
	 * Looks for the nearest water at least 2 deep within 160 blocks and stores where the dock goes: the first water
	 * block from the shore, facing out over the water. Returns false if there is none.
	 */
	static boolean findDockSite(MinecraftServer srv, Settlement s) {
		ServerLevel level = srv.overworld();
		BlockPos h = s.heart();
		java.util.Set<Long> small = new HashSet<>();
		for (int r = 12; r <= 256; r += 4) {
			for (int i = 0; i < r * 2; i++) {
				double a = Math.PI * i / r;
				int x = h.getX() + (int) Math.round(Math.cos(a) * r);
				int z = h.getZ() + (int) Math.round(Math.sin(a) * r);
				int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
				BlockPos p = new BlockPos(x, y, z);
				if (!level.getFluidState(p).isSource() || !level.getFluidState(p.below()).isSource() || !level.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER)) {
					continue;
				}
				if (small.contains(p.asLong()) || !bigWater(level, p, small)) {
					continue; // ponds don't get a harbor: only a river, a lake or the sea
				}
				// walk back toward the heart to the shore
				int dx = Integer.signum(h.getX() - x);
				int dz = Integer.signum(h.getZ() - z);
				boolean alongX = Math.abs(h.getX() - x) >= Math.abs(h.getZ() - z);
				BlockPos water = p;
				boolean shore = false;
				for (int step = 0; step < 40; step++) {
					BlockPos next = alongX ? water.offset(dx, 0, 0) : water.offset(0, 0, dz);
					if (!level.getFluidState(next).is(net.minecraft.tags.FluidTags.WATER)) {
						shore = true;
						break;
					}
					water = next;
				}
				if (!shore) {
					continue; // still open water after 40 blocks: the pier would stand free in the sea
				}
				boolean open = true;
				for (int k = 1; k <= 14 && open; k++) {
					BlockPos out = alongX ? water.offset(-dx * k, 0, 0) : water.offset(0, 0, -dz * k);
					open = level.getFluidState(out).is(net.minecraft.tags.FluidTags.WATER);
				}
				if (!open) {
					continue; // the pier needs open water ahead
				}
				if (!dockFits(level, s, water)) {
					continue; // inside the walls (that water becomes land) or in front of a door or on a building
				}
				// facing: from the shore out over the water (away from the heart)
				Rotation rot = alongX ? (dx > 0 ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90) : (dz > 0 ? Rotation.CLOCKWISE_180 : Rotation.NONE);
				BlockPos rel = water.subtract(h);
				putState(srv, s.withLevel("dock_x", rel.getX()).withLevel("dock_y", rel.getY()).withLevel("dock_z", rel.getZ())
						.withLevel("dock_r", rot.ordinal()).withLevel("dock_set", 1));
				return true;
			}
		}
		return false;
	}

	/**
	 * A harbor lies outside the walls, beyond the moat and the shore (water inside the ring is made into land), and
	 * clear of every building and every door's approach (field test: a pier stood in front of the trophy hall).
	 */
	private static boolean dockFits(ServerLevel level, Settlement s, BlockPos water) {
		BlockPos h = s.heart();
		Rotation turn = Rotation.values()[s.rotation()];
		int[] ring = io.github.sotadevop.kingdomquest.building.Layout.ring(round(s));
		BlockPos a = h.offset(Template.rotate(new BlockPos(ring[0] - 18, 0, ring[1] - 18), turn));
		BlockPos b = h.offset(Template.rotate(new BlockPos(ring[2] + 18, 0, ring[3] + 18), turn));
		int minX = Math.min(a.getX(), b.getX()), maxX = Math.max(a.getX(), b.getX());
		int minZ = Math.min(a.getZ(), b.getZ()), maxZ = Math.max(a.getZ(), b.getZ());
		if (water.getX() >= minX && water.getX() <= maxX && water.getZ() >= minZ && water.getZ() <= maxZ) {
			return false;
		}
		Set<Long> taken = new HashSet<>(doorApproaches(level, s));
		s.built().forEach((track, blocks) -> {
			for (long p : blocks) {
				taken.add(BlockPos.asLong(BlockPos.getX(p), 0, BlockPos.getZ(p)));
			}
		});
		for (int dx = -8; dx <= 8; dx++) {
			for (int dz = -8; dz <= 8; dz++) {
				if (taken.contains(BlockPos.asLong(water.getX() + dx, 0, water.getZ() + dz))) {
					return false;
				}
			}
		}
		return true;
	}

	/** True if the water surface around {@code p} is at least 400 blocks big; smaller ones are remembered in {@code small}. */
	private static boolean bigWater(ServerLevel level, BlockPos p, java.util.Set<Long> small) {
		java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
		java.util.Set<Long> seen = new HashSet<>();
		queue.add(p);
		seen.add(p.asLong());
		while (!queue.isEmpty() && seen.size() < 400) {
			BlockPos c = queue.poll();
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos n = c.relative(d);
				if (seen.size() < 400 && level.getFluidState(n).is(net.minecraft.tags.FluidTags.WATER) && level.getBlockState(n.above()).isAir()
						&& seen.add(n.asLong())) {
					queue.add(n);
				}
			}
		}
		if (seen.size() >= 400) {
			return true;
		}
		small.addAll(seen);
		return false;
	}

	/**
	 * The town houses of the main track (user, 2026-11-24): each stands on the plateau, wherever the main building
	 * stands (a hill castle's keep is on its motte). Heart-relative column -> extra lift, or MIN_VALUE where the house's
	 * spot isn't flat (a hill of the look): that house isn't built.
	 */
	private static Map<Long, Integer> houseLifts(Settlement s) {
		return houseLifts(s, 0);
	}

	/** As {@link #houseLifts(Settlement)}, each house's area grown by {@code margin} (the ground worked around it). */
	private static Map<Long, Integer> houseLifts(Settlement s, int margin) {
		List<int[]> houses = io.github.sotadevop.kingdomquest.building.Layout.houses(s.style());
		Grade g = server == null || houses.isEmpty() ? null : Grade.of(server, s);
		if (g == null) {
			return Map.of();
		}
		Rotation r = Rotation.values()[s.rotation()];
		Map<Long, Integer> out = new HashMap<>();
		for (int[] h : houses) {
			int lift = g.plateau + 1 - s.heart().getY();
			for (int[] c : new int[][] {{h[0], h[1]}, {h[2], h[1]}, {h[0], h[3]}, {h[2], h[3]}, {(h[0] + h[2]) / 2, (h[1] + h[3]) / 2}}) {
				BlockPos q = s.heart().offset(io.github.sotadevop.kingdomquest.building.Layout.rel(r, c[0], 0, c[1]));
				if (g.topAt(q.getX(), q.getZ()) != g.plateau) {
					lift = Integer.MIN_VALUE;
				}
			}
			for (int x = h[0] - margin; x <= h[2] + margin; x++) {
				for (int z = h[1] - margin; z <= h[3] + margin; z++) {
					BlockPos o = io.github.sotadevop.kingdomquest.building.Layout.rel(r, x, 0, z);
					out.putIfAbsent(BlockPos.asLong(o.getX(), 0, o.getZ()), lift);
				}
			}
		}
		return out;
	}

	private static List<Template.Placement> basePlacements(Settlement s, String job) {
		Template t = Template.get(job);
		if (t == null) {
			return List.of();
		}
		if (t.anchored) {
			BlockPos off = dockOffset(s);
			String key = job + "|" + s.color() + "|" + off.asLong() + "|" + s.level("dock_r");
			return PLACEMENTS.computeIfAbsent(key, k -> {
				List<Template.Placement> out = new ArrayList<>();
				for (Template.Placement p : t.placements(dye(s.color()), dockRotation(s))) {
					out.add(new Template.Placement(p.offset().offset(off), p.state(), p.segment(), p.anchor()));
				}
				out.sort(java.util.Comparator.comparingInt((Template.Placement p) -> attached(p.state()) ? 1 : 0));
				return List.copyOf(out);
			});
		}
		StringBuilder mask = new StringBuilder();
		for (Template.Segment seg : t.segments) {
			// a rule (track, level) holds once the track has the level; (track, -level) while it hasn't yet (a wall's plug
			// stays until the postern that opens it is built)
			boolean on = seg.any().stream().anyMatch(r -> {
				int v = r.getValue();
				if (r.getKey().equals(Progression.POSTERN)) {
					return posternOn(s, Math.abs(v)) == v > 0;
				}
				return v >= 0 ? s.level(r.getKey()) >= v : s.level(r.getKey()) < -v;
			});
			mask.append(on ? '1' : '0');
		}
		int dy = lift(s, job);
		String track = builtTrack(job); // "wall_3" is the walls track, "road_2" the roads
		boolean red = (track.equals(Progression.ROADS) || track.equals(Progression.WALLS) || track.equals(Progression.GATE)) && redLand(s);
		Map<Long, Integer> houseLift = Settlement.MAIN.equals(track) ? houseLifts(s) : Map.of();
		String key = job + "|" + s.color() + "|" + s.rotation() + "|" + mask + "|" + dy + (red ? "|red" : "")
				+ (houseLift.isEmpty() ? "" : "|" + s.id());
		return PLACEMENTS.computeIfAbsent(key, k -> {
			List<Template.Placement> all = new ArrayList<>();
			for (Template.Placement p : t.placements(dye(s.color()), Rotation.values()[s.rotation()])) {
				BlockState st = red ? reddened(p.state()) : p.state();
				int extra = 0;
				if (!houseLift.isEmpty()) {
					Integer h = houseLift.get(BlockPos.asLong(p.offset().getX(), 0, p.offset().getZ()));
					if (h != null) {
						if (h == Integer.MIN_VALUE) {
							continue; // a town house whose spot isn't flat (a hill of the look): it isn't built
						}
						extra = h;
					}
				}
				all.add(dy + extra == 0 && !red ? p : new Template.Placement(p.offset().above(dy + extra), st, p.segment(), p.anchor()));
			}
			// blocks that hang on others (ladders, torches, banners, doors, beds...) come after the solid ones
			all.sort(java.util.Comparator.comparingInt((Template.Placement p) -> attached(p.state()) ? 1 : 0));
			if (t.segments.isEmpty()) {
				return List.copyOf(all);
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

	/** Whether the settlement's castle is round (founding menu; 19.11). */
	public static boolean round(Settlement s) {
		return s.level(Grade.SHAPE) == 1;
	}

	/**
	 * The template id of one of a settlement's buildings: a round castle's own version (walls, gate, posterns, roads,
	 * lamps: tools/buildings.py writes them to <style>_round/) where there is one, else the style's.
	 */
	public static String template(Settlement s, String building) {
		if (round(s)) {
			String id = s.style() + "_round/" + building;
			if (Template.get(id) != null) {
				return id;
			}
		}
		return s.style() + "/" + building;
	}

	private static final Map<String, Boolean> POSTERN_FITS = new HashMap<>();

	/**
	 * Whether side gate n stands: bought, and its spot fits. The ground just inside, at the door and on the far bank
	 * of the ditch must lie within 2 blocks of each other: on a hill site the inside can stand far above the land
	 * outside the wall, and a door there would open into the fill. Such a side stays closed (its wall keeps the plug).
	 */
	static boolean posternOn(Settlement s, int n) {
		if (s.level(Progression.POSTERN) < n) {
			return false;
		}
		if (server == null) {
			return true;
		}
		String key = s.id() + "|" + s.rotation() + "|" + n;
		Boolean known = POSTERN_FITS.get(key);
		if (known != null) {
			return known;
		}
		Grade plan = Grade.of(server, s);
		Template t = Template.get(template(s, "postern_3"));
		if (plan == null || t == null) {
			return false; // worked out once the site's plan is known
		}
		boolean fits = false;
		for (Template.Placement p : t.placements(dye(s.color()), Rotation.values()[s.rotation()])) {
			if (p.segment() != n - 1 || !(p.state().getBlock() instanceof net.minecraft.world.level.block.DoorBlock)
					|| p.state().getValue(net.minecraft.world.level.block.DoorBlock.HALF) != net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) {
				continue;
			}
			BlockPos door = s.heart().offset(p.offset());
			net.minecraft.core.Direction in = p.state().getValue(net.minecraft.world.level.block.DoorBlock.FACING);
			BlockPos inside = door.relative(in, 3);
			BlockPos bank = door.relative(in.getOpposite(), 12);
			int a = plan.topAt(door.getX(), door.getZ());
			int b = plan.topAt(inside.getX(), inside.getZ());
			int c = plan.topAt(bank.getX(), bank.getZ());
			fits = a != Integer.MIN_VALUE && b != Integer.MIN_VALUE && c != Integer.MIN_VALUE
					&& Math.max(a, Math.max(b, c)) - Math.min(a, Math.min(b, c)) <= 2;
			// and the street inside (its lane to the door) must come down to the sill: on a hill site the streets
			// can run well above the land at the foot of the wall (field test: 9 blocks)
			StreetPlan streets = StreetPlan.of(server, s);
			for (int k = 2; fits && streets != null && k <= 6; k++) {
				BlockPos q = door.relative(in, k);
				Integer f = streets.feetAt(BlockPos.asLong(q.getX(), 0, q.getZ()));
				if (f != null) {
					fits = Math.abs(f - (a + 1 - s.heart().getY())) <= 2;
					break;
				}
			}
			break;
		}
		POSTERN_FITS.put(key, fits);
		return fits;
	}

	private static final Map<UUID, Boolean> RED = new HashMap<>();

	/**
	 * A desert settlement on red land (badlands, wooded badlands): its walls, gate and roads are of red sandstone,
	 * like the red walls of Marrakech, not yellow on red land (user, 2026-11-18).
	 */
	static boolean redLand(Settlement s) {
		if (!"arid".equals(s.style()) || server == null) {
			return false;
		}
		return RED.computeIfAbsent(s.id(), k -> {
			ServerLevel level = server.overworld();
			BlockState top = SiteWork.surface(level, s.heart());
			BlockState layer = SiteWork.strata(level, s.heart()).get(Integer.MIN_VALUE);
			return top.is(Blocks.RED_SAND) || top.is(net.minecraft.tags.BlockTags.TERRACOTTA) || layer != null && layer.is(net.minecraft.tags.BlockTags.TERRACOTTA);
		});
	}

	/** The red sandstone twin of a sandstone block, with the same shape (stairs facing, slab type, wall sides). */
	static BlockState reddened(BlockState st) {
		String path = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
		if (!path.contains("sandstone") || path.contains("red_sandstone")) {
			return st;
		}
		Identifier id = Identifier.withDefaultNamespace(path.replace("sandstone", "red_sandstone"));
		if (!BuiltInRegistries.BLOCK.containsKey(id)) {
			return st;
		}
		BlockState out = BuiltInRegistries.BLOCK.getValue(id).defaultBlockState();
		for (net.minecraft.world.level.block.state.properties.Property<?> p : st.getProperties()) {
			if (out.hasProperty(p)) {
				out = copyProperty(out, st, p);
			}
		}
		return out;
	}

	private static <T extends Comparable<T>> BlockState copyProperty(BlockState to, BlockState from,
			net.minecraft.world.level.block.state.properties.Property<T> p) {
		return to.setValue(p, from.getValue(p));
	}

	/** The side of the wall ring a position outside it faces, in the template's frame: 0 north, 1 south (the gate), 2 west, 3 east. */
	private static final int SOUTH = 1;

	private static int ringSide(BlockPos rel, Rotation turn) {
		Rotation back = switch (turn) {
			case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
			case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
			default -> turn;
		};
		BlockPos t = Template.rotate(rel, back);
		int[] ring = io.github.sotadevop.kingdomquest.building.Layout.ring(); // x0, z0, x1, z1 relative to the heart
		int ex = t.getX() < ring[0] ? ring[0] - t.getX() : Math.max(0, t.getX() - ring[2]);
		int ez = t.getZ() < ring[1] ? ring[1] - t.getZ() : Math.max(0, t.getZ() - ring[3]);
		if (ez >= ex) {
			return t.getZ() > (ring[1] + ring[3]) / 2 ? SOUTH : 0;
		}
		return t.getX() < (ring[0] + ring[2]) / 2 ? 2 : 3;
	}

	/** The planned top of the ground (world y) in a column: the grading plan, or the land itself where there is none. */
	static int plannedTop(ServerLevel level, Settlement s, int x, int z, Set<Long> skip) {
		Grade g = server == null ? null : Grade.of(server, s);
		int t = g == null ? Integer.MIN_VALUE : g.topAt(x, z);
		return t != Integer.MIN_VALUE ? t : SiteWork.groundDeep(level, x, z, s.heart().getY(), skip);
	}

	/** Water at the surface within two blocks of a column (looking through what the gate track built: bridge, landing). */
	private static boolean waterNear(ServerLevel level, int x, int z, Set<Long> skip) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x + dx, z + dz) - 1;
				while (y > level.getMinY() && skip.contains(pos.set(x + dx, y, z + dz).asLong())) {
					y--;
				}
				BlockState st = level.getBlockState(pos.set(x + dx, y, z + dz));
				if (!st.getFluidState().isEmpty() && st.getFluidState().is(net.minecraft.tags.FluidTags.WATER) || st.is(Blocks.ICE)) {
					return true; // a frozen lake counts: under the ice it is water
				}
			}
		}
		return false;
	}

	/** The job's blocks for this settlement; terrain-following templates sit on the ground at each anchor column. */
	static List<Template.Placement> placements(Settlement s, String job) {
		List<Template.Placement> base = basePlacements(s, job);
		Template t = Template.get(job);
		if (t != null && !t.follow && server != null && trackOf(job).equals("gate") && s.level(Progression.GATE) >= 3) {
			// the dry moat follows the land: each column is dug from the ground just outside the ditch (its anchor,
			// never dug itself, so the depth is the same every time it is worked out), unbroken around the wall
			String key = s.id() + "|" + job + "|moat|" + base.size() + "|" + s.color();
			return FOLLOWED.computeIfAbsent(key, k -> {
				ServerLevel level = server.overworld();
				Map<Long, Integer> ground = new HashMap<>();
				Map<Long, Boolean> wet = new HashMap<>();
				Set<Long> columns = new HashSet<>();
				Set<Long> mine = new HashSet<>(s.built().getOrDefault(Progression.GATE, List.of()));
				BlockState surface = SiteWork.surface(level, s.heart());
				List<Template.Placement> out = new ArrayList<>(base.size());
				List<Template.Placement> banks = new ArrayList<>();
				List<Template.Placement> moatOut = new ArrayList<>();
				// each side of the ring decides on its own (agreed with the user): where the land along a side falls
				// away or rises steeply, that side gets no ditch and the slope is its defence, as at a hill castle.
				// The gate side always keeps its ditch: the drawbridge spans it.
				Rotation turn = Rotation.values()[s.rotation()];
				int[][] count = new int[4][2]; // side -> {columns, steep columns}
				Set<Long> seen = new HashSet<>();
				for (Template.Placement p : base) {
					if (!(p.offset().getY() < 0 && (p.state().isAir() || p.state().is(Blocks.COARSE_DIRT)))
							|| p.anchor().getX() == 0 && p.anchor().getZ() == 0) {
						continue;
					}
					BlockPos here = s.heart().offset(p.offset());
					if (!seen.add(BlockPos.asLong(here.getX(), 0, here.getZ()))) {
						continue;
					}
					// the land beyond the plateau's flat band on this side: where the graded slope falls or climbs far, the
					// slope is the side's defence
					int side = ringSide(p.offset(), turn);
					int[] outward = side == SOUTH ? new int[] {0, 1} : side == 0 ? new int[] {0, -1} : side == 2 ? new int[] {-1, 0} : new int[] {1, 0};
					BlockPos dir = Template.rotate(new BlockPos(outward[0], 0, outward[1]), turn);
					int far = Grade.PAD + 6;
					int beyond = plannedTop(level, s, here.getX() + dir.getX() * far, here.getZ() + dir.getZ() * far, mine);
					int local = plannedTop(level, s, here.getX(), here.getZ(), mine);
					count[side][0]++;
					if (Math.abs(beyond - local) > 4) {
						count[side][1]++;
					}
				}
				boolean[] steep = new boolean[4];
				for (int side = 0; side < 4; side++) {
					steep[side] = side != SOUTH && count[side][1] * 5 > count[side][0];
				}
				for (Template.Placement p : base) {
					boolean moat = p.offset().getY() < 0 && (p.state().isAir() || p.state().is(Blocks.COARSE_DIRT));
					if (moat && steep[ringSide(p.offset(), turn)]) {
						continue;
					}
					if (moat) {
						BlockPos here = s.heart().offset(p.offset());
						// by the sea, a lake or a river the water is the moat: a ditch dug there only floods in patches
						if (wet.computeIfAbsent(BlockPos.asLong(here.getX(), 0, here.getZ()), c -> waterNear(level, here.getX(), here.getZ(), mine))) {
							continue;
						}
						BlockPos col = s.heart().offset(p.anchor());
						// anchored to the heart's own column: in front of the gate the ditch lies at the gate's level
						boolean level0 = p.anchor().getX() == 0 && p.anchor().getZ() == 0;
						int g = level0 ? trackLift(s, Progression.GATE) : ground.computeIfAbsent(col.asLong(), c -> plannedTop(level, s, col.getX(),
								col.getZ(), Set.of()) + 1 - s.heart().getY());
						g -= trackLift(s, Progression.GATE); // the gate's own blocks already stand at its level
						if (Math.abs(g) > 16) {
							continue; // a cliff: no ditch needed there
						}
						columns.add(BlockPos.asLong(here.getX(), 0, here.getZ()));
						// the bottom and the bank are the land's own surface, with bare patches
						BlockState state = p.state().isAir() || Math.floorMod(here.getX() * 31 + here.getZ() * 17, 4) == 0 ? p.state() : surface;
						if (!state.isAir()) {
							// a bank or bottom piece needs something solid within 5 straight below it (old moat blocks
							// don't count): where the land falls away, at a corner by a tower or on a hillside, none is
							// heaped up in the air (the ditch is still dug, so its line stays whole)
							BlockPos at = s.heart().offset(p.offset().above(g));
							boolean held = false;
							for (int down = 1; down <= 5 && !held; down++) {
								BlockPos q = at.below(down);
								BlockState qs = level.getBlockState(q);
								held = !mine.contains(q.asLong()) && !qs.isAir() && !qs.canBeReplaced() && qs.getFluidState().isEmpty();
							}
							if (!held) {
								continue;
							}
						}
						Template.Placement mp = new Template.Placement(p.offset().above(g), state, p.segment(), p.anchor());
						out.add(mp);
						moatOut.add(mp);
						if (!state.isAir()) {
							banks.add(mp);
						}
						continue;
					}
					out.add(p);
				}
				// at a corner the two arms of the ditch overlap: one digs a column out, the other would lay its bank turf
				// on top of the same column (turf hanging over a dug hole). The dig wins: no moat turf above a dug cell
				Map<Long, Integer> lowestDug = new HashMap<>();
				for (Template.Placement o : moatOut) {
					if (o.state().isAir()) {
						lowestDug.merge(BlockPos.asLong(o.offset().getX(), 0, o.offset().getZ()), o.offset().getY(), Math::min);
					}
				}
				Set<Template.Placement> hanging = new HashSet<>();
				for (Template.Placement o : moatOut) {
					Integer dug = lowestDug.get(BlockPos.asLong(o.offset().getX(), 0, o.offset().getZ()));
					if (!o.state().isAir() && dug != null && dug < o.offset().getY()) {
						hanging.add(o);
					}
				}
				out.removeAll(hanging);
				banks.removeAll(hanging);
				// where the land falls away under the bank (a hillside), earth is carried down under it: no floating turf
				Set<Long> taken = new HashSet<>();
				out.forEach(o -> taken.add(o.offset().asLong()));
				List<Template.Placement> under = new ArrayList<>();
				BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
				for (Template.Placement b : banks) {
					for (int down = 1; down <= 4; down++) {
						BlockPos rel = b.offset().below(down);
						bp.set(s.heart().getX() + rel.getX(), s.heart().getY() + rel.getY(), s.heart().getZ() + rel.getZ());
						BlockState st = level.getBlockState(bp);
						if (!(st.isAir() || st.canBeReplaced()) || !st.getFluidState().isEmpty() || mine.contains(bp.asLong()) || !taken.add(rel.asLong())) {
							break;
						}
						under.add(new Template.Placement(rel, SiteWork.subsoil(surface), b.segment(), b.anchor()));
					}
				}
				out.addAll(0, under);
				MOAT.put(s.id(), columns);
				return List.copyOf(out);
			});
		}
		if (t == null || !t.follow || server == null) {
			return base;
		}
		String key = s.id() + "|" + job + "|" + base.size() + "|" + s.color();
		return FOLLOWED.computeIfAbsent(key, k -> {
			ServerLevel level = server.overworld();
			String track = builtTrack(job);
			boolean walls = track.equals(Progression.WALLS);
			Set<Long> own = new HashSet<>(s.built().getOrDefault(track, List.of()));
			// what other tracks built is looked through: a road under the gatehouse lies on the ground, not on its roof
			Set<Long> others = new HashSet<>();
			s.built().forEach((k2, v) -> {
				if (!k2.equals(track)) {
					others.addAll(v);
				}
			});
			Set<Long> through = new HashSet<>(others);
			through.addAll(own);
			int heartY = s.heart().getY();
			Map<Long, Integer> ground = new HashMap<>();
			List<Template.Placement> out = new ArrayList<>(base.size());
			int minY = 0;
			for (Template.Placement p : base) {
				minY = Math.min(minY, p.offset().getY());
			}
			Set<Long> placed = new HashSet<>();
			// roads take their height from the street plan (worked out once, never changed by later builds); without one
			// (site not loaded yet) the old per-column evening is the fallback
			StreetPlan streets = (track.equals(Progression.ROADS) || track.equals(Progression.TRAM)) && server != null ? StreetPlan.of(server, s) : null;
			if (streets != null && !streets.columns().isEmpty()) {
				for (Template.Placement p : base) {
					BlockPos col = s.heart().offset(p.anchor());
					Integer f = streets.feetAt(BlockPos.asLong(col.getX(), 0, col.getZ()));
					if (f != null) {
						ground.put(col.asLong(), f);
					}
				}
			}
			// a wall that stands (after a restart, for repairs, for a rebuild) is found where it is: the top of
			// what was built in an anchor column against the top of the plan there gives its height exactly
			Progression.Step standing = Progression.current(s, track);
			boolean same = walls && !own.isEmpty() && !job.equals(s.job()) && standing != null && job.endsWith("/" + standing.building());
			Map<Long, Integer> planTop = new HashMap<>();
			Map<Long, List<Integer>> planYs = new HashMap<>();
			if (same) {
				for (Template.Placement p : base) {
					if (p.offset().getX() == p.anchor().getX() && p.offset().getZ() == p.anchor().getZ()) {
						long a = BlockPos.asLong(p.anchor().getX(), 0, p.anchor().getZ());
						planTop.merge(a, p.offset().getY(), Math::max);
						planYs.computeIfAbsent(a, n0 -> new ArrayList<>()).add(p.offset().getY());
					}
				}
			}
			if (walls && !same) {
				towersUp(level, s, base, ground, heartY, others, through, own);
			}
			// a lamp's arm follows its post, not the road under it (field test: arms left hanging where the road and the
			// ground beside it differ): a lamp block in a column without a post takes the neighbouring post's column
			Map<Long, BlockPos> lampPost = new HashMap<>();
			if (track.equals(Progression.LAMPS)) {
				Set<Long> lampPosts = new HashSet<>();
				for (Template.Placement p : base) {
					if (p.offset().getY() == 0) {
						lampPosts.add(BlockPos.asLong(p.anchor().getX(), 0, p.anchor().getZ()));
					}
				}
				for (Template.Placement p : base) {
					long lpCol = BlockPos.asLong(p.anchor().getX(), 0, p.anchor().getZ());
					if (!lampPosts.contains(lpCol)) {
						for (int[] lpD : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
							long lpN = BlockPos.asLong(p.anchor().getX() + lpD[0], 0, p.anchor().getZ() + lpD[1]);
							if (lampPosts.contains(lpN)) {
								lampPost.put(lpCol, new BlockPos(p.anchor().getX() + lpD[0], 0, p.anchor().getZ() + lpD[1]));
								break;
							}
						}
					}
				}
			}
			for (Template.Placement p : base) {
				BlockPos col = s.heart().offset(lampPost.getOrDefault(BlockPos.asLong(p.anchor().getX(), 0, p.anchor().getZ()), p.anchor()));
				Integer top = planTop.get(BlockPos.asLong(p.anchor().getX(), 0, p.anchor().getZ()));
				List<Integer> ys = planYs.get(BlockPos.asLong(p.anchor().getX(), 0, p.anchor().getZ()));
				int off = ground.computeIfAbsent(col.asLong(), c -> {
					int g;
					Grade plan = Grade.of(server, s);
					if (top == null && plan != null && plan.topAt(col.getX(), col.getZ()) != Integer.MIN_VALUE) {
						return Math.max(-40, Math.min(30, plan.topAt(col.getX(), col.getZ()) + 1 - heartY));
					}
					if (top != null) {
						// the height at which the most of this column's own blocks are found built (the highest built
						// block can belong to a tower anchored elsewhere: field test, a palisade corner's roof slab
						// put its wall's ground strip 11 up once the plan was worked out again)
						BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
						int best = 0;
						int bestOff = 0;
						for (int o = -40; o <= 30; o++) {
							int hits = 0;
							for (int y : ys) {
								if (own.contains(at.set(col.getX(), heartY + y + o, col.getZ()).asLong())) {
									hits++;
								}
							}
							if (hits > best) {
								best = hits;
								bestOff = o;
							}
						}
						if (best > 0) {
							return bestOff;
						}
					}
					if (walls && !own.isEmpty()) {
						// a wall that already stands replaced the ground under itself: take the land beside it
						// (two blocks to each side, looking through everything built), so a rebuild stays where it is
						List<Integer> near = new ArrayList<>();
						for (int[] d2 : new int[][] {{2, 0}, {-2, 0}, {0, 2}, {0, -2}}) {
							near.add(SiteWork.groundDeep(level, col.getX() + d2[0], col.getZ() + d2[1], heartY, through));
						}
						java.util.Collections.sort(near);
						g = near.get(2);
					} else {
						g = plannedTop(level, s, col.getX(), col.getZ(), others);
					}
					return Math.max(-40, Math.min(30, g + 1 - heartY));
				});
				Template.Placement at = new Template.Placement(p.offset().above(off), p.state(), p.segment(), p.anchor());
				out.add(at);
				placed.add(at.offset().asLong());
			}
			if (track.equals(Progression.TRAM)) {
				if (streets == null) {
					out.clear(); // laid once the street plan is known
				} else {
					Tram.trim(s, streets, out);
				}
			}
			if (track.equals(Progression.ROADS)) {
				// a paved road that climbs gets a slab on the lower block of every step: you walk up, you don't jump
				// (trodden dirt paths stay bare: a path under a slab turns to dirt)
				Map<Long, Integer> top = new HashMap<>();
				for (Template.Placement p : out) {
					top.merge(BlockPos.asLong(p.offset().getX(), 0, p.offset().getZ()), p.offset().getY(), Math::max);
				}
				List<Template.Placement> steps = new ArrayList<>();
				Map<Long, BlockState> stairAt = new HashMap<>();
				for (Template.Placement p : out) {
					BlockState st = p.state();
					if (st.is(Blocks.DIRT_PATH) || st.is(Blocks.COARSE_DIRT) || !st.isSolid()) {
						continue;
					}
					boolean climbs = false;
					int climbing = 0;
					boolean straight = false;
					net.minecraft.core.Direction stepUp = null;
					for (int[] d2 : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
						Integer n = top.get(BlockPos.asLong(p.offset().getX() + d2[0], 0, p.offset().getZ() + d2[1]));
						if (n != null && n == p.offset().getY() + 1) {
							climbs = true;
							climbing++;
							stepUp = d2[0] > 0 ? net.minecraft.core.Direction.EAST : d2[0] < 0 ? net.minecraft.core.Direction.WEST
									: d2[1] > 0 ? net.minecraft.core.Direction.SOUTH : net.minecraft.core.Direction.NORTH;
							Integer behind = top.get(BlockPos.asLong(p.offset().getX() - d2[0], 0, p.offset().getZ() - d2[1]));
							straight = behind == null || behind <= p.offset().getY();
						}
					}
					// a stair only on a straight run of a flight: at a bend it would face the wrong way (field test: 1.5 steps)
					straight &= climbing == 1;
					BlockPos stepWorld = s.heart().offset(p.offset());
					long stepCol = BlockPos.asLong(stepWorld.getX(), 0, stepWorld.getZ());
					boolean stepPlanned = streets != null && streets.halfAt(stepCol) != null;
					boolean stepStair = stepPlanned && streets.steep(stepCol) && climbs && straight; // a stepped lane: real stairs
					boolean stepSlab = stepPlanned ? streets.slab(stepCol) && !stepStair : climbs; // the plan's half heights are slabs
					BlockPos above = p.offset().above();
					boolean rough = st.is(Blocks.COBBLESTONE) || st.is(Blocks.MOSSY_COBBLESTONE) || st.is(Blocks.GRAVEL);
					if (stepStair) {
						// a stepped lane: on a whole planned height the stair takes the road block's own place (its back is the
						// cell's walking level); on a half height it stands on the road block, where the slab would be (its front
						// is the walking level). Either way each stair meets the next half a block apart (field test: the half
						// was lost and the flight met the music hall's landing 1.5 high)
						BlockState stairState = (rough ? Blocks.COBBLESTONE_STAIRS : Blocks.STONE_BRICK_STAIRS).defaultBlockState()
								.setValue(net.minecraft.world.level.block.StairBlock.FACING, stepUp);
						if (streets.slab(stepCol)) {
							if (!others.contains(s.heart().offset(above).asLong()) && placed.add(above.asLong())) {
								steps.add(new Template.Placement(above, stairState, p.segment(), p.anchor()));
							}
						} else {
							stairAt.put(p.offset().asLong(), stairState);
						}
					} else if (stepSlab && !others.contains(s.heart().offset(above).asLong()) && placed.add(above.asLong())) {
						steps.add(new Template.Placement(above, (rough ? Blocks.COBBLESTONE_SLAB : Blocks.STONE_BRICK_SLAB).defaultBlockState(),
								p.segment(), p.anchor()));
					}
				}
				if (!stairAt.isEmpty()) {
					out.replaceAll(pl -> stairAt.containsKey(pl.offset().asLong()) ? new Template.Placement(pl.offset(), stairAt.get(pl.offset().asLong()), pl.segment(), pl.anchor()) : pl);
				}
				out.addAll(steps);
				// cut and fill: where the road was evened out it gets earth under it, or the land above it is opened
				out.addAll(0, roadEarthwork(level, s, out, others, through, placed));
				// over water a road is a causeway: stone under it down to the bed, so it is no floating strip
				// (a castle founded in a lake gets real streets between its islands)
				List<Template.Placement> piers = new ArrayList<>();
				BlockPos.MutableBlockPos wp = new BlockPos.MutableBlockPos();
				Map<Long, Template.Placement> lowest = new HashMap<>();
				for (Template.Placement p : out) {
					lowest.merge(BlockPos.asLong(p.offset().getX(), 0, p.offset().getZ()), p, (a, b) -> a.offset().getY() <= b.offset().getY() ? a : b);
				}
				for (Template.Placement low : lowest.values()) {
					for (int down = 1; down <= 24; down++) {
						BlockPos rel = low.offset().below(down);
						wp.set(s.heart().getX() + rel.getX(), heartY + rel.getY(), s.heart().getZ() + rel.getZ());
						if (!(level.getBlockState(wp).getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) && !own.contains(wp.asLong())
								|| others.contains(wp.asLong()) || !placed.add(rel.asLong())) {
							break;
						}
						piers.add(new Template.Placement(rel, Blocks.COBBLESTONE.defaultBlockState(), low.segment(), low.anchor()));
					}
				}
				out.addAll(0, piers);
			}
			if (walls && minY <= -3) {
				// foundations go down to the ground: where the land dips under a wall section or a tower, the
				// lowest course is carried on down, so no piece of wall hangs in the air
				List<Template.Placement> footing = new ArrayList<>();
				BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
				for (int i = 0; i < base.size(); i++) {
					if (base.get(i).offset().getY() != minY) {
						continue;
					}
					Template.Placement low = out.get(i);
					for (int down = 1; down <= 96; down++) { // over a ravine too: masonry down the rock, never a hanging wall
						BlockPos rel = low.offset().below(down);
						pos.set(s.heart().getX() + rel.getX(), heartY + rel.getY(), s.heart().getZ() + rel.getZ());
						BlockState st = level.getBlockState(pos);
						boolean open = st.isAir() || st.canBeReplaced() || st.getCollisionShape(level, pos).isEmpty() // coral fans, flowers...
								|| st.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock
								|| st.is(net.minecraft.tags.BlockTags.LEAVES) || SiteWork.plant(st);
						if (!open && !own.contains(pos.asLong()) || placed.contains(rel.asLong())) {
							break;
						}
						footing.add(new Template.Placement(rel, low.state(), low.segment(), low.anchor()));
					}
				}
				out.addAll(0, footing);
			}
			return List.copyOf(out);
		});
	}

	/**
	 * A wall tower stands at least as high as the wall sections it joins (field test: where the land stepped, a tower
	 * lower than its neighbour put its roof's eaves into that section's masonry). Towers are the anchors whose blocks
	 * reach the roof (template y 12 and up); every section anchor with a block within one of a tower's footprint lifts
	 * the tower to its own height. Writes the offsets into {@code ground} (anchor column -> offset from the heart).
	 */
	private static void towersUp(ServerLevel level, Settlement s, List<Template.Placement> base, Map<Long, Integer> ground, int heartY,
			Set<Long> others, Set<Long> through, Set<Long> own) {
		Map<Long, int[]> box = new HashMap<>(); // anchor (relative) -> minX, minZ, maxX, maxZ, maxY
		for (Template.Placement p : base) {
			long a = BlockPos.asLong(p.anchor().getX(), 0, p.anchor().getZ());
			int x = p.offset().getX(), z = p.offset().getZ(), y = p.offset().getY();
			box.merge(a, new int[] {x, z, x, z, y}, (o, n) -> new int[] {Math.min(o[0], n[0]), Math.min(o[1], n[1]), Math.max(o[2], n[2]),
					Math.max(o[3], n[3]), Math.max(o[4], n[4])});
		}
		java.util.function.ToIntFunction<Long> land = a -> {
			BlockPos col = s.heart().offset(BlockPos.getX(a), 0, BlockPos.getZ(a));
			return ground.computeIfAbsent(col.asLong(), c -> {
				int g;
				Grade plan = Grade.of(server, s);
				if (plan != null && plan.topAt(col.getX(), col.getZ()) != Integer.MIN_VALUE) {
					g = plan.topAt(col.getX(), col.getZ()); // the grading plan: the wall stands where the land was planned
				} else if (!own.isEmpty()) {
					List<Integer> near = new ArrayList<>();
					for (int[] d2 : new int[][] {{2, 0}, {-2, 0}, {0, 2}, {0, -2}}) {
						near.add(SiteWork.groundDeep(level, col.getX() + d2[0], col.getZ() + d2[1], heartY, through));
					}
					java.util.Collections.sort(near);
					g = near.get(2);
				} else {
					g = SiteWork.groundDeep(level, col.getX(), col.getZ(), heartY, others);
				}
				return Math.max(-40, Math.min(30, g + 1 - heartY));
			});
		};
		for (Map.Entry<Long, int[]> t : box.entrySet()) {
			int[] tb = t.getValue();
			if (tb[4] < 12 || tb[2] - tb[0] > 8 || tb[3] - tb[1] > 8) {
				continue; // not a tower (a tower and its roof are at most 7 across)
			}
			int high = land.applyAsInt(t.getKey());
			for (Map.Entry<Long, int[]> o : box.entrySet()) {
				int[] ob = o.getValue();
				if (o.getKey().equals(t.getKey()) || ob[4] >= 12) {
					continue;
				}
				boolean touches = ob[0] <= tb[2] + 1 && ob[2] >= tb[0] - 1 && ob[1] <= tb[3] + 1 && ob[3] >= tb[1] - 1;
				if (touches) {
					high = Math.max(high, land.applyAsInt(o.getKey()));
				}
			}
			BlockPos col = s.heart().offset(BlockPos.getX(t.getKey()), 0, BlockPos.getZ(t.getKey()));
			ground.put(col.asLong(), high);
		}
	}

	/**
	 * The height of every road column (offset from the heart's level, as the road follows the land) after cut and
	 * fill: neighbouring road columns never differ by more than one block, so every step can be walked (and gets a
	 * slab). Columns next to anything built (doors, the gatehouse, terraces) keep the height of their land; between
	 * them the road is the middle of the land pulled down to a walkable slope and pulled up to one, which spreads a
	 * big step evenly over cut on the high side and fill on the low side, as a road builder would.
	 */
	/** Earth under a road that was raised above its land, air over one that was cut into it (natural blocks only). */
	private static List<Template.Placement> roadEarthwork(ServerLevel level, Settlement s, List<Template.Placement> road, Set<Long> others,
			Set<Long> through, Set<Long> placed) {
		int heartY = s.heart().getY();
		Map<Long, int[]> span = new HashMap<>(); // column -> lowest, highest road block (relative)
		Map<Long, Template.Placement> any = new HashMap<>();
		for (Template.Placement p : road) {
			long c = BlockPos.asLong(p.offset().getX(), 0, p.offset().getZ());
			span.merge(c, new int[] {p.offset().getY(), p.offset().getY()}, (a, b) -> new int[] {Math.min(a[0], b[0]), Math.max(a[1], b[1])});
			any.putIfAbsent(c, p);
		}
		BlockState fill = SiteWork.subsoil(SiteWork.surface(level, s.heart()));
		List<Template.Placement> out = new ArrayList<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (Map.Entry<Long, int[]> e : span.entrySet()) {
			int rx = BlockPos.getX(e.getKey()), rz = BlockPos.getZ(e.getKey());
			int x = s.heart().getX() + rx, z = s.heart().getZ() + rz;
			Template.Placement p = any.get(e.getKey());
			int ground = SiteWork.groundDeep(level, x, z, heartY, through) - heartY; // relative top of the land (own old road looked through)
			for (int y = ground + 1; y < e.getValue()[0]; y++) { // raised: earth under it
				pos.set(x, heartY + y, z);
				BlockState st = level.getBlockState(pos);
				// air, plants, hanging moss and leaves give way to the fill (they can't carry a road)
				if (!others.contains(pos.asLong()) && (st.isAir() || st.canBeReplaced() || st.getCollisionShape(level, pos).isEmpty()
						|| st.is(net.minecraft.tags.BlockTags.LEAVES)) && st.getFluidState().isEmpty() && placed.add(BlockPos.asLong(rx, y, rz))) {
					out.add(new Template.Placement(new BlockPos(rx, y, rz), fill, p.segment(), p.anchor()));
				}
			}
			for (int y = e.getValue()[1] + 1; y <= Math.max(ground, e.getValue()[1]) + 2; y++) { // cut: open sky over it
				pos.set(x, heartY + y, z);
				BlockState st = level.getBlockState(pos);
				if (!others.contains(pos.asLong()) && !st.isAir() && SiteWork.natural(st) && st.getFluidState().isEmpty() && placed.add(BlockPos.asLong(rx, y, rz))) {
					out.add(new Template.Placement(new BlockPos(rx, y, rz), Blocks.AIR.defaultBlockState(), p.segment(), p.anchor()));
				}
			}
		}
		return out;
	}

	/**
	 * After a building is finished, roads and lamps that already exist grow to it: their new segments are
	 * placed at once at the current road/lamp level.
	 */
	private static void extendInfrastructure(MinecraftServer server, Settlement s) {
		extendInfrastructure(server, s, List.of(Progression.ROADS, Progression.LAMPS));
	}

	private static void extendInfrastructure(MinecraftServer server, Settlement s, List<String> tracks) {
		ServerLevel level = server.overworld();
		BlockState surface = SiteWork.surface(level, s.heart());
		for (String track : tracks) {
			Progression.Step current = Progression.current(s, track);
			if (current == null) {
				continue;
			}
			String job = template(s, current.building());
			// the land may have been cut or filled under them since they were laid: work out again where they lie,
			// take up what is left hanging or buried, and lay what is missing (new lanes included)
			String prefix = s.id() + "|" + job + "|";
			FOLLOWED.keySet().removeIf(k -> k.startsWith(prefix));
			if (track.equals(Progression.ROADS)) {
				// road blocks left hanging (the land under them was cut since) are taken up first, so the road is
				// worked out again from the land itself and laid on it, with proper fill where a ramp has to be
				// raised. Filling under them instead locked a misplaced block in place (a 4-block step at a gate)
				Set<Long> ditch = moatColumns(s);
				Set<Long> others = new HashSet<>();
				s.built().forEach((k2, v) -> {
					if (!k2.equals(Progression.ROADS)) {
						others.addAll(v);
					}
				});
				List<Long> kept = new ArrayList<>();
				boolean any = false;
				for (long r : s.built().getOrDefault(track, List.of())) {
					BlockPos rp = BlockPos.of(r);
					BlockState here = level.getBlockState(rp);
					BlockState under = level.getBlockState(rp.below());
					boolean hanging = !here.isAir() && !(here.getBlock() instanceof net.minecraft.world.level.block.SlabBlock)
							&& (under.isAir() || under.canBeReplaced()) && under.getFluidState().isEmpty() && !others.contains(rp.below().asLong())
							&& !ditch.contains(BlockPos.asLong(rp.getX(), 0, rp.getZ()));
					if (hanging) {
						level.setBlock(rp, Blocks.AIR.defaultBlockState(), NO_DROPS);
						any = true;
					} else {
						kept.add(r);
					}
				}
				if (any) {
					s = s.withBuilt(track, kept);
				}
			}
			List<Template.Placement> list = placements(s, job);
			Set<Long> next = new HashSet<>();
			for (Template.Placement p : list) {
				next.add(s.heart().offset(p.offset()).asLong());
			}
			List<Long> built = new ArrayList<>();
			for (long old : s.built().getOrDefault(track, List.of())) {
				if (next.contains(old)) {
					built.add(old);
					continue;
				}
				BlockPos p = BlockPos.of(old);
				if (level.getBlockState(p).isAir()) {
					// opened earthwork over a cut road: it just leaves the list. Filled back as ground it put a turf
					// block on top of the road, and the road climbed one block every time it was worked out (field test)
					continue;
				}
				boolean ground = track.equals(Progression.ROADS) && level.getBlockState(p.below()).isSolid();
				level.setBlock(p, !ground ? Blocks.AIR.defaultBlockState()
						: level.getBlockState(p.above()).isSolid() ? Blocks.DIRT.defaultBlockState() : surface, NO_DROPS);
			}
			Set<Long> have = new HashSet<>(built);
			Set<Long> all = new HashSet<>();
			s.built().values().forEach(all::addAll);
			Set<Long> otherTracks = new HashSet<>();
			s.built().forEach((k2, v) -> {
				if (!k2.equals(track)) {
					otherTracks.addAll(v);
				}
			});
			// a lamp is placed whole or not at all: a post left out (another building's block in the way) would
			// leave its arm and lantern hanging
			Set<Long> blockedLamps = new HashSet<>();
			if (track.equals(Progression.LAMPS)) {
				for (Template.Placement p : list) {
					if (otherTracks.contains(s.heart().offset(p.offset()).asLong())) {
						blockedLamps.add(p.anchor().asLong());
					}
				}
			}
			for (Template.Placement p : list) {
				BlockPos pos = s.heart().offset(p.offset());
				if (otherTracks.contains(pos.asLong()) || blockedLamps.contains(p.anchor().asLong())) {
					continue; // never over another building's block (a lamp in the cellar wall, a road through a gate pier)
				}
				if (have.contains(pos.asLong())) {
					// laid before, but a later build may have covered it (a bank, a regrade): laid again
					BlockState now = level.getBlockState(pos);
					if (now.getBlock() == p.state().getBlock() || p.state().isAir() && !SiteWork.natural(now)) {
						continue;
					}
				}
				level.setBlock(pos, neighbourShaped(p.state(), level, pos), NO_DROPS);
				SiteWork.clearAbove(level, pos, all);
				if (have.add(pos.asLong())) {
					built.add(pos.asLong());
				}
			}
			s = s.withBuilt(track, built);
		}
		put(server, s);
	}

	private static boolean attached(BlockState st) {
		Block b = st.getBlock();
		return b instanceof net.minecraft.world.level.block.LadderBlock || b instanceof net.minecraft.world.level.block.BaseTorchBlock
				|| b instanceof net.minecraft.world.level.block.LanternBlock || b instanceof net.minecraft.world.level.block.AbstractBannerBlock
				|| b instanceof net.minecraft.world.level.block.BellBlock || b instanceof net.minecraft.world.level.block.CarpetBlock
				|| b instanceof net.minecraft.world.level.block.DoorBlock || b instanceof net.minecraft.world.level.block.BedBlock
				|| b instanceof net.minecraft.world.level.block.LeverBlock || b instanceof net.minecraft.world.level.block.TrapDoorBlock
				|| b instanceof net.minecraft.world.level.block.SnowLayerBlock || b instanceof CropBlock;
	}

	private static BlockState neighbourShaped(BlockState state, ServerLevel level, BlockPos pos) {
		if (state.getBlock() instanceof CrossCollisionBlock || state.getBlock() instanceof WallBlock) {
			return Block.updateFromNeighbourShapes(state, level, pos); // fences, panes and walls join their neighbours
		}
		return state;
	}

	// ---- building over time ----

	/**
	 * A dock that was built without a place on the water (the test commands allowed that) stands as a strip of
	 * planks on the square. It is taken away again; the dock can be built properly once there is water near.
	 */
	private static void removeStrayDock(MinecraftServer server, Settlement s) {
		ServerLevel level = server.overworld();
		BlockState surface = SiteWork.surface(level, s.heart());
		Set<Long> others = new HashSet<>();
		s.built().forEach((track, list) -> {
			if (!track.equals(Progression.DOCK)) {
				others.addAll(list);
			}
		});
		for (long l : s.built().getOrDefault(Progression.DOCK, List.of())) {
			BlockPos p = BlockPos.of(l);
			if (p.equals(s.heart()) || others.contains(l)) {
				continue;
			}
			int dy = p.getY() - s.heart().getY();
			level.setBlock(p, dy >= 0 ? Blocks.AIR.defaultBlockState() : dy == -1 ? surface : SiteWork.subsoil(surface), NO_DROPS);
		}
		put(server, s.withLevel(Progression.DOCK, 0).withBuilt(Progression.DOCK, List.of()));
	}

	static void tick(MinecraftServer server) {
		WorldMap.tick(server);
		boolean produce = server.getTickCount() % Production.INTERVAL == 0;
		boolean people = server.getTickCount() % 200 == 0;
		for (Settlement s : all(server)) {
			if (people && s.level(Progression.DOCK) > 0 && s.level("dock_set") == 0 && s.job().isEmpty() && server.overworld().isLoaded(s.heart())) {
				removeStrayDock(server, s);
				continue; // the settlement changed: the rest runs on the next tick with the new state
			}
			if (people) {
				People.ensureArchitect(server.overworld(), s);
				People.ensureBuilders(server.overworld(), s);
				People.spread(server.overworld(), s);
				Workers.tick(server.overworld(), s);
			}
			repairTick(server, s);
			rebuildTick(server, s);
			if (server.getTickCount() % 1200 == 0) {
				Defense.guards(server.overworld(), s);
				Collections.pets(server, s);
				Collections.trophies(server, s);
				Collections.lair(server, s);
				Companion.tick(server);
			}
			if (server.getTickCount() % 100 == 0) {
				Defense.attacks(server, s);
			}
			Grade.work(server, s);
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

	/** Per settlement and job: the world box its blocks and ground work cover (x0, z0, x1, z1). */
	private static final Map<String, int[]> JOB_BOX = new HashMap<>();

	private static int[] jobBox(Settlement s, List<Template.Placement> list, List<SiteWork.Column> columns) {
		int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
		for (Template.Placement p : list) {
			x0 = Math.min(x0, p.offset().getX());
			z0 = Math.min(z0, p.offset().getZ());
			x1 = Math.max(x1, p.offset().getX());
			z1 = Math.max(z1, p.offset().getZ());
		}
		for (SiteWork.Column c : columns) {
			x0 = Math.min(x0, c.dx());
			z0 = Math.min(z0, c.dz());
			x1 = Math.max(x1, c.dx());
			z1 = Math.max(z1, c.dz());
		}
		BlockPos h = s.heart();
		return x0 > x1 ? new int[] {h.getX(), h.getZ(), h.getX(), h.getZ()} : new int[] {h.getX() + x0, h.getZ() + z0, h.getX() + x1, h.getZ() + z1};
	}

	static void step(MinecraftServer server, Settlement s, boolean instant) {
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
		ServerPlayer ownerNow = server.getPlayerList().getPlayer(s.owner());
		boolean fast = ownerNow != null && ownerNow.isCreative();
		int colsPerTick = instant ? cols : fast ? Math.max(1, (int) Math.ceil(cols / (CREATIVE_TICKS * 0.4)))
				: Math.max(4, (int) Math.ceil(cols / (t.seconds * 8.0)));
		Set<Long> moat = moatColumns(s);
		BlockPos base = s.heart().above(lift(s, s.job()));
		// the land under the job must be shaped first (the grading plan): the builders work where the game runs
		Grade grade = Grade.of(server, s);
		if (grade != null && !grade.complete()) {
			int[] box = JOB_BOX.computeIfAbsent(s.id() + "|" + s.job(), k -> jobBox(s, list, columns));
			if (!grade.ready(level, s, box[0], box[1], box[2], box[3], instant ? Integer.MAX_VALUE : 2)) {
				return; // waits for the land; it is shaped when someone comes near
			}
		}
		Set<Long> loose = new HashSet<>(s.built().getOrDefault(Progression.ROADS, List.of()));
		loose.addAll(s.built().getOrDefault(Progression.LAMPS, List.of()));
		BlockState surface = SiteWork.surface(level, s.heart());
		// the town houses of the main track stand on the plateau: their ground is worked at their own level
		Map<Long, Integer> houseGround = Settlement.MAIN.equals(s.jobTrack()) ? houseLifts(s, 4) : Map.of();
		for (int n = 0; n < colsPerTick && index < cols; n++, index++) {
			SiteWork.Column c = columns.get(index);
			if (moat.contains(BlockPos.asLong(s.heart().getX() + c.dx(), 0, s.heart().getZ() + c.dz()))) {
				continue; // the moat is nobody's building ground: never levelled, never filled
			}
			Integer h = houseGround.get(BlockPos.asLong(c.dx(), 0, c.dz()));
			if (h != null && h == Integer.MIN_VALUE) {
				continue; // a house that isn't built (on a hill of the look)
			}
			SiteWork.clear(level, h == null ? base : base.above(h), c, allBuilt, surface, loose);
		}
		// phase 2: place the blocks
		int perTick = instant ? list.size() : fast ? Math.max(1, (int) Math.ceil(list.size() / (CREATIVE_TICKS * 0.6)))
				: Math.max(1, (int) Math.ceil(list.size() / (t.seconds * 12.0)));
		List<Long> built = new ArrayList<>(s.built().getOrDefault(s.jobTrack(), List.of()));
		Set<Long> builtSet = new HashSet<>(built);
		BlockPos first = null;
		BlockPos last = null;
		Map<Long, BlockState> changes = LordChanges.of(server, s);
		Map<Long, BlockState> oldPlan = changes.isEmpty() ? Map.of() : oldPlan(s);
		List<Long> replaced = new ArrayList<>();
		if (index >= cols) {
			for (int n = 0; n < perTick && index < cols + list.size(); n++, index++) {
				Template.Placement p = list.get(index - cols);
				BlockPos pos = s.heart().offset(p.offset());
				BlockState was = changes.get(pos.asLong());
				if (was != null) {
					// the lord changed this spot: it stays his where the new plan has the same block as the old one
					// here (the upgrade doesn't touch it); where the plan changes, the upgrade wins
					if (oldPlan == null) {
						continue;
					}
					BlockState planned = oldPlan.get(pos.asLong());
					BlockState plannedOld = planned == null ? Blocks.AIR.defaultBlockState() : planned;
					if (plannedOld.getBlock() == p.state().getBlock()) {
						continue;
					}
					replaced.add(pos.asLong());
				}
				BlockState state = neighbourShaped(p.state(), level, pos);
				if (t.follow) {
					SiteWork.clearAbove(level, pos, allBuilt); // grass and flowers go, trees go whole
				}
				if (n == 0) {
					first = pos;
				}
				last = pos;
				if (state.isAir() && pos.getY() < s.heart().getY() + 24 && moat.contains(BlockPos.asLong(pos.getX(), 0, pos.getZ()))) {
					SiteWork.openSky(level, pos, allBuilt); // nothing stays over the ditch: no earth, no trees, no overhang
				}
				if (!level.getBlockState(pos).equals(state)) {
					level.setBlock(pos, state, NO_DROPS);
					if (!state.getCollisionShape(level, pos).isEmpty()) {
						pushOut(level, pos);
					}
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
		if (first != null) {
			sweepDrops(level, first, last);
		}
		if (!replaced.isEmpty()) {
			LordChanges.forget(server, s, replaced);
			REPLACED.merge(s.id() + "|" + s.job(), replaced.size(), Integer::sum);
		}
		if (index >= cols + list.size()) {
			Integer gone = REPLACED.remove(s.id() + "|" + s.job());
			OLD_PLAN.remove(s.id() + "|" + s.job());
			ServerPlayer lord = server.getPlayerList().getPlayer(s.owner());
			if (gone != null && lord != null) {
				People.say(lord, "Architect", "The new plan needed " + gone + (gone == 1 ? " spot" : " spots")
						+ " you had changed. The rest of your changes stay as you made them.");
			}
			if (Progression.DOCK.equals(s.jobTrack())) {
				// the pier's posts go down to the bed, however deep the water is here
				List<Long> posts = new ArrayList<>();
				for (long l : built) {
					BlockPos p = BlockPos.of(l);
					BlockState st = level.getBlockState(p);
					if (!st.is(net.minecraft.tags.BlockTags.LOGS)) {
						continue;
					}
					BlockPos.MutableBlockPos q = p.mutable().move(Direction.DOWN);
					for (int k = 0; k < 32 && !builtSet.contains(q.asLong()) && !allBuilt.contains(q.asLong()); k++, q.move(Direction.DOWN)) {
						BlockState below = level.getBlockState(q);
						if (!(below.isAir() || below.canBeReplaced() || below.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)) {
							break;
						}
						level.setBlock(q, st, NO_DROPS);
						posts.add(q.asLong());
					}
				}
				posts.forEach(l -> {
					if (builtSet.add(l)) {
						built.add(l);
					}
				});
			}
			Settlement done = s.withJob("", "", 0).withBuilt(s.jobTrack(), built);
			// a block this build put where another track's block was (the gate in the wall's opening, a cellar over an
			// old lane) is this build's now: the other track no longer counts it as its own, so no repair puts it back
			for (Map.Entry<String, List<Long>> e : s.built().entrySet()) {
				if (!e.getKey().equals(s.jobTrack()) && e.getValue().stream().anyMatch(builtSet::contains)) {
					List<Long> rest = new ArrayList<>(e.getValue());
					rest.removeIf(builtSet::contains);
					done = done.withBuilt(e.getKey(), rest);
				}
			}
			put(server, done);
			spawnAnimals(level, s, t);
			People.removeWorkers(level, s);
			Set<Long> nowBuilt = new HashSet<>(allBuilt);
			nowBuilt.addAll(built);
			if (LIFTED.contains(s.jobTrack()) || Settlement.MAIN.equals(s.jobTrack())) {
				SiteWork.underpin(level, built, s.heart().getY() + lift(s, s.job()), nowBuilt, SiteWork.surface(level, s.heart()));
			}
			// natural pieces this build left hanging (overhangs, stumps) come down, stalk stumps go, snow comes back: in the
			// job's own area (the land itself was shaped by the grading plan before)
			{
				int[] box = JOB_BOX.computeIfAbsent(s.id() + "|" + s.job(), k -> jobBox(s, list, columns));
				SiteWork.dropFloating(level, box[0] - 4, box[1] - 4, box[2] + 4, box[3] + 4, nowBuilt);
				SiteWork.stumps(level, box[0] - 8, box[1] - 8, box[2] + 8, box[3] + 8, nowBuilt, SiteWork.surface(level, s.heart()));
				// snow back on the whole worked area: the ring, the moat bank and the roads out of the gate (country roads too)
				int[] ring = Grade.ringBox(s.heart(), Rotation.values()[s.rotation()], round(s));
				SiteWork.snowCover(level, ring[0] - 40, ring[1] - 40, ring[2] + 40, ring[3] + 40, nowBuilt);
				JOB_BOX.remove(s.id() + "|" + s.job());
			}
			carryRoads(level, done, SiteWork.surface(level, s.heart()));
			if (s.jobTrack().equals(Progression.GATE) && !moatColumns(done).isEmpty()) {
				SiteWork.sealDitch(level, moatColumns(done), nowBuilt, SiteWork.surface(level, s.heart()));
			}
			SiteWork.decorate(level, s.heart(), columns, nowBuilt);
			Production.armTraps(level, done, list);
			if (Progression.KENNEL.equals(s.jobTrack())) {
				ServerPlayer o = server.getPlayerList().getPlayer(s.owner());
				String kind = Collections.petKind(done.level(Progression.KENNEL));
				if (o != null && kind != null) {
					ServerPlayNetworking.send(o, new NamePayload(s.heart(), "pet_" + kind, Collections.defaultName(kind)));
				}
			}
			if (Progression.ROADS.equals(s.jobTrack()) && done.level(Progression.ROADS) == 3) {
				RoadLink.upgradeAll(server, done); // country roads get gravel, cobble and lanterns too
			}
			if (Progression.ROADS.equals(s.jobTrack())) {
				Roadside.update(server, byHeart(server, s.heart())); // waystations and road extras grow with the roads
			}
			if (Progression.DOCK.equals(s.jobTrack()) && done.level(Progression.DOCK) == 1) {
				RoadLink.link(server, null, done, "dock", dockPoint(done, 0, 0, -1), "the dock");
			}
			if (!Progression.ROADS.equals(s.jobTrack()) && !Progression.LAMPS.equals(s.jobTrack())) {
				extendInfrastructure(server, done.withLevel(s.jobTrack(), done.level(s.jobTrack())));
			}
			// the gate must open onto land that leads away. From gate 3 the landing and the moat are where they stay;
			// checked after every build (last, on the stored state: the roads update above writes the settlement too),
			// since any build's slopes and banks could cover the road
			Settlement fresh = byHeart(server, s.heart());
			// not while the drawbridge is up: the gate is shut on purpose then (user's world: with the bridge raised the
			// check found no way out after every build, laid a 2-block road and said so each time)
			if (fresh != null && fresh.level(Progression.WALLS) >= 1 && fresh.level(Progression.GATE) >= 3 && fresh.level(Drawbridge.STATE) != 1) {
				Set<Long> allNow = new HashSet<>();
				fresh.built().values().forEach(allNow::addAll);
				RoadLink.wayOut(server, fresh, allNow);
			}
			// the way-out road's earthwork can cut or raise the land under a lamp post: the posts are seated again
			// (field test: lanterns hanging a block over cut ground, posts sunk into a raised bank)
			Settlement seated = byHeart(server, s.heart());
			if (seated != null && seated.level(Progression.LAMPS) > 0 && !Progression.LAMPS.equals(s.jobTrack())) {
				extendInfrastructure(server, seated, List.of(Progression.LAMPS));
			}
			// every door can be walked to: steps where the lane meets a raised plot too low (field test: porches)
			Settlement stepped = byHeart(server, s.heart());
			if (stepped != null) {
				Settlement withSteps = DoorSteps.connect(server, stepped);
				if (withSteps != stepped) {
					put(server, withSteps);
				}
			}
			ServerPlayer owner = server.getPlayerList().getPlayer(s.owner());
			if (owner != null) {
				owner.sendSystemMessage(Component.translatable("kingdomquest.build.done", s.name()));
			}
			Chronicle.built(server, byHeart(server, s.heart()), s.jobTrack());
			Settlement latest = byHeart(server, s.heart());
			if (latest != null) {
				Progression.Step cur = Progression.current(latest, s.jobTrack());
				Component title = cur == null ? Component.literal(s.jobTrack()) : Component.translatable(cur.titleKey());
				if (LIFTED.contains(s.jobTrack()) || Progression.LAIR.equals(s.jobTrack())) {
					Set<Long> taken = new HashSet<>();
					latest.built().values().forEach(taken::addAll);
					Component label = Component.translatable("kingdomquest.track." + s.jobTrack());
					List<Long> sign = Landmarks.sign(level, latest, s.jobTrack(), label, taken);
					if (!sign.isEmpty()) {
						List<Long> with = new ArrayList<>(latest.built().getOrDefault(s.jobTrack(), List.of()));
						with.addAll(sign);
						latest = latest.withBuilt(s.jobTrack(), with);
					}
				}
				put(server, latest.withName("last_built", s.jobTrack()));
				Landmarks.celebrate(level, latest, s.jobTrack(), title, owner);
			}
			return;
		}
		put(server, s.withJob(s.job(), s.jobTrack(), index).withBuilt(s.jobTrack(), built));
		if (server.getTickCount() % 40 == 0) {
			int at = Math.max(0, Math.min(list.size() - 1, index - cols));
			People.herdWorkers(level, s, s.heart().offset(index < cols ? columns.get(index).dx() : list.get(at).offset().getX(), 0,
					index < cols ? columns.get(index).dz() : list.get(at).offset().getZ()));
		}
		ServerPlayer owner = server.getPlayerList().getPlayer(s.owner());
		if (owner != null && server.getTickCount() % 20 == 0 && owner.distanceToSqr(Vec3.atCenterOf(s.heart())) < 64 * 64) {
			owner.sendOverlayMessage(Component.translatable(index < cols ? "kingdomquest.build.clearing" : "kingdomquest.build.progress", s.name(),
					index * 100 / (cols + list.size())));
		}
	}

	/** Nobody gets stuck in a wall that was just built: people and animals inside the block step up and out. */
	static void pushOut(ServerLevel level, BlockPos pos) {
		for (net.minecraft.world.entity.LivingEntity e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, new net.minecraft.world.phys.AABB(pos))) {
			BlockPos p = pos;
			for (int up = 0; up < 12; up++) {
				p = p.above();
				if (level.getBlockState(p).getCollisionShape(level, p).isEmpty() && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()) {
					break;
				}
			}
			e.teleportTo(e.getX(), p.getY(), e.getZ());
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
			BlockPos pos = s.heart().offset(t.offset(a.pos(), Rotation.values()[s.rotation()])).above(lift(s, s.job()));
			for (int up = 0; up < 12 && !(level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()); up++) {
				pos = pos.above(); // never inside a block
			}
			e.snapTo(Vec3.atBottomCenterOf(pos), level.getRandom().nextFloat() * 360, 0);
			e.setAttached(People.ROLE, "animal|" + s.id() + "|" + a.type().getPath()); // shows up in the realm register
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
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), NO_DROPS);
					LordChanges.noteBreak(level, player, pos); // the lord's own change: repair leaves it
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
			String lockedBy = next == null ? "" : s.level(Settlement.MAIN) < next.minMain() ? mainSteps.get(next.minMain() - 1).titleKey()
					: !Progression.wallsOk(s, next) ? Progression.WALL_STEPS.get(Progression.GATE_MIN_WALLS[next.level() - 1] - 1).titleKey() : "";
			options.add(new KingdomInfoPayload.Option(track, lvl, steps.stream().map(Progression.Step::titleKey).toList(),
					next == null || next.requirementKey() == null ? "" : next.requirementKey(),
					next != null && mine && Progression.requirementMet(player, next), next == null ? "" : Progression.costText(next),
					next != null && mine && Progression.canPay(player, next), lockedBy));
		}
		Progression.Step current = Progression.current(s, Settlement.MAIN);
		int bridge = s.level(Progression.GATE) >= 3 ? s.level(Drawbridge.STATE) : -1;
		int broken = s.job().isEmpty() && !REPAIRS.containsKey(s.id()) ? damaged((ServerLevel) player.level().getServer().overworld(), s).size() : 0;
		int changed = mine ? LordChanges.of(player.level().getServer(), s).size() : 0;
		Grade grade = Grade.of(player.level().getServer(), s);
		int site = grade == null || grade.complete() ? -1 : grade.progress();
		ServerPlayNetworking.send(player, new KingdomInfoPayload(s.heart(), s.name(), s.color(), s.logo(),
				current == null ? "" : current.titleKey(), percent, options, bridge, broken, changed, site));
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
			SiteWork.clear(level, heart, c, Set.of(), surface, Set.of());
		}
		int left = 0;
		Map<String, Integer> kinds = new HashMap<>();
		int water = 0;
		int bare = 0;
		for (SiteWork.Column c : columns) {
			if (!c.pad()) {
				continue; // graded land around the pad: only shaped, not flattened
			}
			BlockPos base = heart.offset(c.dx(), 0, c.dz());
			for (int y = base.equals(heart) ? 1 : 0; y < 4; y++) {
				BlockState st = level.getBlockState(base.above(y));
				if (!st.isAir()) {
					left++;
					kinds.merge(st.getBlock().getDescriptionId() + "@y" + y, 1, Integer::sum);
				}
			}
			if (!level.getBlockState(base.below(1)).getFluidState().isEmpty() || !level.getBlockState(base.below(2)).getFluidState().isEmpty()) {
				water++;
			}
			if (!base.equals(heart) && !level.getBlockState(base.below(1)).is(Blocks.GRASS_BLOCK)) {
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
