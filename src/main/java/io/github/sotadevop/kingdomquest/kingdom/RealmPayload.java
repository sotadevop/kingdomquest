package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.KingdomQuest;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server -> client: everything the realm screen shows (map, buildings, people, animals), as JSON. */
public record RealmPayload(String json) implements CustomPacketPayload {
	public static final Type<RealmPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(KingdomQuest.MOD_ID, "realm"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RealmPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(1 << 20), RealmPayload::json, RealmPayload::new);

	@Override
	public Type<RealmPayload> type() {
		return TYPE;
	}
}
