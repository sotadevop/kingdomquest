package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.building.Layout;
import io.github.sotadevop.kingdomquest.building.Template;
import io.github.sotadevop.kingdomquest.npc.KingdomNpc;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The kingdom's animals (19.11, 19.9), all vanilla and all coming back after a while:
 * <ul>
 * <li>the animals of the buildings' templates (farm animals in their pens, bees, horses in the stables) are topped up
 * every few minutes;</li>
 * <li>war dogs (Town): armoured wolves sitting inside the gate, two and one more per barracks level;</li>
 * <li>cats (Town): at the storehouse door and in the square;</li>
 * <li>mounted knights (Castle): guards in iron on armoured horses riding the main street between the gate and the
 * square, two, four at the Citadel;</li>
 * <li>caravans: a pack donkey and a llama walk the goods in from the gate to the storehouse now and then;</li>
 * <li>stable breeding: the lord buys a horse at the stables; the higher the stables, the faster and higher it goes.</li>
 * </ul>
 * Animals are tagged with their settlement (role "animal|id|kind", knights "knight|id"), so they show in the realm
 * register and can be counted.
 */
final class Animals {
	private static final Map<UUID, Long> CARAVAN = new HashMap<>();

	private Animals() {
	}

	static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			int t = server.getTickCount();
			if (t % 200 != 91) {
				return;
			}
			ServerLevel level = server.overworld();
			for (Settlement s : Settlements.all(server)) {
				ServerPlayer owner = server.getPlayerList().getPlayer(s.owner());
				if (owner == null || owner.level() != level || owner.distanceToSqr(Vec3.atCenterOf(s.heart())) > 160 * 160 || !level.isLoaded(s.heart())) {
					continue;
				}
				dogs(level, s, owner);
				cats(level, s, owner);
				knights(level, s);
				if (t % 6000 == 91) {
					templates(level, s);
				}
				caravan(level, s);
			}
		});
	}

	// ---- helpers ----

	private static BlockPos at(Settlement s, int bx, int bz) {
		return s.heart().offset(Layout.rel(Rotation.values()[s.rotation()], bx, 0, bz));
	}

	/** The first spot to stand on in a column, from below the heart up. */
	static BlockPos floor(ServerLevel level, BlockPos col, int y0) {
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int y = y0 - 12; y < y0 + 30; y++) {
			p.set(col.getX(), y, col.getZ());
			if (level.getBlockState(p.below()).isCollisionShapeFullBlock(level, p.below()) && level.getBlockState(p).getCollisionShape(level, p).isEmpty()
					&& level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()) {
				return p.immutable();
			}
		}
		return null;
	}

	private static List<Entity> tagged(ServerLevel level, Settlement s, String role) {
		return level.getEntitiesOfClass(Entity.class, new AABB(s.heart()).inflate(220, 64, 220), e -> e.isAlive() && role.equals(e.getAttached(People.ROLE)));
	}

	private static Mob spawn(ServerLevel level, Settlement s, String type, String role, BlockPos pos) {
		if (!level.isPositionEntityTicking(pos)) {
			return null; // its place isn't running: the one there may just be unseen
		}
		EntityType<?> et = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace(type));
		Entity e = et == null ? null : et.create(level, EntitySpawnReason.MOB_SUMMONED);
		if (!(e instanceof Mob m)) {
			return null;
		}
		m.snapTo(Vec3.atBottomCenterOf(pos), level.getRandom().nextFloat() * 360, 0);
		m.setPersistenceRequired();
		m.setAttached(People.ROLE, role);
		level.addFreshEntity(m);
		return m;
	}

	// ---- the kinds ----

	/** War dogs at the gate: armoured wolves, tame to the lord and told to sit. */
	private static void dogs(ServerLevel level, Settlement s, ServerPlayer owner) {
		if (s.level(Settlement.MAIN) < 8 || s.level(Progression.GATE) < 1) {
			return;
		}
		int want = Math.min(5, 2 + s.level(Progression.BARRACKS));
		String role = "animal|" + s.id() + "|war_dog";
		List<Entity> have = tagged(level, s, role);
		int gz = Layout.gateZ(Settlements.round(s));
		int gx = Layout.gateX();
		for (int i = have.size(); i < want; i++) {
			int side = i % 2 == 0 ? -1 : 1;
			BlockPos p = floor(level, at(s, gx + side * (3 + i / 2 * 2), gz - 6), s.heart().getY());
			if (p == null) {
				continue;
			}
			Mob m = spawn(level, s, "wolf", role, p);
			if (m instanceof TamableAnimal t) {
				t.tame(owner);
				t.setOrderedToSit(true);
				t.setInSittingPose(true);
				t.setItemSlot(EquipmentSlot.BODY, new ItemStack(Items.WOLF_ARMOR));
				t.setCustomName(Component.translatable("kingdomquest.animal.war_dog"));
			}
		}
	}

	/** Cats at the storehouse door (the mice!) and one in the square. */
	private static void cats(ServerLevel level, Settlement s, ServerPlayer owner) {
		if (s.level(Settlement.MAIN) < 8) {
			return;
		}
		String role = "animal|" + s.id() + "|cat";
		List<Entity> have = tagged(level, s, role);
		int[][] spots = s.level(Progression.STOREHOUSE) > 0 ? new int[][] {{-24, 64}, {-29, 64}, {3, 21}} : new int[][] {{3, 21}};
		for (int i = have.size(); i < spots.length; i++) {
			BlockPos p = floor(level, at(s, spots[i][0], spots[i][1]), s.heart().getY());
			if (p == null) {
				continue;
			}
			Mob m = spawn(level, s, "cat", role, p);
			if (m instanceof TamableAnimal t) {
				t.tame(owner);
				t.setOrderedToSit(i < 2);
				t.setInSittingPose(i < 2);
			}
		}
	}

	/** Mounted knights riding the main street between the gate and the square. */
	private static void knights(ServerLevel level, Settlement s) {
		int main = s.level(Settlement.MAIN);
		if (main < 9) {
			return;
		}
		int want = main >= 11 ? 4 : 2;
		String role = "knight|" + s.id();
		List<Entity> riders = tagged(level, s, role);
		int gz = Layout.gateZ(Settlements.round(s));
		BlockPos gateEnd = at(s, Layout.gateX(), gz - 12);
		BlockPos squareEnd = at(s, Layout.gateX(), 30);
		for (int i = riders.size(); i < want; i++) {
			BlockPos p = floor(level, at(s, Layout.gateX() + (i % 2 == 0 ? -2 : 2), 34 + i * 6), s.heart().getY());
			if (p == null) {
				continue;
			}
			Mob horse = spawn(level, s, "horse", "animal|" + s.id() + "|knight_horse", p);
			if (horse == null) {
				continue;
			}
			// the horse's armour grows with the barracks: leather in the kingdom colour, then iron, then diamond (user,
			// 2026-11-26: real horse armour, the colour only on leather)
			int barracks = Math.max(1, Math.min(3, s.level(Progression.BARRACKS)));
			int rgb = net.minecraft.world.item.DyeColor.byId(s.color()).getTextureDiffuseColor() & 0xFFFFFF;
			horse.setItemSlot(EquipmentSlot.BODY, barracks == 1 ? KingdomNpc.dyed(Items.LEATHER_HORSE_ARMOR, rgb)
					: new ItemStack(barracks == 2 ? Items.IRON_HORSE_ARMOR : Items.DIAMOND_HORSE_ARMOR));
			KingdomNpc knight = People.visitor(level, s, "knight", p);
			if (knight == null) {
				horse.discard();
				continue;
			}
			// a mounted guard: the garrison's look (coloured helmet, armour of the barracks), a lance (spear) and a shield
			knight.dressGuard(s.style(), barracks, false, s.color(), "Knight", People.namesInUse(level, s));
			knight.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(barracks >= 3 ? Items.DIAMOND_SPEAR : Items.IRON_SPEAR));
			knight.setItemSlot(EquipmentSlot.OFFHAND, knight.shield(s.color()));
			knight.setNoAi(true);
			knight.startRiding(horse);
		}
		// the ride: each horse heads for the far end of the street, then turns back, in its own lane and at its own
		// distance from the ends (user, 2026-11-25: the knights stood bunched in one spot, all riding to the same block)
		for (int i = 0; i < riders.size(); i++) {
			Entity e = riders.get(i);
			if (!(e.getVehicle() instanceof Mob horse)) {
				e.discard(); // a knight without his horse goes back to the barracks
				continue;
			}
			int bl = Math.max(1, Math.min(3, s.level(Progression.BARRACKS)));
			net.minecraft.world.item.component.DyedItemColor kd = e instanceof KingdomNpc kk
					? kk.getItemBySlot(EquipmentSlot.HEAD).get(net.minecraft.core.component.DataComponents.DYED_COLOR) : null;
			int want = net.minecraft.world.item.DyeColor.byId(s.color()).getTextureDiffuseColor() & 0xFFFFFF;
			if (e instanceof KingdomNpc k && (k.guardArmor() != bl || !k.getItemBySlot(EquipmentSlot.HEAD).is(Items.LEATHER_HELMET)
					|| kd == null || kd.rgb() != want)) {
				// knights from before (all iron) and after a barracks upgrade get the garrison's look
				k.dressGuard(s.style(), bl, false, s.color(), "Knight");
				k.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(bl >= 3 ? Items.DIAMOND_SPEAR : Items.IRON_SPEAR));
				k.setItemSlot(EquipmentSlot.OFFHAND, k.shield(s.color()));
				int rgb = net.minecraft.world.item.DyeColor.byId(s.color()).getTextureDiffuseColor() & 0xFFFFFF;
				horse.setItemSlot(EquipmentSlot.BODY, bl == 1 ? KingdomNpc.dyed(Items.LEATHER_HORSE_ARMOR, rgb)
						: new ItemStack(bl == 2 ? Items.IRON_HORSE_ARMOR : Items.DIAMOND_HORSE_ARMOR));
			}
			int lane = i % 2 == 0 ? -2 : 2;
			BlockPos a = floorOr(level, at(s, Layout.gateX() + lane, gz - 12 - (i / 2) * 6), gateEnd);
			BlockPos b = floorOr(level, at(s, Layout.gateX() + lane, 30 + (i / 2) * 6), squareEnd);
			BlockPos target = horse.distanceToSqr(Vec3.atCenterOf(a)) < 9 ? b
					: horse.distanceToSqr(Vec3.atCenterOf(b)) < 9 ? a
					: horse.getNavigation().isDone() ? (horse.distanceToSqr(Vec3.atCenterOf(a)) < horse.distanceToSqr(Vec3.atCenterOf(b)) ? b : a) : null;
			if (target != null) {
				horse.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
			}
		}
		// never two knights in one spot: one that stands on another rides on
		for (int i = 0; i < riders.size(); i++) {
			for (int j = i + 1; j < riders.size(); j++) {
				if (riders.get(i).getVehicle() instanceof Mob h1 && riders.get(j).getVehicle() instanceof Mob h2 && h1.distanceToSqr(h2) < 4) {
					h2.getNavigation().stop();
				}
			}
		}
	}

	private static BlockPos floorOr(ServerLevel level, BlockPos p, BlockPos fallback) {
		BlockPos f = floor(level, p, p.getY());
		return f != null ? f : fallback;
	}

	/** The animals the buildings came with: whatever went missing comes back. */
	private static void templates(ServerLevel level, Settlement s) {
		for (String track : Progression.ORDER) {
			Progression.Step step = Progression.current(s, track);
			if (step == null) {
				continue;
			}
			String job = Settlements.template(s, step.building());
			Template t = Template.get(job);
			if (t == null || t.animals.isEmpty()) {
				continue;
			}
			Map<Identifier, Integer> want = new HashMap<>();
			for (Template.Animal a : t.animals) {
				want.merge(a.type(), 1, Integer::sum);
			}
			for (Map.Entry<Identifier, Integer> w : want.entrySet()) {
				String role = "animal|" + s.id() + "|" + w.getKey().getPath();
				int have = tagged(level, s, role).size();
				if (have >= w.getValue()) {
					continue;
				}
				for (Template.Animal a : t.animals) {
					if (have >= w.getValue()) {
						break;
					}
					if (!a.type().equals(w.getKey())) {
						continue;
					}
					BlockPos pos = s.heart().offset(t.offset(a.pos(), Rotation.values()[s.rotation()])).above(Settlements.lift(s, job));
					BlockPos p = floor(level, pos, pos.getY());
					if (p != null && spawn(level, s, a.type().getPath(), role, p) != null) {
						have++;
					}
				}
			}
		}
	}

	/** Now and then a pack donkey and a llama bring goods in from the gate to the storehouse, then go back out. */
	private static void caravan(ServerLevel level, Settlement s) {
		if (s.level(Progression.STOREHOUSE) <= 0 || s.level(Progression.WOOD) <= 0 && s.level("mine") <= 0) {
			return;
		}
		String role = "animal|" + s.id() + "|caravan";
		List<Entity> have = tagged(level, s, role);
		long now = level.getGameTime();
		BlockPos store = at(s, -26, 62);
		if (!have.isEmpty()) {
			for (Entity e : have) {
				Mob m = (Mob) e;
				if (m.distanceToSqr(Vec3.atCenterOf(store)) < 16 || now - CARAVAN.getOrDefault(s.id(), now) > 6000) {
					m.discard();
				} else if (m.getNavigation().isDone()) {
					m.getNavigation().moveTo(store.getX() + 0.5, store.getY(), store.getZ() + 0.5, 0.9);
				}
			}
			return;
		}
		if (now - CARAVAN.getOrDefault(s.id(), now - 12000) < 12000) {
			return;
		}
		CARAVAN.put(s.id(), now);
		int gz = Layout.gateZ(Settlements.round(s));
		BlockPos in = floor(level, at(s, Layout.gateX(), gz - 4), s.heart().getY());
		if (in == null) {
			return;
		}
		for (String type : new String[] {"donkey", "llama"}) {
			Mob m = spawn(level, s, type, role, in);
			if (m != null) {
				m.setCustomName(Component.translatable("kingdomquest.animal.caravan"));
				BlockPos target = store.atY(in.getY());
				m.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.9);
			}
		}
	}

	/** Stable breeding: the lord buys a horse; the stables' level makes it faster, higher jumping and tougher. */
	static void buyHorse(ServerPlayer player, Settlement s) {
		int lvl = s.level(Progression.STABLES);
		if (lvl <= 0) {
			player.sendOverlayMessage(Component.translatable("kingdomquest.animal.no_stables"));
			return;
		}
		int price = 8;
		int have = 0;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			if (player.getInventory().getItem(i).is(Items.EMERALD)) {
				have += player.getInventory().getItem(i).getCount();
			}
		}
		if (have < price) {
			player.sendOverlayMessage(Component.translatable("kingdomquest.animal.horse_price", price));
			return;
		}
		int[] b = Landmarks.bounds(s, Progression.STABLES);
		BlockPos col = b == null ? player.blockPosition() : new BlockPos((b[0] + b[2]) / 2, 0, b[3] + 2);
		ServerLevel level = player.level().getServer().overworld();
		BlockPos p = floor(level, col, s.heart().getY());
		if (p == null) {
			p = player.blockPosition();
		}
		int removed = 0;
		for (int i = 0; i < player.getInventory().getContainerSize() && removed < price; i++) {
			ItemStack st = player.getInventory().getItem(i);
			if (st.is(Items.EMERALD)) {
				int take = Math.min(price - removed, st.getCount());
				st.shrink(take);
				removed += take;
			}
		}
		double speed = Math.min(0.3375, 0.2 + 0.03 * lvl);
		double jump = Math.min(1.0, 0.55 + 0.1 * lvl);
		int health = 18 + 2 * lvl;
		int[] owner = UUIDUtil.uuidToIntArray(player.getUUID());
		String nbt = String.format(java.util.Locale.ROOT,
				"{Tame:1b,Owner:[I;%d,%d,%d,%d],Variant:%d,Health:%d.0f,attributes:[{id:\"minecraft:movement_speed\",base:%.4f},"
						+ "{id:\"minecraft:jump_strength\",base:%.3f},{id:\"minecraft:max_health\",base:%d.0}],equipment:{saddle:{id:\"minecraft:saddle\",count:1}},"
						+ "Tags:[\"kingdomquest_bred\"]}",
				owner[0], owner[1], owner[2], owner[3], level.getRandom().nextInt(7) + 256 * level.getRandom().nextInt(5), health, speed, jump, health);
		MinecraftServer server = player.level().getServer();
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
				"summon minecraft:horse " + p.getX() + " " + p.getY() + " " + p.getZ() + " " + nbt);
		player.sendSystemMessage(Component.translatable("kingdomquest.animal.horse_bought", s.name()));
	}
}
