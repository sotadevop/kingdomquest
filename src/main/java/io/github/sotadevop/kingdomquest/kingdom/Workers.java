package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.building.Template;
import io.github.sotadevop.kingdomquest.npc.KingdomNpc;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * One worker per building: they stay at their workplace, carry the tool of their trade and tell the lord what
 * they do when talked to. Spawned when the building exists, removed when it doesn't.
 */
final class Workers {
	record Spec(String track, int minLevel, String name, String tool, int x, int z, String line) {
	}

	static final List<Spec> SPECS = List.of(
			new Spec("main", 4, "Steward", "writable_book", 9, 8, ""),
			new Spec("main", 9, "Warchief", "iron_sword", 12, 44, ""),
			new Spec("farm", 1, "Farmer", "iron_hoe", 26, 11, "The fields are growing well. The harvest goes into the barrel by the gate."),
			new Spec("farm", 2, "Shepherd", "shears", 39, 11, "The sheep are fat and happy. Wool, eggs and meat for the storehouse."),
			new Spec("stables", 1, "Stable Hand", "lead", 47, 25, "The horses are brushed and fed, my lord."),
			new Spec("apiary", 1, "Beekeeper", "glass_bottle", 52, 11, "Mind the bees! The honey is in the barrel."),
			new Spec("smith", 1, "Blacksmith", "iron_ingot", -12, 9, "Iron in, steel out. Fresh ingots in the barrel."),
			new Spec("wood", 1, "Lumberjack", "iron_axe", -26, 20, "Plenty of logs stacked and ready."),
			new Spec("library", 1, "Librarian", "book", -28, 5, "Knowledge is power. More shelves, stronger enchantments."),
			new Spec("brewery", 1, "Alchemist", "blaze_rod", -17, 24, "Healing draughts are brewing. Don't drink the green one."),
			new Spec("barracks", 1, "Guard Captain", "iron_sword", 16, 32, "The guards are drilled and ready."),
			new Spec("tavern", 1, "Innkeeper", "honey_bottle", 28, -13, "Welcome, my lord! Travelers bring news from afar."),
			new Spec("market", 1, "Merchant", "emerald", 45, -15, "Fine wares from every corner of the land."),
			new Spec("cartographer", 1, "Cartographer", "map", 60, -13, "Every map I draw shows a little more of the world."),
			new Spec("music", 1, "Bard", "note_block", -37, -19, "Shall I play you a song of your victories?"),
			new Spec("workshop", 1, "Tinkerer", "redstone", -42, 29, "Copper, redstone and a little bit of magic."),
			new Spec("armorer", 1, "Armorer", "iron_chestplate", -20, 37, "Good armor saves lives. And it can look splendid, too."),
			new Spec("tannery", 1, "Tanner", "leather", 30, 37, "Hides cured and ready. Don't mind the smell."),
			new Spec("archaeology", 1, "Archaeologist", "brush", 63, 23, "Every sherd tells a story of the old world."),
			new Spec("mine", 1, "Miner", "iron_pickaxe", 64, 11, "The deep galleries are rich today."),
			new Spec("nether", 1, "Nether Trader", "gold_ingot", -43, 14, "The piglins drive a hard bargain, but gold speaks every language."),
			new Spec("dock", 1, "Fisher", "fishing_rod", 0, 0, "The fish are biting! The catch is in the barrel on the pier."));

	/** Inside the houses (base frame): main house, then the three houses on the street. */
	private static final int[][] HOMES = {{7, 1}, {-5, 26}, {19, 26}, {31, 26}};

	private Workers() {
	}

	private static String tag(Settlement s, Spec spec) {
		return "worker|" + s.id() + "|" + spec.name();
	}

	static BlockPos home(Settlement s, Spec spec) {
		if (spec.track().equals(Progression.DOCK)) {
			return Settlements.dockPoint(s, 0, 1, 6);
		}
		return s.heart().offset(Template.rotate(new BlockPos(spec.x() - 7, 0, spec.z() - 10), Rotation.values()[s.rotation()]));
	}

	/** Every 10 seconds: workers appear at their buildings, wanderers go back, workers of removed buildings leave. */
	static void tick(ServerLevel level, Settlement s) {
		if (!level.isLoaded(s.heart())) {
			return;
		}
		List<KingdomNpc> all = level.getEntitiesOfClass(KingdomNpc.class, new AABB(s.heart()).inflate(120, 64, 120),
				n -> n.getAttached(People.ROLE) != null && n.getAttached(People.ROLE).startsWith("worker|" + s.id()));
		for (Spec spec : SPECS) {
			boolean wanted = s.level(spec.track()) >= spec.minLevel();
			String tag = tag(s, spec);
			KingdomNpc npc = all.stream().filter(n -> tag.equals(n.getAttached(People.ROLE))).findFirst().orElse(null);
			BlockPos home = home(s, spec);
			if (!wanted) {
				if (npc != null) {
					npc.discard();
				}
				continue;
			}
			if (npc == null) {
				npc = KingdomNpc.TYPE.create(level, EntitySpawnReason.MOB_SUMMONED);
				if (npc == null) {
					continue;
				}
				npc.setup("worker", Math.floorMod(spec.name().hashCode(), 9), s.owner());
				Item tool = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(spec.tool()));
				npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new net.minecraft.world.item.ItemStack(tool));
				npc.setCustomName(Component.literal(spec.name()));
				npc.setAttached(People.ROLE, tag);
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, home.getX(), home.getZ());
				npc.snapTo(home.getX() + 0.5, y, home.getZ() + 0.5, 0, 0);
				level.addFreshEntity(npc);
			} else if (level.getSkyDarken() >= 4) {
				// night: everyone goes home to the houses around the square
				BlockPos bed = s.heart().offset(Template.rotate(new BlockPos(HOMES[Math.floorMod(spec.name().hashCode(), HOMES.length)][0] - 7, 0,
						HOMES[Math.floorMod(spec.name().hashCode(), HOMES.length)][1] - 10), Rotation.values()[s.rotation()]));
				npc.getNavigation().moveTo(bed.getX() + 0.5, s.heart().getY() + 1, bed.getZ() + 0.5, 0.7);
			} else if (npc.distanceToSqr(Vec3.atCenterOf(home)) > 10 * 10) {
				npc.getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0.8);
			} else if (level.getRandom().nextInt(3) == 0) {
				// potter around the workplace
				npc.getNavigation().moveTo(home.getX() + 0.5 + level.getRandom().nextInt(7) - 3, home.getY(),
						home.getZ() + 0.5 + level.getRandom().nextInt(7) - 3, 0.5);
				npc.swingArm();
			}
		}
	}

	static boolean talk(ServerPlayer player, KingdomNpc npc) {
		String role = npc.getAttached(People.ROLE);
		if (role == null || !role.startsWith("worker|")) {
			return false;
		}
		String name = role.substring(role.lastIndexOf('|') + 1);
		if (name.equals("Steward") || name.equals("Warchief")) {
			Settlement s = Settlements.nearestOwned(player);
			if (s == null) {
				return true;
			}
			if (name.equals("Steward")) {
				Settlements.stewardAdvice(player, s);
			} else {
				People.say(player, name, Defense.status(player.level().getServer(), s));
			}
			return true;
		}
		for (Spec spec : SPECS) {
			if (spec.name().equals(name)) {
				People.say(player, name, spec.line());
				return true;
			}
		}
		return false;
	}
}
