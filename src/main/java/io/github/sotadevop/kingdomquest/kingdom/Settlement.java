package io.github.sotadevop.kingdomquest.kingdom;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.UUID;

/**
 * One founded settlement (overworld only for now).
 *
 * @param rotation  0-3, the {@link net.minecraft.world.level.block.Rotation} ordinal of all its buildings
 * @param level     the level that is built (or being built)
 * @param job       template being built ("" when idle), {@code jobIndex} blocks of it placed so far
 * @param built     positions placed by the builders; they drop nothing when broken
 */
public record Settlement(UUID id, UUID owner, String name, int color, Identifier logo, String style, BlockPos heart,
		int rotation, int level, String job, int jobIndex, List<Long> built) {
	public static final Codec<Settlement> CODEC = RecordCodecBuilder.create(i -> i.group(
			UUIDUtil.CODEC.fieldOf("id").forGetter(Settlement::id),
			UUIDUtil.CODEC.fieldOf("owner").forGetter(Settlement::owner),
			Codec.STRING.fieldOf("name").forGetter(Settlement::name),
			Codec.INT.fieldOf("color").forGetter(Settlement::color),
			Identifier.CODEC.fieldOf("logo").forGetter(Settlement::logo),
			Codec.STRING.fieldOf("style").forGetter(Settlement::style),
			BlockPos.CODEC.fieldOf("heart").forGetter(Settlement::heart),
			Codec.INT.fieldOf("rotation").forGetter(Settlement::rotation),
			Codec.INT.fieldOf("level").forGetter(Settlement::level),
			Codec.STRING.fieldOf("job").forGetter(Settlement::job),
			Codec.INT.fieldOf("job_index").forGetter(Settlement::jobIndex),
			Codec.LONG.listOf().fieldOf("built").forGetter(Settlement::built)
	).apply(i, Settlement::new));

	public Settlement withJob(String job, int jobIndex, int level) {
		return new Settlement(id, owner, name, color, logo, style, heart, rotation, level, job, jobIndex, built);
	}

	public Settlement withBuilt(List<Long> built) {
		return new Settlement(id, owner, name, color, logo, style, heart, rotation, level, job, jobIndex, built);
	}
}
