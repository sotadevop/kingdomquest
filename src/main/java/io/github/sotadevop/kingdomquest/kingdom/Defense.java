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

	private static BlockPos at(Settlement s, int bx, int bz) {
		return s.heart().offset(Template.rotate(new BlockPos(bx - 7, 0, bz - 10), Rotation.values()[s.rotation()]));
	}

	/** Every minute: missing guards come back from the barracks; guards that wandered off walk home. */
	static void guards(ServerLevel level, Settlement s) {
		int want = s.level(Progression.BARRACKS) >= 2 ? 4 : s.level(Progression.BARRACKS) >= 1 ? 2 : 0;
		if (want == 0 || !level.isLoaded(s.heart())) {
			return;
		}
		List<Mob> guards = find(level, s, GUARD, 120);
		for (int i = guards.size(); i < want; i++) {
			Mob g = spawn(level, s, "iron_golem", GUARD, at(s, 16, 31));
			if (g != null) {
				g.setCustomName(net.minecraft.network.chat.Component.literal("Guard"));
				level.sendParticles(ParticleTypes.POOF, g.getX(), g.getY() + 1, g.getZ(), 10, 0.4, 0.6, 0.4, 0.02);
			}
		}
		for (Mob g : guards) {
			if (g.distanceToSqr(Vec3.atCenterOf(s.heart())) > 70 * 70 && g.getTarget() == null) {
				BlockPos home = at(s, 16, 31);
				g.getNavigation().moveTo(home.getX() + 0.5, s.heart().getY(), home.getZ() + 0.5, 1.0);
			}
		}
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
				spawn(level, s, i % 3 == 2 ? "vindicator" : "pillager", RAIDER, at(s, 1 + i % 7 * 2, 84 + i / 7 * 2));
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
		BlockPos gate = at(s, 7, 46);
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
