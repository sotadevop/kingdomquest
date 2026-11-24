package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.KingdomQuest;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server -> client: the kingdom's chronicle (name and entries), as JSON. */
public record ChroniclePayload(String json) implements CustomPacketPayload {
	public static final Type<ChroniclePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(KingdomQuest.MOD_ID, "chronicle"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ChroniclePayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(262144), ChroniclePayload::json, ChroniclePayload::new);

	@Override
	public Type<ChroniclePayload> type() {
		return TYPE;
	}
}
