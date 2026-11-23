package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.KingdomQuest;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client -> server: something done in the storehouse ledger. The server checks everything again (see
 * {@link Storehouse#handle}): "take" (line id, amount), "deposit" (inventory slot), "deposit_all", "limit" (line id,
 * amount, 0 = none), "toggle|producer|item", "upgrade|producer", "unload", "open".
 */
public record StoreActionPayload(BlockPos heart, String action, int id, int amount) implements CustomPacketPayload {
	public static final Type<StoreActionPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(KingdomQuest.MOD_ID, "store_action"));
	public static final StreamCodec<RegistryFriendlyByteBuf, StoreActionPayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, StoreActionPayload::heart, ByteBufCodecs.stringUtf8(96), StoreActionPayload::action,
			ByteBufCodecs.VAR_INT, StoreActionPayload::id, ByteBufCodecs.VAR_INT, StoreActionPayload::amount, StoreActionPayload::new);

	@Override
	public Type<StoreActionPayload> type() {
		return TYPE;
	}
}
