package io.github.sotadevop.kingdomquest.kingdom;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.sotadevop.kingdomquest.KingdomQuest;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server -> client: everything the kingdom menu shows. {@code percent} is -1 when nothing is being built. */
public record KingdomInfoPayload(BlockPos heart, String name, int color, Identifier logo, int level, int percent,
		String nextTitle, String requirement, boolean requirementMet, String cost, boolean canPay) implements CustomPacketPayload {
	public static final Type<KingdomInfoPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(KingdomQuest.MOD_ID, "kingdom_info"));
	private static final Codec<KingdomInfoPayload> DATA = RecordCodecBuilder.create(i -> i.group(
			BlockPos.CODEC.fieldOf("heart").forGetter(KingdomInfoPayload::heart),
			Codec.STRING.fieldOf("name").forGetter(KingdomInfoPayload::name),
			Codec.INT.fieldOf("color").forGetter(KingdomInfoPayload::color),
			Identifier.CODEC.fieldOf("logo").forGetter(KingdomInfoPayload::logo),
			Codec.INT.fieldOf("level").forGetter(KingdomInfoPayload::level),
			Codec.INT.fieldOf("percent").forGetter(KingdomInfoPayload::percent),
			Codec.STRING.fieldOf("next").forGetter(KingdomInfoPayload::nextTitle),
			Codec.STRING.fieldOf("req").forGetter(KingdomInfoPayload::requirement),
			Codec.BOOL.fieldOf("req_met").forGetter(KingdomInfoPayload::requirementMet),
			Codec.STRING.fieldOf("cost").forGetter(KingdomInfoPayload::cost),
			Codec.BOOL.fieldOf("can_pay").forGetter(KingdomInfoPayload::canPay)
	).apply(i, KingdomInfoPayload::new));
	public static final StreamCodec<RegistryFriendlyByteBuf, KingdomInfoPayload> CODEC = ByteBufCodecs.fromCodec(DATA).cast();

	@Override
	public Type<KingdomInfoPayload> type() {
		return TYPE;
	}
}
