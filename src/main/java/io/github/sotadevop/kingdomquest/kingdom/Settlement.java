package io.github.sotadevop.kingdomquest.kingdom;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One founded settlement (overworld only for now).
 *
 * @param rotation 0-3, the {@link net.minecraft.world.level.block.Rotation} ordinal of all its buildings
 * @param levels   level per track ("main" = tent/hamlet/..., add-ons such as "farm"); 0 = not built
 * @param job      template being built ("" when idle) on track {@code jobTrack}, {@code jobIndex} blocks placed so far
 * @param built    per track, positions placed by the builders; they drop nothing when broken
 */
public record Settlement(UUID id, UUID owner, String name, int color, Identifier logo, String style, BlockPos heart,
		int rotation, Map<String, Integer> levels, String job, String jobTrack, int jobIndex, Map<String, List<Long>> built) {
	public static final String MAIN = "main";

	public static final Codec<Settlement> CODEC = RecordCodecBuilder.create(i -> i.group(
			UUIDUtil.CODEC.fieldOf("id").forGetter(Settlement::id),
			UUIDUtil.CODEC.fieldOf("owner").forGetter(Settlement::owner),
			Codec.STRING.fieldOf("name").forGetter(Settlement::name),
			Codec.INT.fieldOf("color").forGetter(Settlement::color),
			Identifier.CODEC.fieldOf("logo").forGetter(Settlement::logo),
			Codec.STRING.fieldOf("style").forGetter(Settlement::style),
			BlockPos.CODEC.fieldOf("heart").forGetter(Settlement::heart),
			Codec.INT.fieldOf("rotation").forGetter(Settlement::rotation),
			Codec.unboundedMap(Codec.STRING, Codec.INT).fieldOf("levels").forGetter(Settlement::levels),
			Codec.STRING.fieldOf("job").forGetter(Settlement::job),
			Codec.STRING.fieldOf("job_track").forGetter(Settlement::jobTrack),
			Codec.INT.fieldOf("job_index").forGetter(Settlement::jobIndex),
			Codec.unboundedMap(Codec.STRING, Codec.LONG.listOf()).fieldOf("built").forGetter(Settlement::built)
	).apply(i, Settlement::new));

	public int level(String track) {
		return this.levels.getOrDefault(track, 0);
	}

	public Settlement withJob(String job, String track, int index) {
		return new Settlement(id, owner, name, color, logo, style, heart, rotation, levels, job, track, index, built);
	}

	public Settlement withLevel(String track, int level) {
		Map<String, Integer> m = new HashMap<>(this.levels);
		m.put(track, level);
		return new Settlement(id, owner, name, color, logo, style, heart, rotation, Map.copyOf(m), job, jobTrack, jobIndex, built);
	}

	public Settlement withBuilt(String track, List<Long> list) {
		Map<String, List<Long>> m = new HashMap<>(this.built);
		m.put(track, List.copyOf(list));
		return new Settlement(id, owner, name, color, logo, style, heart, rotation, levels, job, jobTrack, jobIndex, Map.copyOf(m));
	}

	public boolean isBuilt(long pos) {
		for (List<Long> list : this.built.values()) {
			if (list.contains(pos)) {
				return true;
			}
		}
		return false;
	}
}
