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
import net.minecraft.world.InteractionHand;
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

	static String role(Entity e) {
		String r = e.getAttached(ROLE);
		return r == null ? "" : r;
	}

	private People() {
	}

	static void register() {
		// our people can't be hurt
		// and a guard isn't hurt by his own lord (he doesn't turn on him either; he stays at his post)
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> !role(entity).startsWith(ARCHITECT) && !role(entity).startsWith(WORKER)
				&& !role(entity).startsWith("pet|") && !(entity instanceof KingdomNpc n && n.lord() != null && source.getEntity() != null
				&& n.lord().equals(source.getEntity().getUUID()) && (n.role().equals("guard") || Guild.isAlly(n))));
		UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			if (!(player instanceof ServerPlayer sp) || !(entity instanceof KingdomNpc npc)) {
				return InteractionResult.PASS;
			}
			if (hand != InteractionHand.MAIN_HAND) {
				return InteractionResult.SUCCESS; // the event fires for both hands: answer once
			}
			if (npc.role().equals("companion")) {
				say(sp, "Aldric", sp.getUUID().equals(npc.lord()) ? "Right behind you. I'll keep an eye out for anything useful." : "I travel with someone else.");
				return InteractionResult.SUCCESS;
			}
			if (npc.role().equals("guard")) {
				String who = npc.getCustomName() == null ? "Guard" : io.github.sotadevop.kingdomquest.npc.Folk.nameOf(npc.getCustomName().getString());
				if (npc.rallied() && sp.getUUID().equals(npc.lord())) {
					// talking to a called guard sends all the called guards back to their posts
					String tag = role(npc);
					List<KingdomNpc> called = ((ServerLevel) sp.level()).getEntities(KingdomNpc.TYPE, sp.getBoundingBox().inflate(160),
							g -> g.isAlive() && g.rallied() && tag.equals(role(g)));
					called.forEach(g -> g.setRallied(false));
					say(sp, who, "Back to our posts, my lord.");
				} else {
					say(sp, who, "All quiet on my watch, my lord.");
				}
				return InteractionResult.SUCCESS;
			}
			if (!Guild.isAlly(npc) && role(entity).isEmpty()) {
				return InteractionResult.PASS;
			}
			if (Workers.talk(sp, npc)) {
				return InteractionResult.SUCCESS;
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
		v.setup(role, KingdomNpc.skinFor(name), s.owner());
		// a face of the region, fixed per person (the architect is a scholar, builders craftsmen)
		int region = io.github.sotadevop.kingdomquest.npc.Folk.region(s.style());
		int outfit = role.equals(ARCHITECT) ? io.github.sotadevop.kingdomquest.npc.Folk.SCHOLAR
				: role.equals("builder") ? io.github.sotadevop.kingdomquest.npc.Folk.BUILDER : io.github.sotadevop.kingdomquest.npc.Folk.outfit(name);
		v.setSkin(io.github.sotadevop.kingdomquest.npc.Folk.skin(region, outfit, name.hashCode() + s.id().hashCode()));
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

	/** The personal names already given to the kingdom's people (workers, guards, knights), so a new one gets another. */
	static java.util.Set<String> namesInUse(ServerLevel level, Settlement s) {
		java.util.Set<String> out = new java.util.HashSet<>();
		String id = s.id().toString();
		for (KingdomNpc n : level.getEntitiesOfClass(KingdomNpc.class, new AABB(s.heart()).inflate(320, 96, 320),
				n -> n.getCustomName() != null && role(n).contains(id))) {
			out.add(io.github.sotadevop.kingdomquest.npc.Folk.nameOf(n.getCustomName().getString()));
		}
		return out;
	}

	/**
	 * A townsperson who comes to look (market day), half of them women; a knight is a man of the garrison. Sent off
	 * again by whoever called them.
	 */
	static KingdomNpc visitor(ServerLevel level, Settlement s, String role, BlockPos at) {
		int region = io.github.sotadevop.kingdomquest.npc.Folk.region(s.style());
		int seed = level.getRandom().nextInt();
		boolean knight = role.equals("knight");
		boolean female = !knight && (seed & 1) == 1;
		String name = io.github.sotadevop.kingdomquest.npc.Folk.name(region, female, seed >>> 1);
		KingdomNpc v = spawn(level, s, role, name, at);
		if (v != null) {
			int outfit = knight ? io.github.sotadevop.kingdomquest.npc.Folk.GUARD : female ? ((seed >>> 3 & 1) == 0 ? io.github.sotadevop.kingdomquest.npc.Folk.HOUSEWIFE
					: io.github.sotadevop.kingdomquest.npc.Folk.WORKER_WOMAN) : ((seed >>> 3 & 1) == 0 ? io.github.sotadevop.kingdomquest.npc.Folk.PEASANT
					: io.github.sotadevop.kingdomquest.npc.Folk.BURGHER);
			v.setSkin(io.github.sotadevop.kingdomquest.npc.Folk.skin(region, outfit, seed >>> 5));
		}
		return v;
	}

	/** Every 10 seconds: settlements with a house get their Architect (again, if he went missing). */
	static void ensureArchitect(ServerLevel level, Settlement s) {
		// villagers from older versions of the mod (before our own NPCs) are retired
		level.getEntitiesOfClass(net.minecraft.world.entity.npc.villager.Villager.class, new AABB(s.heart()).inflate(96, 48, 96),
				v -> v.getAttached(ROLE) != null).forEach(Entity::discard);
		if (s.level(Settlement.MAIN) < 4 || !level.isLoaded(s.heart()) || !find(level, s, ARCHITECT).isEmpty()) {
			return;
		}
		KingdomNpc v = spawn(level, s, ARCHITECT, "Architect", s.heart().offset(Template_rotate(s, 0, -2)));
		if (v != null) {
			v.setNoAi(true);
		}
	}

	/** The architect stays at his post, and nobody stands inside anybody else. */
	static void spread(ServerLevel level, Settlement s) {
		List<KingdomNpc> all = level.getEntitiesOfClass(KingdomNpc.class, new AABB(s.heart()).inflate(110, 48, 110), Entity::isAlive);
		for (int i = 0; i < all.size(); i++) {
			for (int j = i + 1; j < all.size(); j++) {
				KingdomNpc a = all.get(i);
				KingdomNpc b = all.get(j);
				if (a.distanceToSqr(b) < 0.5 * 0.5) {
					KingdomNpc mover = a.isNoAi() ? b : a;
					double ang = level.getRandom().nextDouble() * Math.PI * 2;
					BlockPos to = BlockPos.containing(mover.getX() + Math.cos(ang) * 1.5, mover.getY(), mover.getZ() + Math.sin(ang) * 1.5);
					if (level.getBlockState(to).getCollisionShape(level, to).isEmpty()
							&& level.getBlockState(to.above()).getCollisionShape(level, to.above()).isEmpty()) {
						mover.teleportTo(to.getX() + 0.5, mover.getY(), to.getZ() + 0.5);
					}
				}
			}
		}
	}

	/** How many builders live in the village: two in camp days, up to four in a castle. */
	private static int builders(Settlement s) {
		int main = s.level(Settlement.MAIN);
		return main < 4 ? 2 : main < 7 ? 3 : 4;
	}

	/** Where builder i sleeps: in the houses around the square once there are some, by the camp fire before. */
	private static BlockPos builderHome(Settlement s, int i) {
		if (s.level(Settlement.MAIN) < 4) {
			return s.heart().offset(Template_rotate(s, -3 + i * 2, 3));
		}
		int[][] homes = {{-6, 35}, {20, 35}, {-18, 35}, {7, 1}};
		int[] h = homes[i % homes.length];
		return s.heart().offset(Template_rotate(s, h[0] - 7, h[1] - 10));
	}

	/**
	 * Every 10 seconds: the builders live in the village. Missing ones move in (out of sight, at their home);
	 * without a job they stroll around the square by day and go home at night.
	 */
	static void ensureBuilders(ServerLevel level, Settlement s) {
		if (!level.isLoaded(s.heart())) {
			return;
		}
		List<KingdomNpc> have = find(level, s, WORKER);
		for (int i = have.size(); i < builders(s); i++) {
			BlockPos home = builderHome(s, i);
			if (level.getNearestPlayer(home.getX(), home.getY(), home.getZ(), 16, false) != null) {
				return; // nobody appears in front of the player
			}
			KingdomNpc v = spawn(level, s, WORKER, "Builder", home);
			if (v != null) {
				v.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_PICKAXE));
			}
		}
		if (!s.job().isEmpty()) {
			return;
		}
		for (int i = 0; i < have.size(); i++) {
			KingdomNpc v = have.get(i);
			BlockPos home = builderHome(s, i);
			if (level.getSkyDarken() >= 4) {
				v.getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0.7);
			} else if (level.getRandom().nextInt(3) == 0) {
				BlockPos sq = s.heart().offset(Template_rotate(s, level.getRandom().nextInt(13) - 6, level.getRandom().nextInt(7)));
				v.getNavigation().moveTo(sq.getX() + 0.5, sq.getY(), sq.getZ() + 0.5, 0.5);
			}
		}
	}

	private static BlockPos Template_rotate(Settlement s, int dx, int dz) {
		return io.github.sotadevop.kingdomquest.building.Template.rotate(new BlockPos(dx, 0, dz), Rotation.values()[s.rotation()]);
	}

	/** A build starts: the Architect sends the village's builders over. */
	static void sendWorkers(ServerLevel level, Settlement s, int blocks, ServerPlayer owner) {
		int count = find(level, s, WORKER).size();
		if (owner != null && s.level(Settlement.MAIN) >= 4 && count > 0) {
			say(owner, "Architect", count == 1 ? "I'll send our builder right away." : "I'll send our " + count + " builders right away.");
		}
	}

	/** Keeps the builders near the block being placed (they hop over when they wander off). */
	static void herdWorkers(ServerLevel level, Settlement s, BlockPos target) {
		for (KingdomNpc v : find(level, s, WORKER)) {
			if (v.distanceToSqr(Vec3.atCenterOf(target)) > 8 * 8) {
				// they walk to the site; only one who is stuck (no path) is helped over
				if (!v.getNavigation().isDone() || v.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.9)) {
					continue;
				}
				BlockPos spot = target.offset(level.getRandom().nextInt(5) - 2, 0, level.getRandom().nextInt(5) - 2);
				for (int up = 0; up < 16 && !(level.getBlockState(spot).isAir() && level.getBlockState(spot.above()).isAir()); up++) {
					spot = spot.above();
				}
				v.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
			} else {
				v.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.6);
				if (v.distanceToSqr(Vec3.atCenterOf(target)) < 5 * 5) {
					// the right tool for the work: axe for wood, shovel for earth, pickaxe for stone
					var st = level.getBlockState(target);
					v.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new net.minecraft.world.item.ItemStack(
							st.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE) ? net.minecraft.world.item.Items.IRON_AXE
									: st.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL) ? net.minecraft.world.item.Items.IRON_SHOVEL
									: net.minecraft.world.item.Items.IRON_PICKAXE));
					v.swingArm(); // hammering away
				}
			}
			v.getLookControl().setLookAt(Vec3.atCenterOf(target));
		}
	}

	/** The job is done: the builders walk home (they live in the village now). */
	static void removeWorkers(ServerLevel level, Settlement s) {
		List<KingdomNpc> list = find(level, s, WORKER);
		for (int i = 0; i < list.size(); i++) {
			BlockPos home = builderHome(s, i);
			list.get(i).getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0.7);
		}
	}

	private static void discardWorkers(ServerLevel level, Settlement s) {
		for (KingdomNpc v : find(level, s, WORKER)) {
			level.sendParticles(ParticleTypes.POOF, v.getX(), v.getY() + 1, v.getZ(), 8, 0.3, 0.5, 0.3, 0.02);
			v.discard();
		}
	}

	static void removeAll(ServerLevel level, Settlement s) {
		discardWorkers(level, s);
		find(level, s, ARCHITECT).forEach(Entity::discard);
	}
}
