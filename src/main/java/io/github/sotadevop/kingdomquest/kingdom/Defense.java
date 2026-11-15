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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Guards and attacks.
 * <ul>
 * <li>Barracks: iron golem guards (2 at level 1, 4 at level 2) stay around the settlement and come back if they
 * die (one per minute).</li>
 * <li>Attacks: from the Castle on, every third night an army of pillagers and vindicators marches on the gate. The
 * Warchief warns 30 seconds before; when every attacker is beaten the kingdom gets a reward.</li>
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

	/** Every 5 seconds: warn, send the army, steer it to the gate, reward the defenders. */
	static void attacks(MinecraftServer server, Settlement s) {
		ServerLevel level = server.overworld();
		if (s.level(Settlement.MAIN) < 9 || s.level(OFF) == 1 || !level.isLoaded(s.heart())) {
			return;
		}
		long time = level.getGameTime();
		long day = level.getDayTime() / 24000L;
		long tod = level.getDayTime() % 24000L;
		ServerPlayer owner = server.getPlayerList().getPlayer(s.owner());
		Long arrive = PENDING.get(s.id());
		if (arrive == null && s.level(NEXT) == 0) {
			Settlements.putState(server, s.withLevel(NEXT, (int) day + 3));
			return;
		}
		if (arrive == null && day >= s.level(NEXT) && tod > 13000 && tod < 14000) {
			start(server, s, owner);
			return;
		}
		if (arrive != null && time >= arrive) {
			PENDING.remove(s.id());
			int castle = Math.max(1, s.level(Settlement.MAIN) - 8);
			int size = 4 + 3 * castle;
			for (int i = 0; i < size; i++) {
				spawn(level, s, i % 3 == 2 ? "vindicator" : "pillager", RAIDER, at(s, 7 - 6 + i % 7 * 2, 40 + 40 + i / 7 * 2));
			}
			if (owner != null) {
				People.say(owner, "Warchief", "They're here! " + size + " raiders at the gate. Man the walls!");
			}
			return;
		}
		List<Mob> raiders = find(level, s, RAIDER, 160);
		if (arrive == null && raiders.isEmpty() && s.level("attack_running") == 1) {
			Settlements.putState(server, s.withLevel("attack_running", 0).withLevel(NEXT, (int) day + 3));
			if (owner != null) {
				People.say(owner, "Warchief", "Victory! The army is beaten. The people send you their thanks.");
				ItemStack reward = new ItemStack(Items.EMERALD, 8 + 4 * Math.max(1, s.level(Settlement.MAIN) - 8));
				if (!owner.getInventory().add(reward)) {
					owner.drop(reward, false);
				}
			}
			return;
		}
		BlockPos gate = at(s, 7, 46);
		for (Mob m : raiders) {
			if (m.getTarget() == null) {
				m.getNavigation().moveTo(gate.getX() + 0.5, s.heart().getY(), gate.getZ() + 0.5, 1.0);
			}
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
