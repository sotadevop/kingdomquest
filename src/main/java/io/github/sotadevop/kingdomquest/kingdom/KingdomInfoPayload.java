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
 * Server -> client: everything the kingdom menu shows. {@code percent} is -1 when nothing is being built, {@code site} -1
 * once the land of the whole site is shaped (else how much of it is).
 * One {@link Option} per track that has a next step.
 */
public record KingdomInfoPayload(BlockPos heart, String name, int color, Identifier logo, String levelTitle, int percent,
		List<Option> options, int bridge, int damaged, int changed, int site, boolean admin) implements CustomPacketPayload {
	/**
	 * One upgrade track as the menu shows it.
	 *
	 * @param steps    title keys of every level of the track, in order
	 * @param lockedBy title key of the main level the next step needs, "" if not locked
	 */
	public record Option(String track, int level, List<String> steps, String requirement, boolean requirementMet, String cost,
			boolean canPay, String lockedBy) {
		static final Codec<Option> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("track").forGetter(Option::track),
				Codec.INT.fieldOf("level").forGetter(Option::level),
				Codec.STRING.listOf().fieldOf("steps").forGetter(Option::steps),
				Codec.STRING.fieldOf("req").forGetter(Option::requirement),
				Codec.BOOL.fieldOf("req_met").forGetter(Option::requirementMet),
				Codec.STRING.fieldOf("cost").forGetter(Option::cost),
				Codec.BOOL.fieldOf("can_pay").forGetter(Option::canPay),
				Codec.STRING.fieldOf("locked_by").forGetter(Option::lockedBy)
		).apply(i, Option::new));

		public boolean maxed() {
			return this.level >= this.steps.size();
		}

		public boolean locked() {
			return !this.lockedBy.isEmpty();
		}

		/** Can be built right now (by this player, if nothing else is being built). */
		public boolean ready() {
			return !maxed() && !locked() && this.requirementMet && this.canPay;
		}
	}

	public static final Type<KingdomInfoPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(KingdomQuest.MOD_ID, "kingdom_info"));
	private static final Codec<KingdomInfoPayload> DATA = RecordCodecBuilder.create(i -> i.group(
			BlockPos.CODEC.fieldOf("heart").forGetter(KingdomInfoPayload::heart),
			Codec.STRING.fieldOf("name").forGetter(KingdomInfoPayload::name),
			Codec.INT.fieldOf("color").forGetter(KingdomInfoPayload::color),
			Identifier.CODEC.fieldOf("logo").forGetter(KingdomInfoPayload::logo),
			Codec.STRING.fieldOf("level").forGetter(KingdomInfoPayload::levelTitle),
			Codec.INT.fieldOf("percent").forGetter(KingdomInfoPayload::percent),
			Option.CODEC.listOf().fieldOf("options").forGetter(KingdomInfoPayload::options),
			Codec.INT.fieldOf("bridge").forGetter(KingdomInfoPayload::bridge),
			Codec.INT.fieldOf("damaged").forGetter(KingdomInfoPayload::damaged),
			Codec.INT.optionalFieldOf("changed", 0).forGetter(KingdomInfoPayload::changed),
			Codec.INT.optionalFieldOf("site", -1).forGetter(KingdomInfoPayload::site),
			// opened by the steward: the administration window instead of the building book
			Codec.BOOL.optionalFieldOf("admin", false).forGetter(KingdomInfoPayload::admin)
	).apply(i, KingdomInfoPayload::new));
	public static final StreamCodec<RegistryFriendlyByteBuf, KingdomInfoPayload> CODEC = ByteBufCodecs.fromCodec(DATA).cast();

	@Override
	public Type<KingdomInfoPayload> type() {
		return TYPE;
	}
}
