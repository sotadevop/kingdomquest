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
 * @param gate  where the gate faces: -1 auto (the gentlest way in, chosen on founding), else the
 *              {@link net.minecraft.world.level.block.Rotation} ordinal of the settlement (user, 2026-11-19)
 * @param look  the site's look (design 19.10): 0 flat castle, 1 hill castle (motte and bailey), 2 hill town
 * @param shape the castle's shape (19.11): 0 square, 1 round (planned, not built yet)
 */
public record CharterData(String name, int color, Identifier logo, int gate, int look, int shape) {
	public static final int MAX_NAME = 32;
	public static final int AUTO = -1;
	public static final int LOOKS = 3;

	public CharterData(String name, int color, Identifier logo) {
		this(name, color, logo, AUTO, 0, 0);
	}

	public static final Codec<CharterData> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("name").forGetter(CharterData::name),
			Codec.INT.fieldOf("color").forGetter(CharterData::color),
			Identifier.CODEC.fieldOf("logo").forGetter(CharterData::logo),
			Codec.INT.optionalFieldOf("gate", AUTO).forGetter(CharterData::gate),
			Codec.INT.optionalFieldOf("look", 0).forGetter(CharterData::look),
			Codec.INT.optionalFieldOf("shape", 0).forGetter(CharterData::shape)
	).apply(i, CharterData::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, CharterData> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC).cast();
}
