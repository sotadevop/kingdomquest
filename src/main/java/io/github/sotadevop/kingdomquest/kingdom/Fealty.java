package io.github.sotadevop.kingdomquest.kingdom;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.sotadevop.kingdomquest.npc.KingdomNpc;
import it.unimi.dsi.fastutil.ints.IntList;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Fealty Ceremony and vassal villages (design 19.5, 19.11). Villages found by the cartographer still simply
 * join the realm's map; this is the manual way to make one a vassal: the ruler rings a vanilla village's bell, names
 * the village in the "Claim Fealty" menu and signs the charter on parchment. Then the villagers gather at the bell,
 * it rings three times, the kingdom banner is raised beside it, the new title is proclaimed and fireworks go up.
 * Afterwards the village has two kingdom guards at its bell, border stones where its streets run out into the land,
 * a road to the kingdom (built by {@link RoadLink}), and the Warchief warns when it is raided (the signal fires light).
 * <p>
 * Kept on the liege settlement's {@code names}: "village|x,z" (the name, as for every known village), "vassal|x,z" =
 * "bx,by,bz|day|villagers|px,py,pz" (bell, day sworn, villagers at the signing, foot of the banner pole or empty),
 * "vassal_blocks|x,z" (blocks the realm put up there; they drop nothing) and "chronicle|<game time>" =
 * "<day>|<kind>|<text>", events for the chronicler (day 1 = the world's first day).
 */
public final class Fealty {
	static final String VASSAL = "vassal|";
	private static final String BLOCKS = "vassal_blocks|";
	static final String CHRONICLE = "chronicle|";
	/** People.ROLE of a vassal village's guards: "vassal_guard|settlement id|x,z". */
	static final String GUARD = "vassal_guard";
	private static final int GUARDS = 2;
	private static final int NO_DROPS = Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS;
	/**
	 * The ceremony, in ticks: the people gather (at least 3 seconds; until four in five stand at the bell, nobody has
	 * come closer for 5 seconds, or 30 seconds are up), then from the first ring: three rings, the banner goes up step by
	 * step, the proclamation, the end.
	 */
	private static final int GATHER_MIN = 60;
	private static final int GATHER_STALL = 100;
	static final int GATHER_MAX = 600;
	private static final int RING = 40;
	static final int RINGS = 3;
	private static final int RAISE = RINGS * RING + 10;
	private static final int RAISE_STEP = 14;
	private static final int POLE = 3;
	private static final int PROCLAIM = RAISE + (POLE + 1) * RAISE_STEP + 6;
	static final int END = PROCLAIM + 90;

	/** A running ceremony (only while the server runs; the oath itself is saved at once). */
	static final class Ceremony {
		final UUID owner;
		final UUID liege;
		final String key;
		final String name;
		final BlockPos bell;
		final BlockPos pole;
		final BlockPos road;
		final List<BlockPos> ends;
		final List<UUID> villagers = new ArrayList<>();
		final List<BlockPos> spots = new ArrayList<>();
		/** Villagers within 8 blocks of the bell at each ring. */
		final int[] gathered = new int[RINGS];
		int t;
		/** Tick of the first ring, -1 while the people still gather. */
		int ringAt = -1;
		/** Closest the people have come so far (sum of their distances to the bell) and when. */
		double closest = Double.MAX_VALUE;
		int closestAt;
		int rung;
		int raised;

		Ceremony(UUID owner, UUID liege, String key, String name, BlockPos bell, BlockPos pole, BlockPos road, List<BlockPos> ends) {
			this.owner = owner;
			this.liege = liege;
			this.key = key;
			this.name = name;
			this.bell = bell;
			this.pole = pole;
			this.road = road;
			this.ends = ends;
		}
	}

	static final Map<Long, Ceremony> CEREMONIES = new HashMap<>();
	/** Vassal villages ("x,z") under a vanilla raid right now, with the raid. */
	static final Map<String, Raid> RAIDED = new HashMap<>();

	/** A sworn village as stored on its liege. */
	record Vassal(Settlement liege, String key, BlockPos bell, int day, int villagers, BlockPos pole) {
		String name() {
			return this.liege.names().getOrDefault("village|" + this.key, "the village");
		}

		String tag() {
			return GUARD + "|" + this.liege.id() + "|" + this.key;
		}
	}

	private Fealty() {
	}

	static void register() {
		PayloadTypeRegistry.clientboundPlay().register(FealtyPayload.TYPE, FealtyPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(FealtyPayload.TYPE, FealtyPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(FealtyPayload.TYPE, (payload, context) -> signed(context.player(), payload));
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> player instanceof ServerPlayer sp && hand == InteractionHand.MAIN_HAND
				&& level.dimension() == Level.OVERWORLD ? useBell(sp, hit.getBlockPos()) : InteractionResult.PASS);
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
				!(level instanceof ServerLevel sl) || player.isCreative() || sl.dimension() != Level.OVERWORLD || !breakRealmBlock(sl, pos));
		ServerTickEvents.END_SERVER_TICK.register(Fealty::tick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(srv -> {
			CEREMONIES.clear();
			RAIDED.clear();
		});
	}

	private static void tick(MinecraftServer server) {
		ceremonies(server);
		int n = server.getTickCount();
		if (n % 100 == 50) {
			raids(server);
		}
		if (n % 1200 == 600) {
			upkeep(server);
		}
	}

	// ---- data ----

	static int day(ServerLevel level) {
		return (int) (level.getGameTime() / 24000L) + 1;
	}

	private static int[] xz(String key) {
		String[] p = key.split(",");
		return new int[] {Integer.parseInt(p[0]), Integer.parseInt(p[1])};
	}

	private static String pos(BlockPos p) {
		return p.getX() + "," + p.getY() + "," + p.getZ();
	}

	private static BlockPos pos(String s) {
		if (s.isEmpty()) {
			return null;
		}
		String[] p = s.split(",");
		return new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
	}

	/** Every vassal village of the world. */
	static List<Vassal> vassals(MinecraftServer server) {
		List<Vassal> out = new ArrayList<>();
		for (Settlement s : Settlements.all(server)) {
			for (Map.Entry<String, String> e : s.names().entrySet()) {
				if (e.getKey().startsWith(VASSAL)) {
					String[] p = e.getValue().split("\\|", -1);
					out.add(new Vassal(s, e.getKey().substring(VASSAL.length()), pos(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]),
							p.length > 3 ? pos(p[3]) : null));
				}
			}
		}
		return out;
	}

	/** Villagers recorded at the signing, from a stored "vassal|" value. */
	static int villagers(String value) {
		String[] p = value.split("\\|", -1);
		return p.length > 2 ? Integer.parseInt(p[2]) : 0;
	}

	/** Day sworn, from a stored "vassal|" value. */
	static int daySworn(String value) {
		String[] p = value.split("\\|", -1);
		return p.length > 1 ? Integer.parseInt(p[1]) : 0;
	}

	private static Settlement byId(MinecraftServer server, UUID id) {
		for (Settlement s : Settlements.all(server)) {
			if (s.id().equals(id)) {
				return s;
			}
		}
		return null;
	}

	/** Records an event for the chronicler (design 19.11): "chronicle|<game time>" = "<day>|<kind>|<text>". */
	static Settlement chronicle(ServerLevel level, Settlement s, String kind, String text) {
		long t = level.getGameTime();
		while (s.names().containsKey(CHRONICLE + t)) {
			t++;
		}
		return s.withName(CHRONICLE + t, day(level) + "|" + kind + "|" + text);
	}

	/** The village (its structure start) a block belongs to, or null. */
	static StructureStart villageAt(ServerLevel level, BlockPos pos) {
		StructureStart s = level.structureManager().getStructureWithPieceAt(pos, StructureTags.VILLAGE);
		return s != null && s.isValid() ? s : null;
	}

	/**
	 * The village's key: the one the cartographer already gave it ("village|x,z" within 96 blocks of its start, see
	 * {@link Realm}), else its start chunk's corner, which is what he would find.
	 */
	private static String keyOf(MinecraftServer server, UUID owner, StructureStart village) {
		int x = village.getChunkPos().getMinBlockX();
		int z = village.getChunkPos().getMinBlockZ();
		for (Settlement o : Settlements.all(server)) {
			if (!o.owner().equals(owner)) {
				continue;
			}
			for (String k : o.names().keySet()) {
				if (k.startsWith("village|")) {
					int[] p = xz(k.substring(8));
					if (Math.abs(p[0] - x) < 96 && Math.abs(p[1] - z) < 96) {
						return k.substring(8);
					}
				}
			}
		}
		return x + "," + z;
	}

	/** The vassal whose bell stands in this village, or null. */
	private static Vassal vassalOf(MinecraftServer server, StructureStart village) {
		for (Vassal v : vassals(server)) {
			if (village.getBoundingBox().isInside(v.bell())) {
				return v;
			}
		}
		return null;
	}

	static List<Villager> villagers(ServerLevel level, StructureStart village) {
		BoundingBox b = village.getBoundingBox();
		AABB box = new AABB(b.minX(), b.minY(), b.minZ(), b.maxX() + 1, b.maxY() + 1, b.maxZ() + 1).inflate(8, 16, 8);
		return level.getEntitiesOfClass(Villager.class, box, Entity::isAlive);
	}

	// ---- names in the region's style ----

	private static final Map<String, String[][]> NAMES = Map.of(
			Style.TEMPERATE, new String[][] {{"Linden", "Eichen", "Birken", "Rosen", "Mühl", "Wald", "Kirch", "Hohen", "Alten", "Stein", "Buchen", "Tannen",
					"Wiesen", "Erlen", "Hasel"}, {"dorf", "bach", "feld", "hausen", "heim", "au", "tal", "brunn", "furt", "ingen", "rode", "hofen"}},
			Style.FROZEN, new String[][] {{"Ask", "Bjørn", "Frost", "Hvit", "Is", "Ulf", "Rav", "Sne", "Grå", "Tor", "Fjell", "Varg", "Ørn"},
					{"vik", "heim", "by", "stad", "fjord", "dal", "nes", "holm", "vang", "berg"}},
			Style.HIGHLAND, new String[][] {{"Glen", "Kil", "Ach", "Dun", "Inver", "Strath", "Bal", "Aber", "Kin", "Tor", "Craig"},
					{"more", "lochy", "carron", "ross", "dee", "brae", "fern", "garry", "nevis", "tay", "aird", "lin"}},
			Style.ARID, new String[][] {{"Dar ", "Qasr ", "Bab ", "Ain ", "Wadi ", "Ksar ", "Sidi "},
					{"Zahra", "Nur", "Rimal", "Tamar", "Safra", "Hamra", "Kabir", "Salam", "Yasmin", "Amal", "Badr"}},
			Style.WILD, new String[][] {{"Ban ", "Kampung ", "Muang ", "Phum ", "Desa "},
					{"Nong", "Sungai", "Mai", "Klong", "Batu", "Tani", "Hutan", "Chai", "Pa Sak", "Bukit", "Rimba"}});

	/** A village name in the style of its region, the same for the same place, not one the realm already uses. */
	static String suggestName(String style, int x, int z, Set<String> taken) {
		String[][] parts = NAMES.getOrDefault(style, NAMES.get(Style.TEMPERATE));
		int seed = x * 73856093 ^ z * 19349663;
		for (int i = 0; i < 40; i++) {
			int h = Math.floorMod(seed * 31 + i * 7919, 1 << 20);
			String name = parts[0][h % parts[0].length] + parts[1][h / parts[0].length % parts[1].length];
			if (!taken.contains(name)) {
				return name;
			}
		}
		return parts[0][0] + parts[1][0];
	}

	private static Set<String> takenNames(MinecraftServer server, UUID owner) {
		Set<String> taken = new HashSet<>();
		for (Settlement s : Settlements.all(server)) {
			if (s.owner().equals(owner)) {
				taken.add(s.name());
				s.names().forEach((k, v) -> {
					if (k.startsWith("village|")) {
						taken.add(v);
					}
				});
			}
		}
		return taken;
	}

	// ---- the bell and the menu ----

	/** A ruler rings the bell of a village that is not sworn yet: the "Claim Fealty" menu opens. */
	private static InteractionResult useBell(ServerPlayer player, BlockPos pos) {
		MinecraftServer server = player.level().getServer();
		ServerLevel level = server.overworld();
		if (!(level.getBlockState(pos).getBlock() instanceof BellBlock)) {
			return InteractionResult.PASS;
		}
		Settlement liege = Settlements.nearestOwned(player);
		StructureStart village = liege == null ? null : villageAt(level, pos);
		if (village == null) {
			return InteractionResult.PASS;
		}
		Vassal sworn = vassalOf(server, village);
		if (sworn != null) {
			player.sendOverlayMessage(Component.translatable(sworn.liege().owner().equals(player.getUUID()) ? "kingdomquest.fealty.yours"
					: "kingdomquest.fealty.other", sworn.name(), sworn.liege().name()));
			return InteractionResult.PASS; // the bell rings as usual
		}
		if (CEREMONIES.containsKey(pos.asLong())) {
			return InteractionResult.SUCCESS;
		}
		ServerPlayNetworking.send(player, new FealtyPayload(pos, info(level, player, liege, village).toString()));
		ring(level, pos); // rung by us: vanilla's ring would send the villagers into hiding
		return InteractionResult.SUCCESS;
	}

	/** What the "Claim Fealty" menu shows. */
	static JsonObject info(ServerLevel level, ServerPlayer player, Settlement liege, StructureStart village) {
		MinecraftServer server = level.getServer();
		String key = keyOf(server, liege.owner(), village);
		String known = null;
		for (Settlement o : Settlements.all(server)) {
			if (o.owner().equals(liege.owner())) {
				known = o.names().getOrDefault("village|" + key, known);
			}
		}
		int[] p = xz(key);
		String style = Style.forBiome(level.getBiome(new BlockPos(p[0], 64, p[1])).getRegisteredName());
		JsonObject j = new JsonObject();
		// the cartographer's "Village 3" is only a placeholder: a name the lord gave it is kept
		j.addProperty("name", known != null && !known.matches("Village \\d+") ? known : suggestName(style, p[0], p[1], takenNames(server, liege.owner())));
		j.addProperty("villagers", villagers(level, village).size());
		j.addProperty("kingdom", liege.name());
		j.addProperty("empire", player == null ? liege.name() : Realm.empire(server, player));
		j.addProperty("ruler", player == null ? "the ruler" : player.getName().getString());
		j.addProperty("color", liege.color());
		j.addProperty("logo", liege.logo().toString());
		j.addProperty("day", day(level));
		j.addProperty("style", style);
		return j;
	}

	/** The charter came back signed and sealed: the oath is sworn and the ceremony begins. */
	private static void signed(ServerPlayer player, FealtyPayload p) {
		MinecraftServer server = player.level().getServer();
		ServerLevel level = server.overworld();
		BlockPos bell = p.bell();
		Settlement liege = Settlements.nearestOwned(player);
		if (liege == null || player.level().dimension() != Level.OVERWORLD || player.distanceToSqr(Vec3.atCenterOf(bell)) > 32 * 32
				|| !(level.getBlockState(bell).getBlock() instanceof BellBlock) || CEREMONIES.containsKey(bell.asLong())) {
			return;
		}
		StructureStart village = villageAt(level, bell);
		if (village == null || vassalOf(server, village) != null) {
			return;
		}
		if (villagers(level, village).isEmpty()) {
			player.sendOverlayMessage(Component.translatable("kingdomquest.fealty.nobody"));
			return;
		}
		String name;
		try {
			name = JsonParser.parseString(p.json()).getAsJsonObject().get("name").getAsString().strip();
		} catch (RuntimeException e) {
			name = "";
		}
		if (name.length() > 32) {
			name = name.substring(0, 32);
		}
		if (name.isEmpty()) {
			name = info(level, player, liege, village).get("name").getAsString();
		}
		begin(server, liege, player, village, bell, name);
	}

	/** Swears the village in (saved at once) and starts the ceremony. Also used by the field test (no player). */
	static Ceremony begin(MinecraftServer server, Settlement liege, ServerPlayer player, StructureStart village, BlockPos bell, String name) {
		ServerLevel level = server.overworld();
		String key = keyOf(server, liege.owner(), village);
		List<Villager> people = villagers(level, village);
		BlockPos pole = poleSpot(level, bell);
		List<BlockPos> ends = streetEnds(level, village.getBoundingBox(), bell);
		BlockPos road = roadEnd(level, village.getBoundingBox(), bell, ends, RoadLink.gateExit(liege));
		Settlement s = liege.withName("village|" + key, name)
				.withName(VASSAL + key, pos(bell) + "|" + day(level) + "|" + people.size() + "|" + (pole == null ? "" : pos(pole)));
		s = chronicle(level, s, "fealty", "The village of " + name + " swore fealty to " + liege.name() + ".");
		Settlements.putState(server, s);
		Ceremony c = new Ceremony(liege.owner(), liege.id(), key, name, bell, pole, road, ends);
		people.forEach(v -> c.villagers.add(v.getUUID()));
		CEREMONIES.put(bell.asLong(), c);
		// the kingdom's guards march in from the road
		double dx = road.getX() - bell.getX();
		double dz = road.getZ() - bell.getZ();
		double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
		int fx = bell.getX() + (int) Math.round(dx / len * Math.min(18, len));
		int fz = bell.getZ() + (int) Math.round(dz / len * Math.min(18, len));
		BlockPos from = new BlockPos(fx, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, fx, fz), fz);
		for (BlockPos post : posts(level, bell, pole)) {
			spawnGuard(level, s, key, from, post);
		}
		herald(level, bell, "Hear ye! The people of " + name + " gather at the bell to swear fealty to " + liege.name() + ".");
		return c;
	}

	// ---- the ceremony ----

	/** Every tick: the running ceremonies move on. */
	static void ceremonies(MinecraftServer server) {
		for (Ceremony c : new ArrayList<>(CEREMONIES.values())) {
			step(server, c);
		}
	}

	private static void step(MinecraftServer server, Ceremony c) {
		ServerLevel level = server.overworld();
		if (!level.isLoaded(c.bell)) {
			finish(server, c); // everyone left: the village is sworn all the same
			return;
		}
		c.t++;
		if (c.t == 1) {
			int n = Math.max(1, c.villagers.size());
			for (int i = 0; i < c.villagers.size(); i++) {
				double a = i * Math.PI * 2 / n;
				double r = 3.5 + (n > 8 ? i % 2 * 1.5 : 0);
				BlockPos want = c.bell.offset((int) Math.round(Math.cos(a) * r), 0, (int) Math.round(Math.sin(a) * r));
				BlockPos spot = standNear(level, want, c.bell.getY(), c.spots, c.pole);
				c.spots.add(spot != null ? spot : new BlockPos(want.getX(), SiteWork.groundTop(level, want.getX(), want.getZ(), c.bell.getY()) + 1, want.getZ()));
			}
		}
		gather(level, c);
		if (c.ringAt < 0) {
			if (c.t % 20 == 0) {
				double sum = 0;
				for (UUID id : c.villagers) {
					if (level.getEntity(id) instanceof Villager v && v.isAlive()) {
						sum += Math.sqrt(v.distanceToSqr(Vec3.atCenterOf(c.bell)));
					}
				}
				if (sum < c.closest - 1) {
					c.closest = sum;
					c.closestAt = c.t;
				}
			}
			boolean ready = near(level, c, 8) * 5 >= c.villagers.size() * 4 || c.t - c.closestAt > GATHER_STALL;
			if (c.t >= GATHER_MIN && ready || c.t >= GATHER_MAX) {
				c.ringAt = c.t;
			}
			return;
		}
		int t = c.t - c.ringAt;
		if (c.rung < RINGS && t >= c.rung * RING) {
			c.gathered[c.rung] = near(level, c, 8);
			ring(level, c.bell);
			c.rung++;
		}
		if (c.pole != null && c.raised <= POLE && t >= RAISE + c.raised * RAISE_STEP) {
			raise(level, c, c.raised++);
		}
		if (t == PROCLAIM) {
			proclaim(server, level, c);
		}
		if (t >= END) {
			finish(server, c);
		}
	}

	/** The villagers walk to their places around the bell and look at it (not to bed, not into hiding). */
	private static void gather(ServerLevel level, Ceremony c) {
		for (int i = 0; i < c.villagers.size() && i < c.spots.size(); i++) {
			if (!(level.getEntity(c.villagers.get(i)) instanceof Villager v) || !v.isAlive()) {
				continue;
			}
			if (v.isSleeping()) {
				v.stopSleeping();
			}
			Brain<Villager> brain = v.getBrain();
			brain.eraseMemory(MemoryModuleType.HEARD_BELL_TIME);
			brain.eraseMemory(MemoryModuleType.HIDING_PLACE);
			BlockPos spot = c.spots.get(i);
			if (v.distanceToSqr(Vec3.atBottomCenterOf(spot)) > 1.5 * 1.5) {
				brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(spot, 0.75f, 0));
			} else {
				brain.eraseMemory(MemoryModuleType.WALK_TARGET);
				v.getNavigation().stop();
			}
			brain.setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(c.bell));
		}
	}

	private static int near(ServerLevel level, Ceremony c, double r) {
		int n = 0;
		for (UUID id : c.villagers) {
			if (level.getEntity(id) instanceof Villager v && v.isAlive() && v.distanceToSqr(Vec3.atCenterOf(c.bell)) < r * r) {
				n++;
			}
		}
		return n;
	}

	/** One ring: the sound and the swing, without vanilla's alarm that sends villagers home to hide. */
	private static void ring(ServerLevel level, BlockPos bell) {
		BlockState st = level.getBlockState(bell);
		Direction facing = st.hasProperty(BellBlock.FACING) ? st.getValue(BellBlock.FACING) : Direction.NORTH;
		level.playSound(null, bell, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 2f, 1f);
		level.getServer().getPlayerList().broadcast(null, bell.getX(), bell.getY(), bell.getZ(), 96, level.dimension(),
				new ClientboundBlockEventPacket(bell, st.getBlock(), 1, facing.get3DDataValue()));
	}

	/** The banner goes up the pole: step 0 sets the stone foot with the banner on it, each later step adds a length of pole. */
	private static void raise(ServerLevel level, Ceremony c, int step) {
		Settlement liege = byId(level.getServer(), c.liege);
		if (liege == null) {
			return;
		}
		String style = Style.forBiome(level.getBiome(c.bell).getRegisteredName());
		if (step == 0) {
			level.setBlock(c.pole, stone(style)[0].defaultBlockState(), NO_DROPS);
		} else {
			level.setBlock(c.pole.above(step), pole(style).defaultBlockState(), NO_DROPS);
		}
		level.setBlock(c.pole.above(step + 1), banner(liege.color(), c.pole, c.bell), Block.UPDATE_ALL);
		level.playSound(null, c.pole.above(step + 1), SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 1f, 0.8f + step * 0.1f);
		if (step == POLE) {
			List<BlockPos> blocks = new ArrayList<>();
			for (int i = 0; i <= POLE + 1; i++) {
				blocks.add(c.pole.above(i));
			}
			addBlocks(level.getServer(), c.liege, c.key, blocks);
		}
	}

	/** A standing banner in the kingdom color facing the bell. */
	private static BlockState banner(int color, BlockPos at, BlockPos bell) {
		Block b = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(Settlements.dye(color) + "_banner"));
		double yaw = Math.toDegrees(Math.atan2(-(bell.getX() - at.getX()), bell.getZ() - at.getZ()));
		return b.defaultBlockState().setValue(BannerBlock.ROTATION, Math.floorMod((int) Math.round(yaw / 22.5), 16));
	}

	/** The title is proclaimed: on screen for everyone near, the Herald's words, fireworks in the kingdom color. */
	private static void proclaim(MinecraftServer server, ServerLevel level, Ceremony c) {
		Settlement liege = byId(server, c.liege);
		String kingdom = liege == null ? "the realm" : liege.name();
		Chronicle.vassal(server, liege, c.name);
		for (ServerPlayer p : level.players()) {
			if (p.distanceToSqr(Vec3.atCenterOf(c.bell)) < 160 * 160) {
				p.connection.send(new ClientboundSetTitlesAnimationPacket(15, 80, 25));
				p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(c.name).withStyle(net.minecraft.ChatFormatting.GOLD)));
				p.connection.send(new ClientboundSetSubtitleTextPacket(Component.translatable("kingdomquest.fealty.subtitle", kingdom)));
			}
		}
		herald(level, c.bell, "From this day on " + c.name + " is a vassal of " + kingdom + ", under the protection of its ruler. Long live "
				+ kingdom + "!");
		level.playSound(null, c.bell, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.5f, 1f);
		int color = DyeColor.byId(liege == null ? 0 : liege.color()).getFireworkColor();
		BlockPos top = c.pole != null ? c.pole.above(POLE + 2) : c.bell.above(3);
		for (int i = 0; i < 3; i++) {
			ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
			rocket.set(DataComponents.FIREWORKS, new Fireworks(1 + i % 2, List.of(new FireworkExplosion(i == 1 ? FireworkExplosion.Shape.STAR
					: FireworkExplosion.Shape.LARGE_BALL, IntList.of(color), IntList.of(0xF0C070), true, i != 1))));
			level.addFreshEntity(new net.minecraft.world.entity.projectile.FireworkRocketEntity(level, top.getX() + 0.5 + (i - 1) * 2.5, top.getY(),
					top.getZ() + 0.5, rocket));
		}
		for (UUID id : c.villagers) {
			if (level.getEntity(id) instanceof Villager v && v.isAlive()) {
				level.sendParticles(ParticleTypes.HAPPY_VILLAGER, v.getX(), v.getY() + 1.8, v.getZ(), 6, 0.3, 0.3, 0.3, 0);
			}
		}
	}

	/** The ceremony is over: the people go back to their day, border stones are set and the road is begun. */
	private static void finish(MinecraftServer server, Ceremony c) {
		CEREMONIES.remove(c.bell.asLong());
		ServerLevel level = server.overworld();
		Settlement liege = byId(server, c.liege);
		if (liege == null) {
			return;
		}
		if (level.isLoaded(c.bell)) {
			for (UUID id : c.villagers) {
				if (level.getEntity(id) instanceof Villager v) {
					v.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
					v.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
				}
			}
			while (c.pole != null && c.raised <= POLE) {
				raise(level, c, c.raised++);
			}
			String style = Style.forBiome(level.getBiome(c.bell).getRegisteredName());
			List<BlockPos> stones = new ArrayList<>();
			List<BlockPos> ends = new ArrayList<>(c.ends);
			if (!ends.contains(c.road)) {
				ends.add(c.road);
			}
			for (BlockPos end : ends) {
				stones.addAll(borderStone(level, end, c.bell, style, liege.color()));
			}
			addBlocks(server, c.liege, c.key, stones);
			liege = byId(server, c.liege);
		}
		ServerPlayer player = server.getPlayerList().getPlayer(c.owner);
		String key = "v:" + c.key;
		RoadLink.link(server, player, liege, key, c.road, c.name);
		roadPlanned(server, liege, key, RoadLink.gateExit(liege), c.road);
	}

	/**
	 * Hook for the waystations (design 19.6, wave 2): a road from a kingdom to one of its vassal villages was planned,
	 * from {@code start} (just outside the gate) to {@code end} (where it meets the village's streets).
	 */
	static void roadPlanned(MinecraftServer server, Settlement from, String key, BlockPos start, BlockPos end) {
		Roadside.planned(server, from, key, start, end); // the station itself is placed when the road is laid
	}

	private static void herald(ServerLevel level, BlockPos at, String line) {
		for (ServerPlayer p : level.players()) {
			if (p.distanceToSqr(Vec3.atCenterOf(at)) < 160 * 160) {
				People.say(p, "Herald", line);
			}
		}
	}

	// ---- where things go ----

	/** A free spot for the banner pole 2 to 6 blocks from the bell, on the same ground (off the streets if possible). */
	static BlockPos poleSpot(ServerLevel level, BlockPos bell) {
		BlockPos onPath = null;
		for (int r = 2; r <= 6; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					int x = bell.getX() + dx;
					int z = bell.getZ() + dz;
					int top = SiteWork.groundTop(level, x, z, bell.getY());
					BlockPos ground = new BlockPos(x, top, z);
					BlockState g = level.getBlockState(ground);
					if (top < bell.getY() - 4 || top > bell.getY() || !g.getFluidState().isEmpty() || g.getCollisionShape(level, ground).isEmpty()
							|| !free(level, ground.above(), POLE + 3)) {
						continue;
					}
					if (!g.is(Blocks.DIRT_PATH)) {
						return ground.above();
					}
					if (onPath == null) {
						onPath = ground.above();
					}
				}
			}
		}
		return onPath;
	}

	/** {@code height} blocks of air (or grass and flowers) from {@code pos} up. */
	private static boolean free(ServerLevel level, BlockPos pos, int height) {
		for (int h = 0; h < height; h++) {
			BlockState st = level.getBlockState(pos.above(h));
			if (!(st.isAir() || st.canBeReplaced() && st.getFluidState().isEmpty())) {
				return false;
			}
		}
		return true;
	}

	/** The guards' posts: either side of the banner pole (or the bell). */
	static List<BlockPos> posts(ServerLevel level, BlockPos bell, BlockPos pole) {
		BlockPos c = pole != null ? pole : bell;
		Direction d = horizontal(c.getX() - bell.getX(), c.getZ() - bell.getZ());
		List<BlockPos> out = new ArrayList<>();
		for (int sgn : new int[] {1, -1}) {
			BlockPos at = standNear(level, c.relative(d.getClockWise(), 2 * sgn), bell.getY(), out, pole);
			if (at != null) {
				out.add(at);
			}
		}
		return out;
	}

	private static Direction horizontal(double dx, double dz) {
		if (dx == 0 && dz == 0) {
			return Direction.SOUTH;
		}
		return Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
	}

	/** The nearest spot within 3 to stand on: free feet and head over something solid. */
	private static BlockPos standNear(ServerLevel level, BlockPos want, int y, List<BlockPos> taken, BlockPos pole) {
		for (int r = 0; r <= 3; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					int x = want.getX() + dx;
					int z = want.getZ() + dz;
					BlockPos feet = new BlockPos(x, SiteWork.groundTop(level, x, z, y) + 1, z);
					if (pole != null && feet.getX() == pole.getX() && feet.getZ() == pole.getZ() || taken.contains(feet)) {
						continue;
					}
					if (level.getBlockState(feet).getCollisionShape(level, feet).isEmpty() && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
							&& !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty() && level.getBlockState(feet).getFluidState().isEmpty()) {
						return feet;
					}
				}
			}
		}
		return null;
	}

	/**
	 * Where the village's streets run out into the land: in each eighth around the bell, the street (dirt path) cell
	 * farthest from it, if that is at least 16 blocks out.
	 */
	static List<BlockPos> streetEnds(ServerLevel level, BoundingBox box, BlockPos bell) {
		BlockPos[] best = new BlockPos[8];
		double[] far = new double[8];
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int x = box.minX() - 4; x <= box.maxX() + 4; x++) {
			for (int z = box.minZ() - 4; z <= box.maxZ() + 4; z++) {
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
				if (!level.getBlockState(p.set(x, y, z)).is(Blocks.DIRT_PATH)) {
					continue;
				}
				double dx = x - bell.getX();
				double dz = z - bell.getZ();
				double d = Math.sqrt(dx * dx + dz * dz);
				int sector = Math.floorMod((int) Math.round(Math.atan2(dz, dx) / (Math.PI / 4)), 8);
				if (d > far[sector]) {
					far[sector] = d;
					best[sector] = p.immutable();
				}
			}
		}
		List<BlockPos> out = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			BlockPos e = best[i];
			if (e != null && far[i] >= 16 && out.stream().noneMatch(o -> o.distSqr(e) < 12 * 12)) { // one street, two eighths: one stone
				out.add(e);
			}
		}
		return out;
	}

	/** Where the road from the kingdom meets the village: the street end nearest to the gate, else the village's edge. */
	static BlockPos roadEnd(ServerLevel level, BoundingBox box, BlockPos bell, List<BlockPos> ends, BlockPos gate) {
		BlockPos best = null;
		for (BlockPos e : ends) {
			if (best == null || e.distSqr(gate) < best.distSqr(gate)) {
				best = e;
			}
		}
		if (best != null) {
			return best;
		}
		int x = Math.clamp(gate.getX(), box.minX() - 3, box.maxX() + 3);
		int z = Math.clamp(gate.getZ(), box.minZ() - 3, box.maxZ() + 3);
		return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
	}

	// ---- what the realm builds there ----

	/** Border stone materials per style: foot, carved stone, cap. None of them is taken for land or old road work. */
	private static Block[] stone(String style) {
		return switch (style) {
			case Style.FROZEN -> new Block[] {Blocks.POLISHED_DEEPSLATE, Blocks.CHISELED_DEEPSLATE, Blocks.POLISHED_DEEPSLATE_SLAB}; // a runestone
			case Style.HIGHLAND -> new Block[] {Blocks.POLISHED_ANDESITE, Blocks.CHISELED_STONE_BRICKS, Blocks.MOSSY_STONE_BRICK_SLAB};
			case Style.ARID -> new Block[] {Blocks.CUT_SANDSTONE, Blocks.CHISELED_SANDSTONE, Blocks.SMOOTH_SANDSTONE_SLAB};
			case Style.WILD -> new Block[] {Blocks.SMOOTH_RED_SANDSTONE, Blocks.CHISELED_RED_SANDSTONE, Blocks.SMOOTH_RED_SANDSTONE_SLAB};
			default -> new Block[] {Blocks.POLISHED_ANDESITE, Blocks.CHISELED_STONE_BRICKS, Blocks.POLISHED_ANDESITE_SLAB};
		};
	}

	private static Block pole(String style) {
		return switch (style) {
			case Style.FROZEN, Style.HIGHLAND -> Blocks.SPRUCE_FENCE;
			case Style.ARID -> Blocks.ACACIA_FENCE;
			case Style.WILD -> Blocks.BAMBOO_FENCE;
			default -> Blocks.DARK_OAK_FENCE;
		};
	}

	/**
	 * A border stone beside a street where it leaves the village: a carved stone on a foot with a cap, the kingdom's
	 * banner on its outer face. Stands three blocks to the side of the street, a little outside its end.
	 */
	static List<BlockPos> borderStone(ServerLevel level, BlockPos end, BlockPos bell, String style, int color) {
		Direction out = horizontal(end.getX() - bell.getX(), end.getZ() - bell.getZ());
		Direction side = out.getClockWise();
		for (int[] o : new int[][] {{1, 3}, {1, -3}, {1, 4}, {1, -4}, {2, 3}, {2, -3}, {0, 3}, {0, -3}}) {
			BlockPos col = end.relative(out, o[0]).relative(side, o[1]);
			int top = SiteWork.groundTop(level, col.getX(), col.getZ(), end.getY());
			BlockPos ground = new BlockPos(col.getX(), top, col.getZ());
			BlockState g = level.getBlockState(ground);
			BlockPos face = ground.above(2).relative(out);
			if (Math.abs(top - end.getY()) > 3 || g.is(Blocks.DIRT_PATH) || !SiteWork.isGround(g) // natural ground, not a roof or a street
					|| !free(level, ground.above(), 3) || !free(level, face, 1)) {
				continue;
			}
			Block[] m = stone(style);
			level.setBlock(ground.above(), m[0].defaultBlockState(), NO_DROPS);
			level.setBlock(ground.above(2), m[1].defaultBlockState(), NO_DROPS);
			level.setBlock(ground.above(3), m[2].defaultBlockState(), NO_DROPS);
			Block b = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(Settlements.dye(color) + "_wall_banner"));
			level.setBlock(face, b.defaultBlockState().setValue(WallBannerBlock.FACING, out), NO_DROPS);
			return List.of(ground.above(), ground.above(2), ground.above(3), face);
		}
		return List.of();
	}

	private static void addBlocks(MinecraftServer server, UUID liegeId, String key, List<BlockPos> blocks) {
		Settlement s = byId(server, liegeId);
		if (s == null || blocks.isEmpty()) {
			return;
		}
		StringBuilder b = new StringBuilder(s.names().getOrDefault(BLOCKS + key, ""));
		for (BlockPos p : blocks) {
			b.append(b.isEmpty() ? "" : ";").append(p.asLong());
		}
		Settlements.putState(server, s.withName(BLOCKS + key, b.toString()));
	}

	/** Blocks the realm put up at its vassal villages: list per "x,z" key. */
	static List<Long> blocks(Settlement s, String key) {
		List<Long> out = new ArrayList<>();
		for (String p : s.names().getOrDefault(BLOCKS + key, "").split(";")) {
			if (!p.isEmpty()) {
				out.add(Long.parseLong(p));
			}
		}
		return out;
	}

	/** A block the realm put up at a vassal village is broken: it goes without dropping anything (like built blocks). */
	private static boolean breakRealmBlock(ServerLevel level, BlockPos pos) {
		for (Vassal v : vassals(level.getServer())) {
			if (v.bell().distSqr(pos) > 160 * 160) {
				continue;
			}
			List<Long> list = blocks(v.liege(), v.key());
			if (list.remove(pos.asLong())) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), NO_DROPS);
				StringBuilder b = new StringBuilder();
				list.forEach(l -> b.append(b.isEmpty() ? "" : ";").append(l));
				Settlements.putState(level.getServer(), v.liege().withName(BLOCKS + v.key(), b.toString()));
				return true;
			}
		}
		return false;
	}

	// ---- the vassal villages over time ----

	/**
	 * Every minute, at each loaded vassal village: two guards at the bell (fallen ones are replaced, gear follows the
	 * liege's barracks), and the banner on its pole in the kingdom's current color.
	 */
	static void upkeep(MinecraftServer server) {
		ServerLevel level = server.overworld();
		for (Vassal v : vassals(server)) {
			if (!level.isLoaded(v.bell()) || CEREMONIES.containsKey(v.bell().asLong())) {
				continue;
			}
			int lvl = Math.max(1, v.liege().level(Progression.BARRACKS));
			List<KingdomNpc> guards = level.getEntitiesOfClass(KingdomNpc.class, new AABB(v.bell()).inflate(96, 48, 96),
					g -> g.isAlive() && v.tag().equals(People.role(g)));
			Set<Long> manned = new HashSet<>();
			for (KingdomNpc g : guards) {
				if (manned.size() >= GUARDS || !manned.add(g.post())) {
					g.discard();
					continue;
				}
				if (g.guardArmor() != Math.max(1, Math.min(3, lvl)) || io.github.sotadevop.kingdomquest.npc.Folk.roleOf(
						g.getCustomName() == null ? "" : g.getCustomName().getString()) == null) {
					g.dressGuard(v.liege().style(), lvl, false, v.liege().color(), "Village Guard");
				}
				g.setTier(lvl);
			}
			for (BlockPos post : posts(level, v.bell(), v.pole())) {
				if (manned.size() < GUARDS && manned.add(post.asLong())) {
					spawnGuard(level, v.liege(), v.key(), post, post);
				}
			}
			BlockPos pole = v.pole();
			if (pole != null && level.isLoaded(pole) && level.getBlockState(pole.above(POLE)).getBlock() == pole(Style.forBiome(level.getBiome(v.bell())
					.getRegisteredName()))) {
				BlockState want = banner(v.liege().color(), pole, v.bell());
				BlockState now = level.getBlockState(pole.above(POLE + 1));
				if (now.getBlock() != want.getBlock() && (now.isAir() || now.getBlock() instanceof BannerBlock)) {
					level.setBlock(pole.above(POLE + 1), want, Block.UPDATE_ALL);
				}
			}
		}
	}

	private static void spawnGuard(ServerLevel level, Settlement liege, String key, BlockPos at, BlockPos post) {
		KingdomNpc g = KingdomNpc.TYPE.create(level, EntitySpawnReason.EVENT);
		if (g == null) {
			return;
		}
		int lvl = Math.max(1, liege.level(Progression.BARRACKS));
		g.setPost(post);
		g.guardGear(lvl, false, liege.color(), g.getUUID().hashCode());
		g.setup("guard", KingdomNpc.GUARD_SKIN, liege.owner());
		g.dressGuard(liege.style(), lvl, false, liege.color(), "Village Guard");
		g.setTier(lvl);
		g.setHome(post);
		g.setAttached(People.ROLE, GUARD + "|" + liege.id() + "|" + key);
		g.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.getRandom().nextFloat() * 360, 0);
		level.addFreshEntity(g);
		level.sendParticles(ParticleTypes.POOF, g.getX(), g.getY() + 1, g.getZ(), 10, 0.4, 0.6, 0.4, 0.02);
	}

	/**
	 * Every 5 seconds: a vanilla raid on a vassal village brings the Warchief's warning and lights the realm's signal
	 * fires; its end brings his report and puts them out.
	 */
	private static void raids(MinecraftServer server) {
		ServerLevel level = server.overworld();
		Set<String> seen = new HashSet<>();
		for (Vassal v : vassals(server)) {
			seen.add(v.key());
			Raid known = RAIDED.get(v.key());
			Raid raid = level.isLoaded(v.bell()) ? level.getRaidAt(v.bell()) : null;
			ServerPlayer lord = server.getPlayerList().getPlayer(v.liege().owner());
			if (raid != null && !raid.isOver() && known == null) {
				RAIDED.put(v.key(), raid);
				SignalFires.alarm(server, v.liege().owner(), VASSAL + v.key(), v.name(), v.bell());
				if (lord != null) {
					People.say(lord, "Warchief", "My lord! Raiders are attacking " + v.name() + ", sworn to " + v.liege().name()
							+ ". Our guards at the bell stand ready. Ride to their aid!");
				}
			} else if (known != null && (known.isOver() || raid == null)) {
				RAIDED.remove(v.key());
				SignalFires.allClear(VASSAL + v.key());
				if (lord != null) {
					People.say(lord, "Warchief", known.isVictory() ? "The raid on " + v.name() + " is beaten off. The village stands."
							: "The raid on " + v.name() + " is over. They will need time to recover.");
				}
			}
		}
		for (String gone : new ArrayList<>(RAIDED.keySet())) {
			if (!seen.contains(gone)) {
				RAIDED.remove(gone);
				SignalFires.allClear(VASSAL + gone);
			}
		}
	}
}
