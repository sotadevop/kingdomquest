package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.building.Template;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The production system: every producing building has a worker whose output is worked out, not grown or killed.
 * What each producer makes is one entry in {@link #PRODUCERS}; adding a producer is adding an entry.
 * <ul>
 * <li>Production levels 1-5 per producer (bought in the storehouse ledger): x1, x2, x4, x4 plus a rarer drop, x8 plus
 * the rarest drop. Levels 4 and 5 need a quest (a vanilla advancement until StoryQuest has the Kingdom questline).</li>
 * <li>Rare drops can also need a vanilla milestone of the lord (the Nether, the End, ...), remembered on the settlement.</li>
 * <li>Output runs on game time with no limit: every round works out everything made since the last one, also after
 * the lord was away for days. Only room limits it: a full store (or stock limit) makes that output wait, nothing is
 * thrown away.</li>
 * <li>Everything goes to the {@link Storehouse}; before one is built, into the building's own barrel.</li>
 * </ul>
 */
public final class Production {
	/** Every 30 seconds while the settlement is loaded; output is worked out from the game time since the last round. */
	public static final int INTERVAL = 600;
	static final int DAY = 24000;
	public static final int MAX_LEVEL = 5;
	private static final int[] MULTIPLIER = {0, 1, 2, 4, 4, 8};

	/** Vanilla milestones that gate rare drops (the advancement the lord must have). */
	enum Milestone {
		NETHER("story/enter_the_nether"), FORTRESS("nether/find_fortress"), BASTION("nether/find_bastion"), END("story/enter_the_end"),
		END_CITY("end/find_end_city"), DRAGON("end/kill_dragon"), DIAMONDS("story/mine_diamond"), SNIFFER("husbandry/obtain_sniffer_egg"),
		TRIALS("adventure/minecraft_trials_edition");

		final String advancement;

		Milestone(String advancement) {
			this.advancement = advancement;
		}

		String key() {
			return "ms_" + name().toLowerCase();
		}
	}

	/**
	 * @param item      item id ("wheat"; "{wood}" is the style's wood, "potion:healing" a potion)
	 * @param perDay    items per game day at production level 1 (multiplied by the level)
	 * @param level     production level that unlocks it (4 = rarer drop, 5 = rarest)
	 * @param milestone also needed before it is made, or null
	 */
	record Output(String item, double perDay, int level, Milestone milestone) {
	}

	/**
	 * @param host      track of the building the worker works in, and the level it needs
	 * @param levels    false: no production levels (taxes, treasury)
	 * @param upgrade   item paid (with emeralds) for each production level
	 * @param quest4    advancement needed for level 4 (the quest placeholder), quest5 for level 5
	 */
	record Producer(String id, String host, int hostLevel, boolean levels, String upgrade, String quest4, String quest5, List<Output> outputs) {
	}

	private static Output out(String item, double perDay) {
		return new Output(item, perDay, 1, null);
	}

	private static Output rare(String item, double perDay, int level, Milestone milestone) {
		return new Output(item, perDay, level, milestone);
	}

	private static Producer producer(String id, String host, int hostLevel, String upgrade, String quest4, String quest5, Output... outputs) {
		return new Producer(id, host, hostLevel, true, upgrade, quest4, quest5, List.of(outputs));
	}

	private static List<Output> each(List<String> items, double perDay, int level, Milestone milestone) {
		return items.stream().map(i -> new Output(i, perDay, level, milestone)).toList();
	}

	private static Producer with(Producer p, List<Output> more) {
		List<Output> all = new ArrayList<>(p.outputs());
		all.addAll(more);
		return new Producer(p.id(), p.host(), p.hostLevel(), p.levels(), p.upgrade(), p.quest4(), p.quest5(), List.copyOf(all));
	}

	private static final Milestone NO = null;

	/**
	 * Who makes what. Level 1 of a raw-goods producer makes about a chest a game day. The hunter works from the
	 * tannery until it gets its own lodge (production chains); a new producer is one more entry here.
	 */
	static final List<Producer> PRODUCERS = List.of(
			producer("farmer", Progression.FARM, 1, "hay_block", "adventure/trade", "husbandry/plant_any_sniffer_seed",
					out("wheat", 448), out("carrot", 256), out("potato", 256), out("beetroot", 128), out("wheat_seeds", 128),
					out("beetroot_seeds", 64), out("pumpkin", 64), out("melon_slice", 128), out("sugar_cane", 128),
					rare("sweet_berries", 32, 4, NO), rare("glow_berries", 16, 4, NO), rare("cocoa_beans", 16, 4, NO),
					rare("nether_wart", 16, 4, Milestone.NETHER), rare("torchflower_seeds", 2, 5, Milestone.SNIFFER),
					rare("pitcher_pod", 2, 5, Milestone.SNIFFER), rare("chorus_fruit", 8, 5, Milestone.END)),
			producer("shepherd", Progression.FARM, 2, "lead", "husbandry/tame_an_animal", "husbandry/bred_all_animals",
					out("white_wool", 384), out("mutton", 192), rare("light_gray_wool", 32, 4, NO), rare("gray_wool", 32, 4, NO),
					rare("black_wool", 32, 4, NO), rare("brown_wool", 32, 4, NO), rare("pink_wool", 8, 5, NO)),
			producer("herder", Progression.FARM, 2, "lead", "husbandry/tame_an_animal", "husbandry/bred_all_animals",
					out("beef", 192), out("porkchop", 192), out("chicken", 128), out("egg", 128), out("feather", 128), out("leather", 128),
					rare("rabbit", 32, 4, NO), rare("rabbit_hide", 32, 4, NO), rare("rabbit_foot", 4, 4, NO),
					rare("armadillo_scute", 8, 5, NO), rare("turtle_scute", 4, 5, NO)),
			producer("hunter", "tannery", 1, "arrow", "adventure/kill_a_mob", "adventure/kill_all_mobs",
					out("rotten_flesh", 384), out("bone", 256), out("string", 192), out("gunpowder", 128), out("arrow", 256), out("spider_eye", 64),
					rare("slime_ball", 32, 4, NO), rare("phantom_membrane", 8, 4, NO), rare("ender_pearl", 8, 4, Milestone.END),
					rare("blaze_rod", 8, 4, Milestone.NETHER), rare("magma_cream", 8, 4, Milestone.NETHER),
					rare("ghast_tear", 2, 5, Milestone.NETHER), rare("shulker_shell", 2, 5, Milestone.END_CITY),
					rare("wither_skeleton_skull", 0.25, 5, Milestone.FORTRESS), rare("breeze_rod", 4, 5, Milestone.TRIALS)),
			producer("tanner", "tannery", 1, "leather", "husbandry/breed_an_animal", "husbandry/bred_all_animals",
					out("leather", 192), out("rabbit_hide", 64), rare("bundle", 1, 4, NO), rare("saddle", 0.5, 4, NO),
					rare("leather_horse_armor", 1, 5, NO)),
			producer("beekeeper", Progression.APIARY, 1, "honeycomb", "husbandry/safely_harvest_honey", "husbandry/silk_touch_nest",
					out("honeycomb", 192), out("honey_bottle", 64), out("poppy", 32), out("dandelion", 32), rare("honey_block", 16, 4, NO),
					rare("bee_nest", 0.5, 5, NO)),
			producer("fisher", Progression.DOCK, 1, "string", "husbandry/tactical_fishing", "husbandry/axolotl_in_a_bucket",
					out("cod", 384), out("salmon", 256), out("tropical_fish", 64), out("pufferfish", 32), out("ink_sac", 64), out("kelp", 192),
					out("lily_pad", 32), rare("nautilus_shell", 2, 4, NO), rare("name_tag", 1, 4, NO), rare("saddle", 1, 4, NO),
					rare("glow_ink_sac", 16, 4, NO), rare("prismarine_shard", 32, 5, NO), rare("prismarine_crystals", 16, 5, NO),
					rare("wet_sponge", 0.5, 5, NO)),
			producer("lumberjack", Progression.WOOD, 1, "iron_ingot", "story/upgrade_tools", "adventure/trade",
					out("{wood}_log", 960), out("stick", 256), out("{wood}_sapling", 64), out("apple", 32), out("charcoal", 64),
					rare("birch_log", 64, 4, NO), rare("cherry_log", 64, 4, NO), rare("dark_oak_log", 64, 4, NO),
					rare("pale_oak_log", 32, 5, NO), rare("crimson_stem", 32, 5, Milestone.NETHER), rare("warped_stem", 32, 5, Milestone.NETHER)),
			producer("miner", "mine", 1, "torch", "story/mine_diamond", "nether/obtain_ancient_debris",
					out("cobblestone", 448), out("coal", 384), out("raw_iron", 128), out("raw_copper", 192), out("redstone", 128),
					out("lapis_lazuli", 64), out("flint", 64), out("cobbled_deepslate", 192), rare("raw_gold", 64, 4, NO),
					rare("amethyst_shard", 32, 4, NO), rare("emerald", 4, 4, NO), rare("obsidian", 16, 5, NO),
					rare("diamond", 2, 5, Milestone.DIAMONDS)),
			with(producer("smith", Progression.SMITH, 1, "iron_ingot", "story/mine_diamond", "end/kill_dragon",
					out("iron_ingot", 64), out("iron_nugget", 128), out("gold_ingot", 16), rare("crossbow", 0.5, 4, NO),
					rare("netherite_scrap", 0.5, 5, Milestone.NETHER), rare("elytra", 0.125, 5, Milestone.DRAGON)),
					concat(each(List.of("iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots", "iron_sword", "iron_pickaxe", "iron_axe",
							"iron_shovel", "shield", "bucket", "shears"), 0.25, 1, NO),
							each(List.of("diamond_sword", "diamond_pickaxe", "diamond_helmet", "diamond_chestplate", "diamond_leggings", "diamond_boots"),
									0.125, 4, Milestone.DIAMONDS))),
			with(producer("armorer", "armorer", 1, "iron_ingot", "story/mine_diamond", "nether/netherite_armor",
					out("iron_ingot", 32), rare("netherite_upgrade_smithing_template", 0.25, 5, Milestone.BASTION)),
					concat(each(List.of("chainmail_helmet", "chainmail_chestplate", "chainmail_leggings", "chainmail_boots"), 0.25, 1, NO),
							each(List.of("coast_armor_trim_smithing_template", "dune_armor_trim_smithing_template", "wild_armor_trim_smithing_template",
									"sentry_armor_trim_smithing_template", "vex_armor_trim_smithing_template", "tide_armor_trim_smithing_template",
									"rib_armor_trim_smithing_template"), 0.25, 4, NO))),
			producer("tinkerer", "workshop", 1, "copper_ingot", "husbandry/wax_on", "adventure/minecraft_trials_edition",
					out("copper_ingot", 64), out("redstone", 64), rare("repeater", 4, 4, NO), rare("comparator", 2, 4, NO),
					rare("trial_key", 1, 5, Milestone.TRIALS)),
			with(producer("merchant", "market", 1, "emerald", "adventure/trade", "adventure/trade_at_world_height",
					rare("name_tag", 1, 4, NO), rare("globe_banner_pattern", 0.25, 5, NO)),
					concat(each(List.of("red_dye", "blue_dye", "yellow_dye", "green_dye", "white_dye", "black_dye"), 8, 1, NO),
							each(List.of("oak_sapling", "birch_sapling", "cherry_sapling", "acacia_sapling", "spruce_sapling"), 2, 1, NO))),
			producer("cartographer", "cartographer", 1, "paper", "story/follow_ender_eye", "end/find_end_city",
					out("paper", 64), out("compass", 1), rare("map", 4, 4, NO), rare("spyglass", 0.5, 5, NO)),
			with(producer("bard", "music", 1, "note_block", "adventure/play_jukebox_in_meadows", "nether/find_bastion",
					out("note_block", 8), rare("jukebox", 1, 4, NO), rare("music_disc_pigstep", 0.125, 5, Milestone.BASTION)),
					each(List.of("music_disc_13", "music_disc_cat", "music_disc_blocks", "music_disc_chirp", "music_disc_far", "music_disc_mall",
							"music_disc_mellohi", "music_disc_stal", "music_disc_strad", "music_disc_ward", "music_disc_wait"), 0.05, 1, NO)),
			with(producer("archaeologist", "archaeology", 1, "brush", "adventure/salvage_sherd", "husbandry/obtain_sniffer_egg",
					out("brush", 0.5), rare("sniffer_egg", 0.125, 5, Milestone.SNIFFER)),
					concat(each(List.of("archer_pottery_sherd", "arms_up_pottery_sherd", "prize_pottery_sherd", "skull_pottery_sherd"), 0.25, 1, NO),
							each(List.of("heart_pottery_sherd", "howl_pottery_sherd", "miner_pottery_sherd"), 0.25, 4, NO))),
			producer("librarian", Progression.LIBRARY, 1, "book", "story/enchant_item", "adventure/read_power_of_chiseled_bookshelf",
					out("paper", 64), out("book", 32), out("lapis_lazuli", 32), rare("experience_bottle", 16, 4, NO),
					rare("chiseled_bookshelf", 2, 5, NO)),
			// the monastery's scriptorium: monks copy books, and some come out enchanted
			producer("scribe", Progression.MONASTERY, 1, "book", "story/enchant_item", "adventure/read_power_of_chiseled_bookshelf",
					out("book", 16), out("paper", 32), out("book:unbreaking:1", 1), out("book:efficiency:1", 1), out("book:protection:1", 1),
					rare("book:sharpness:3", 1, 4, NO), rare("book:feather_falling:3", 1, 4, NO),
					rare("book:mending:1", 0.5, 5, Milestone.DIAMONDS), rare("book:fortune:3", 0.5, 5, Milestone.DIAMONDS)),
			producer("alchemist", Progression.BREWERY, 1, "glass_bottle", "nether/brew_potion", "nether/all_potions",
					out("potion:healing", 4), out("glass_bottle", 32), rare("potion:swiftness", 2, 4, NO), rare("potion:regeneration", 2, 4, NO),
					rare("potion:strong_healing", 2, 5, Milestone.NETHER), rare("potion:fire_resistance", 2, 5, Milestone.NETHER)),
			producer("innkeeper", "tavern", 1, "bread", "adventure/trade", "husbandry/balanced_diet",
					out("bread", 64), out("cooked_cod", 32), out("pumpkin_pie", 16), rare("cake", 2, 4, NO), rare("golden_carrot", 16, 5, NO)),
			producer("stable_hand", Progression.STABLES, 1, "hay_block", "husbandry/breed_an_animal", "story/mine_diamond",
					out("leather", 32), out("wheat", 64), rare("golden_carrot", 16, 4, NO), rare("saddle", 1, 4, NO),
					rare("diamond_horse_armor", 0.25, 5, Milestone.DIAMONDS)),
			producer("miller", "mill", 1, "wheat", "adventure/trade", "husbandry/plant_any_sniffer_seed",
					out("sugar", 192), out("bone_meal", 128), out("wheat", 128), out("hay_block", 32), rare("bone_block", 16, 4, NO),
					rare("cocoa_beans", 32, 5, NO)),
			producer("baker", "bakery", 1, "wheat", "husbandry/balanced_diet", "husbandry/plant_any_sniffer_seed",
					out("bread", 256), out("cookie", 128), out("pumpkin_pie", 32), out("cake", 4), rare("golden_carrot", 16, 4, NO),
					rare("golden_apple", 1, 5, Milestone.DIAMONDS)),
			producer("herbalist", "herbs", 1, "bone_meal", "nether/brew_potion", "nether/all_potions",
					out("sweet_berries", 128), out("brown_mushroom", 64), out("red_mushroom", 64), out("glow_berries", 32),
					new Output("nether_wart", 64, 1, Milestone.NETHER), rare("glistering_melon_slice", 16, 4, NO), rare("golden_carrot", 16, 4, NO),
					rare("fermented_spider_eye", 16, 4, NO), rare("rabbit_foot", 2, 5, NO), rare("phantom_membrane", 4, 5, NO)),
			producer("weaver", "weaver", 1, "string", "adventure/trade", "husbandry/bred_all_animals",
					out("white_carpet", 128), out("white_wool", 128), out("white_banner", 8), out("painting", 8), out("white_bed", 4),
					rare("lead", 8, 4, NO), rare("name_tag", 1, 5, NO)),
			with(producer("dyer", "dyehouse", 1, "bone_meal", "adventure/trade", "husbandry/bred_all_animals",
					rare("glow_ink_sac", 8, 4, NO), rare("glow_lichen", 16, 5, NO)),
					each(List.of("white_dye", "light_gray_dye", "gray_dye", "black_dye", "brown_dye", "red_dye", "orange_dye", "yellow_dye",
							"lime_dye", "green_dye", "cyan_dye", "light_blue_dye", "blue_dye", "purple_dye", "magenta_dye", "pink_dye"), 24, 1, NO)),
			producer("carpenter", "carpenter", 1, "stick", "adventure/trade", "story/mine_diamond",
					out("chest", 16), out("barrel", 16), out("crafting_table", 8), out("{wood}_fence", 64), out("ladder", 64), out("bowl", 64),
					out("{wood}_door", 8), rare("bookshelf", 8, 4, NO), rare("jukebox", 1, 5, Milestone.DIAMONDS)),
			producer("sawyer", "sawmill", 1, "iron_axe", "adventure/trade", "story/mine_diamond",
					out("{wood}_planks", 512), out("stick", 256), out("{wood}_slab", 128), rare("dark_oak_planks", 128, 4, NO),
					rare("cherry_planks", 64, 5, NO), rare("mangrove_planks", 64, 5, NO)),
			producer("smelter", "smelter", 1, "coal", "story/smelt_iron", "nether/obtain_ancient_debris",
					out("iron_ingot", 64), out("copper_ingot", 128), out("gold_ingot", 32), out("glass", 256), out("charcoal", 128),
					out("smooth_stone", 128), rare("iron_block", 4, 4, NO), rare("netherite_scrap", 0.25, 5, Milestone.NETHER)),
			producer("saddler", "saddler", 1, "leather", "husbandry/tame_an_animal", "story/mine_diamond",
					out("saddle", 2), out("leather_horse_armor", 2), out("lead", 16), out("bundle", 4), rare("iron_horse_armor", 1, 4, NO),
					rare("golden_horse_armor", 0.5, 5, NO), rare("diamond_horse_armor", 0.25, 5, Milestone.DIAMONDS)),
			producer("nether_trader", Progression.NETHER, 1, "gold_ingot", "nether/distract_piglin", "nether/find_bastion",
					out("quartz", 64), out("soul_sand", 32), out("obsidian", 16), out("ender_pearl", 4), rare("crying_obsidian", 16, 4, NO),
					rare("potion:fire_resistance", 2, 4, NO), rare("ancient_debris", 0.5, 5, Milestone.BASTION)),
			// no production levels: the lord's income
			new Producer("taxes", Settlement.MAIN, 5, false, "", "", "", List.of(out("emerald", 8), out("cooked_beef", 12), out("bread", 12))),
			new Producer("town_taxes", Settlement.MAIN, 8, false, "", "", "", List.of(out("emerald", 16), out("gold_ingot", 8))),
			new Producer("treasury", Progression.CELLAR, 2, false, "", "", "", List.of(out("emerald", 8), out("gold_ingot", 8))));

	private static List<Output> concat(List<Output> a, List<Output> b) {
		List<Output> all = new ArrayList<>(a);
		all.addAll(b);
		return all;
	}

	private static final Map<String, Holder<Potion>> POTIONS = Map.of("healing", Potions.HEALING, "strong_healing", Potions.STRONG_HEALING,
			"swiftness", Potions.SWIFTNESS, "regeneration", Potions.REGENERATION, "fire_resistance", Potions.FIRE_RESISTANCE);

	private Production() {
	}

	/** Output multiplier of a production level. */
	public static int multiplier(int level) {
		return MULTIPLIER[Math.max(0, Math.min(MAX_LEVEL, level))];
	}

	static Producer producer(String id) {
		return PRODUCERS.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
	}

	/** The producer works: its building stands at the level it needs. */
	static boolean active(Settlement s, Producer p) {
		return s.level(p.host()) >= p.hostLevel();
	}

	/** Production level 1-5 (1 for producers without levels). */
	static int level(Settlement s, Producer p) {
		return p.levels() ? Math.max(1, Math.min(MAX_LEVEL, s.level("prod_" + p.id()))) : 1;
	}

	static boolean unlocked(Settlement s, Producer p, Output o) {
		return level(s, p) >= o.level() && (o.milestone() == null || s.level(o.milestone().key()) > 0);
	}

	/** Items a game day at the producer's current level (0 while locked). */
	static double perDay(Settlement s, Producer p, Output o) {
		return unlocked(s, p, o) ? o.perDay() * MULTIPLIER[level(s, p)] : 0;
	}

	static String woodOf(String style) {
		return switch (style) {
			case Style.FROZEN, Style.HIGHLAND -> "spruce";
			case Style.ARID -> "acacia";
			case Style.WILD -> "jungle";
			default -> "oak";
		};
	}

	/** The stack (count 1) an output stands for in this style; empty if the item doesn't exist. */
	static ItemStack stack(String spec, String style) {
		if (spec.startsWith("book:")) {             // an enchanted book: "book:<enchantment>:<level>"
			String[] b = spec.split(":");
			MinecraftServer srv = Settlements.server();
			if (srv == null || b.length != 3) {
				return ItemStack.EMPTY;
			}
			return srv.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
					.get(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT, Identifier.withDefaultNamespace(b[1])))
					.map(h -> net.minecraft.world.item.enchantment.EnchantmentHelper.createBook(
							new net.minecraft.world.item.enchantment.EnchantmentInstance(h, Integer.parseInt(b[2]))))
					.orElse(ItemStack.EMPTY);
		}
		if (spec.startsWith("potion:")) {
			Holder<Potion> potion = POTIONS.get(spec.substring(7));
			return potion == null ? ItemStack.EMPTY : PotionContents.createItemStack(Items.POTION, potion);
		}
		Item item = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(spec.replace("{wood}", woodOf(style))));
		return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
	}

	/** The key of one output of one producer: for the drop toggles and the fractions carried between rounds. */
	static String key(Producer p, Output o) {
		return p.id() + "|" + o.item();
	}

	static void run(ServerLevel level, Settlement s) {
		if (level.isLoaded(s.heart())) {
			produce(level, s, level.getGameTime());
		}
	}

	/**
	 * Works out everything the producers made since the last round (game time, no limit) and delivers it. Returns
	 * the number of items delivered.
	 */
	static long produce(ServerLevel level, Settlement given, long now) {
		MinecraftServer server = level.getServer();
		Settlement s = milestones(server, Settlements.byHeart(server, given.heart()));
		if (s == null) {
			return 0;
		}
		if (server.getTickCount() % 6000 < INTERVAL) {
			Progression.Step gate = Progression.current(s, Progression.GATE);
			if (gate != null && gate.level() >= 4 && !Progression.GATE.equals(s.jobTrack())) {
				Template t = Template.get(Settlements.template(s, gate.building()));
				if (t != null) {
					armTraps(level, s, t.placements(Settlements.dye(s.color()), Rotation.values()[s.rotation()]));
				}
			}
		}
		Storehouse.Stock stock = Storehouse.stock(server, s);
		long last = stock.last;
		if (last <= 0 || now <= last) {
			if (last <= 0) {
				stock.last = now; // the first round only starts the clock
				stock.save(server, s);
			}
			return 0;
		}
		long elapsed = now - last;
		boolean stored = Storehouse.built(s);
		int capacity = Storehouse.capacity(s);
		long delivered = 0;
		Map<String, Double> carry = new HashMap<>();
		for (Producer p : PRODUCERS) {
			if (!active(s, p)) {
				continue;
			}
			Container barrel = stored ? null : barrel(level, s, p.host());
			if (!stored && barrel == null) {
				continue;
			}
			for (Output o : p.outputs()) {
				String key = key(p, o);
				ItemStack item = stack(o.item(), s.style());
				if (item.isEmpty() || !unlocked(s, p, o) || stock.off.contains(key)) {
					continue;
				}
				double amount = perDay(s, p, o) * elapsed / DAY + stock.carry.getOrDefault(key, 0.0);
				long n = (long) Math.floor(amount);
				long got = n <= 0 ? 0 : stored ? stock.add(item, n, capacity, true, now) : intoBarrel(barrel, item, n);
				delivered += got;
				if (got == n && amount - n > 1e-9) {
					carry.put(key, amount - n); // a full store makes the output wait: no time is banked meanwhile
				}
			}
			if (barrel != null) {
				barrel.setChanged();
			}
		}
		stock.carry.clear();
		stock.carry.putAll(carry);
		stock.last = now;
		stock.save(server, s);
		if (stored) {
			Storehouse.updateFill(level, s, stock);
		}
		ServerPlayer owner = server.getPlayerList().getPlayer(s.owner());
		if (delivered > 0 && elapsed >= DAY / 2 && owner != null && owner.distanceToSqr(Vec3.atCenterOf(s.heart())) < 96 * 96) {
			owner.sendOverlayMessage(Component.translatable("kingdomquest.production", s.name()));
		}
		return delivered;
	}

	/** Remembers on the settlement every rare-drop milestone the lord has reached (he must be online to check). */
	static Settlement milestones(MinecraftServer server, Settlement s) {
		if (s == null) {
			return null;
		}
		ServerPlayer owner = server.getPlayerList().getPlayer(s.owner());
		if (owner == null) {
			return s;
		}
		Settlement next = s;
		for (Milestone m : Milestone.values()) {
			if (next.level(m.key()) == 0) {
				var holder = server.getAdvancements().get(Identifier.withDefaultNamespace(m.advancement));
				if (holder != null && owner.getAdvancements().getOrStartProgress(holder).isDone()) {
					next = next.withLevel(m.key(), 1);
					Chronicle.milestone(server, s, m.name().toLowerCase());
				}
			}
		}
		if (next != s) {
			Settlements.put(server, next);
		}
		return next;
	}

	// ---- production levels ----

	/** The step to the next production level (costs and quest), or null at the top or without levels. */
	static Progression.Step nextLevel(Settlement s, Producer p) {
		int lvl = level(s, p);
		if (!p.levels() || lvl >= MAX_LEVEL) {
			return null;
		}
		int next = lvl + 1;
		String quest = next == 4 ? p.quest4() : next == 5 ? p.quest5() : "";
		Item material = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(p.upgrade()));
		int count = Math.min(material.getDefaultMaxStackSize() * 2, 8 << (next - 1));
		List<Progression.Cost> costs = List.of(
				new Progression.Cost(p.upgrade().replace('_', ' '), count, st -> st.is(material)),
				new Progression.Cost("emerald", 4 << (next - 1), st -> st.is(Items.EMERALD)));
		return new Progression.Step("prod_" + p.id(), "", next, "kingdomquest.producer." + p.id(), 0,
				quest.isEmpty() ? null : Identifier.withDefaultNamespace(quest), quest.isEmpty() ? null : advancementTitle(quest), costs);
	}

	/** Vanilla's own title of an advancement ("story/mine_diamond" -> its "Diamonds!" key). */
	static String advancementTitle(String path) {
		return "advancements." + path.replace('/', '.') + ".title";
	}

	/** Buys the next production level (server side, all checks here). */
	static boolean upgrade(ServerPlayer player, Settlement s, Producer p) {
		Progression.Step step = p == null || !active(s, p) ? null : nextLevel(s, p);
		if (step == null || !Progression.requirementMet(player, step) || !Progression.canPay(player, step)) {
			return false;
		}
		Progression.pay(player, step);
		Settlements.put(player.level().getServer(), s.withLevel("prod_" + p.id(), step.level()));
		return true;
	}

	// ---- containers ----

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

	/** The barrel of the host building's current level (before there is a storehouse), or null. */
	private static Container barrel(ServerLevel level, Settlement s, String host) {
		Progression.Step step = Progression.current(s, host);
		return step == null || host.equals(s.jobTrack()) ? null : storage(level, s, Settlements.template(s, step.building()));
	}

	/** Puts up to n of the item into a container (only what fits); returns how many went in. */
	private static long intoBarrel(Container box, ItemStack item, long n) {
		long left = n;
		while (left > 0) {
			ItemStack part = item.copyWithCount((int) Math.min(left, item.getMaxStackSize()));
			int before = part.getCount();
			insert(box, part);
			left -= before - part.getCount();
			if (!part.isEmpty()) {
				break;
			}
		}
		return n - left;
	}

	/**
	 * The steward's round: everything the workshops, farms and shops have put in their stores is handed to the lord
	 * (what does not fit is dropped at his feet). Returns {items, stores emptied}.
	 */
	static int[] collect(ServerLevel level, Settlement s, net.minecraft.server.level.ServerPlayer player) {
		int items = 0;
		int stores = 0;
		for (String track : Progression.ORDER) {
			Progression.Step step = Progression.current(s, track);
			if (step == null || track.equals(Progression.CELLAR) || track.equals(Progression.STOREHOUSE) || track.equals(s.jobTrack())) {
				continue; // the cellar is the lord's own store, the storehouse has its ledger
			}
			Container box = storage(level, s, Settlements.template(s, step.building()));
			if (box == null) {
				continue;
			}
			boolean any = false;
			for (int i = 0; i < box.getContainerSize(); i++) {
				ItemStack stack = box.getItem(i);
				if (stack.isEmpty()) {
					continue;
				}
				box.setItem(i, ItemStack.EMPTY);
				items += stack.getCount();
				any = true;
				if (!player.getInventory().add(stack) && !stack.isEmpty()) {
					net.minecraft.world.Containers.dropItemStack(level, player.getX(), player.getY(), player.getZ(), stack);
				}
			}
			if (any) {
				box.setChanged();
				stores++;
			}
		}
		return new int[] {items, stores};
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
