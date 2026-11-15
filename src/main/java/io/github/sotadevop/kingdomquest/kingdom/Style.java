package io.github.sotadevop.kingdomquest.kingdom;

import java.util.Set;

/** Biome categories, each with its own historical building style. */
public final class Style {
	public static final String TEMPERATE = "temperate";
	public static final String FROZEN = "frozen";
	public static final String ARID = "arid";
	public static final String HIGHLAND = "highland";
	public static final String WILD = "wild";
	public static final java.util.List<String> ALL = java.util.List.of(TEMPERATE, FROZEN, ARID, HIGHLAND, WILD);

	private static final Set<String> FROZEN_BIOMES = Set.of("snowy_plains", "ice_spikes", "snowy_taiga", "snowy_slopes", "grove",
			"frozen_peaks", "jagged_peaks", "snowy_beach", "frozen_river", "frozen_ocean", "deep_frozen_ocean");
	private static final Set<String> ARID_BIOMES = Set.of("desert", "savanna", "savanna_plateau", "windswept_savanna", "badlands",
			"eroded_badlands", "wooded_badlands");
	private static final Set<String> HIGHLAND_BIOMES = Set.of("taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga", "windswept_hills",
			"windswept_gravelly_hills", "windswept_forest", "stony_peaks", "stony_shore");
	private static final Set<String> WILD_BIOMES = Set.of("jungle", "sparse_jungle", "bamboo_jungle", "swamp", "mangrove_swamp");

	private Style() {
	}

	/** Style for a biome id such as "minecraft:snowy_plains"; everything else is temperate. */
	public static String forBiome(String biome) {
		String b = biome.startsWith("minecraft:") ? biome.substring(10) : biome;
		if (FROZEN_BIOMES.contains(b)) {
			return FROZEN;
		}
		if (ARID_BIOMES.contains(b)) {
			return ARID;
		}
		if (HIGHLAND_BIOMES.contains(b)) {
			return HIGHLAND;
		}
		return WILD_BIOMES.contains(b) ? WILD : TEMPERATE;
	}
}
