package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.KingdomQuest;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Server -> client: the storehouse ledger. Stock lines (item with count 1, how many, the stock limit for that item,
 * when it last came in) and the production overview. Room is counted in stacks.
 */
public record StorePayload(BlockPos heart, String name, int level, int used, int capacity, List<Line> lines, List<Prod> producers)
		implements CustomPacketPayload {
	/** @param limit stock limit for the item (production stops there), 0 = none */
	public record Line(int id, ItemStack item, long count, int limit, long since) {
		static final StreamCodec<RegistryFriendlyByteBuf, Line> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Line::id, ItemStack.STREAM_CODEC, Line::item, ByteBufCodecs.VAR_LONG, Line::count,
				ByteBufCodecs.VAR_INT, Line::limit, ByteBufCodecs.VAR_LONG, Line::since, Line::new);
	}

	/**
	 * One output of a producer.
	 *
	 * @param perDay    items a game day now (0 while locked or switched off)
	 * @param level     production level that unlocks it
	 * @param milestone vanilla advancement title key it also needs ("" for none), {@code reached} if the lord has it
	 * @param on        the lord's drop toggle
	 * @param spec      the output's item as the production table names it (for the toggle)
	 */
	public record Out(String spec, ItemStack item, float perDay, int level, String milestone, boolean reached, boolean on) {
		static final StreamCodec<RegistryFriendlyByteBuf, Out> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, Out::spec, ItemStack.STREAM_CODEC, Out::item, ByteBufCodecs.FLOAT, Out::perDay, ByteBufCodecs.VAR_INT, Out::level,
				ByteBufCodecs.STRING_UTF8, Out::milestone, ByteBufCodecs.BOOL, Out::reached, ByteBufCodecs.BOOL, Out::on, Out::new);
	}

	/**
	 * One producer: its production level (0 = no levels), what the next level costs and needs.
	 *
	 * @param requirement title key of the advancement the next level needs, "" for none
	 */
	public record Prod(String id, int level, String cost, String requirement, boolean requirementMet, boolean canPay, List<Out> outs) {
		static final StreamCodec<RegistryFriendlyByteBuf, Prod> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, Prod::id, ByteBufCodecs.VAR_INT, Prod::level, ByteBufCodecs.STRING_UTF8, Prod::cost,
				ByteBufCodecs.STRING_UTF8, Prod::requirement, ByteBufCodecs.BOOL, Prod::requirementMet, ByteBufCodecs.BOOL, Prod::canPay,
				Out.CODEC.apply(ByteBufCodecs.list()), Prod::outs, Prod::new);

		public boolean maxed() {
			return this.level == 0 || this.level >= Production.MAX_LEVEL;
		}
	}

	public static final Type<StorePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(KingdomQuest.MOD_ID, "store"));
	public static final StreamCodec<RegistryFriendlyByteBuf, StorePayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, StorePayload::heart, ByteBufCodecs.STRING_UTF8, StorePayload::name, ByteBufCodecs.VAR_INT, StorePayload::level,
			ByteBufCodecs.VAR_INT, StorePayload::used, ByteBufCodecs.VAR_INT, StorePayload::capacity,
			Line.CODEC.apply(ByteBufCodecs.list()), StorePayload::lines, Prod.CODEC.apply(ByteBufCodecs.list()), StorePayload::producers,
			StorePayload::new);

	@Override
	public Type<StorePayload> type() {
		return TYPE;
	}
}
