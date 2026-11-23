package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.building.Template;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Guards and attacks.
 * <ul>
 * <li>Barracks: iron golem guards (2 at level 1, 4 at level 2) stay around the settlement and come back if they
 * die (one per minute).</li>
 * <li>Attacks: from the Castle on, every fifth day (only while the lord is home) an army of pillagers and vindicators marches on the gate. The
 * Warchief warns 30 seconds before. Raiders break buildings; the Architect repairs them.</li>
 * </ul>
 */
final class Defense {
	static final String GUARD = "guard";
	static final String RAIDER = "raider";
	private static final String NEXT = "next_attack";
	static final String OFF = "attacks_off";
	private static final Map<UUID, Long> PENDING = new HashMap<>(); // settlement -> game time the army arrives

	private Defense() {
	}

	private static List<Mob> find(ServerLevel level, Settlement s, String role, double r) {
		String want = role + "|" + s.id();
		return level.getEntitiesOfClass(Mob.class, new AABB(s.heart()).inflate(r, 64, r), m -> m.isAlive() && want.equals(m.getAttached(People.ROLE)));
	}

	private static Mob spawn(ServerLevel level, Settlement s, String id, String role, BlockPos at) {
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace(id));
		if (!(type.create(level, EntitySpawnReason.EVENT) instanceof Mob m)) {
			return null;
		}
		BlockPos pos = new BlockPos(at.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()), at.getZ());
		m.snapTo(Vec3.atBottomCenterOf(pos), level.getRandom().nextFloat() * 360, 0);
		m.setAttached(People.ROLE, role + "|" + s.id());
		m.setPersistenceRequired();
		level.addFreshEntity(m);
		return m;
	}

	/** A column of the base frame of tools/buildings.py (already where it lies in the site). */
	private static BlockPos at(Settlement s, int bx, int bz) {
		return s.heart().offset(io.github.sotadevop.kingdomquest.building.Layout.rel(Rotation.values()[s.rotation()], bx, 0, bz));
	}

	/** A guard's fixed post: where he stands, and whether he is a tower guard (bow, never leaves). */
	record Post(BlockPos pos, boolean tower) {
	}

	/** Nearest place to stand in a column around {@code y}: a full block under two free ones. Null if there is none within 10. */
	private static BlockPos standAt(ServerLevel level, int x, int z, int y) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int d = 0; d <= 10; d++) {
			for (int sign : new int[] {1, -1}) {
				pos.set(x, y + d * sign, z);
				if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
						&& level.getBlockState(pos.below()).isCollisionShapeFullBlock(level, pos.below())) {
					return pos.immutable();
				}
			}
		}
		return null;
	}

	/**
	 * The fixed posts of a settlement: two guards inside the gate, two in front of the main building, and one on
	 * every wall tower (from the walls' first towers on). Tower tops are found in the world, on what the walls built.
	 */
	static List<Post> posts(ServerLevel level, Settlement s) {
		List<Post> out = new ArrayList<>();
		int y = s.heart().getY();
		int gz = io.github.sotadevop.kingdomquest.building.Layout.gateZ();
		for (int[] p : new int[][] {{4, gz - 4}, {10, gz - 4}, {4, 7}, {10, 7}}) {
			BlockPos at = at(s, p[0], p[1]);
			BlockPos stand = level.isLoaded(at) ? standAt(level, at.getX(), at.getZ(), y) : null;
			if (stand != null) {
				out.add(new Post(stand, false));
			}
		}
		int walls = s.level(Progression.WALLS);
		if (walls >= 2 && !Progression.WALLS.equals(s.jobTrack())) {
			java.util.Set<Long> built = new java.util.HashSet<>(s.built().getOrDefault(Progression.WALLS, List.of()));
			int[] ring = io.github.sotadevop.kingdomquest.building.Layout.wall(); // the wall ring and towers of tools/buildings.py (WALL, tower_spots)
			int x0 = ring[0], x1 = ring[1], z0 = ring[2], z1 = ring[3];
			List<int[]> spots = io.github.sotadevop.kingdomquest.building.Layout.towers(walls);
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			for (int[] spot : spots) {
				// stone towers stand one block inside the ring line, wooden ones on it
				int cx = walls >= 3 ? Math.max(x0 + 1, Math.min(x1 - 1, spot[0])) : spot[0];
				int cz = walls >= 3 ? (spot[1] == z1 ? z1 : Math.max(z0 + 1, Math.min(z1 - 1, spot[1]))) : spot[1];
				BlockPos col = at(s, cx, cz);
				for (int yy = y + 60; yy >= y - 40 && level.isLoaded(col); yy--) {
					pos.set(col.getX(), yy, col.getZ());
					// the highest floor the walls built there: the fighting platform (roof slabs and lanterns are no floor)
					if (built.contains(pos.asLong()) && level.getBlockState(pos).isCollisionShapeFullBlock(level, pos)
							&& level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
							&& level.getBlockState(pos.above(2)).getCollisionShape(level, pos.above(2)).isEmpty()) {
						out.add(new Post(pos.above().immutable(), true));
						break;
					}
				}
			}
		}
		return out;
	}

	private static io.github.sotadevop.kingdomquest.npc.KingdomNpc spawnGuard(ServerLevel level, Settlement s, int lvl, BlockPos where, Post post) {
		io.github.sotadevop.kingdomquest.npc.KingdomNpc g = io.github.sotadevop.kingdomquest.npc.KingdomNpc.TYPE.create(level, EntitySpawnReason.EVENT);
		if (g == null) {
			return null;
		}
		if (post != null) {
			g.setPost(post.pos());
		}
		g.guardGear(lvl, post != null && post.tower()); // before setup: the bow decides how he fights
		g.setup("guard", io.github.sotadevop.kingdomquest.npc.KingdomNpc.GUARD_SKIN, s.owner());
		g.setTier(lvl);
		g.setHome(s.heart());
		g.setCustomName(net.minecraft.network.chat.Component.literal(post == null ? "Guard" : post.tower() ? "Tower Guard" : "Gate Guard"));
		g.setAttached(People.ROLE, GUARD + "|" + s.id());
		g.snapTo(where.getX() + 0.5, where.getY(), where.getZ() + 0.5, 0, 0);
		level.addFreshEntity(g);
		level.sendParticles(ParticleTypes.POOF, g.getX(), g.getY() + 1, g.getZ(), 10, 0.4, 0.6, 0.4, 0.02);
		return g;
	}

	/**
	 * Every minute: the garrison is brought up to strength. All guards look the same (one face, full iron armor).
	 * Patrols (2/4/6 by barracks level) walk the village; posted guards stand at the gate, before the main building
	 * and on every tower. Fallen ones are replaced.
	 */
	static void guards(ServerLevel level, Settlement s) {
		int lvl = s.level(Progression.BARRACKS);
		int want = lvl * 2;
		if (!level.isLoaded(s.heart())) {
			return;
		}
		// golem guards from older versions retire
		level.getEntitiesOfClass(Mob.class, new AABB(s.heart()).inflate(120, 64, 120),
				m -> !(m instanceof io.github.sotadevop.kingdomquest.npc.KingdomNpc) && (GUARD + "|" + s.id()).equals(m.getAttached(People.ROLE)))
				.forEach(Mob::discard);
		List<Mob> guards = find(level, s, GUARD, 120);
		List<Post> posts = lvl >= 1 && s.job().isEmpty() ? posts(level, s) : null; // while something is being built, posts stay as they are
		java.util.Set<Long> wanted = new java.util.HashSet<>();
		if (posts != null) {
			posts.forEach(p -> wanted.add(p.pos().asLong()));
		}
		java.util.Set<Long> manned = new java.util.HashSet<>();
		int patrols = 0;
		for (Mob g : guards) {
			if (!(g instanceof io.github.sotadevop.kingdomquest.npc.KingdomNpc npc)) {
				continue;
			}
			boolean gone = posts != null && !wanted.contains(npc.post()) && level.isLoaded(BlockPos.of(npc.post()));
			if (npc.post() != 0 && (lvl == 0 || gone || !manned.add(npc.post()))) {
				npc.discard(); // his post is gone (the walls changed) or double: a new guard takes the new one
				continue;
			}
			if (npc.post() == 0) {
				patrols++;
			}
			npc.guardGear(lvl, npc.getMainHandItem().is(net.minecraft.world.item.Items.BOW)); // same face and iron for all, old guards too
			npc.setTier(lvl);
		}
		for (int i = patrols; i < want; i++) {
			BlockPos yard = io.github.sotadevop.kingdomquest.building.Layout.at(s.heart(), Rotation.values()[s.rotation()], Progression.BARRACKS, 16, 0, 33);
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, yard.getX(), yard.getZ());
			spawnGuard(level, s, lvl, new BlockPos(yard.getX(), y, yard.getZ()), null);
		}
		if (posts != null) {
			for (Post p : posts) {
				if (!manned.contains(p.pos().asLong())) {
					spawnGuard(level, s, lvl, p.pos(), p);
				}
			}
		}
	}

	/** What the Warchief says about the next attack. */
	static String status(MinecraftServer server, Settlement s) {
		if (s.level(OFF) == 1) {
			return "The borders are quiet. No army will come while the attacks are off.";
		}
		if (s.level("attack_running") == 1) {
			return "We are under attack! To the walls!";
		}
		int day = (int) (server.overworld().getGameTime() / 24000L);
		int left = s.level(NEXT) - day;
		return left > 0 ? "Our scouts expect the next army in about " + left + (left == 1 ? " day." : " days.")
				: "An army is overdue. They are gathering in the hills and will strike soon.";
	}

	/** Days between attacks. */
	private static final int EVERY = 5;
	/** How long the lord must be home before an overdue attack comes (1.5 minutes). */
	private static final int HOME_TICKS = 1800;
	private static final Map<UUID, Long> HOME_SINCE = new HashMap<>();

	/**
	 * Every 5 seconds. Attacks only happen while the lord is at home (within 100 blocks): one is due every 5 game
	 * days; if he was away, it is overdue and comes after he has been back for a while. The army can wreck the
	 * buildings (never the heart); the kingdom can't be lost, but it needs repairs afterwards.
	 */
	static void attacks(MinecraftServer server, Settlement s) {
		ServerLevel level = server.overworld();
		if (s.level(Settlement.MAIN) < 9 || s.level(OFF) == 1 || !level.isLoaded(s.heart())) {
			return;
		}
		long time = level.getGameTime();
		int day = (int) (time / 24000L);
		ServerPlayer owner = server.getPlayerList().getPlayer(s.owner());
		boolean home = owner != null && owner.level() == level && owner.distanceToSqr(Vec3.atCenterOf(s.heart())) < 100 * 100;
		if (home) {
			HOME_SINCE.putIfAbsent(s.id(), time);
		} else {
			HOME_SINCE.remove(s.id());
		}
		if (s.level(NEXT) == 0) {
			Settlements.putState(server, s.withLevel(NEXT, day + EVERY));
			return;
		}
		Long arrive = PENDING.get(s.id());
		List<Mob> raiders = find(level, s, RAIDER, 160);
		if (arrive == null && s.level("attack_running") == 0 && day >= s.level(NEXT) && home && time - HOME_SINCE.get(s.id()) >= HOME_TICKS) {
			start(server, s, owner);
			return;
		}
		if (arrive != null && time >= arrive) {
			PENDING.remove(s.id());
			int castle = Math.max(1, s.level(Settlement.MAIN) - 8);
			int size = 4 + 3 * castle;
			for (int i = 0; i < size; i++) {
				spawn(level, s, i % 3 == 2 ? "vindicator" : "pillager", RAIDER, at(s, 1 + i % 7 * 2, io.github.sotadevop.kingdomquest.building.Layout.gateZ() + 44 + i / 7 * 2));
			}
			if (owner != null) {
				People.say(owner, "Warchief", "They're here! " + size + " raiders at the gate. Man the walls!");
			}
			return;
		}
		if (arrive == null && raiders.isEmpty() && s.level("attack_running") == 1) {
			Settlements.putState(server, s.withLevel("attack_running", 0).withLevel(NEXT, day + EVERY));
			if (owner != null) {
				People.say(owner, "Warchief", "Victory! The army is beaten. Have the Architect repair what they broke.");
			}
			return;
		}
		BlockPos gate = at(s, io.github.sotadevop.kingdomquest.building.Layout.gateX(), io.github.sotadevop.kingdomquest.building.Layout.gateZ() + 6);
		Set<Long> built = new java.util.HashSet<>();
		s.built().values().forEach(built::addAll);
		for (Mob m : raiders) {
			if (m.getTarget() == null) {
				m.getNavigation().moveTo(gate.getX() + 0.5, s.heart().getY(), gate.getZ() + 0.5, 1.0);
			}
			wreck(level, s, m, built);
		}
	}

	/** A raider breaks up to two of the settlement's blocks within 4 blocks of him (not the heart, not the ground). */
	private static void wreck(ServerLevel level, Settlement s, Mob m, Set<Long> built) {
		BlockPos c = m.blockPosition();
		int broken = 0;
		for (BlockPos p : BlockPos.randomInCube(level.getRandom(), 40, c.above(1), 4)) {
			if (broken >= 2) {
				return;
			}
			if (p.getY() < s.heart().getY() || p.equals(s.heart()) || !built.contains(p.asLong()) || level.getBlockState(p).isAir()) {
				continue;
			}
			level.levelEvent(2001, p, net.minecraft.world.level.block.Block.getId(level.getBlockState(p)));
			level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
					net.minecraft.world.level.block.Block.UPDATE_ALL | net.minecraft.world.level.block.Block.UPDATE_SUPPRESS_DROPS);
			broken++;
		}
	}

	/** Starts an attack now (also used by the test command). */
	static void start(MinecraftServer server, Settlement s, ServerPlayer owner) {
		PENDING.put(s.id(), server.overworld().getGameTime() + 600);
		Settlements.putState(server, s.withLevel("attack_running", 1));
		if (owner != null) {
			People.say(owner, "Warchief", "My lord! Scouts report an army marching on " + s.name() + ". They will reach the gate in 30 seconds!");
		}
	}

	static boolean isRaider(Entity e) {
		String r = e.getAttached(People.ROLE);
		return r != null && r.startsWith(RAIDER);
	}
}
