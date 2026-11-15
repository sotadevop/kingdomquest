package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.KingdomQuest;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -> server: build the next level of the settlement whose heart is at {@code heart}. */
public record UpgradePayload(BlockPos heart) implements CustomPacketPayload {
	public static final Type<UpgradePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(KingdomQuest.MOD_ID, "upgrade"));
	public static final StreamCodec<RegistryFriendlyByteBuf, UpgradePayload> CODEC =
			StreamCodec.composite(BlockPos.STREAM_CODEC, UpgradePayload::heart, UpgradePayload::new);

	@Override
	public Type<UpgradePayload> type() {
		return TYPE;
	}
}
