package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.KingdomQuest;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -> server: ride the tram from one stop to another (checked again on the server, see {@link Tram}). */
public record TramGoPayload(BlockPos heart, int from, int to) implements CustomPacketPayload {
	public static final Type<TramGoPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(KingdomQuest.MOD_ID, "tram_go"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TramGoPayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, TramGoPayload::heart, ByteBufCodecs.VAR_INT, TramGoPayload::from,
			ByteBufCodecs.VAR_INT, TramGoPayload::to, TramGoPayload::new);

	@Override
	public Type<TramGoPayload> type() {
		return TYPE;
	}
}
