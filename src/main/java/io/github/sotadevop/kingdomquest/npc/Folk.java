package io.github.sotadevop.kingdomquest.npc;

import java.util.Map;

/**
 * Who the kingdom's people are: their region (from the build style), their sex (as the job was held in the middle
 * ages), their regional name and their generated skin (tools/skins.py: region x outfit x variant).
 */
public final class Folk {
	public static final String[] REGIONS = {"temperate", "frozen", "highland", "arid", "wild"};
	/** Outfits as drawn by tools/skins.py; 7 and up are women's (slim arms). */
	public static final int GUARD = 0, PEASANT = 1, SMITH = 2, BURGHER = 3, SCHOLAR = 4, BUILDER = 5, CAPTAIN = 6, HOUSEWIFE = 7,
			WORKER_WOMAN = 8, COOK_WOMAN = 9;
	public static final int OUTFITS = 10;
	public static final int VARIANTS = 4;
	/** First generated skin id; ids up to {@link #LAST} are generated. */
	public static final int FIRST = 100;
	public static final int LAST = FIRST + REGIONS.length * OUTFITS * VARIANTS - 1;

	private Folk() {
	}

	/** A job's outfit (and so whether a man or a woman holds it). */
	public static int outfit(String job) {
		return switch (job) {
			case "Beekeeper", "Weaver", "Herbalist" -> WORKER_WOMAN;
			case "Baker", "Innkeeper" -> COOK_WOMAN;
			case "Housewife" -> HOUSEWIFE;
			case "Blacksmith", "Armorer", "Tanner", "Smelter", "Saddler" -> SMITH;
			case "Merchant", "Cartographer", "Bard", "Nether Trader", "Storekeeper", "Dyer" -> BURGHER;
			case "Steward", "Librarian", "Alchemist", "Chronicler", "Architect" -> SCHOLAR;
			case "Tinkerer", "Archaeologist", "Miller", "Carpenter", "Sawyer", "Builder" -> BUILDER;
			case "Warchief", "Guard Captain" -> CAPTAIN;
			case "Guard", "Knight" -> GUARD;
			default -> PEASANT;
		};
	}

	public static boolean female(int outfit) {
		return outfit >= HOUSEWIFE;
	}

	public static int region(String style) {
		for (int i = 0; i < REGIONS.length; i++) {
			if (REGIONS[i].equals(style)) {
				return i;
			}
		}
		return 0;
	}

	public static int skin(int region, int outfit, int variant) {
		return FIRST + (Math.floorMod(region, REGIONS.length) * OUTFITS + Math.floorMod(outfit, OUTFITS)) * VARIANTS + Math.floorMod(variant, VARIANTS);
	}

	public static boolean generated(int base) {
		return base >= FIRST && base <= LAST;
	}

	/** Region of a generated skin. */
	public static int regionOf(int base) {
		return (base - FIRST) / (OUTFITS * VARIANTS);
	}

	/** Outfit of a generated skin. */
	public static int outfitOf(int base) {
		return (base - FIRST) / VARIANTS % OUTFITS;
	}

	private static final Map<Integer, String[][]> NAMES = Map.of(
			0, new String[][] {
					{"Konrad", "Heinrich", "Dietrich", "Ulrich", "Friedrich", "Gottfried", "Eberhard", "Wolfram", "Lothar", "Rupert", "Siegfried",
							"Albrecht", "Bernhard", "Hartmut", "Otto", "Walther", "Reinhard", "Burkhard", "Gerhard", "Ludwig"},
					{"Adelheid", "Gertrud", "Hildegard", "Kunigunde", "Mechthild", "Irmgard", "Agnes", "Elisabeth", "Margarete", "Gisela", "Ida",
							"Liutgard", "Richenza", "Hedwig", "Beatrix", "Walburga", "Jutta", "Sophia", "Ermengard", "Hiltrud"}},
			1, new String[][] {
					{"Sigurd", "Bjorn", "Leif", "Ragnar", "Erik", "Gunnar", "Halvard", "Ivar", "Olaf", "Sven", "Torstein", "Ulf", "Harald", "Knut",
							"Einar", "Arne", "Hakon", "Eirik", "Thorvald", "Asmund"},
					{"Astrid", "Ingrid", "Sigrid", "Ragnhild", "Gudrun", "Thora", "Helga", "Freydis", "Solveig", "Ylva", "Asa", "Gunnhild", "Torunn",
							"Liv", "Hallveig", "Signy", "Bergljot", "Ingeborg", "Ase", "Tove"}},
			2, new String[][] {
					{"Callum", "Angus", "Duncan", "Ewan", "Fergus", "Hamish", "Malcolm", "Ruairidh", "Alasdair", "Lachlan", "Niall", "Murdo", "Donald",
							"Iain", "Kenneth", "Seumas", "Tormod", "Coinneach", "Dougal", "Gillespie"},
					{"Morag", "Mairi", "Eilidh", "Catriona", "Isobel", "Ailsa", "Fiona", "Kirsty", "Seonag", "Marsaili", "Effie", "Elspeth", "Una",
							"Ishbel", "Mhairi", "Beathag", "Oighrig", "Peigi", "Raonaid", "Sine"}},
			3, new String[][] {
					{"Yusuf", "Tariq", "Hassan", "Idris", "Karim", "Malik", "Nasir", "Rashid", "Salim", "Zayd", "Hamid", "Faris", "Bilal", "Khalid",
							"Musa", "Ali", "Ibrahim", "Umar", "Jabir", "Hisham"},
					{"Aisha", "Fatima", "Layla", "Maryam", "Zaynab", "Khadija", "Nur", "Salma", "Amina", "Yasmin", "Hafsa", "Rania", "Samira", "Lubna",
							"Dalia", "Hana", "Ruqayya", "Sumayya", "Asma", "Wallada"}},
			4, new String[][] {
					{"Bima", "Wira", "Adi", "Raden", "Surya", "Tirta", "Darma", "Satria", "Banyu", "Arya", "Sena", "Wisnu", "Agung", "Gede", "Putu",
							"Made", "Jaya", "Bayu", "Kencana", "Hayam"},
					{"Dewi", "Sari", "Ratna", "Kartika", "Lestari", "Ayu", "Mega", "Putri", "Wulan", "Indah", "Citra", "Melati", "Sekar", "Laras",
							"Tara", "Intan", "Kirana", "Anjani", "Sinta", "Gayatri"}});

	/** A name of the region for a man or a woman, fixed by {@code seed}. */
	public static String name(int region, boolean female, int seed) {
		String[] list = NAMES.get(Math.floorMod(region, REGIONS.length))[female ? 1 : 0];
		return list[Math.floorMod(seed * 31 + 17, list.length)];
	}

	/** "Name, Role": the name tag shows both when one looks at the person. */
	public static String label(String name, String role) {
		return name + ", " + role;
	}

	/** The personal name of a "Name, Role" label (the whole label if it has no role). */
	public static String nameOf(String label) {
		int c = label.indexOf(", ");
		return c < 0 ? label : label.substring(0, c);
	}

	/** The role of a "Name, Role" label, or null. */
	public static String roleOf(String label) {
		int c = label.indexOf(", ");
		return c < 0 ? null : label.substring(c + 2);
	}
}
