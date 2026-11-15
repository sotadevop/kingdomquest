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

import java.util.List;

/**
 * Server -> client: everything the kingdom menu shows. {@code percent} is -1 when nothing is being built.
 * One {@link Option} per track that has a next step.
 */
public record KingdomInfoPayload(BlockPos heart, String name, int color, Identifier logo, String levelTitle, int percent,
		List<Option> options) implements CustomPacketPayload {
	public record Option(String track, String title, String requirement, boolean requirementMet, String cost, boolean canPay) {
		static final Codec<Option> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("track").forGetter(Option::track),
				Codec.STRING.fieldOf("title").forGetter(Option::title),
				Codec.STRING.fieldOf("req").forGetter(Option::requirement),
				Codec.BOOL.fieldOf("req_met").forGetter(Option::requirementMet),
				Codec.STRING.fieldOf("cost").forGetter(Option::cost),
				Codec.BOOL.fieldOf("can_pay").forGetter(Option::canPay)
		).apply(i, Option::new));
	}

	public static final Type<KingdomInfoPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(KingdomQuest.MOD_ID, "kingdom_info"));
	private static final Codec<KingdomInfoPayload> DATA = RecordCodecBuilder.create(i -> i.group(
			BlockPos.CODEC.fieldOf("heart").forGetter(KingdomInfoPayload::heart),
			Codec.STRING.fieldOf("name").forGetter(KingdomInfoPayload::name),
			Codec.INT.fieldOf("color").forGetter(KingdomInfoPayload::color),
			Identifier.CODEC.fieldOf("logo").forGetter(KingdomInfoPayload::logo),
			Codec.STRING.fieldOf("level").forGetter(KingdomInfoPayload::levelTitle),
			Codec.INT.fieldOf("percent").forGetter(KingdomInfoPayload::percent),
			Option.CODEC.listOf().fieldOf("options").forGetter(KingdomInfoPayload::options)
	).apply(i, KingdomInfoPayload::new));
	public static final StreamCodec<RegistryFriendlyByteBuf, KingdomInfoPayload> CODEC = ByteBufCodecs.fromCodec(DATA).cast();

	@Override
	public Type<KingdomInfoPayload> type() {
		return TYPE;
	}
}
