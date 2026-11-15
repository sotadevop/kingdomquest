package io.github.sotadevop.kingdomquest.kingdom;

import java.util.Set;

/** Biome categories; each has its own building style (only temperate and frozen are built so far). */
public final class Style {
	public static final String TEMPERATE = "temperate";
	public static final String FROZEN = "frozen";

	private static final Set<String> FROZEN_BIOMES = Set.of("minecraft:snowy_plains", "minecraft:ice_spikes", "minecraft:snowy_taiga",
			"minecraft:snowy_slopes", "minecraft:grove", "minecraft:frozen_peaks", "minecraft:jagged_peaks", "minecraft:snowy_beach",
			"minecraft:frozen_river", "minecraft:frozen_ocean", "minecraft:deep_frozen_ocean");

	private Style() {
	}

	/** Style for a biome id such as "minecraft:snowy_plains"; everything not built yet falls back to temperate. */
	public static String forBiome(String biome) {
		return FROZEN_BIOMES.contains(biome) ? FROZEN : TEMPERATE;
	}
}
