package io.github.sotadevop.kingdomquest.charter;

import io.github.sotadevop.kingdomquest.KingdomQuest;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Sent by the founding menu: the choices for the charter held in {@code offhand} or the main hand. */
public record FoundingPayload(CharterData data, boolean offhand) implements CustomPacketPayload {
	public static final Type<FoundingPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(KingdomQuest.MOD_ID, "founding"));
	public static final StreamCodec<RegistryFriendlyByteBuf, FoundingPayload> CODEC = StreamCodec.composite(
			CharterData.STREAM_CODEC, FoundingPayload::data,
			net.minecraft.network.codec.ByteBufCodecs.BOOL, FoundingPayload::offhand,
			FoundingPayload::new);

	@Override
	public Type<FoundingPayload> type() {
		return TYPE;
	}
}
