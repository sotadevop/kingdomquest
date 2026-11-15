package io.github.sotadevop.kingdomquest.charter;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Right-click opens the founding menu (client side; set by the client entrypoint). */
public class RoyalCharterItem extends Item {
	/** Opens the founding menu for the charter in the given hand; replaced by the client. */
	public static BiConsumer<ItemStack, Boolean> openScreen = (stack, offhand) -> {
	};

	public RoyalCharterItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (level.isClientSide()) {
			openScreen.accept(player.getItemInHand(hand), hand == InteractionHand.OFF_HAND);
		}
		return InteractionResult.SUCCESS;
	}

	/** Sealed charter on the top of a block: preview is drawn by the client, two clicks found the settlement. */
	@Override
	public InteractionResult useOn(net.minecraft.world.item.context.UseOnContext context) {
		ItemStack stack = context.getItemInHand();
		CharterData data = stack.get(RoyalCharter.DATA);
		Player player = context.getPlayer();
		if (data == null || player == null || player.isShiftKeyDown() || context.getClickedFace() != net.minecraft.core.Direction.UP) {
			return use(context.getLevel(), player, context.getHand());
		}
		if (player instanceof net.minecraft.server.level.ServerPlayer sp
				&& io.github.sotadevop.kingdomquest.kingdom.Settlements.tryFound(sp, data, context.getClickedPos())) {
			stack.shrink(1);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> lines, TooltipFlag flag) {
		CharterData d = stack.get(RoyalCharter.DATA);
		if (d == null) {
			lines.accept(Component.translatable("item.kingdomquest.royal_charter.tooltip").withStyle(ChatFormatting.GRAY));
			return;
		}
		lines.accept(Component.literal(d.name()).withStyle(ChatFormatting.GOLD));
		lines.accept(Component.translatable("item.kingdomquest.royal_charter.sealed").withStyle(ChatFormatting.GRAY));
		lines.accept(Component.translatable("kingdomquest.founding.color").append(": ")
				.append(Component.translatable("color.minecraft." + DyeColor.byId(d.color()).getName())).withStyle(ChatFormatting.GRAY));
	}
}
