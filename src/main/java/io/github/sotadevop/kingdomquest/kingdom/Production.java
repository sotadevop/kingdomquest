package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.building.Template;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Buildings that produce goods put them into their storage container (the template's "storage" barrel).
 * Runs every {@link #INTERVAL} ticks while the settlement is loaded; a full container loses the overflow.
 * Main track from Hamlet II on: taxes into the barrel by the fire.
 */
public final class Production {
	/** 5 minutes. */
	public static final int INTERVAL = 6000;

	private static final List<Item> SMITH_GEAR = List.of(Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS,
			Items.IRON_SWORD, Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_SHOVEL, Items.SHIELD, Items.BUCKET, Items.SHEARS);

	private Production() {
	}

	static void run(ServerLevel level, Settlement s) {
		if (!level.isLoaded(s.heart())) {
			return;
		}
		Progression.Step gate = Progression.current(s, Progression.GATE);
		if (gate != null && gate.level() >= 4 && !Progression.GATE.equals(s.jobTrack())) {
			Template t = Template.get(s.style() + "/" + gate.building());
			if (t != null) {
				armTraps(level, s, t.placements(Settlements.dye(s.color()), Rotation.values()[s.rotation()]));
			}
		}
		boolean any = false;
		for (String track : Progression.ORDER) {
			Progression.Step step = Progression.current(s, track);
			if (step == null || track.equals(s.jobTrack())) {
				continue;
			}
			List<ItemStack> goods = goods(track, step.level(), s.style(), level.getRandom());
			Container box = goods.isEmpty() ? null : storage(level, s, s.style() + "/" + step.building());
			if (box != null) {
				goods.forEach(stack -> insert(box, stack));
				box.setChanged();
				any = true;
			}
		}
		ServerPlayer owner = level.getServer().getPlayerList().getPlayer(s.owner());
		if (any && owner != null && owner.distanceToSqr(Vec3.atCenterOf(s.heart())) < 64 * 64) {
			owner.sendOverlayMessage(Component.translatable("kingdomquest.production", s.name()));
		}
	}

	/** Fills every dispenser among the given placements with arrows (gate traps), up to a full stack each. */
	static void armTraps(ServerLevel level, Settlement s, List<io.github.sotadevop.kingdomquest.building.Template.Placement> placements) {
		for (var p : placements) {
			if (p.state().getBlock() instanceof net.minecraft.world.level.block.DispenserBlock
					&& level.getBlockEntity(s.heart().offset(p.offset())) instanceof Container c) {
				int have = 0;
				for (int i = 0; i < c.getContainerSize(); i++) {
					have += c.getItem(i).is(Items.ARROW) ? c.getItem(i).getCount() : 0;
				}
				if (have < 64) {
					insert(c, new ItemStack(Items.ARROW, 64 - have));
					c.setChanged();
				}
			}
		}
	}

	static List<ItemStack> goods(String track, int lvl, String style, RandomSource random) {
		List<ItemStack> g = new ArrayList<>();
		switch (track) {
			case Settlement.MAIN -> {
				if (lvl >= 5) { // taxes
					g.add(new ItemStack(Items.EMERALD, lvl >= 6 ? 4 : 2));
					g.add(new ItemStack(Items.COOKED_BEEF, 3));
				}
			}
			case Progression.FARM -> {
				g.add(new ItemStack(Items.WHEAT, 6));
				g.add(new ItemStack(Items.CARROT, 3));
				g.add(new ItemStack(Items.POTATO, 3));
				if (lvl >= 2) { // pasture; meat comes cooked once the smokehouse stands
					boolean smoked = lvl >= 3;
					g.add(new ItemStack(Items.EGG, 3));
					g.add(new ItemStack(item("white_wool"), 2));
					g.add(new ItemStack(Items.LEATHER, 1));
					g.add(new ItemStack(smoked ? Items.COOKED_BEEF : Items.BEEF, 2));
					g.add(new ItemStack(smoked ? Items.COOKED_MUTTON : Items.MUTTON, 2));
					g.add(new ItemStack(smoked ? Items.COOKED_CHICKEN : Items.CHICKEN, 1));
				}
				if (lvl >= 3) {
					g.add(new ItemStack(Items.BREAD, 3));
					g.add(new ItemStack(Items.BAKED_POTATO, 3));
				}
			}
			case Progression.SMITH -> {
				g.add(new ItemStack(Items.IRON_INGOT, 3));
				g.add(new ItemStack(Items.IRON_NUGGET, 6));
				if (lvl >= 2) {
					g.add(new ItemStack(Items.GOLD_INGOT, 1));
					if (random.nextInt(3) == 0) {
						g.add(new ItemStack(SMITH_GEAR.get(random.nextInt(SMITH_GEAR.size()))));
					}
				}
				if (lvl >= 2 && random.nextInt(5) == 0) {
					g.add(new ItemStack(Items.CROSSBOW));
				}
				if (lvl >= 3 && random.nextInt(4) == 0) {
					g.add(new ItemStack(List.of(Items.DIAMOND_SWORD, Items.DIAMOND_PICKAXE, Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE,
							Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS).get(random.nextInt(6))));
				}
				if (lvl >= 3) { // the citadel smith: elytra only after the dragon
					if (random.nextInt(6) == 0) {
						g.add(new ItemStack(Items.NETHERITE_SCRAP));
					}
					if (random.nextInt(24) == 0) {
						g.add(new ItemStack(Items.ELYTRA));
					}
				}
			}
			case Progression.WOOD -> {
				String wood = Style.FROZEN.equals(style) ? "spruce" : "oak";
				g.add(new ItemStack(item(wood + "_log"), 16));
				g.add(new ItemStack(Items.STICK, 8));
				if (lvl >= 2) {
					g.add(new ItemStack(item(wood + "_planks"), 32));
					g.add(new ItemStack(Items.CHARCOAL, 4));
				}
			}
			case Progression.APIARY -> {
				g.add(new ItemStack(Items.HONEY_BOTTLE, 2));
				g.add(new ItemStack(Items.HONEYCOMB, lvl >= 2 ? 4 : 2));
			}
			case "tavern" -> {
				g.add(new ItemStack(Items.BREAD, 3));
				g.add(new ItemStack(Items.COOKED_COD, 2));
				if (lvl >= 2) {
					g.add(new ItemStack(Items.PUMPKIN_PIE, 2));
				}
			}
			case "market" -> {
				g.add(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(
						List.of("red_dye", "blue_dye", "yellow_dye", "green_dye", "white_dye", "black_dye").get(random.nextInt(6)))), 2));
				g.add(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(
						List.of("oak_sapling", "birch_sapling", "cherry_sapling", "acacia_sapling", "spruce_sapling").get(random.nextInt(5))))));
				if (lvl >= 2 && random.nextInt(4) == 0) {
					g.add(new ItemStack(Items.NAME_TAG));
				}
			}
			case "cartographer" -> {
				g.add(new ItemStack(Items.PAPER, 4));
				if (random.nextInt(3) == 0) {
					g.add(new ItemStack(Items.COMPASS));
				}
				if (lvl >= 2) {
					g.add(new ItemStack(item("map"), 2));
				}
			}
			case "music" -> {
				g.add(new ItemStack(Items.NOTE_BLOCK));
				if (random.nextInt(lvl >= 2 ? 3 : 6) == 0) {
					g.add(new ItemStack(item(List.of("music_disc_13", "music_disc_cat", "music_disc_blocks", "music_disc_chirp", "music_disc_far",
							"music_disc_mall", "music_disc_mellohi", "music_disc_stal", "music_disc_strad", "music_disc_ward", "music_disc_wait")
							.get(random.nextInt(11)))));
				}
			}
			case "workshop" -> {
				g.add(new ItemStack(Items.COPPER_INGOT, 4));
				g.add(new ItemStack(Items.REDSTONE, 6));
				if (lvl >= 2) {
					g.add(new ItemStack(Items.REPEATER, 2));
					g.add(new ItemStack(Items.COMPARATOR));
				}
			}
			case "armorer" -> {
				g.add(new ItemStack(Items.IRON_INGOT, 2));
				if (lvl >= 2 && random.nextInt(5) == 0) {
					g.add(new ItemStack(item(List.of("coast_armor_trim_smithing_template", "dune_armor_trim_smithing_template",
							"wild_armor_trim_smithing_template", "sentry_armor_trim_smithing_template", "vex_armor_trim_smithing_template",
							"tide_armor_trim_smithing_template", "rib_armor_trim_smithing_template").get(random.nextInt(7)))));
				}
			}
			case "tannery" -> {
				g.add(new ItemStack(Items.LEATHER, 3));
				g.add(new ItemStack(Items.RABBIT_HIDE, 2));
				if (lvl >= 2 && random.nextInt(4) == 0) {
					g.add(new ItemStack(item("bundle")));
				}
			}
			case "archaeology" -> {
				if (random.nextInt(3) == 0) {
					g.add(new ItemStack(Items.BRUSH));
				}
				if (random.nextInt(lvl >= 2 ? 2 : 4) == 0) {
					g.add(new ItemStack(item(List.of("archer_pottery_sherd", "arms_up_pottery_sherd", "prize_pottery_sherd", "skull_pottery_sherd",
							"heart_pottery_sherd", "howl_pottery_sherd", "miner_pottery_sherd").get(random.nextInt(7)))));
				}
			}
			case "mine" -> {
				g.add(new ItemStack(Items.COAL, 6));
				g.add(new ItemStack(Items.RAW_IRON, 3));
				g.add(new ItemStack(Items.RAW_COPPER, 4));
				if (lvl >= 2) {
					g.add(new ItemStack(Items.REDSTONE, 4));
					g.add(new ItemStack(Items.LAPIS_LAZULI, 3));
					g.add(new ItemStack(Items.RAW_GOLD, 1));
					if (random.nextInt(10) == 0) {
						g.add(new ItemStack(Items.DIAMOND));
					}
				}
			}
			case Progression.DOCK -> {
				g.add(new ItemStack(Items.COD, 3));
				g.add(new ItemStack(Items.SALMON, 2));
				if (lvl >= 2) {
					g.add(new ItemStack(Items.TROPICAL_FISH, 1));
					if (random.nextInt(6) == 0) {
						g.add(new ItemStack(Items.NAUTILUS_SHELL));
					}
				}
			}
			case Progression.NETHER -> { // the piglin traders' barter
				g.add(new ItemStack(Items.QUARTZ, 4));
				g.add(new ItemStack(Items.SOUL_SAND, 2));
				g.add(new ItemStack(Items.OBSIDIAN, 1));
				if (random.nextInt(3) == 0) {
					g.add(new ItemStack(Items.ENDER_PEARL, 2));
				}
				if (lvl >= 2) {
					g.add(new ItemStack(Items.CRYING_OBSIDIAN, 1));
					if (random.nextInt(4) == 0) {
						g.add(PotionContents.createItemStack(Items.POTION, Potions.FIRE_RESISTANCE));
					}
				}
			}
			case Progression.CELLAR -> {
				if (lvl >= 2) { // treasury
					g.add(new ItemStack(Items.EMERALD, 2));
					g.add(new ItemStack(Items.GOLD_INGOT, 2));
				}
			}
			case Progression.LIBRARY -> {
				g.add(new ItemStack(Items.PAPER, 6));
				g.add(new ItemStack(Items.BOOK, 2));
				g.add(new ItemStack(Items.LAPIS_LAZULI, 3));
				if (lvl >= 2) {
					g.add(new ItemStack(Items.EXPERIENCE_BOTTLE, 4));
				}
			}
			case Progression.BREWERY -> {
				g.add(PotionContents.createItemStack(Items.POTION, Potions.HEALING));
				g.add(PotionContents.createItemStack(Items.POTION, Potions.HEALING));
				g.add(new ItemStack(Items.GLASS_BOTTLE, 3));
				if (lvl >= 2) {
					g.add(PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS));
					g.add(PotionContents.createItemStack(Items.POTION, Potions.REGENERATION));
				}
			}
			case Progression.STABLES -> {
				g.add(new ItemStack(Items.LEATHER, 2));
				g.add(new ItemStack(Items.WHEAT, 6));
				if (lvl >= 2) {
					g.add(new ItemStack(Items.GOLDEN_CARROT, 2));
					if (random.nextInt(4) == 0) {
						g.add(new ItemStack(Items.SADDLE));
					}
				}
			}
			default -> {
			}
		}
		return g;
	}

	private static Item item(String id) {
		return BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(id));
	}

	private static Container storage(ServerLevel level, Settlement s, String job) {
		Template t = Template.get(job);
		if (t != null && t.anchored && t.storage != null) {
			BlockPos p = s.heart().offset(Settlements.dockOffset(s)).offset(Template.rotate(t.storage.subtract(t.heart), Settlements.dockRotation(s)));
			return level.getBlockEntity(p) instanceof Container c ? c : null;
		}
		BlockPos offset = t == null ? null : t.storageOffset(Rotation.values()[s.rotation()]);
		return offset != null && level.getBlockEntity(s.heart().offset(offset).above(Settlements.lift(s, job))) instanceof Container c ? c : null;
	}

	/** Merges into matching stacks first, then empty slots. */
	static void insert(Container box, ItemStack stack) {
		for (int pass = 0; pass < 2 && !stack.isEmpty(); pass++) {
			for (int i = 0; i < box.getContainerSize() && !stack.isEmpty(); i++) {
				ItemStack slot = box.getItem(i);
				if (pass == 0 && !slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, stack)) {
					int move = Math.min(stack.getCount(), slot.getMaxStackSize() - slot.getCount());
					slot.grow(move);
					stack.shrink(move);
				} else if (pass == 1 && slot.isEmpty()) {
					box.setItem(i, stack.copy());
					stack.setCount(0);
				}
			}
		}
	}
}
