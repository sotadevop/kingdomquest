package io.github.sotadevop.kingdomquest.kingdom;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Upgrade steps per track. Until StoryQuest has the Kingdom questline, each step is unlocked by a vanilla
 * advancement (placeholder for the quest), then paid for with items.
 */
public final class Progression {
	public record Cost(String label, int count, Predicate<ItemStack> matches) {
	}

	/**
	 * @param building template name without style, e.g. "tent_2"
	 * @param minMain  main-track level needed before this step is offered (add-ons)
	 */
	public record Step(String track, String building, int level, String titleKey, int minMain, Identifier advancement,
			String requirementKey, List<Cost> costs) {
	}

	private static Cost item(Item item, int count) {
		return new Cost(BuiltInRegistries.ITEM.getKey(item).getPath().replace('_', ' '), count, s -> s.is(item));
	}

	private static Cost tag(TagKey<Item> tag, String label, int count) {
		return new Cost(label, count, s -> s.is(tag));
	}

	private static Identifier adv(String path) {
		return Identifier.withDefaultNamespace(path);
	}

	public static final String FARM = "farm";

	public static final Map<String, List<Step>> TRACKS = Map.of(
			Settlement.MAIN, List.of(
					new Step(Settlement.MAIN, "tent_1", 1, "kingdomquest.level.tent_1", 0, null, null, List.of()),
					new Step(Settlement.MAIN, "tent_2", 2, "kingdomquest.level.tent_2", 0, adv("story/smelt_iron"), "kingdomquest.req.smelt_iron",
							List.of(tag(ItemTags.LOGS, "logs", 16), tag(ItemTags.WOOL, "wool", 8))),
					new Step(Settlement.MAIN, "tent_3", 3, "kingdomquest.level.tent_3", 0, adv("story/iron_tools"), "kingdomquest.req.iron_tools",
							List.of(tag(ItemTags.LOGS, "logs", 32), item(Items.COBBLESTONE, 16), item(Items.IRON_INGOT, 8))),
					new Step(Settlement.MAIN, "hamlet_1", 4, "kingdomquest.level.hamlet_1", 0, adv("adventure/sleep_in_bed"),
							"kingdomquest.req.sleep", List.of(tag(ItemTags.LOGS, "logs", 48), item(Items.COBBLESTONE, 48), item(Items.GLASS, 8),
									item(Items.BRICK, 16))),
					new Step(Settlement.MAIN, "hamlet_2", 5, "kingdomquest.level.hamlet_2", 0, adv("husbandry/fishy_business"),
							"kingdomquest.req.fish", List.of(tag(ItemTags.LOGS, "logs", 48), item(Items.COBBLESTONE, 64), item(Items.GLASS, 8),
									item(Items.BRICK, 16))),
					new Step(Settlement.MAIN, "hamlet_3", 6, "kingdomquest.level.hamlet_3", 0, adv("adventure/trade"),
							"kingdomquest.req.trade", List.of(tag(ItemTags.LOGS, "logs", 48), item(Items.COBBLESTONE, 64), item(Items.GLASS, 8),
									tag(ItemTags.WOOL, "wool", 8), item(Items.EMERALD, 4))),
					new Step(Settlement.MAIN, "village_1", 7, "kingdomquest.level.village_1", 0, adv("story/enchant_item"),
							"kingdomquest.req.enchant", List.of(tag(ItemTags.LOGS, "logs", 64), item(Items.COBBLESTONE, 128), item(Items.GLASS, 8),
									item(Items.BRICK, 16), item(Items.EMERALD, 8))),
					new Step(Settlement.MAIN, "town_1", 8, "kingdomquest.level.town_1", 0, adv("story/enter_the_nether"),
							"kingdomquest.req.nether", List.of(item(Items.STONE_BRICKS, 256), tag(ItemTags.LOGS, "logs", 64), item(Items.GLASS_PANE, 32),
									item(Items.BRICK, 64), item(Items.EMERALD, 16), item(Items.BELL, 1))),
					new Step(Settlement.MAIN, "castle_1", 9, "kingdomquest.level.castle_1", 0, adv("nether/find_fortress"),
							"kingdomquest.req.fortress", List.of(item(Items.STONE_BRICKS, 1024), item(Items.IRON_BARS, 32), tag(ItemTags.LOGS, "logs", 64),
									item(Items.EMERALD, 32), item(Items.GOLD_BLOCK, 4))),
					new Step(Settlement.MAIN, "castle_2", 10, "kingdomquest.level.castle_2", 0, adv("nether/obtain_blaze_rod"),
							"kingdomquest.req.blaze", List.of(item(Items.STONE_BRICKS, 512), tag(ItemTags.DIRT, "dirt", 512), item(Items.LANTERN, 4))),
					new Step(Settlement.MAIN, "citadel_1", 11, "kingdomquest.level.citadel_1", 0, adv("end/kill_dragon"), "kingdomquest.req.dragon",
							List.of(item(Items.STONE_BRICKS, 768), item(Items.IRON_BLOCK, 9), item(Items.BEACON, 1)))),
			FARM, List.of(
					new Step(FARM, "farm_1", 1, "kingdomquest.addon.farm_1", 4, adv("husbandry/plant_seed"), "kingdomquest.req.plant_seed",
							List.of(tag(ItemTags.LOGS, "logs", 16), item(Items.WHEAT_SEEDS, 16), item(Items.IRON_INGOT, 2))),
					new Step(FARM, "farm_2", 2, "kingdomquest.addon.farm_2", 4, adv("husbandry/breed_an_animal"), "kingdomquest.req.breed",
							List.of(tag(ItemTags.LOGS, "logs", 24), item(Items.WHEAT, 16), item(Items.LEAD, 2))),
					new Step(FARM, "farm_3", 3, "kingdomquest.addon.farm_3", 4, adv("husbandry/tame_an_animal"), "kingdomquest.req.tame",
							List.of(tag(ItemTags.LOGS, "logs", 16), item(Items.COBBLESTONE, 32), item(Items.SMOKER, 1), item(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace("iron_chain")), 2)))));

	public static final String SMITH = "smith";
	public static final String WOOD = "wood";

	public static final Map<String, List<Step>> ADDONS_2 = Map.of(
			SMITH, List.of(
					new Step(SMITH, "smith_1", 1, "kingdomquest.addon.smith_1", 4, adv("story/obtain_armor"), "kingdomquest.req.armor",
							List.of(item(Items.COBBLESTONE, 48), tag(ItemTags.LOGS, "logs", 8), item(Items.BLAST_FURNACE, 1), item(Items.IRON_INGOT, 8))),
					new Step(SMITH, "smith_2", 2, "kingdomquest.addon.smith_2", 5, adv("story/mine_diamond"), "kingdomquest.req.diamond",
							List.of(item(Items.COBBLESTONE, 32), item(Items.IRON_INGOT, 16), item(Items.GOLD_INGOT, 4))),
					new Step(SMITH, "smith_3", 3, "kingdomquest.addon.smith_3", 11, adv("end/elytra"), "kingdomquest.req.elytra",
							List.of(item(Items.NETHERITE_INGOT, 1), item(Items.PURPUR_BLOCK, 8), item(Items.PHANTOM_MEMBRANE, 8)))),
			WOOD, List.of(
					new Step(WOOD, "wood_1", 1, "kingdomquest.addon.wood_1", 4, adv("story/upgrade_tools"), "kingdomquest.req.stone_tools",
							List.of(tag(ItemTags.LOGS, "logs", 32), item(Items.IRON_AXE, 1))),
					new Step(WOOD, "wood_2", 2, "kingdomquest.addon.wood_2", 5, adv("story/lava_bucket"), "kingdomquest.req.lava",
							List.of(tag(ItemTags.LOGS, "logs", 16), item(Items.STONECUTTER, 1), item(Items.IRON_INGOT, 4)))));

	/** Walls are their own track from the Village on; upgrading the tier keeps the wall level. */
	public static final String WALLS = "walls";
	public static final List<Step> WALL_STEPS = List.of(
			new Step(WALLS, "wall_1", 1, "kingdomquest.walls.wall_1", 7, adv("adventure/kill_a_mob"), "kingdomquest.req.kill",
					List.of(tag(ItemTags.LOGS, "logs", 96), item(Items.STICK, 32))),
			new Step(WALLS, "wall_2", 2, "kingdomquest.walls.wall_2", 7, adv("adventure/shoot_arrow"), "kingdomquest.req.arrow",
					List.of(tag(ItemTags.LOGS, "logs", 64), tag(ItemTags.PLANKS, "planks", 64), item(Items.LADDER, 8))),
			new Step(WALLS, "wall_3", 3, "kingdomquest.walls.wall_3", 8, adv("story/form_obsidian"), "kingdomquest.req.obsidian",
					List.of(item(Items.STONE_BRICKS, 512), item(Items.COBBLESTONE, 128), item(Items.IRON_BARS, 3))),
			new Step(WALLS, "wall_4", 4, "kingdomquest.walls.wall_4", 8, adv("adventure/kill_all_mobs"), "kingdomquest.req.monsters",
					List.of(item(Items.STONE_BRICKS, 512), item(Items.LADDER, 64), item(Items.LANTERN, 8))));

	/** Roads and lamps: the village infrastructure, each upgraded on its own. */
	public static final String ROADS = "roads";
	public static final String LAMPS = "lamps";
	public static final List<Step> ROAD_STEPS = List.of(
			new Step(ROADS, "road_1", 1, "kingdomquest.roads.road_1", 4, null, null, List.of(item(Items.STONE_SHOVEL, 1))),
			new Step(ROADS, "road_2", 2, "kingdomquest.roads.road_2", 5, adv("story/mine_stone"), "kingdomquest.req.mine_stone",
					List.of(item(Items.IRON_SHOVEL, 1), item(Items.GRAVEL, 16))),
			new Step(ROADS, "road_3", 3, "kingdomquest.roads.road_3", 7, adv("story/smelt_iron"), "kingdomquest.req.smelt_iron",
					List.of(item(Items.COBBLESTONE, 192), item(Items.GRAVEL, 64))),
			new Step(ROADS, "road_4", 4, "kingdomquest.roads.road_4", 7, adv("story/enchant_item"), "kingdomquest.req.enchant",
					List.of(item(Items.STONE_BRICKS, 192), item(Items.POLISHED_ANDESITE, 64))));
	public static final List<Step> LAMP_STEPS = List.of(
			new Step(LAMPS, "lamp_1", 1, "kingdomquest.lamps.lamp_1", 4, null, null, List.of(item(Items.TORCH, 16), tag(ItemTags.LOGS, "logs", 4))),
			new Step(LAMPS, "lamp_2", 2, "kingdomquest.lamps.lamp_2", 5, adv("story/smelt_iron"), "kingdomquest.req.smelt_iron",
					List.of(item(Items.LANTERN, 16), tag(ItemTags.LOGS, "logs", 8))),
			new Step(LAMPS, "lamp_3", 3, "kingdomquest.lamps.lamp_3", 7, adv("adventure/kill_a_mob"), "kingdomquest.req.kill",
					List.of(item(Items.LANTERN, 32), item(Items.STONE_BRICKS, 16), tag(ItemTags.LOGS, "logs", 8))));

	public static final String LIBRARY = "library";
	public static final String BREWERY = "brewery";
	public static final String STABLES = "stables";
	public static final String BARRACKS = "barracks";
	public static final String APIARY = "apiary";
	public static final String KENNEL = "kennel";
	public static final String TROPHY = "trophy";
	/** The lord's own house. */
	public static final String ROYAL = "royal";
	public static final List<Step> ROYAL_STEPS = List.of(
			new Step(ROYAL, "royal_1", 1, "kingdomquest.addon.royal_1", 6, adv("adventure/sleep_in_bed"), "kingdomquest.req.sleep",
					List.of(item(Items.STONE_BRICKS, 128), tag(ItemTags.PLANKS, "planks", 96), item(Items.GLASS, 24))),
			new Step(ROYAL, "royal_2", 2, "kingdomquest.addon.royal_2", 8, adv("story/mine_diamond"), "kingdomquest.req.diamond",
					List.of(item(Items.GOLD_INGOT, 18), item(Items.BOOKSHELF, 12), item(Items.LANTERN, 6))));
	public static final Map<String, List<Step>> ADDONS_3 = Map.of(
			LIBRARY, List.of(
					new Step(LIBRARY, "library_1", 1, "kingdomquest.addon.library_1", 7, adv("story/enchant_item"), "kingdomquest.req.enchant",
							List.of(item(Items.STONE_BRICKS, 96), item(Items.BOOKSHELF, 6), item(Items.ENCHANTING_TABLE, 1))),
					new Step(LIBRARY, "library_2", 2, "kingdomquest.addon.library_2", 7, adv("story/mine_diamond"), "kingdomquest.req.diamond",
							List.of(item(Items.BOOKSHELF, 20), item(Items.LECTERN, 1), item(Items.LANTERN, 2)))),
			BREWERY, List.of(
					new Step(BREWERY, "brewery_1", 1, "kingdomquest.addon.brewery_1", 7, adv("nether/obtain_blaze_rod"), "kingdomquest.req.blaze",
							List.of(item(Items.STONE_BRICKS, 64), item(Items.BREWING_STAND, 1), item(Items.CAULDRON, 2))),
					new Step(BREWERY, "brewery_2", 2, "kingdomquest.addon.brewery_2", 7, adv("nether/brew_potion"), "kingdomquest.req.brew",
							List.of(item(Items.BREWING_STAND, 1), item(Items.BARREL, 4), item(Items.GLASS_BOTTLE, 16)))),
			APIARY, List.of(
					new Step(APIARY, "apiary_1", 1, "kingdomquest.addon.apiary_1", 5, adv("husbandry/safely_harvest_honey"), "kingdomquest.req.honey",
							List.of(tag(ItemTags.LOGS, "logs", 16), item(Items.HONEYCOMB, 3), item(Items.POPPY, 8))),
					new Step(APIARY, "apiary_2", 2, "kingdomquest.addon.apiary_2", 7, adv("husbandry/silk_touch_nest"), "kingdomquest.req.nest",
							List.of(item(Items.BEEHIVE, 3), item(Items.CAMPFIRE, 1)))),
			KENNEL, List.of(
					new Step(KENNEL, "kennel_1", 1, "kingdomquest.addon.kennel_1", 4, adv("husbandry/tame_an_animal"), "kingdomquest.req.tame",
							List.of(tag(ItemTags.LOGS, "logs", 12), item(Items.BONE, 8))),
					new Step(KENNEL, "kennel_2", 2, "kingdomquest.addon.kennel_2", 5, adv("adventure/trade"), "kingdomquest.req.trade",
							List.of(item(Items.COD, 8), tag(ItemTags.WOOL, "wool", 3))),
					new Step(KENNEL, "kennel_3", 3, "kingdomquest.addon.kennel_3", 7, adv("husbandry/plant_seed"), "kingdomquest.req.plant_seed",
							List.of(item(Items.WHEAT_SEEDS, 16), tag(ItemTags.LOGS, "logs", 4)))),
			TROPHY, List.of(
					new Step(TROPHY, "trophy_1", 1, "kingdomquest.addon.trophy_1", 7, adv("story/enter_the_nether"), "kingdomquest.req.nether",
							List.of(item(Items.STONE_BRICKS, 96), item(Items.POLISHED_DEEPSLATE, 20))),
					new Step(TROPHY, "trophy_2", 2, "kingdomquest.addon.trophy_2", 9, adv("story/enter_the_end"), "kingdomquest.req.end",
							List.of(item(Items.LANTERN, 4), item(Items.GOLD_INGOT, 8)))),
			BARRACKS, List.of(
					new Step(BARRACKS, "barracks_1", 1, "kingdomquest.addon.barracks_1", 7, adv("adventure/kill_a_mob"), "kingdomquest.req.kill",
							List.of(item(Items.STONE_BRICKS, 96), item(Items.IRON_BLOCK, 8), item(Items.CARVED_PUMPKIN, 2))),
					new Step(BARRACKS, "barracks_2", 2, "kingdomquest.addon.barracks_2", 9, adv("adventure/voluntary_exile"), "kingdomquest.req.raid_captain",
							List.of(item(Items.STONE_BRICKS, 64), item(Items.IRON_INGOT, 16), item(Items.SHIELD, 4))),
					new Step(BARRACKS, "barracks_3", 3, "kingdomquest.addon.barracks_3", 10, adv("adventure/hero_of_the_village"), "kingdomquest.req.hero",
							List.of(item(Items.STONE_BRICKS, 96), item(Items.IRON_BLOCK, 4), tag(ItemTags.LOGS, "logs", 32)))),
			STABLES, List.of(
					new Step(STABLES, "stables_1", 1, "kingdomquest.addon.stables_1", 5, adv("husbandry/tame_an_animal"), "kingdomquest.req.tame",
							List.of(tag(ItemTags.LOGS, "logs", 48), item(Items.HAY_BLOCK, 6), item(Items.LEAD, 2))),
					new Step(STABLES, "stables_2", 2, "kingdomquest.addon.stables_2", 7, adv("husbandry/breed_an_animal"), "kingdomquest.req.breed",
							List.of(tag(ItemTags.LOGS, "logs", 32), item(Items.HAY_BLOCK, 8), item(Items.SADDLE, 1)))));

	/** Cellar under the main building: storeroom, then treasury. */
	public static final String CELLAR = "cellar";
	public static final List<Step> CELLAR_STEPS = List.of(
			new Step(CELLAR, "cellar_1", 1, "kingdomquest.cellar.cellar_1", 5, adv("story/mine_stone"), "kingdomquest.req.mine_stone",
					List.of(item(Items.COBBLESTONE, 128), item(Items.CHEST, 2), item(Items.LADDER, 6))),
			new Step(CELLAR, "cellar_2", 2, "kingdomquest.cellar.cellar_2", 8, adv("story/mine_diamond"), "kingdomquest.req.diamond",
					List.of(item(Items.STONE_BRICKS, 64), item(Items.CHEST, 6), item(Items.GOLD_INGOT, 8))),
			new Step(CELLAR, "cellar_3", 3, "kingdomquest.cellar.cellar_3", 9, adv("story/mine_diamond"), "kingdomquest.req.diamond",
					List.of(item(Items.STONE_BRICKS, 96), item(Items.STICKY_PISTON, 4), item(Items.GOLD_BLOCK, 1))));

	/** Guild of allies: swordfighter, archer, mage, called with a goat horn. */
	public static final String GUILD = "guild";
	public static final List<Step> GUILD_STEPS = List.of(
			new Step(GUILD, "guild_1", 1, "kingdomquest.guild.guild_1", 8, adv("adventure/kill_a_mob"), "kingdomquest.req.kill",
					List.of(item(Items.STONE_BRICKS, 96), item(Items.IRON_SWORD, 1), item(Items.GOAT_HORN, 1))),
			new Step(GUILD, "guild_2", 2, "kingdomquest.guild.guild_2", 9, adv("adventure/shoot_arrow"), "kingdomquest.req.arrow",
					List.of(item(Items.BOW, 1), item(Items.ARROW, 64), item(Items.TARGET, 3))),
			new Step(GUILD, "guild_3", 3, "kingdomquest.guild.guild_3", 10, adv("nether/brew_potion"), "kingdomquest.req.brew",
					List.of(item(Items.BREWING_STAND, 1), item(Items.BOOKSHELF, 2), item(Items.AMETHYST_SHARD, 8))),
			new Step(GUILD, "guild_4", 4, "kingdomquest.guild.guild_4", 9, adv("story/mine_diamond"), "kingdomquest.req.diamond",
					List.of(item(Items.DIAMOND, 8))),
			new Step(GUILD, "guild_5", 5, "kingdomquest.guild.guild_5", 9, adv("adventure/ol_betsy"), "kingdomquest.req.crossbow",
					List.of(item(Items.CROSSBOW, 1), item(Items.CHAINMAIL_HELMET, 1))),
			new Step(GUILD, "guild_6", 6, "kingdomquest.guild.guild_6", 10, adv("nether/all_potions"), "kingdomquest.req.potions",
					List.of(item(Items.AMETHYST_SHARD, 16), item(Items.GLOWSTONE_DUST, 16))));

	/** Gate: its own track at the wall's gate opening. Levels 2-4 need stone walls. */
	public static final String GATE = "gate";
	public static final List<Step> GATE_STEPS = List.of(
			new Step(GATE, "gate_1", 1, "kingdomquest.gate.gate_1", 7, null, null, List.of(tag(ItemTags.LOGS, "logs", 16), item(Items.OAK_FENCE_GATE, 6))),
			new Step(GATE, "gate_2", 2, "kingdomquest.gate.gate_2", 8, adv("story/form_obsidian"), "kingdomquest.req.obsidian",
					List.of(item(Items.STONE_BRICKS, 192), item(Items.IRON_BARS, 3), item(Items.LADDER, 8))),
			new Step(GATE, "gate_3", 3, "kingdomquest.gate.gate_3", 8, adv("story/enter_the_nether"), "kingdomquest.req.nether",
					List.of(item(Items.WATER_BUCKET, 2), tag(ItemTags.PLANKS, "planks", 32), item(Items.IRON_SHOVEL, 1))),
			new Step(GATE, "gate_4", 4, "kingdomquest.gate.gate_4", 8, adv("adventure/kill_a_mob"), "kingdomquest.req.kill",
					List.of(item(Items.DISPENSER, 2), item(Items.LEVER, 2), item(Items.ARROW, 64))),
			new Step(GATE, "gate_5", 5, "kingdomquest.gate.gate_5", 9, adv("nether/obtain_blaze_rod"), "kingdomquest.req.blaze",
					List.of(item(Items.PISTON, 3), item(Items.LAVA_BUCKET, 3), item(Items.LEVER, 1))));
	/** Walls level each gate level needs. */
	public static final int[] GATE_MIN_WALLS = {1, 3, 3, 3, 3};

	/** One building per vanilla system, each with a worker: trades, crafts, resources (the dock sits at the nearest water). */
	public static final String DOCK = "dock";
	public static final Map<String, List<Step>> TRADES = Map.ofEntries(
				Map.entry("tavern", List.of(
					new Step("tavern", "tavern_1", 1, "kingdomquest.trade.tavern_1", 5, adv("husbandry/plant_seed"), "kingdomquest.req.plant_seed", List.of(tag(ItemTags.LOGS, "logs", 48), item(Items.BARREL, 4), item(Items.SMOKER, 1))),
					new Step("tavern", "tavern_2", 2, "kingdomquest.trade.tavern_2", 7, adv("adventure/trade"), "kingdomquest.req.trade", List.of(item(Items.LANTERN, 4), item(Items.CAKE, 1))),
					new Step("tavern", "tavern_3", 3, "kingdomquest.trade.tavern_3", 9, adv("husbandry/bred_all_animals"), "kingdomquest.req.bred_all",
							List.of(tag(ItemTags.LOGS, "logs", 48), tag(ItemTags.BEDS, "beds", 3), item(Items.LADDER, 8))))),
				Map.entry("market", List.of(
					new Step("market", "market_1", 1, "kingdomquest.trade.market_1", 7, adv("adventure/trade"), "kingdomquest.req.trade", List.of(tag(ItemTags.LOGS, "logs", 48), item(Items.EMERALD, 8), item(Items.LOOM, 1))),
					new Step("market", "market_2", 2, "kingdomquest.trade.market_2", 8, adv("story/mine_diamond"), "kingdomquest.req.diamond", List.of(item(Items.EMERALD, 16), item(Items.LANTERN, 4))),
					new Step("market", "market_3", 3, "kingdomquest.trade.market_3", 9, adv("adventure/trade_at_world_height"), "kingdomquest.req.trade_high",
							List.of(tag(ItemTags.LOGS, "logs", 48), item(Items.BARREL, 6), item(Items.EMERALD, 24))))),
				Map.entry("cartographer", List.of(
					new Step("cartographer", "cartographer_1", 1, "kingdomquest.trade.cartographer_1", 7, adv("adventure/trade"), "kingdomquest.req.trade", List.of(item(Items.CARTOGRAPHY_TABLE, 1), item(Items.COMPASS, 1), item(Items.PAPER, 16))),
					new Step("cartographer", "cartographer_2", 2, "kingdomquest.trade.cartographer_2", 8, adv("story/follow_ender_eye"), "kingdomquest.req.eye", List.of(item(Items.SPYGLASS, 1), item(Items.MAP, 2))))),
				Map.entry("music", List.of(
					new Step("music", "music_1", 1, "kingdomquest.trade.music_1", 7, adv("adventure/play_jukebox_in_meadows"), "kingdomquest.req.jukebox", List.of(item(Items.JUKEBOX, 1), item(Items.NOTE_BLOCK, 4), tag(ItemTags.LOGS, "logs", 32))),
					new Step("music", "music_2", 2, "kingdomquest.trade.music_2", 8, adv("story/mine_diamond"), "kingdomquest.req.diamond", List.of(item(Items.NOTE_BLOCK, 8), item(Items.AMETHYST_SHARD, 8))))),
				Map.entry("workshop", List.of(
					new Step("workshop", "workshop_1", 1, "kingdomquest.trade.workshop_1", 5, adv("husbandry/wax_on"), "kingdomquest.req.wax", List.of(item(Items.COPPER_INGOT, 16), item(Items.REDSTONE, 16), item(Items.COBBLESTONE, 64))),
					new Step("workshop", "workshop_2", 2, "kingdomquest.trade.workshop_2", 8, adv("adventure/minecraft_trials_edition"), "kingdomquest.req.trials", List.of(item(Items.CRAFTER, 1), item(Items.REPEATER, 4))))),
				Map.entry("armorer", List.of(
					new Step("armorer", "armorer_1", 1, "kingdomquest.trade.armorer_1", 8, adv("adventure/trim_with_any_armor_pattern"), "kingdomquest.req.trim", List.of(item(Items.BLAST_FURNACE, 1), item(Items.ANVIL, 1), item(Items.STONE_BRICKS, 64))),
					new Step("armorer", "armorer_2", 2, "kingdomquest.trade.armorer_2", 9, adv("nether/netherite_armor"), "kingdomquest.req.netherite", List.of(item(Items.SMITHING_TABLE, 1), item(Items.IRON_BLOCK, 4))))),
				Map.entry("tannery", List.of(
					new Step("tannery", "tannery_1", 1, "kingdomquest.trade.tannery_1", 5, adv("husbandry/breed_an_animal"), "kingdomquest.req.breed", List.of(item(Items.CAULDRON, 2), item(Items.LEATHER, 8), tag(ItemTags.LOGS, "logs", 32))),
					new Step("tannery", "tannery_2", 2, "kingdomquest.trade.tannery_2", 7, adv("husbandry/tame_an_animal"), "kingdomquest.req.tame", List.of(item(Items.SMOKER, 1), item(Items.LEATHER, 16))))),
				Map.entry("archaeology", List.of(
					new Step("archaeology", "archaeology_1", 1, "kingdomquest.trade.archaeology_1", 7, adv("adventure/salvage_sherd"), "kingdomquest.req.sherd", List.of(item(Items.BRUSH, 1), item(Items.DECORATED_POT, 2), item(Items.SANDSTONE, 32))),
					new Step("archaeology", "archaeology_2", 2, "kingdomquest.trade.archaeology_2", 8, adv("adventure/craft_decorated_pot_using_only_sherds"), "kingdomquest.req.pot", List.of(item(Items.CHISELED_BOOKSHELF, 2), item(Items.BRUSH, 1))))),
				Map.entry("mine", List.of(
					new Step("mine", "mine_1", 1, "kingdomquest.trade.mine_1", 4, adv("story/iron_tools"), "kingdomquest.req.iron_tools", List.of(item(Items.IRON_PICKAXE, 1), item(Items.LADDER, 24), item(Items.TORCH, 16))),
					new Step("mine", "mine_2", 2, "kingdomquest.trade.mine_2", 7, adv("story/mine_diamond"), "kingdomquest.req.diamond", List.of(item(Items.RAIL, 16), item(Items.DIAMOND_PICKAXE, 1))))),
				Map.entry("dock", List.of(
					new Step("dock", "dock_1", 1, "kingdomquest.trade.dock_1", 4, adv("husbandry/fishy_business"), "kingdomquest.req.fish", List.of(tag(ItemTags.LOGS, "logs", 32), tag(ItemTags.PLANKS, "planks", 64), item(Items.FISHING_ROD, 1))),
					new Step("dock", "dock_2", 2, "kingdomquest.trade.dock_2", 6, adv("husbandry/tactical_fishing"), "kingdomquest.req.bucket_fish", List.of(tag(ItemTags.PLANKS, "planks", 48), item(Items.LANTERN, 2))))));

	public static final String LAIR = "lair";
	public static final String NETHER = "nether";
	public static final List<Step> LAIR_STEPS = List.of(
			new Step(LAIR, "lair_1", 1, "kingdomquest.lair.lair_1", 11, adv("end/kill_dragon"), "kingdomquest.req.dragon",
					List.of(item(Items.END_STONE, 128), item(Items.OBSIDIAN, 64), item(Items.END_ROD, 8))),
			new Step(LAIR, "lair_2", 2, "kingdomquest.lair.lair_2", 11, adv("end/dragon_egg"), "kingdomquest.req.egg",
					List.of(item(Items.PURPUR_BLOCK, 32), item(Items.CHORUS_FLOWER, 3))));
	public static final List<Step> NETHER_STEPS = List.of(
			new Step(NETHER, "nether_1", 1, "kingdomquest.trade.nether_1", 7, adv("nether/distract_piglin"), "kingdomquest.req.piglin",
					List.of(item(Items.NETHER_BRICKS, 64), item(Items.GOLD_BLOCK, 1), item(Items.CRYING_OBSIDIAN, 2))),
			new Step(NETHER, "nether_2", 2, "kingdomquest.trade.nether_2", 9, adv("nether/ride_strider"), "kingdomquest.req.strider",
					List.of(item(Items.GOLD_BLOCK, 2), item(Items.RESPAWN_ANCHOR, 1))));

	/** The storehouse: all production is delivered here (see {@link Storehouse}); its level sets how much it holds. */
	public static final String STOREHOUSE = "storehouse";
	public static final List<Step> STOREHOUSE_STEPS = List.of(
			new Step(STOREHOUSE, "storehouse_1", 1, "kingdomquest.storehouse.storehouse_1", 4, adv("story/mine_stone"), "kingdomquest.req.mine_stone",
					List.of(tag(ItemTags.LOGS, "logs", 48), item(Items.CHEST, 2), item(Items.LECTERN, 1))),
			new Step(STOREHOUSE, "storehouse_2", 2, "kingdomquest.storehouse.storehouse_2", 5, adv("story/smelt_iron"), "kingdomquest.req.smelt_iron",
					List.of(tag(ItemTags.PLANKS, "planks", 64), item(Items.CHEST, 2), item(Items.EMERALD, 4))),
			new Step(STOREHOUSE, "storehouse_3", 3, "kingdomquest.storehouse.storehouse_3", 7, adv("adventure/trade"), "kingdomquest.req.trade",
					List.of(tag(ItemTags.LOGS, "logs", 64), item(Items.LADDER, 8), item(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace("iron_chain")), 4), item(Items.EMERALD, 12))),
			new Step(STOREHOUSE, "storehouse_4", 4, "kingdomquest.storehouse.storehouse_4", 8, adv("story/enchant_item"), "kingdomquest.req.enchant",
					List.of(item(Items.STONE_BRICKS, 256), item(Items.CHEST, 2), item(Items.EMERALD, 24))),
			new Step(STOREHOUSE, "storehouse_5", 5, "kingdomquest.storehouse.storehouse_5", 9, adv("story/mine_diamond"), "kingdomquest.req.diamond",
					List.of(item(Items.STONE_BRICKS, 128), item(Items.HAY_BLOCK, 16), item(Items.EMERALD, 48))));

	/** The church (Town) and the cathedral (Citadel), on the plot kept for it (a hill town's highest hill). */
	public static final String CHURCH = "church";
	public static final List<Step> CHURCH_STEPS = List.of(
			new Step(CHURCH, "church_1", 1, "kingdomquest.church.church_1", 8, adv("story/enchant_item"), "kingdomquest.req.enchant",
					List.of(item(Items.STONE_BRICKS, 256), item(Items.GLASS_PANE, 32), item(Items.BELL, 1), item(Items.EMERALD, 32))),
			new Step(CHURCH, "church_2", 2, "kingdomquest.church.church_2", 11, adv("end/kill_dragon"), "kingdomquest.req.dragon",
					List.of(item(Items.STONE_BRICKS, 768), item(Items.GLASS_PANE, 64), item(Items.GOLD_BLOCK, 4), item(Items.EMERALD, 96))));

	/** The town hall with its clock tower (Town): the council over an arcade on the square's east street. */
	public static final String TOWNHALL = "townhall";
	public static final List<Step> TOWNHALL_STEPS = List.of(
			new Step(TOWNHALL, "townhall_1", 1, "kingdomquest.townhall.townhall_1", 8, adv("adventure/trade"), "kingdomquest.req.trade",
					List.of(item(Items.STONE_BRICKS, 192), tag(ItemTags.LOGS, "logs", 64), item(Items.BELL, 1), item(Items.EMERALD, 24))));

	/** The palace garden (Castle) beside the lord's house: hedges, flower beds, a fountain. */
	public static final String GARDEN = "garden";
	public static final List<Step> GARDEN_STEPS = List.of(
			new Step(GARDEN, "garden_1", 1, "kingdomquest.garden.garden_1", 9, adv("husbandry/plant_seed"), "kingdomquest.req.plant_seed",
					List.of(item(Items.STONE_BRICKS, 96), tag(ItemTags.LEAVES, "leaves", 64), item(Items.POPPY, 16), item(Items.DANDELION, 16), item(Items.EMERALD, 16))));

	/** The covered market hall (Town) west of the keep, where market day is held. */
	public static final String MARKETHALL = "markethall";
	public static final List<Step> MARKETHALL_STEPS = List.of(
			new Step(MARKETHALL, "markethall_1", 1, "kingdomquest.markethall.markethall_1", 8, adv("adventure/trade"), "kingdomquest.req.trade",
					List.of(tag(ItemTags.LOGS, "logs", 96), item(Items.STONE_BRICKS, 96), item(Items.BARREL, 4), item(Items.EMERALD, 24))));

	/** The belfry (Castle) at the square: its bell rings when the signal fires are lit. */
	public static final String BELFRY = "belfry";
	public static final List<Step> BELFRY_STEPS = List.of(
			new Step(BELFRY, "belfry_1", 1, "kingdomquest.belfry.belfry_1", 9, adv("adventure/voluntary_exile"), "kingdomquest.req.voluntary_exile",
					List.of(item(Items.STONE_BRICKS, 256), item(Items.BELL, 1), item(Items.EMERALD, 32))));

	/** Order the tracks appear in the menu. */
	public static final List<String> ORDER = List.of(Settlement.MAIN, CELLAR, ROYAL, WALLS, GATE, BARRACKS, GUILD, APIARY, KENNEL, TROPHY, "tavern", "market", "cartographer", "music",
			"workshop", "armorer", "tannery", "archaeology", "mine", DOCK, NETHER, LAIR, ROADS, LAMPS, FARM, STABLES, SMITH, WOOD, LIBRARY, BREWERY,
			STOREHOUSE, CHURCH, TOWNHALL, GARDEN, MARKETHALL, BELFRY);

	public static List<Step> steps(String track) {
		List<Step> l = TRACKS.get(track);
		if (l != null) {
			return l;
		}
		return switch (track) {
			case WALLS -> WALL_STEPS;
			case GATE -> GATE_STEPS;
			case CELLAR -> CELLAR_STEPS;
			case LAIR -> LAIR_STEPS;
			case NETHER -> NETHER_STEPS;
			case GUILD -> GUILD_STEPS;
			case ROYAL -> ROYAL_STEPS;
			case ROADS -> ROAD_STEPS;
			case LAMPS -> LAMP_STEPS;
			case STOREHOUSE -> STOREHOUSE_STEPS;
			case CHURCH -> CHURCH_STEPS;
			case TOWNHALL -> TOWNHALL_STEPS;
			case GARDEN -> GARDEN_STEPS;
			case MARKETHALL -> MARKETHALL_STEPS;
			case BELFRY -> BELFRY_STEPS;
			default -> ADDONS_2.containsKey(track) ? ADDONS_2.get(track) : ADDONS_3.containsKey(track) ? ADDONS_3.get(track) : TRADES.get(track);
		};
	}

	private Progression() {
	}

	/** The next step of a track, or null if the track is done or not open yet. */
	public static Step next(Settlement s, String track) {
		List<Step> steps = steps(track);
		int level = s.level(track);
		if (steps == null || level >= steps.size()) {
			return null;
		}
		Step step = steps.get(level);
		return s.level(Settlement.MAIN) >= step.minMain() && wallsOk(s, step) ? step : null;
	}

	/** Gate levels need a certain walls level. */
	public static boolean wallsOk(Settlement s, Step step) {
		return !GATE.equals(step.track()) || s.level(WALLS) >= GATE_MIN_WALLS[step.level() - 1];
	}

	public static Step current(Settlement s, String track) {
		List<Step> steps = steps(track);
		int level = s.level(track);
		return steps == null || level <= 0 || level > steps.size() ? null : steps.get(level - 1);
	}

	public static boolean requirementMet(ServerPlayer player, Step step) {
		if (step.advancement() == null || player.isCreative()) {
			return true;
		}
		var holder = player.level().getServer().getAdvancements().get(step.advancement());
		return holder != null && player.getAdvancements().getOrStartProgress(holder).isDone();
	}

	public static boolean canPay(ServerPlayer player, Step step) {
		if (player.isCreative()) {
			return true;
		}
		Inventory inv = player.getInventory();
		for (Cost c : step.costs()) {
			int have = 0;
			for (int i = 0; i < inv.getContainerSize(); i++) {
				ItemStack s = inv.getItem(i);
				if (c.matches().test(s)) {
					have += s.getCount();
				}
			}
			if (have < c.count()) {
				return false;
			}
		}
		return true;
	}

	public static void pay(ServerPlayer player, Step step) {
		if (player.isCreative()) {
			return;
		}
		Inventory inv = player.getInventory();
		for (Cost c : step.costs()) {
			int left = c.count();
			for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
				ItemStack s = inv.getItem(i);
				if (c.matches().test(s)) {
					int take = Math.min(left, s.getCount());
					s.shrink(take);
					left -= take;
				}
			}
		}
		inv.setChanged();
	}

	public static String costText(Step step) {
		StringBuilder b = new StringBuilder();
		for (Cost c : step.costs()) {
			if (!b.isEmpty()) {
				b.append(", ");
			}
			b.append(c.count()).append(' ').append(c.label());
		}
		return b.isEmpty() ? "-" : b.toString();
	}
}
