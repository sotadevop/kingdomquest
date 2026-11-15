package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.KingdomQuest;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -> server: a menu action for the settlement with this heart ("bridge", "link_kingdom", "link_village"). */
public record ActionPayload(BlockPos heart, String action) implements CustomPacketPayload {
	public static final Type<ActionPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(KingdomQuest.MOD_ID, "action"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ActionPayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, ActionPayload::heart, ByteBufCodecs.STRING_UTF8, ActionPayload::action, ActionPayload::new);

	@Override
	public Type<ActionPayload> type() {
		return TYPE;
	}
}
