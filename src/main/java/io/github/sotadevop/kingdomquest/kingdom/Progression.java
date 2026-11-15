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
import java.util.function.Predicate;

/**
 * Upgrade steps. Until StoryQuest has the Kingdom questline, each step is unlocked by a vanilla
 * advancement (placeholder for the quest), then paid for with items.
 */
public final class Progression {
	public record Cost(String label, int count, Predicate<ItemStack> matches) {
	}

	/** @param building template name without style, e.g. "tent_2" */
	public record Step(String building, int level, String titleKey, Identifier advancement, String requirementKey, List<Cost> costs) {
	}

	private static Cost item(Item item, int count) {
		return new Cost(BuiltInRegistries.ITEM.getKey(item).getPath().replace('_', ' '), count, s -> s.is(item));
	}

	private static Cost tag(TagKey<Item> tag, String label, int count) {
		return new Cost(label, count, s -> s.is(tag));
	}

	public static final List<Step> TENT = List.of(
			new Step("tent_1", 1, "kingdomquest.level.tent_1", null, null, List.of()),
			new Step("tent_2", 2, "kingdomquest.level.tent_2", Identifier.withDefaultNamespace("story/smelt_iron"),
					"kingdomquest.req.smelt_iron", List.of(tag(ItemTags.LOGS, "logs", 16), tag(ItemTags.WOOL, "wool", 8))),
			new Step("tent_3", 3, "kingdomquest.level.tent_3", Identifier.withDefaultNamespace("story/iron_tools"),
					"kingdomquest.req.iron_tools", List.of(tag(ItemTags.LOGS, "logs", 32), item(Items.COBBLESTONE, 16), item(Items.IRON_INGOT, 8))));

	private Progression() {
	}

	public static Step next(int level) {
		return level < TENT.size() ? TENT.get(level) : null;
	}

	public static boolean requirementMet(ServerPlayer player, Step step) {
		if (step.advancement() == null) {
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
