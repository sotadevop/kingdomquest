package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.KingdomQuest;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -> server: give the settlement with this heart a new kingdom color (dye id). */
public record RecolorPayload(BlockPos heart, int color) implements CustomPacketPayload {
	public static final Type<RecolorPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(KingdomQuest.MOD_ID, "recolor"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RecolorPayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, RecolorPayload::heart, ByteBufCodecs.VAR_INT, RecolorPayload::color, RecolorPayload::new);

	@Override
	public Type<RecolorPayload> type() {
		return TYPE;
	}
}
