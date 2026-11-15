package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.KingdomQuest;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Both ways: the server asks for a name ({@code name} = suggestion), the client answers with the chosen name.
 * {@code key} says what is being named, e.g. "pet_wolf".
 */
public record NamePayload(BlockPos heart, String key, String name) implements CustomPacketPayload {
	public static final Type<NamePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(KingdomQuest.MOD_ID, "name"));
	public static final StreamCodec<RegistryFriendlyByteBuf, NamePayload> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, NamePayload::heart,
			ByteBufCodecs.STRING_UTF8, NamePayload::key, ByteBufCodecs.STRING_UTF8, NamePayload::name, NamePayload::new);

	@Override
	public Type<NamePayload> type() {
		return TYPE;
	}
}
