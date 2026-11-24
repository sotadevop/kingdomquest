package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.KingdomQuest;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server -> client: the tram stop menu (settlement heart, the stop you are at, the stops on the line), as JSON. */
public record TramPayload(String json) implements CustomPacketPayload {
	public static final Type<TramPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(KingdomQuest.MOD_ID, "tram"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TramPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(4096), TramPayload::json, TramPayload::new);

	@Override
	public Type<TramPayload> type() {
		return TYPE;
	}
}
