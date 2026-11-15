package io.github.sotadevop.kingdomquest.kingdom;

import com.mojang.serialization.Codec;
import io.github.sotadevop.kingdomquest.KingdomQuest;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import io.github.sotadevop.kingdomquest.npc.KingdomNpc;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The settlement's people, for now vanilla villagers with a role (until KingdomQuest's own NPC entity exists):
 * the Architect stands in front of the main building and opens the kingdom menu; Builders appear when a build
 * starts, stay close to the blocks being placed and leave when it's done.
 */
final class People {
	private static final String ARCHITECT = "architect";
	private static final String WORKER = "worker";
	/** "role|settlement id" on our villagers (a Fabric attachment, saved with the entity). */
	static final AttachmentType<String> ROLE = AttachmentRegistry.<String>builder()
			.persistent(Codec.STRING)
			.buildAndRegister(Identifier.fromNamespaceAndPath(KingdomQuest.MOD_ID, "role"));

	private static String role(Entity e) {
		String r = e.getAttached(ROLE);
		return r == null ? "" : r;
	}

	private People() {
	}

	static void register() {
		// our people can't be hurt
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> !role(entity).startsWith(ARCHITECT) && !role(entity).startsWith(WORKER));
		UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			if (!(player instanceof ServerPlayer sp) || !(entity instanceof KingdomNpc npc) || !Guild.isAlly(npc) && role(entity).isEmpty()) {
				return InteractionResult.PASS;
			}
			if (Guild.isAlly(npc)) {
				Guild.talk(sp, npc);
				return InteractionResult.SUCCESS;
			}
			Settlement s = settlementOf(sp, entity);
			if (s == null) {
				return InteractionResult.PASS;
			}
			if (role(entity).startsWith(ARCHITECT)) {
				say(sp, "Architect", s.job().isEmpty() ? "Welcome back! What shall we build next?" : "My builders are hard at work.");
				Settlements.sendInfo(sp, s);
			} else {
				say(sp, "Builder", "Busy here! We'll be done soon.");
			}
			return InteractionResult.SUCCESS; // no trading screen
		});
	}

	private static Settlement settlementOf(ServerPlayer player, Entity entity) {
		String r = role(entity);
		int bar = r.indexOf('|');
		if (bar < 0) {
			return null;
		}
		for (Settlement s : Settlements.all(player.level().getServer())) {
			if (s.id().toString().equals(r.substring(bar + 1))) {
				return s;
			}
		}
		return null;
	}

	static void say(ServerPlayer player, String who, String line) {
		player.sendSystemMessage(Component.literal("[" + who + "] ").withStyle(ChatFormatting.GOLD).append(Component.literal(line).withStyle(ChatFormatting.WHITE)));
	}

	private static List<KingdomNpc> find(ServerLevel level, Settlement s, String role) {
		String want = role + "|" + s.id();
		return level.getEntitiesOfClass(KingdomNpc.class, new AABB(s.heart()).inflate(96, 48, 96), v -> v.isAlive() && want.equals(role(v)));
	}

	private static KingdomNpc spawn(ServerLevel level, Settlement s, String role, String name, BlockPos at) {
		KingdomNpc v = KingdomNpc.TYPE.create(level, EntitySpawnReason.MOB_SUMMONED);
		if (v == null) {
			return null;
		}
		v.setup(role, level.getRandom().nextInt(9), s.owner());
		BlockPos pos = at;
		for (int up = 0; up < 12 && !(level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()); up++) {
			pos = pos.above();
		}
		v.snapTo(Vec3.atBottomCenterOf(pos), level.getRandom().nextFloat() * 360, 0);
		v.setAttached(ROLE, role + "|" + s.id());
		v.setCustomName(Component.literal(name));
		v.setPersistenceRequired();
		level.addFreshEntity(v);
		level.sendParticles(ParticleTypes.POOF, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, 8, 0.3, 0.5, 0.3, 0.02);
		return v;
	}

	/** Every 10 seconds: settlements with a house get their Architect (again, if he went missing). */
	static void ensureArchitect(ServerLevel level, Settlement s) {
		if (s.level(Settlement.MAIN) < 4 || !level.isLoaded(s.heart()) || !find(level, s, ARCHITECT).isEmpty()) {
			return;
		}
		KingdomNpc v = spawn(level, s, ARCHITECT, "Architect", s.heart().offset(Template_rotate(s, 0, -2)));
		if (v != null) {
			v.setNoAi(true);
		}
	}

	private static BlockPos Template_rotate(Settlement s, int dx, int dz) {
		return io.github.sotadevop.kingdomquest.building.Template.rotate(new BlockPos(dx, 0, dz), Rotation.values()[s.rotation()]);
	}

	/** A build starts: the Architect sends his builders, more for bigger jobs. */
	static void sendWorkers(ServerLevel level, Settlement s, int blocks, ServerPlayer owner) {
		removeWorkers(level, s);
		int count = Math.max(1, Math.min(4, blocks / 200 + 1));
		for (int i = 0; i < count; i++) {
			spawn(level, s, WORKER, "Builder", s.heart().offset(Template_rotate(s, -2 + i * 2, 2)));
		}
		if (owner != null && s.level(Settlement.MAIN) >= 4) {
			say(owner, "Architect", count == 1 ? "I'll send a builder right away." : "I'll send " + count + " builders right away.");
		}
	}

	/** Keeps the builders near the block being placed (they hop over when they wander off). */
	static void herdWorkers(ServerLevel level, Settlement s, BlockPos target) {
		for (KingdomNpc v : find(level, s, WORKER)) {
			if (v.distanceToSqr(Vec3.atCenterOf(target)) > 8 * 8) {
				BlockPos spot = target.offset(level.getRandom().nextInt(5) - 2, 0, level.getRandom().nextInt(5) - 2);
				for (int up = 0; up < 16 && !(level.getBlockState(spot).isAir() && level.getBlockState(spot.above()).isAir()); up++) {
					spot = spot.above();
				}
				v.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
			} else {
				v.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.6);
				if (v.distanceToSqr(Vec3.atCenterOf(target)) < 5 * 5) {
					v.swingArm(); // hammering away
				}
			}
			v.getLookControl().setLookAt(Vec3.atCenterOf(target));
		}
	}

	static void removeWorkers(ServerLevel level, Settlement s) {
		for (KingdomNpc v : find(level, s, WORKER)) {
			level.sendParticles(ParticleTypes.POOF, v.getX(), v.getY() + 1, v.getZ(), 8, 0.3, 0.5, 0.3, 0.02);
			v.discard();
		}
	}

	static void removeAll(ServerLevel level, Settlement s) {
		removeWorkers(level, s);
		find(level, s, ARCHITECT).forEach(Entity::discard);
	}
}
