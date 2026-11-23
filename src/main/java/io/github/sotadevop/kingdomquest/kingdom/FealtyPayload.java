package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.KingdomQuest;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Both ways, for the Fealty Ceremony at a village bell: the server opens the "Claim Fealty" menu with what it shows
 * (JSON: suggested name, villager count, kingdom, ruler, color, logo, day); the client answers once the charter is
 * signed and sealed (JSON: the chosen name).
 */
public record FealtyPayload(BlockPos bell, String json) implements CustomPacketPayload {
	public static final Type<FealtyPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(KingdomQuest.MOD_ID, "fealty"));
	public static final StreamCodec<RegistryFriendlyByteBuf, FealtyPayload> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, FealtyPayload::bell,
			ByteBufCodecs.stringUtf8(4096), FealtyPayload::json, FealtyPayload::new);

	@Override
	public Type<FealtyPayload> type() {
		return TYPE;
	}
}
