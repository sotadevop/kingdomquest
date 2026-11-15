package io.github.sotadevop.kingdomquest.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.sotadevop.kingdomquest.KingdomQuest;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Client display settings, saved as config/kingdomquest.json. Same choices and defaults as StoryQuest. */
public final class ClientConfig {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("kingdomquest.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static final class Values {
		/** "auto" (follow the resource pack's windows), "light" or "dark". */
		String theme = "auto";
		/** "modern" (rounded, flat) or "vanilla" (square, bevelled). */
		String style = "modern";
		/** Particle preview of the plot while holding a sealed charter. */
		boolean placementPreview = true;
	}

	private static Values values = new Values();

	private ClientConfig() {
	}

	public static void load() {
		if (!Files.exists(FILE)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(FILE)) {
			Values loaded = GSON.fromJson(reader, Values.class);
			if (loaded != null) {
				values = loaded;
			}
		} catch (IOException | RuntimeException e) {
			KingdomQuest.LOG.warn("Could not read {}, using defaults", FILE, e);
		}
	}

	private static void save() {
		try (Writer writer = Files.newBufferedWriter(FILE)) {
			GSON.toJson(values, writer);
		} catch (IOException e) {
			KingdomQuest.LOG.warn("Could not save {}", FILE, e);
		}
	}

	public static String theme() {
		return values.theme == null ? "auto" : values.theme;
	}

	public static void setTheme(String theme) {
		values.theme = theme;
		save();
	}

	public static boolean darkMode() {
		return switch (theme()) {
			case "dark" -> true;
			case "light" -> false;
			default -> PackTheme.dark();
		};
	}

	public static Theme colors() {
		return darkMode() ? Theme.DARK : Theme.LIGHT;
	}

	public static boolean modernStyle() {
		return !"vanilla".equals(values.style);
	}

	public static void setModernStyle(boolean modern) {
		values.style = modern ? "modern" : "vanilla";
		save();
	}

	public static boolean placementPreview() {
		return values.placementPreview;
	}

	public static void setPlacementPreview(boolean on) {
		values.placementPreview = on;
		save();
	}
}
