package io.github.sotadevop.kingdomquest.kingdom;

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

	/**
	 * No two workers share a name (user, 2026-11-26): a worker whose generated name another already has gets a new one
	 * (names the lord gave are kept).
	 */
	private static void unique(ServerLevel level, Settlement s, List<KingdomNpc> all) {
		java.util.Set<String> seen = new java.util.HashSet<>();
		java.util.Set<String> used = null;
		for (KingdomNpc n : all) {
			if (n.getCustomName() == null) {
				continue;
			}
			String label = n.getCustomName().getString();
			String name = io.github.sotadevop.kingdomquest.npc.Folk.nameOf(label);
			String role = io.github.sotadevop.kingdomquest.npc.Folk.roleOf(label);
			String tag = n.getAttached(People.ROLE);
			if (!seen.add(name) && role != null && !s.names().containsKey("npcname|" + tag)) {
				if (used == null) {
					used = People.namesInUse(level, s);
				}
				used.addAll(seen);
				int region = io.github.sotadevop.kingdomquest.npc.Folk.region(s.style());
				int base = KingdomNpc.skinBase(n.skin());
				boolean female = io.github.sotadevop.kingdomquest.npc.Folk.generated(base)
						&& io.github.sotadevop.kingdomquest.npc.Folk.female(io.github.sotadevop.kingdomquest.npc.Folk.outfitOf(base));
				String fresh = io.github.sotadevop.kingdomquest.npc.Folk.name(region, female, tag.hashCode() >>> 7, used);
				n.setCustomName(Component.literal(io.github.sotadevop.kingdomquest.npc.Folk.label(fresh, role)));
				seen.add(fresh);
				used.add(fresh);
			}
		}
	}

	/** The look and name tag of a worker: a face and name of the region, man or woman as the job was held. */
	private static void dress(KingdomNpc npc, Settlement s, String tag, String job, java.util.Set<String> used) {
		int seed = tag.hashCode();
		int region = io.github.sotadevop.kingdomquest.npc.Folk.region(s.style());
		int outfit = io.github.sotadevop.kingdomquest.npc.Folk.outfit(job);
		boolean female = io.github.sotadevop.kingdomquest.npc.Folk.female(outfit);
		npc.setSkin(io.github.sotadevop.kingdomquest.npc.Folk.skin(region, outfit, seed >>> 3));
		String own = s.names().get("npcname|" + tag); // a name the lord gave him
		String name = own != null ? own : io.github.sotadevop.kingdomquest.npc.Folk.name(region, female, seed >>> 7, used);
		used.add(name);
		npc.setCustomName(Component.literal(io.github.sotadevop.kingdomquest.npc.Folk.label(name, job)));
	}

	/**
	 * Where a worker stands at his workplace: on the floor inside the building near {@code home} (user, 2026-11-26:
	 * many stood on the roofs), under a roof where there is one; null if no place is free.
	 */
	static BlockPos floorNear(ServerLevel level, BlockPos home) {
		int[] dys = {0, 1, -1, 2, -2, 3, -3, 4, -4};
		for (boolean indoors : new boolean[] {true, false}) {
			for (int r = 0; r <= 3; r++) {
				for (int dx = -r; dx <= r; dx++) {
					for (int dz = -r; dz <= r; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
							continue;
						}
						for (int dy : dys) {
							BlockPos feet = home.offset(dx, dy, dz);
							if (standable(level, feet) && (!indoors || !level.canSeeSky(feet.above()))) {
								return feet;
							}
						}
					}
				}
			}
		}
		return null;
	}

	private static boolean standable(ServerLevel level, BlockPos feet) {
		return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty() && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
				&& level.getFluidState(feet).isEmpty() && !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()
				&& level.getFluidState(feet.below()).isEmpty();
	}

	/** Per settlement: the spots beside the beds of its town houses (housewives live there), found once per main level. */
	private static final java.util.Map<String, List<BlockPos>> HOUSE_SPOTS = new java.util.HashMap<>();
	private static final int HOUSEWIVES = 8;

	private static List<BlockPos> houseSpots(ServerLevel level, Settlement s) {
		String key = s.id() + "|" + s.level(Settlement.MAIN) + "|" + s.built().getOrDefault(Settlement.MAIN, List.of()).size();
		return HOUSE_SPOTS.computeIfAbsent(key, k -> {
			List<BlockPos> out = new java.util.ArrayList<>();
			Rotation r = Rotation.values()[s.rotation()];
			BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
			for (int[] h : io.github.sotadevop.kingdomquest.building.Layout.houses(s.style())) {
				if (out.size() >= HOUSEWIVES) {
					break;
				}
				BlockPos a = s.heart().offset(io.github.sotadevop.kingdomquest.building.Layout.rel(r, h[0], 0, h[1]));
				BlockPos b = s.heart().offset(io.github.sotadevop.kingdomquest.building.Layout.rel(r, h[2], 0, h[3]));
				BlockPos bed = null;
				for (int x = Math.min(a.getX(), b.getX()); x <= Math.max(a.getX(), b.getX()) && bed == null; x++) {
					for (int z = Math.min(a.getZ(), b.getZ()); z <= Math.max(a.getZ(), b.getZ()) && bed == null; z++) {
						if (!level.isLoaded(q.set(x, 0, z))) {
							continue;
						}
						for (int y = s.heart().getY() - 8; y <= s.heart().getY() + 20; y++) {
							if (level.getBlockState(q.set(x, y, z)).getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
								bed = q.immutable();
								break;
							}
						}
					}
				}
				if (bed != null) {
					BlockPos spot = floorNear(level, bed);
					if (spot != null) {
						out.add(spot);
					}
				}
			}
			return out;
		});
	}

	private static final String[] HOUSEWIFE_LINES = {"The hearth is warm and the pottage is on, my lord.",
			"The children are at the well and the bread is rising.", "Mind the floor, I've only just swept it.",
			"The hens laid well today. Eggs for the whole lane."};

	/** Keeps a worker at his place: he comes back if he wandered, and potters about the room. */
	private static void keep(ServerLevel level, KingdomNpc npc, BlockPos spot) {
		double d = npc.distanceToSqr(Vec3.atBottomCenterOf(spot));
		if (d > 24 * 24 || d > 3 * 3 && level.canSeeSky(npc.blockPosition().above()) && !level.canSeeSky(spot.above())) {
			// far off, or up on the roof of his own building: put back inside
			npc.getNavigation().stop();
			npc.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, npc.getYRot(), 0);
		} else if (d > 5 * 5) {
			npc.getNavigation().moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0.8);
		} else if (level.getRandom().nextInt(3) == 0) {
			BlockPos to = floorNear(level, spot.offset(level.getRandom().nextInt(5) - 2, 0, level.getRandom().nextInt(5) - 2));
			if (to != null && to.distSqr(spot) <= 9 && level.canSeeSky(to.above()) == level.canSeeSky(spot.above())) {
				npc.getNavigation().moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, 0.5);
			}
			npc.swingArm();
		}
	}

	private static KingdomNpc spawnAt(ServerLevel level, Settlement s, String tag, String job, Item tool, BlockPos spot) {
		KingdomNpc npc = KingdomNpc.TYPE.create(level, EntitySpawnReason.MOB_SUMMONED);
		if (npc == null) {
			return null;
		}
		npc.setup("worker", 0, s.owner());
		if (tool != null) {
			npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new net.minecraft.world.item.ItemStack(tool));
		}
		npc.setAttached(People.ROLE, tag);
		dress(npc, s, tag, job, People.namesInUse(level, s));
		npc.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, level.getRandom().nextFloat() * 360, 0);
		level.addFreshEntity(npc);
		return npc;
	}

	/**
	 * Every 10 seconds: workers appear at their buildings, wanderers go back, workers of removed buildings leave. They
	 * stay at their work inside the building, day and night (user, 2026-11-26). Housewives keep the town houses.
	 */
	static void tick(ServerLevel level, Settlement s) {
		if (!level.isLoaded(s.heart())) {
			return;
		}
		// the outposts' workers live 200 and more blocks out: the whole realm around the heart is looked through (user,
		// 2026-11-25: hundreds of quarrymen on one block, a new one every 10 seconds because the old ones weren't found)
		List<KingdomNpc> all = level.getEntitiesOfClass(KingdomNpc.class, new AABB(s.heart()).inflate(320, 96, 320),
				n -> n.getAttached(People.ROLE) != null && n.getAttached(People.ROLE).startsWith("worker|" + s.id()));
		unique(level, s, all);
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
			BlockPos spot = floorNear(level, home);
			if (spot == null) {
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, home.getX(), home.getZ());
				spot = new BlockPos(home.getX(), y, home.getZ());
			}
			if (npc == null) {
				Item tool = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(spec.tool()));
				spawnAt(level, s, tag, spec.name(), tool, spot);
				continue;
			}
			if (npc.getCustomName() == null || io.github.sotadevop.kingdomquest.npc.Folk.roleOf(npc.getCustomName().getString()) == null
					|| !io.github.sotadevop.kingdomquest.npc.Folk.generated(KingdomNpc.skinBase(npc.skin()))) {
				dress(npc, s, tag, spec.name(), People.namesInUse(level, s)); // workers from before regional people
			}
			keep(level, npc, spot);
		}
		// the housewives: one in each of the first town houses with a bed
		List<BlockPos> spots = s.level(Settlement.MAIN) >= 4 ? houseSpots(level, s) : List.of();
		for (int i = 0; i < HOUSEWIVES; i++) {
			String tag = "worker|" + s.id() + "|Housewife#" + i;
			List<KingdomNpc> mine = all.stream().filter(n -> tag.equals(n.getAttached(People.ROLE))).toList();
			for (int k = 1; k < mine.size(); k++) {
				mine.get(k).discard();
			}
			KingdomNpc npc = mine.isEmpty() ? null : mine.get(0);
			if (i >= spots.size()) {
				if (npc != null) {
					npc.discard();
				}
				continue;
			}
			BlockPos spot = spots.get(i);
			if (npc == null) {
				if (level.isPositionEntityTicking(spot)) {
					spawnAt(level, s, tag, "Housewife", null, spot);
				}
				continue;
			}
			keep(level, npc, spot);
		}
	}

	static boolean talk(ServerPlayer player, KingdomNpc npc) {
		String role = npc.getAttached(People.ROLE);
		if (role == null || !role.startsWith("worker|")) {
			return false;
		}
		String name = role.substring(role.lastIndexOf('|') + 1);
		String who = npc.getCustomName() == null ? name : io.github.sotadevop.kingdomquest.npc.Folk.nameOf(npc.getCustomName().getString());
		if (name.startsWith("Housewife")) {
			People.say(player, who, HOUSEWIFE_LINES[Math.floorMod(npc.getUUID().hashCode() + (int) (player.level().getGameTime() / 2400), HOUSEWIFE_LINES.length)]);
			return true;
		}
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
					// the steward runs the kingdom, the architect builds it (user, 2026-11-26): he opens the administration
					Settlements.sendInfo(player, s, true);
				}
			} else {
				People.say(player, name, Defense.status(player.level().getServer(), s));
			}
			return true;
		}
		for (Spec spec : SPECS) {
			if (spec.name().equals(name)) {
				People.say(player, who, spec.line());
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
