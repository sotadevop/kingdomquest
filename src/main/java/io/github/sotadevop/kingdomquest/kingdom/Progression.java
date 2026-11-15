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
									tag(ItemTags.WOOL, "wool", 8), item(Items.EMERALD, 4)))),
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
							List.of(item(Items.COBBLESTONE, 32), item(Items.IRON_INGOT, 16), item(Items.GOLD_INGOT, 4)))),
			WOOD, List.of(
					new Step(WOOD, "wood_1", 1, "kingdomquest.addon.wood_1", 4, adv("story/upgrade_tools"), "kingdomquest.req.stone_tools",
							List.of(tag(ItemTags.LOGS, "logs", 32), item(Items.IRON_AXE, 1))),
					new Step(WOOD, "wood_2", 2, "kingdomquest.addon.wood_2", 5, adv("story/lava_bucket"), "kingdomquest.req.lava",
							List.of(tag(ItemTags.LOGS, "logs", 16), item(Items.STONECUTTER, 1), item(Items.IRON_INGOT, 4)))));

	/** Order the tracks appear in the menu. */
	public static final List<String> ORDER = List.of(Settlement.MAIN, FARM, SMITH, WOOD);

	public static List<Step> steps(String track) {
		List<Step> l = TRACKS.get(track);
		return l != null ? l : ADDONS_2.get(track);
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
		return s.level(Settlement.MAIN) >= step.minMain() ? step : null;
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
