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
			new Spec("main", 9, "Warchief", "iron_sword", 12, 64, ""),
			new Spec("farm", 1, "Farmer", "iron_hoe", 26, 11, "The fields are growing well. The harvest goes to the storehouse."),
			new Spec("farm", 2, "Shepherd", "shears", 39, 11, "The sheep are fat and happy. Wool, eggs and meat for the storehouse."),
			new Spec("stables", 1, "Stable Hand", "lead", 47, 25, "The horses are brushed and fed, my lord."),
			new Spec("apiary", 1, "Beekeeper", "glass_bottle", 52, 11, "Mind the bees! The honey goes to the storehouse."),
			new Spec("smith", 1, "Blacksmith", "iron_ingot", -12, 9, "Iron in, steel out. Fresh ingots go to the storehouse."),
			new Spec("wood", 1, "Lumberjack", "iron_axe", -29, 20, "Plenty of logs stacked and ready."),
			new Spec("library", 1, "Librarian", "book", -28, 5, "Knowledge is power. More shelves, stronger enchantments."),
			new Spec("brewery", 1, "Alchemist", "blaze_rod", -16, 25, "Healing draughts are brewing. Don't drink the green one."),
			new Spec("barracks", 1, "Guard Captain", "iron_sword", 16, 34, "The guards are drilled and ready."),
			new Spec("tavern", 1, "Innkeeper", "honey_bottle", 28, -13, "Welcome, my lord! Travelers bring news from afar."),
			new Spec("market", 1, "Merchant", "emerald", 46, -15, "Fine wares from every corner of the land."),
			new Spec("cartographer", 1, "Cartographer", "map", 62, -13, "Every map I draw shows a little more of the world."),
			new Spec("music", 1, "Bard", "note_block", -37, -19, "Shall I play you a song of your victories?"),
			new Spec("workshop", 1, "Tinkerer", "redstone", -42, 29, "Copper, redstone and a little bit of magic."),
			new Spec("armorer", 1, "Armorer", "iron_chestplate", -20, 37, "Good armor saves lives. And it can look splendid, too."),
			new Spec("tannery", 1, "Tanner", "leather", 30, 37, "Hides cured and ready. Don't mind the smell."),
			new Spec("archaeology", 1, "Archaeologist", "brush", 63, 23, "Every sherd tells a story of the old world."),
			new Spec("mine", 1, "Miner", "iron_pickaxe", 65, 11, "The deep galleries are rich today."),
			new Spec("nether", 1, "Nether Trader", "gold_ingot", -43, 14, "The piglins drive a hard bargain, but gold speaks every language."),
			new Spec(Progression.STOREHOUSE, 1, "Storekeeper", "writable_book", Storehouse.FROM_TEMPLATE, Storehouse.FROM_TEMPLATE,
					"Everything that comes in is written in the ledger, my lord. It lies on the lectern by the door."),
			new Spec("dock", 1, "Fisher", "fishing_rod", 0, 0, "The fish are biting! The catch goes to the storehouse."),
			new Spec("mill", 1, "Miller", "wheat", -103, 35, "The sails are turning. Flour and sugar for the storehouse."),
			new Spec("bakery", 1, "Baker", "bread", -67, -75, "Fresh from the oven, my lord. Mind, it's hot."),
			new Spec("herbs", 1, "Herbalist", "sweet_berries", -84, 79, "Every herb has its use, if you know where to look."),
			new Spec("weaver", 1, "Weaver", "string", -50, -75, "The looms never rest. Cloth for the whole kingdom."),
			new Spec("dyehouse", 1, "Dyer", "red_dye", -33, -75, "Red from poppies, blue from cornflowers. Every colour has its flower."),
			new Spec("carpenter", 1, "Carpenter", "crafting_table", -16, -75, "Measure twice, cut once."),
			new Spec("sawmill", 1, "Sawyer", "iron_axe", 26, -75, "Logs in, planks out. The saw sings all day."),
			new Spec("smelter", 1, "Smelter", "coal", 92, 74, "The furnaces are roaring. Ingots for the storehouse."),
			new Spec("saddler", 1, "Saddler", "leather", 36, 33, "A good saddle makes a good rider, my lord."),
			new Spec("quarry", 1, "Quarryman", "stone_pickaxe", -203, 2, "Good stone, my lord. The walls will stand a thousand years."),
			new Spec("lumbercamp", 1, "Forester", "iron_axe", 203, -16, "For every tree we fell, we plant two."),
			new Spec("minecamp", 1, "Prospector", "iron_pickaxe", 203, 43, "The seams run deep here. Mind the shaft."),
			new Spec(Progression.TOWNHALL, 1, "Chronicler", "writable_book", 55, 8, "Every deed of the realm goes into my book. Shall I read to you?"));

	/** Inside the houses (base frame): main house, then the three houses on the square. */
	private static final int[][] HOMES = {{7, 1}, {-6, 35}, {20, 35}, {-18, 35}};

	private Workers() {
	}

	private static String tag(Settlement s, Spec spec) {
		return "worker|" + s.id() + "|" + spec.name();
	}

	static BlockPos home(Settlement s, Spec spec) {
		if (spec.track().equals(Progression.DOCK)) {
			return Settlements.dockPoint(s, 0, 1, 6);
		}
		if (spec.track().equals(Progression.STOREHOUSE) && Storehouse.keeperPost(s) != null) {
			return Storehouse.keeperPost(s);
		}
		return io.github.sotadevop.kingdomquest.building.Layout.at(s.heart(), Rotation.values()[s.rotation()], spec.track(), spec.x(),
				Settlements.liftAt(s, spec.track(), spec.x(), spec.z()), spec.z()).offset(Settlements.outpostShift(s, spec.track()));
	}

	/** Every 10 seconds: workers appear at their buildings, wanderers go back, workers of removed buildings leave. */
	static void tick(ServerLevel level, Settlement s) {
		if (!level.isLoaded(s.heart())) {
			return;
		}
		// the outposts' workers live 200 and more blocks out: the whole realm around the heart is looked through (user,
		// 2026-11-25: hundreds of quarrymen on one block, a new one every 10 seconds because the old ones weren't found)
		List<KingdomNpc> all = level.getEntitiesOfClass(KingdomNpc.class, new AABB(s.heart()).inflate(320, 96, 320),
				n -> n.getAttached(People.ROLE) != null && n.getAttached(People.ROLE).startsWith("worker|" + s.id()));
		for (Spec spec : SPECS) {
			boolean wanted = s.level(spec.track()) >= spec.minLevel();
			String tag = tag(s, spec);
			List<KingdomNpc> mine = all.stream().filter(n -> tag.equals(n.getAttached(People.ROLE))).toList();
			for (int i = 1; i < mine.size(); i++) {
				mine.get(i).discard(); // one of each, ever
			}
			KingdomNpc npc = mine.isEmpty() ? null : mine.get(0);
			BlockPos home = home(s, spec);
			if (npc == null && wanted && !level.isPositionEntityTicking(home)) {
				continue; // his workplace isn't running: he may be there unseen, and comes when the land is near
			}
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
				// the steward runs the settlement for the lord: he brings what the stores hold, reports damage
				// and what can be built, and opens the kingdom's book wherever he stands
				ServerLevel lvl = player.level().getServer().overworld();
				int[] got = Production.collect(lvl, s, player);
				if (got[0] > 0) {
					People.say(player, name, "I have made my round, my lord: " + got[0] + " goods from " + got[1] + (got[1] == 1 ? " store." : " stores."));
				}
				int broken = Settlements.damaged(lvl, s).size();
				if (broken > 0) {
					People.say(player, name, broken + " blocks of our buildings are damaged. Order the repair in the book.");
				}
				Settlements.stewardAdvice(player, s);
				if (s.owner().equals(player.getUUID())) {
					Settlements.sendInfo(player, s);
				}
			} else {
				People.say(player, name, Defense.status(player.level().getServer(), s));
			}
			return true;
		}
		for (Spec spec : SPECS) {
			if (spec.name().equals(name)) {
				People.say(player, name, spec.line());
				Settlement here = name.equals("Chronicler") ? Settlements.nearestOwned(player) : null;
				if (here != null) {
					Chronicle.send(player, here);
				}
				return true;
			}
		}
		return false;
	}
}
