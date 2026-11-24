package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.building.Layout;
import io.github.sotadevop.kingdomquest.npc.KingdomNpc;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Tournaments (19.11), visual only: every third game day, while it is light and the lord is near, the tournament field
 * north of the castle fills. Two knights on armoured horses ride at each other down the tilt, turn at the ends and
 * charge again (a clash of lances when they pass); archers loose at the targets; the crowd stands by the lists. At
 * dusk everyone goes home. Positions in the base frame of tools/buildings.py (TOURNEY).
 */
final class Tournament {
	private static final int TILT_Z = -157;
	private static final int WEST = -10;
	private static final int EAST = 18;
	private static final String ROLE = "tourney";
	private static final int[][] ARCHERS = {{24, -151}, {27, -151}, {30, -151}};
	private static final int TARGET_Z = -166;

	private Tournament() {
	}

	static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 20 == 11) {
				for (Settlement s : Settlements.all(server)) {
					tick(server, s);
				}
			}
		});
	}

	static boolean isTournamentDay(long gameTime) {
		return (gameTime / 24000L) % 3 == 1;
	}

	private static BlockPos at(Settlement s, int bx, int bz) {
		return s.heart().offset(Layout.rel(Rotation.values()[s.rotation()], bx, 0, bz));
	}

	private static List<Entity> tagged(ServerLevel level, Settlement s) {
		String want = ROLE + "|" + s.id();
		BlockPos c = at(s, 5, TILT_Z);
		return level.getEntitiesOfClass(Entity.class, new AABB(c).inflate(60, 40, 60), e -> {
			String r = e.getAttached(People.ROLE);
			return r != null && (r.equals(want) || r.equals("animal|" + s.id() + "|tourney_horse"));
		});
	}

	private static void tick(MinecraftServer server, Settlement s) {
		ServerLevel level = server.overworld();
		if (s.level("tourney") <= 0) {
			return;
		}
		BlockPos centre = at(s, 5, TILT_Z);
		if (!level.isLoaded(centre)) {
			return;
		}
		List<Entity> here = tagged(level, s);
		ServerPlayer owner = server.getPlayerList().getPlayer(s.owner());
		boolean near = owner != null && owner.level() == level && owner.distanceToSqr(Vec3.atCenterOf(centre)) < 120 * 120;
		boolean on = isTournamentDay(level.getGameTime()) && level.isBrightOutside();
		if (!on) {
			for (Entity e : here) {
				e.discard();
			}
			return;
		}
		if (here.isEmpty()) {
			if (!near) {
				return;
			}
			start(level, s);
			owner.sendSystemMessage(Component.translatable("kingdomquest.tourney.start", s.name()));
			return;
		}
		joust(level, s, here);
		if (server.getTickCount() % 60 == 11) {
			archery(level, s, here);
		}
	}

	private static void start(ServerLevel level, Settlement s) {
		int y = s.heart().getY() + Settlements.lift(s, Settlements.template(s, "tourney_1"));
		String role = ROLE + "|" + s.id();
		for (int i = 0; i < 2; i++) {
			int lane = TILT_Z + (i == 0 ? 2 : -2);
			BlockPos p = Animals.floor(level, at(s, i == 0 ? WEST : EAST, lane), y);
			if (p == null) {
				continue;
			}
			EntityType<?> ht = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("horse"));
			Entity he = ht == null ? null : ht.create(level, EntitySpawnReason.EVENT);
			if (!(he instanceof Mob horse)) {
				continue;
			}
			horse.snapTo(Vec3.atBottomCenterOf(p), i == 0 ? -90 : 90, 0);
			horse.setPersistenceRequired();
			horse.addTag(i == 0 ? "kq_lane_s" : "kq_lane_n");
			horse.setAttached(People.ROLE, "animal|" + s.id() + "|tourney_horse");
			horse.setItemSlot(EquipmentSlot.BODY, new ItemStack(i == 0 ? Items.GOLDEN_HORSE_ARMOR : Items.IRON_HORSE_ARMOR));
			level.addFreshEntity(horse);
			KingdomNpc knight = People.visitor(level, s, ROLE, p);
			if (knight == null) {
				horse.discard();
				continue;
			}
			knight.setCustomName(Component.translatable("kingdomquest.tourney.knight." + i));
			knight.setItemSlot(EquipmentSlot.HEAD, new ItemStack(i == 0 ? Items.GOLDEN_HELMET : Items.IRON_HELMET));
			knight.setItemSlot(EquipmentSlot.CHEST, new ItemStack(i == 0 ? Items.GOLDEN_CHESTPLATE : Items.IRON_CHESTPLATE));
			knight.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT)); // the nearest thing to a lance
			knight.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
			knight.setNoAi(true);
			knight.startRiding(horse);
		}
		for (int[] a : ARCHERS) {
			BlockPos p = Animals.floor(level, at(s, a[0], a[1]), y);
			KingdomNpc archer = p == null ? null : People.visitor(level, s, ROLE, p);
			if (archer != null) {
				archer.setCustomName(Component.translatable("kingdomquest.tourney.archer"));
				archer.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
				archer.setNoAi(true);
				BlockPos t = at(s, a[0], TARGET_Z);
				archer.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(t.atY(p.getY() + 1)));
			}
		}
		// the crowd by the lists, in front of the stand
		for (int i = 0; i < 8; i++) {
			BlockPos p = Animals.floor(level, at(s, -8 + i * 3, TILT_Z + 5), y);
			KingdomNpc fan = p == null ? null : People.visitor(level, s, ROLE, p);
			if (fan != null) {
				fan.setNoAi(true);
				fan.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(at(s, -8 + i * 3, TILT_Z).atY(p.getY() + 1)));
			}
		}
	}

	/** The horses run to the far end of their lane and turn; when the two pass, the lances clash. */
	private static void joust(ServerLevel level, Settlement s, List<Entity> here) {
		Mob[] horses = here.stream().filter(e -> e instanceof Mob && !(e instanceof KingdomNpc) && !e.getPassengers().isEmpty()).map(e -> (Mob) e)
				.toArray(Mob[]::new);
		for (Mob horse : horses) {
			int lane = TILT_Z + (horse.getTags().contains("kq_lane_s") ? 2 : -2);
			BlockPos west = at(s, WEST, lane);
			BlockPos east = at(s, EAST, lane);
			double dw = horse.distanceToSqr(west.getX() + 0.5, horse.getY(), west.getZ() + 0.5);
			double de = horse.distanceToSqr(east.getX() + 0.5, horse.getY(), east.getZ() + 0.5);
			BlockPos to = dw < 9 ? east : de < 9 ? west : horse.getNavigation().isDone() ? (dw < de ? east : west) : null;
			if (to != null) {
				horse.getNavigation().moveTo(to.getX() + 0.5, horse.getY(), to.getZ() + 0.5, 1.6);
			}
		}
		if (horses.length == 2 && horses[0].distanceToSqr(horses[1]) < 16) {
			level.playSound(null, horses[0].blockPosition(), SoundEvents.SHIELD_BLOCK.value(), SoundSource.NEUTRAL, 1.2f, 0.9f);
			level.sendParticles(ParticleTypes.CRIT, (horses[0].getX() + horses[1].getX()) / 2, horses[0].getY() + 2, (horses[0].getZ() + horses[1].getZ()) / 2,
					12, 0.4, 0.4, 0.4, 0.2);
		}
	}

	/** Each archer looses an arrow at his target now and then. */
	private static void archery(ServerLevel level, Settlement s, List<Entity> here) {
		EntityType<?> at = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("arrow"));
		for (Entity e : here) {
			if (!(e instanceof KingdomNpc npc) || !npc.getMainHandItem().is(Items.BOW) || level.getRandom().nextInt(3) != 0) {
				continue;
			}
			Entity arrow = at == null ? null : at.create(level, EntitySpawnReason.EVENT);
			if (arrow == null) {
				continue;
			}
			Vec3 from = npc.getEyePosition();
			Vec3 dir = npc.getLookAngle();
			arrow.snapTo(from.add(dir), npc.getYRot(), npc.getXRot());
			arrow.setDeltaMovement(dir.scale(2.2).add(0, 0.12, 0));
			if (arrow instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow aa) {
				aa.pickup = net.minecraft.world.entity.projectile.arrow.AbstractArrow.Pickup.DISALLOWED;
			}
			level.addFreshEntity(arrow);
			level.playSound(null, npc.blockPosition(), SoundEvents.ARROW_SHOOT, SoundSource.NEUTRAL, 0.8f, 1.0f);
		}
	}
}
