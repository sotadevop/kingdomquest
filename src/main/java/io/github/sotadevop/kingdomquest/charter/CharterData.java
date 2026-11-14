package io.github.sotadevop.kingdomquest.charter;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * What the founding menu decided, stored on the charter item until the settlement is placed.
 *
 * @param color dye color id (0-15), used only as an accent
 * @param logo  block item shown as the kingdom's logo, together with {@code color}
 */
public record CharterData(String name, int color, Identifier logo) {
	public static final int MAX_NAME = 32;

	public static final Codec<CharterData> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("name").forGetter(CharterData::name),
			Codec.INT.fieldOf("color").forGetter(CharterData::color),
			Identifier.CODEC.fieldOf("logo").forGetter(CharterData::logo)
	).apply(i, CharterData::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, CharterData> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC).cast();
}
