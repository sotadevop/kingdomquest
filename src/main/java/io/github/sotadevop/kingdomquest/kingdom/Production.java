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
		BlockPos offset = t == null ? null : t.storageOffset(Rotation.values()[s.rotation()]);
		return offset != null && level.getBlockEntity(s.heart().offset(offset)) instanceof Container c ? c : null;
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
