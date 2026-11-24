package io.github.sotadevop.kingdomquest.charter;

import io.github.sotadevop.kingdomquest.KingdomQuest;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;

/** The Royal Charter: its founding menu sets the kingdom's name, color and logo. Placing comes next. */
public final class RoyalCharter {
	public static final DataComponentType<CharterData> DATA = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
			Identifier.fromNamespaceAndPath(KingdomQuest.MOD_ID, "charter"),
			DataComponentType.<CharterData>builder().persistent(CharterData.CODEC).networkSynchronized(CharterData.STREAM_CODEC).build());

	public static final ResourceKey<Item> KEY =
			ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(KingdomQuest.MOD_ID, "royal_charter"));
	public static final Item ITEM = Registry.register(BuiltInRegistries.ITEM, KEY,
			new RoyalCharterItem(new Item.Properties().setId(KEY).stacksTo(1).rarity(Rarity.UNCOMMON)));

	private RoyalCharter() {
	}

	public static void register() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(output -> output.accept(ITEM));
		PayloadTypeRegistry.serverboundPlay().register(FoundingPayload.TYPE, FoundingPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(FoundingPayload.TYPE, (payload, context) -> found(context.player(), payload));
	}

	/** Checks the menu's choices on the server and writes them onto the held charter. */
	static void found(ServerPlayer player, FoundingPayload payload) {
		ItemStack stack = player.getItemInHand(payload.offhand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
		if (!stack.is(ITEM)) {
			return;
		}
		CharterData d = payload.data();
		String name = d.name().strip();
		if (name.isEmpty()) {
			return;
		}
		if (name.length() > CharterData.MAX_NAME) {
			name = name.substring(0, CharterData.MAX_NAME);
		}
		if (!BuiltInRegistries.ITEM.containsKey(d.logo()) || BuiltInRegistries.ITEM.getValue(d.logo()) == net.minecraft.world.item.Items.AIR) {
			return;
		}
		int color = Math.clamp(d.color(), 0, DyeColor.values().length - 1);
		stack.set(DATA, new CharterData(name, color, d.logo(), Math.clamp(d.gate(), CharterData.AUTO, 3), Math.clamp(d.look(), 0, CharterData.LOOKS - 1),
				0)); // the round castle comes in a later wave: every castle is square for now
	}
}
