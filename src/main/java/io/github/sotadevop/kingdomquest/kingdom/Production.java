package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.building.Template;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Rotation;

import java.util.List;

/**
 * Add-ons that produce goods put them into their storage container (the barrel of the template's "storage" field).
 * Runs every {@link #INTERVAL} ticks while the settlement is loaded; a full container just loses the overflow.
 */
public final class Production {
	/** 5 minutes. */
	public static final int INTERVAL = 6000;

	private Production() {
	}

	static void run(ServerLevel level, Settlement s) {
		int farm = s.level(Progression.FARM);
		if (farm <= 0 || Progression.FARM.equals(s.jobTrack()) || !level.isLoaded(s.heart())) {
			return;
		}
		Container box = storage(level, s, s.style() + "/farm_" + farm);
		if (box == null) {
			return;
		}
		List<ItemStack> harvest = List.of(new ItemStack(Items.WHEAT, 6), new ItemStack(Items.CARROT, 3), new ItemStack(Items.POTATO, 3));
		harvest.forEach(stack -> insert(box, stack));
		box.setChanged();
		ServerPlayer owner = level.getServer().getPlayerList().getPlayer(s.owner());
		if (owner != null && owner.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(s.heart())) < 64 * 64) {
			owner.sendOverlayMessage(Component.translatable("kingdomquest.farm.harvest", s.name()));
		}
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
