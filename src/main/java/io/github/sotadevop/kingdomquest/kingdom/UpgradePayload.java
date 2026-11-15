package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.KingdomQuest;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -> server: build the next step of {@code track} ("main", "farm", ...) at the settlement with this heart. */
public record UpgradePayload(BlockPos heart, String track) implements CustomPacketPayload {
	public static final Type<UpgradePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(KingdomQuest.MOD_ID, "upgrade"));
	public static final StreamCodec<RegistryFriendlyByteBuf, UpgradePayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, UpgradePayload::heart, ByteBufCodecs.STRING_UTF8, UpgradePayload::track, UpgradePayload::new);

	@Override
	public Type<UpgradePayload> type() {
		return TYPE;
	}
}
