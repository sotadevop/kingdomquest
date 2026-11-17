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
	private static final int SPACING = 200;
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
	/** Job -> its pad is made up with a natural bank (true) or stands on a terrace wall (false). */
	private static final Map<String, Boolean> BANKS = new HashMap<>();
	/** Settlement id -> blocks a repair could not make stay (two plans want the same spot, a block that falls...): not counted again. */
	private static final Map<UUID, Set<Long>> UNFIXABLE = new HashMap<>();
	private static MinecraftServer server;

	private record Pending(BlockPos heart, long tick) {
	}

	private Settlements() {
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
			SitePlan.forgetAll();
			UNFIXABLE.clear();
		});
		// no monsters spawn inside a settlement
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof net.minecraft.world.entity.monster.Enemy && !Defense.isRaider(entity) && level.dimension() == Level.OVERWORLD && insideSettlement(level.getServer(), entity.blockPosition())) {
				entity.discard();
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(Drawbridge::tick);
		People.register();
		Guild.register();
		Companion.register();
		PayloadTypeRegistry.clientboundPlay().register(NamePayload.TYPE, NamePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RealmPayload.TYPE, RealmPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(NamePayload.TYPE, NamePayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(NamePayload.TYPE, (payload, context) -> rename(context.player(), payload));
		ServerTickEvents.END_SERVER_TICK.register(Traps::tick);
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

	/** Protected radius around a heart by tier: camp 20, hamlet 40, village and up the wall ring (~62). */
	static boolean insideSettlement(MinecraftServer srv, BlockPos pos) {
		for (Settlement s : all(srv)) {
			int main = s.level(Settlement.MAIN);
			int r = main >= 7 ? 80 : main >= 4 ? 40 : 20;
			if (Math.abs(pos.getX() - s.heart().getX()) <= r && Math.abs(pos.getZ() - s.heart().getZ()) <= r
					&& Math.abs(pos.getY() - s.heart().getY()) < 48) {
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
		if (p == null || !p.heart().equals(heart) || now - p.tick() > CONFIRM_TICKS) {
			PENDING.put(player.getUUID(), new Pending(heart, now));
			player.sendOverlayMessage(Component.translatable("kingdomquest.found.confirm", data.name()));
			int relief = relief(level, heart);
			if (relief > 20) {
				player.sendSystemMessage(Component.translatable("kingdomquest.found.steep", relief).withStyle(net.minecraft.ChatFormatting.GOLD));
			}
			return false;
		}
		PENDING.remove(player.getUUID());
		String style = Style.forBiome(level.getBiome(heart).getRegisteredName());
		Rotation rotation = rotationFor(player.getDirection());
		Settlement s = new Settlement(UUID.randomUUID(), player.getUUID(), data.name(), data.color(), data.logo(), style, heart,
				rotation.ordinal(), Map.of(Settlement.MAIN, 1), style + "/tent_1", Settlement.MAIN, 0, Map.of(), Map.of());
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

	/** Height difference of the land within the future wall ring (sampled every 8 blocks). */
	static int relief(ServerLevel level, BlockPos heart) {
		int lo = Integer.MAX_VALUE;
		int hi = Integer.MIN_VALUE;
		for (int dx = -60; dx <= 70; dx += 8) {
			for (int dz = -55; dz <= 45; dz += 8) {
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
		if (Template.get(s.style() + "/" + step.building()) == null) {
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
			"trophy", "tavern", "market", "cartographer", "music", "workshop", "armorer", "tannery", "archaeology", "mine", "nether", "royal");

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

	/** How many blocks above (or below) the heart the job's building stands: its terrace height. */
	static int lift(Settlement s, String job) {
		String track = trackOf(job);
		return LIFTED.contains(track) ? s.level("lift_" + track) : 0;
	}

	/**
	 * The terrace height for a new add-on: the median height of the natural ground under it, at most 4 blocks
	 * from the square; differences of a block are levelled out instead.
	 */
	private static int terraceHeight(ServerLevel level, Settlement s, String job) {
		Template t = Template.get(job);
		if (t == null) {
			return 0;
		}
		Set<Long> cols = new HashSet<>();
		for (Template.Placement p : t.placements(dye(s.color()), Rotation.values()[s.rotation()])) {
			cols.add(BlockPos.asLong(p.offset().getX(), 0, p.offset().getZ()));
		}
		for (int[] r : t.pads) { // the whole plot, so the terrace also suits the later levels
			for (int x = r[0]; x <= r[2]; x += 2) {
				for (int z = r[1]; z <= r[3]; z += 2) {
					BlockPos rel = Template.rotate(new BlockPos(x - t.heart.getX(), 0, z - t.heart.getZ()), Rotation.values()[s.rotation()]);
					cols.add(BlockPos.asLong(rel.getX(), 0, rel.getZ()));
				}
			}
		}
		List<Integer> hs = new ArrayList<>();
		SitePlan sitePlan = server == null ? null : SitePlan.of(server, s);
		int n = 0;
		for (long c : cols) {
			if (n++ % 3 != 0) {
				continue; // a sample is enough
			}
			int x = s.heart().getX() + BlockPos.getX(c);
			int z = s.heart().getZ() + BlockPos.getZ(c);
			// from the site plan where there is one: the terrace then fits the landform the whole site is shaped to
			int planned = sitePlan == null ? Integer.MIN_VALUE : sitePlan.at(x, z);
			hs.add(planned != Integer.MIN_VALUE ? planned : SiteWork.groundDeep(level, x, z, s.heart().getY()) + 1 - s.heart().getY());
		}
		if (hs.isEmpty()) {
			return 0;
		}
		java.util.Collections.sort(hs);
		// on the median height of its land, but never more than 10 blocks above the lowest ground under it:
		// up to 3 blocks of drop get a retaining wall, 4-10 a deck on piers; more and the building steps down the slope
		int dy = Math.min(hs.get(hs.size() / 2), hs.get(0) + 10);
		// at most 7 from the square: the trunk roads run near the square's level and the lane from a trunk to a door is
		// often only 5-8 blocks long, so a building lower or higher than that can't be walked to (field test: houses
		// at -11 on steep sites sat in pits nobody could climb out of). Steeper land around the plot is cut and
		// terraced instead.
		// and never more than a walkable ramp (one block per block) can climb between its doors and the main
		// building's plot, which stays at the square's level: next to the main plot that is only a few blocks
		int limit = Math.max(2, Math.min(LIFT_LIMIT, roomToSquare(s, t) - 1));
		dy = Math.max(-limit, Math.min(limit, dy));
		// plots that face each other share a level: a door looking onto a neighbour's plot within 4 blocks, either
		// way round, puts both on the same level (one block either side), or the door looks at a wall (field test)
		int[] range = facingLevels(level, s, t, cols);
		if (range != null) {
			dy = Math.max(range[0], Math.min(range[1], dy));
		}
		return Math.abs(dy) < 2 ? 0 : dy;
	}

	/**
	 * The levels (lift, relative to the heart) a new plot may take so it meets the doors around it: an existing door
	 * whose way out (4 blocks, 3 wide, both sides) crosses the new plot wants the plot at its own floor; a door of the
	 * new building whose way out crosses an existing building wants the plot at that building's level. Null: free.
	 */
	private static int[] facingLevels(ServerLevel level, Settlement s, Template t, Set<Long> plot) {
		int lo = Integer.MIN_VALUE, hi = Integer.MAX_VALUE;
		boolean any = false;
		BlockPos h = s.heart();
		Map<Long, Integer> levelOf = new HashMap<>(); // column -> level of the building standing there
		for (Map.Entry<String, List<Long>> e : s.built().entrySet()) {
			String track = e.getKey();
			if (!LIFTED.contains(track) && !track.equals(Settlement.MAIN)) {
				continue;
			}
			int lift = track.equals(Settlement.MAIN) ? 0 : s.level("lift_" + track);
			for (long p : e.getValue()) {
				BlockPos b = BlockPos.of(p);
				long col = BlockPos.asLong(b.getX() - h.getX(), 0, b.getZ() - h.getZ());
				levelOf.putIfAbsent(col, lift);
				Block block = level.getBlockState(b).getBlock();
				if (block instanceof net.minecraft.world.level.block.DoorBlock
						&& level.getBlockState(b).getValue(net.minecraft.world.level.block.DoorBlock.HALF)
								== net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) {
					int floor = b.getY() - h.getY(); // a plot at this lift has its ground just under this door's sill
					net.minecraft.core.Direction f0 = level.getBlockState(b).getValue(net.minecraft.world.level.block.DoorBlock.FACING);
					for (net.minecraft.core.Direction f : new net.minecraft.core.Direction[] {f0, f0.getOpposite()}) {
						boolean hits = false;
						for (int k = 1; k <= 4 && !hits; k++) {
							for (int side = -1; side <= 1; side++) {
								BlockPos q = b.relative(f, k).relative(f.getClockWise(), side);
								hits |= plot.contains(BlockPos.asLong(q.getX() - h.getX(), 0, q.getZ() - h.getZ()));
							}
						}
						if (hits) {
							lo = Math.max(lo, floor - 1);
							hi = Math.min(hi, floor + 1);
							any = true;
						}
					}
				}
			}
		}
		for (Template.Placement p : t.placements(dye(s.color()), Rotation.values()[s.rotation()])) {
			BlockState st = p.state();
			if (!(st.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) || st.getValue(net.minecraft.world.level.block.DoorBlock.HALF)
					!= net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) {
				continue;
			}
			net.minecraft.core.Direction f0 = st.getValue(net.minecraft.world.level.block.DoorBlock.FACING);
			for (net.minecraft.core.Direction f : new net.minecraft.core.Direction[] {f0, f0.getOpposite()}) {
				for (int k = 1; k <= 4; k++) {
					for (int side = -1; side <= 1; side++) {
						BlockPos q = p.offset().relative(f, k).relative(f.getClockWise(), side);
						Integer other = levelOf.get(BlockPos.asLong(q.getX(), 0, q.getZ()));
						if (other != null && !plot.contains(BlockPos.asLong(q.getX(), 0, q.getZ()))) {
							// this door's sill sits at lift + its height in the template; the other plot's ground at its lift
							int want = other - p.offset().getY();
							lo = Math.max(lo, want - 1);
							hi = Math.min(hi, want + 1);
							any = true;
						}
					}
				}
			}
		}
		if (!any) {
			return null;
		}
		return lo <= hi ? new int[] {lo, hi} : new int[] {lo, lo}; // conflicting neighbours: the stricter one wins
	}

	/** Blocks (walking, along the grid) from the nearest door of {@code t} to the main building's plot; large if unknown. */
	private static int roomToSquare(Settlement s, Template t) {
		Progression.Step main = Progression.current(s, Settlement.MAIN);
		Template m = main == null ? null : Template.get(s.style() + "/" + main.building());
		if (m == null || m.pads.isEmpty()) {
			return 99;
		}
		Rotation r = Rotation.values()[s.rotation()];
		List<int[]> boxes = new ArrayList<>();
		for (int[] pad : m.pads) {
			BlockPos a = Template.rotate(new BlockPos(pad[0] - m.heart.getX(), 0, pad[1] - m.heart.getZ()), r);
			BlockPos b = Template.rotate(new BlockPos(pad[2] - m.heart.getX(), 0, pad[3] - m.heart.getZ()), r);
			boxes.add(new int[] {Math.min(a.getX(), b.getX()), Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()), Math.max(a.getZ(), b.getZ())});
		}
		int best = 99;
		for (Template.Placement p : t.placements(dye(s.color()), r)) {
			if (!(p.state().getBlock() instanceof net.minecraft.world.level.block.DoorBlock)) {
				continue;
			}
			int x = p.offset().getX(), z = p.offset().getZ();
			for (int[] b : boxes) {
				int dx = Math.max(0, Math.max(b[0] - x, x - b[2]));
				int dz = Math.max(0, Math.max(b[1] - z, z - b[3]));
				best = Math.min(best, dx + dz);
			}
		}
		return best;
	}

	/** Before a building goes up, the land around its plot is shaped toward the site plan (see {@link SitePlan}). */
	private static void gradeSite(MinecraftServer server, Settlement s, String job) {
		Template t = Template.get(job);
		SitePlan plan = t == null ? null : SitePlan.of(server, s);
		if (plan == null) {
			return;
		}
		List<SiteWork.Column> columns = SiteWork.columns(job, t, Rotation.values()[s.rotation()]);
		if (columns.isEmpty()) {
			return; // roads, lamps, walls: they follow the land as it is
		}
		int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
		for (SiteWork.Column c : columns) {
			x0 = Math.min(x0, c.dx());
			z0 = Math.min(z0, c.dz());
			x1 = Math.max(x1, c.dx());
			z1 = Math.max(z1, c.dz());
		}
		ServerLevel level = server.overworld();
		Set<Long> all = new HashSet<>();
		s.built().values().forEach(all::addAll);
		Set<Long> roads = new HashSet<>(s.built().getOrDefault(Progression.ROADS, List.of()));
		BlockPos h = s.heart();
		Set<Long> keep = new HashSet<>(moatColumns(s));
		keep.addAll(doorApproaches(level, s));
		SiteWork.grade(level, h, h.getX() + x0 - 6, h.getZ() + z0 - 6, h.getX() + x1 + 6, h.getZ() + z1 + 6, plan::at, all, roads,
				keep, SiteWork.surface(level, h));
	}

	/** The columns of the settlement's moat (empty before gate level 3). */
	static Set<Long> moatColumns(Settlement s) {
		Progression.Step gate = s.level(Progression.GATE) >= 3 ? Progression.current(s, Progression.GATE) : null;
		if (gate == null) {
			return Set.of();
		}
		placements(s, s.style() + "/" + gate.building()); // works the moat out if it hasn't been yet
		return MOAT.getOrDefault(s.id(), Set.of());
	}

	/** Evens out the land inside the settlement's ring after a build. */
	static void smoothGround(ServerLevel level, Settlement s) {
		Rotation r = Rotation.values()[s.rotation()];
		BlockPos a = s.heart().offset(Template.rotate(new BlockPos(-63, 0, -56), r));
		BlockPos b = s.heart().offset(Template.rotate(new BlockPos(69, 0, 40), r));
		Set<Long> all = new HashSet<>();
		s.built().values().forEach(all::addAll);
		Set<Long> roads = new HashSet<>(s.built().getOrDefault(Progression.ROADS, List.of()));
		Set<Long> keep = new HashSet<>(moatColumns(s));
		keep.addAll(doorApproaches(level, s));
		SiteWork.smooth(level, s.heart(), Math.min(a.getX(), b.getX()), Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()),
				Math.max(a.getZ(), b.getZ()), all, roads, SiteWork.surface(level, s.heart()), keep);
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
		String job = s0.style() + "/" + step.building();
		String track0 = track;
		ServerLevel level = server.overworld();
		if (Progression.DOCK.equals(track) && s0.level("dock_set") == 0) {
			if (!findDockSite(server, s0)) {
				return; // no water near: no dock (it used to be built on the square in that case)
			}
			s0 = byHeart(server, s0.heart());
		}
		Settlement s = LIFTED.contains(track) && s0.level(track) == 0 ? s0.withLevel("lift_" + track, terraceHeight(level, s0, job)) : s0;
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
		gradeSite(server, s, job);
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
			case "rebuild" -> startRebuild(player, s);
			default -> {
				if (action.equals("realm")) {
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
		if (step == null || Template.get(rest.style() + "/" + step.building()) == null) {
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

	private static final Map<UUID, List<Template.Placement>> REPAIRS = new HashMap<>();
	private static final Map<UUID, int[]> REPAIR_AT = new HashMap<>();

	/** Blocks of all built levels that are missing or wrong in the world (attacks, griefing). Doors etc. count by type. */
	static List<Template.Placement> damaged(ServerLevel level, Settlement s) {
		List<Template.Placement> out = new ArrayList<>();
		Set<Long> unfixable = UNFIXABLE.getOrDefault(s.id(), Set.of());
		for (String track : Progression.ORDER) {
			Progression.Step step = Progression.current(s, track);
			if (step == null) {
				continue;
			}
			Template t = Template.get(s.style() + "/" + step.building());
			if (t == null || track.equals(s.jobTrack())) {
				continue;
			}
			// every block the builders placed for this track (walls, roads, foundations, moat bridge...) is checked
			Set<Long> mine = new HashSet<>(s.built().getOrDefault(track, List.of()));
			for (Template.Placement p : placements(s, s.style() + "/" + step.building())) {
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
			String job = s.style() + "/" + step.building();
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
				for (int step = 0; step < 40; step++) {
					BlockPos next = alongX ? water.offset(dx, 0, 0) : water.offset(0, 0, dz);
					if (!level.getFluidState(next).is(net.minecraft.tags.FluidTags.WATER)) {
						break;
					}
					water = next;
				}
				boolean open = true;
				for (int k = 1; k <= 14 && open; k++) {
					BlockPos out = alongX ? water.offset(-dx * k, 0, 0) : water.offset(0, 0, -dz * k);
					open = level.getFluidState(out).is(net.minecraft.tags.FluidTags.WATER);
				}
				if (!open) {
					continue; // the pier needs open water ahead
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
			boolean on = seg.any().stream().anyMatch(r -> s.level(r.getKey()) >= r.getValue());
			mask.append(on ? '1' : '0');
		}
		int dy = lift(s, job);
		String key = job + "|" + s.color() + "|" + s.rotation() + "|" + mask + "|" + dy;
		return PLACEMENTS.computeIfAbsent(key, k -> {
			List<Template.Placement> all = new ArrayList<>();
			for (Template.Placement p : t.placements(dye(s.color()), Rotation.values()[s.rotation()])) {
				all.add(dy == 0 ? p : new Template.Placement(p.offset().above(dy), p.state(), p.segment(), p.anchor()));
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

	/** How far above or below the square an add-on's plot may lie. */
	static final int LIFT_LIMIT = 7;
	/** Per job: columns its land shaping filled up (made ground), for {@link SiteWork#dressFaces}. */
	private static final Map<String, Set<Long>> FILLED = new HashMap<>();
	/** Per job: columns in front of other buildings' doors that its land shaping leaves alone. */
	private static final Map<String, Set<Long>> RAMPS = new HashMap<>();
	/** (RAMPS: per job, the columns straight out from its own doors, shaped as an earth ramp.) */
	private static final Map<String, Set<Long>> PROTECTED = new HashMap<>();

	/** The side of the wall ring a position outside it faces, in the template's frame: 0 north, 1 south (the gate), 2 west, 3 east. */
	private static final int SOUTH = 1;

	private static int ringSide(BlockPos rel, Rotation turn) {
		Rotation back = switch (turn) {
			case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
			case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
			default -> turn;
		};
		BlockPos t = Template.rotate(rel, back);
		int ex = t.getX() < -63 ? -63 - t.getX() : Math.max(0, t.getX() - 69);
		int ez = t.getZ() < -56 ? -56 - t.getZ() : Math.max(0, t.getZ() - 40);
		if (ez >= ex) {
			return t.getZ() > -8 ? SOUTH : 0;
		}
		return t.getX() < 3 ? 2 : 3;
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
					BlockPos col = s.heart().offset(p.anchor());
					int g = ground.computeIfAbsent(col.asLong(), c -> SiteWork.groundDeep(level, col.getX(), col.getZ(), s.heart().getY()) + 1
							- s.heart().getY());
					int local = SiteWork.groundDeep(level, here.getX(), here.getZ(), s.heart().getY(), mine) + 1 - s.heart().getY();
					int side = ringSide(p.offset(), turn);
					count[side][0]++;
					if (Math.abs(local - g) > 4) {
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
						int g = level0 ? 0 : ground.computeIfAbsent(col.asLong(), c -> SiteWork.groundDeep(level, col.getX(), col.getZ(),
								s.heart().getY()) + 1 - s.heart().getY());
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
			if (track.equals(Progression.ROADS)) {
				ground.putAll(evenRoad(level, s, base, others, own, through));
			}
			// a wall that stands (after a restart, for repairs, for a rebuild) is found where it is: the top of
			// what was built in an anchor column against the top of the plan there gives its height exactly
			Progression.Step standing = Progression.current(s, track);
			boolean same = walls && !own.isEmpty() && !job.equals(s.job()) && standing != null && job.endsWith("/" + standing.building());
			Map<Long, Integer> planTop = new HashMap<>();
			if (same) {
				for (Template.Placement p : base) {
					if (p.offset().getX() == p.anchor().getX() && p.offset().getZ() == p.anchor().getZ()) {
						planTop.merge(BlockPos.asLong(p.anchor().getX(), 0, p.anchor().getZ()), p.offset().getY(), Math::max);
					}
				}
			}
			for (Template.Placement p : base) {
				BlockPos col = s.heart().offset(p.anchor());
				Integer top = planTop.get(BlockPos.asLong(p.anchor().getX(), 0, p.anchor().getZ()));
				int off = ground.computeIfAbsent(col.asLong(), c -> {
					int g;
					if (top != null) {
						BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
						for (int y = heartY + 70; y >= heartY - 70; y--) {
							if (own.contains(at.set(col.getX(), y, col.getZ()).asLong())) {
								return y - heartY - top;
							}
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
						g = SiteWork.groundDeep(level, col.getX(), col.getZ(), heartY, others);
					}
					return Math.max(-40, Math.min(30, g + 1 - heartY));
				});
				Template.Placement at = new Template.Placement(p.offset().above(off), p.state(), p.segment(), p.anchor());
				out.add(at);
				placed.add(at.offset().asLong());
			}
			if (track.equals(Progression.ROADS)) {
				// a paved road that climbs gets a slab on the lower block of every step: you walk up, you don't jump
				// (trodden dirt paths stay bare: a path under a slab turns to dirt)
				Map<Long, Integer> top = new HashMap<>();
				for (Template.Placement p : out) {
					top.merge(BlockPos.asLong(p.offset().getX(), 0, p.offset().getZ()), p.offset().getY(), Math::max);
				}
				List<Template.Placement> steps = new ArrayList<>();
				for (Template.Placement p : out) {
					BlockState st = p.state();
					if (st.is(Blocks.DIRT_PATH) || st.is(Blocks.COARSE_DIRT) || !st.isSolid()) {
						continue;
					}
					boolean climbs = false;
					for (int[] d2 : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
						Integer n = top.get(BlockPos.asLong(p.offset().getX() + d2[0], 0, p.offset().getZ() + d2[1]));
						climbs |= n != null && n == p.offset().getY() + 1;
					}
					BlockPos above = p.offset().above();
					if (climbs && !others.contains(s.heart().offset(above).asLong()) && placed.add(above.asLong())) {
						boolean rough = st.is(Blocks.COBBLESTONE) || st.is(Blocks.MOSSY_COBBLESTONE) || st.is(Blocks.GRAVEL);
						steps.add(new Template.Placement(above, (rough ? Blocks.COBBLESTONE_SLAB : Blocks.STONE_BRICK_SLAB).defaultBlockState(),
								p.segment(), p.anchor()));
					}
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
						boolean open = st.isAir() || st.canBeReplaced() || st.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock
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
	 * The height of every road column (offset from the heart's level, as the road follows the land) after cut and
	 * fill: neighbouring road columns never differ by more than one block, so every step can be walked (and gets a
	 * slab). Columns next to anything built (doors, the gatehouse, terraces) keep the height of their land; between
	 * them the road is the middle of the land pulled down to a walkable slope and pulled up to one, which spreads a
	 * big step evenly over cut on the high side and fill on the low side, as a road builder would.
	 */
	private static Map<Long, Integer> evenRoad(ServerLevel level, Settlement s, List<Template.Placement> base, Set<Long> others, Set<Long> own,
			Set<Long> through) {
		int heartY = s.heart().getY();
		// the road's own step slabs lie on top of it: measured as ground, the road would climb a block every time it is
		// worked out again (field test: the ground in front of a door rose by one with every later build)
		Set<Long> skip = new HashSet<>(others);
		for (long o : own) {
			if (level.getBlockState(BlockPos.of(o)).getBlock() instanceof net.minecraft.world.level.block.SlabBlock) {
				skip.add(o);
			}
		}
		Map<Long, Integer> g = new HashMap<>();
		for (Template.Placement p : base) {
			BlockPos col = s.heart().offset(p.anchor());
			g.computeIfAbsent(BlockPos.asLong(col.getX(), 0, col.getZ()), c -> {
				int y = SiteWork.groundDeep(level, col.getX(), col.getZ(), heartY, skip);
				BlockPos top = new BlockPos(col.getX(), y, col.getZ());
				BlockState under = level.getBlockState(top.below());
				if (own.contains(top.asLong()) && (under.isAir() || under.canBeReplaced() && under.getFluidState().isEmpty())) {
					// the road itself was found, hanging over land that was cut away since: it goes down to the land
					y = SiteWork.groundDeep(level, col.getX(), col.getZ(), heartY, through);
				}
				return Math.max(-40, Math.min(30, y + 1 - heartY));
			});
		}
		// fixed: in front of a door or gate (the road meets the threshold), under anything built over the road
		// (gatehouse, arch), and on the square around the heart
		Map<Long, List<Integer>> builtYs = new HashMap<>();
		Set<Long> doorCols = new HashSet<>();
		for (long o : others) {
			BlockPos b = BlockPos.of(o);
			builtYs.computeIfAbsent(BlockPos.asLong(b.getX(), 0, b.getZ()), k -> new ArrayList<>()).add(b.getY() - heartY);
			Block block = level.getBlockState(b).getBlock();
			if (block instanceof net.minecraft.world.level.block.DoorBlock || block instanceof net.minecraft.world.level.block.FenceGateBlock) {
				doorCols.add(BlockPos.asLong(b.getX(), 0, b.getZ()));
			}
		}
		Set<Long> fixed = new HashSet<>();
		for (Map.Entry<Long, Integer> e : g.entrySet()) {
			long c = e.getKey();
			int x = BlockPos.getX(c), z = BlockPos.getZ(c);
			if (Math.abs(x - s.heart().getX()) <= 3 && Math.abs(z - s.heart().getZ()) <= 3) {
				fixed.add(c);
				continue;
			}
			for (int y : builtYs.getOrDefault(c, List.of())) {
				if (y > e.getValue() && y <= e.getValue() + 6) {
					fixed.add(c);
				}
			}
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					if (doorCols.contains(BlockPos.asLong(x + dx, 0, z + dz))) {
						fixed.add(c);
					}
				}
			}
		}
		Map<Long, Integer> low = new HashMap<>(g);
		Map<Long, Integer> high = new HashMap<>(g);
		int[][] four = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		for (boolean changed = true; changed; ) {
			changed = false;
			for (long c : g.keySet()) {
				if (fixed.contains(c)) {
					continue;
				}
				int x = BlockPos.getX(c), z = BlockPos.getZ(c);
				for (int[] d : four) {
					long n = BlockPos.asLong(x + d[0], 0, z + d[1]);
					Integer ln = low.get(n);
					if (ln != null && ln + 1 < low.get(c)) {
						low.put(c, ln + 1);
						changed = true;
					}
					Integer hn = high.get(n);
					if (hn != null && hn - 1 > high.get(c)) {
						high.put(c, hn - 1);
						changed = true;
					}
				}
			}
		}
		// how far the fixed ends allow a column to be from them: one block up or down per block along the road.
		// The middle of the land's own envelopes is kept inside these bounds, so the road also meets every door
		// step by step (max and min of walkable slopes are walkable slopes)
		Map<Long, Integer> capHigh = new HashMap<>();
		Map<Long, Integer> capLow = new HashMap<>();
		for (long c : g.keySet()) {
			capHigh.put(c, fixed.contains(c) ? g.get(c) : Integer.MAX_VALUE / 2);
			capLow.put(c, fixed.contains(c) ? g.get(c) : Integer.MIN_VALUE / 2);
		}
		for (boolean changed = !fixed.isEmpty(); changed; ) {
			changed = false;
			for (long c : g.keySet()) {
				int x = BlockPos.getX(c), z = BlockPos.getZ(c);
				for (int[] d : four) {
					long n = BlockPos.asLong(x + d[0], 0, z + d[1]);
					Integer hn = capHigh.get(n);
					if (hn != null && hn + 1 < capHigh.get(c)) {
						capHigh.put(c, hn + 1);
						changed = true;
					}
					Integer ln = capLow.get(n);
					if (ln != null && ln - 1 > capLow.get(c)) {
						capLow.put(c, ln - 1);
						changed = true;
					}
				}
			}
		}
		Map<Long, Integer> h = new HashMap<>();
		for (long c : g.keySet()) {
			int mid = Math.floorDiv(low.get(c) + high.get(c), 2);
			h.put(c, fixed.contains(c) ? g.get(c) : Math.min(capHigh.get(c), Math.max(capLow.get(c), mid)));
		}
		for (int pass = 0; pass < 12; pass++) { // where two fixed ends disagree, the free columns between still close up
			for (long c : g.keySet()) {
				if (fixed.contains(c)) {
					continue;
				}
				int x = BlockPos.getX(c), z = BlockPos.getZ(c);
				int lo = Integer.MIN_VALUE, hi = Integer.MAX_VALUE;
				for (int[] d : four) {
					Integer n = h.get(BlockPos.asLong(x + d[0], 0, z + d[1]));
					if (n != null) {
						lo = Math.max(lo, n - 1);
						hi = Math.min(hi, n + 1);
					}
				}
				if (lo <= hi) {
					h.put(c, Math.max(lo, Math.min(hi, h.get(c))));
				}
			}
		}
		Map<Long, Integer> out = new HashMap<>();
		for (Template.Placement p : base) {
			BlockPos col = s.heart().offset(p.anchor());
			out.put(col.asLong(), h.get(BlockPos.asLong(col.getX(), 0, col.getZ())));
		}
		return out;
	}

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
				if (!others.contains(pos.asLong()) && (st.isAir() || st.canBeReplaced()) && st.getFluidState().isEmpty() && placed.add(BlockPos.asLong(rx, y, rz))) {
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
		ServerLevel level = server.overworld();
		BlockState surface = SiteWork.surface(level, s.heart());
		for (String track : List.of(Progression.ROADS, Progression.LAMPS)) {
			Progression.Step current = Progression.current(s, track);
			if (current == null) {
				continue;
			}
			String job = s.style() + "/" + current.building();
			// the land may have been cut or filled under them since they were laid: work out again where they lie,
			// take up what is left hanging or buried, and lay what is missing (new lanes included)
			String prefix = s.id() + "|" + job + "|";
			FOLLOWED.keySet().removeIf(k -> k.startsWith(prefix));
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
			if (track.equals(Progression.ROADS)) {
				// whatever cut the land since, no road block is left hanging: earth is carried down under it to the
				// ground (like the masonry under a building's ground course). Water stays: there the road is a causeway
				BlockState earth = SiteWork.subsoil(surface);
				Set<Long> ditch = moatColumns(s); // never a land bridge over the moat: the drawbridge is the only way across
				for (long r : List.copyOf(built)) {
					BlockPos rp = BlockPos.of(r);
					if (level.getBlockState(rp).isAir() || ditch.contains(BlockPos.asLong(rp.getX(), 0, rp.getZ()))) {
						continue;
					}
					for (int down = 1; down <= 24; down++) {
						BlockPos q = rp.below(down);
						BlockState qs = level.getBlockState(q);
						if (all.contains(q.asLong()) && !qs.isAir() || !(qs.isAir() || qs.canBeReplaced()) || !qs.getFluidState().isEmpty()) {
							break;
						}
						level.setBlock(q, earth, NO_DROPS);
						if (have.add(q.asLong())) {
							built.add(q.asLong());
						}
					}
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
		// a pad that stands only a little above the land is made up as a natural rise with a bank around it;
		// over a real drop it gets a terrace wall. Decided once per job, before the first column is touched.
		boolean bank = index < cols && BANKS.computeIfAbsent(s.id() + "|" + s.job(), jobKey -> SiteWork.rimDrop(level, base, columns) <= SiteWork.BANK_LIMIT);
		Set<Long> roadBlocks = new HashSet<>(s.built().getOrDefault(Progression.ROADS, List.of()));
		Set<Long> loose = new HashSet<>(s.built().getOrDefault(Progression.ROADS, List.of()));
		loose.addAll(s.built().getOrDefault(Progression.LAMPS, List.of()));
		// the approach to the doors of buildings that already stand is theirs: this job's slopes, terraces and plot
		// levelling never cut it away or heap onto it. Its own blocks still go where they belong (field test: the
		// main building's plot covers the whole square and flattened the land in front of raised houses)
		Set<Long> keepClear = index < cols ? PROTECTED.computeIfAbsent(s.id() + "|" + s.job(), jobKey -> {
			Set<Long> out = doorApproaches(level, s, s.jobTrack());
			for (Template.Placement p : list) {
				BlockPos q = s.heart().offset(p.offset());
				out.remove(BlockPos.asLong(q.getX(), 0, q.getZ()));
			}
			return out;
		}) : Set.of();
		// straight out from this building's own doors the land is an earth ramp (the bank's slope), never a terrace
		// step: whatever the rest of the plot does, every door can be walked up to
		Set<Long> ramps = index < cols ? RAMPS.computeIfAbsent(s.id() + "|" + s.job(), jobKey -> {
			Set<Long> out = new HashSet<>();
			for (Template.Placement p : list) {
				BlockState st = p.state();
				if (st.getBlock() instanceof net.minecraft.world.level.block.DoorBlock && st.hasProperty(net.minecraft.world.level.block.DoorBlock.FACING)) {
					net.minecraft.core.Direction f0 = st.getValue(net.minecraft.world.level.block.DoorBlock.FACING);
					BlockPos d = s.heart().offset(p.offset());
					for (net.minecraft.core.Direction f : new net.minecraft.core.Direction[] {f0, f0.getOpposite()}) { // in or out
						for (int k = 1; k <= 16; k++) {
							for (int side = -2; side <= 2; side++) {
								BlockPos q = d.relative(f, k).relative(f.getClockWise(), side);
								out.add(BlockPos.asLong(q.getX(), 0, q.getZ()));
							}
						}
					}
				}
			}
			return out;
		}) : Set.of();
		for (int n = 0; n < colsPerTick && index < cols; n++, index++) {
			SiteWork.Column c = columns.get(index);
			long colKey = BlockPos.asLong(s.heart().getX() + c.dx(), 0, s.heart().getZ() + c.dz());
			if (moat.contains(colKey)) {
				continue; // the moat is nobody's building ground: never levelled, never filled
			}
			if (keepClear.contains(colKey)) {
				continue;
			}
			if (SiteWork.clear(level, base, c, allBuilt, SiteWork.surface(level, s.heart()), loose, bank || !c.pad() && ramps.contains(colKey), roadBlocks)) {
				FILLED.computeIfAbsent(s.id() + "|" + s.job(), k -> new HashSet<>()).add(colKey);
			}
		}
		// phase 2: place the blocks
		int perTick = instant ? list.size() : fast ? Math.max(1, (int) Math.ceil(list.size() / (CREATIVE_TICKS * 0.6)))
				: Math.max(1, (int) Math.ceil(list.size() / (t.seconds * 12.0)));
		List<Long> built = new ArrayList<>(s.built().getOrDefault(s.jobTrack(), List.of()));
		Set<Long> builtSet = new HashSet<>(built);
		BlockPos first = null;
		BlockPos last = null;
		if (index >= cols) {
			for (int n = 0; n < perTick && index < cols + list.size(); n++, index++) {
				Template.Placement p = list.get(index - cols);
				BlockPos pos = s.heart().offset(p.offset());
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
		if (index >= cols + list.size()) {
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
			BANKS.remove(s.id() + "|" + s.job());
			if (done.level(Progression.WALLS) >= 1) {
				// inside the walls there is no water: by a lake or the sea the castle stands on land of its own
				Rotation turn = Rotation.values()[s.rotation()];
				BlockPos ringA = s.heart().offset(Template.rotate(new BlockPos(-63, 0, -56), turn));
				BlockPos ringB = s.heart().offset(Template.rotate(new BlockPos(69, 0, 40), turn));
				SiteWork.reclaim(level, Math.min(ringA.getX(), ringB.getX()), Math.min(ringA.getZ(), ringB.getZ()),
						Math.max(ringA.getX(), ringB.getX()), Math.max(ringA.getZ(), ringB.getZ()), nowBuilt, moatColumns(done), SiteWork.surface(level, s.heart()));
			}
			smoothGround(level, done);
			// made ground whose side was later left standing 3 or more blocks high (a neighbour column cut away after it
			// was filled) gets a masonry face like every other terrace wall
			Set<Long> filled = FILLED.remove(s.id() + "|" + s.job());
			if (filled != null) {
				SiteWork.dressFaces(level, filled, nowBuilt, SiteWork.surface(level, s.heart()));
			}
			SiteWork.decorate(level, s.heart(), columns, nowBuilt);
			{
				Rotation turn = Rotation.values()[s.rotation()];
				BlockPos sa = s.heart().offset(Template.rotate(new BlockPos(-63 - 16, 0, -56 - 16), turn));
				BlockPos sb = s.heart().offset(Template.rotate(new BlockPos(69 + 16, 0, 40 + 16), turn));
				SiteWork.snowCover(level, Math.min(sa.getX(), sb.getX()), Math.min(sa.getZ(), sb.getZ()), Math.max(sa.getX(), sb.getX()),
						Math.max(sa.getZ(), sb.getZ()), nowBuilt);
			}
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
			if (Progression.DOCK.equals(s.jobTrack()) && done.level(Progression.DOCK) == 1) {
				RoadLink.link(server, null, done, "dock", dockPoint(done, 0, 0, -1), "the dock");
			}
			if (!Progression.ROADS.equals(s.jobTrack()) && !Progression.LAMPS.equals(s.jobTrack())) {
				extendInfrastructure(server, done.withLevel(s.jobTrack(), done.level(s.jobTrack())));
			}
			ServerPlayer owner = server.getPlayerList().getPlayer(s.owner());
			if (owner != null) {
				owner.sendSystemMessage(Component.translatable("kingdomquest.build.done", s.name()));
			}
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
		ServerPlayNetworking.send(player, new KingdomInfoPayload(s.heart(), s.name(), s.color(), s.logo(),
				current == null ? "" : current.titleKey(), percent, options, bridge, broken));
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
