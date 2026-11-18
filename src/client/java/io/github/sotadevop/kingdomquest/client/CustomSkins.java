package io.github.sotadevop.kingdomquest.client;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.sotadevop.kingdomquest.KingdomQuest;
import io.github.sotadevop.kingdomquest.npc.KingdomNpc;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Skins of the player's own for his people (single player): 64x64 PNGs in config/kingdomquest/skins. A skin's id is
 * {@link KingdomNpc#CUSTOM_SKIN} plus a hash of its file name, so it stays the same when other files come and go;
 * whether it has slim (3 pixel) or wide arms is read from the picture itself.
 */
public final class CustomSkins {
	public record Skin(int id, String name, Path file, boolean slim) {
	}

	private static final Map<Integer, Skin> SKINS = new HashMap<>();
	private static final Map<Integer, Identifier> TEXTURES = new HashMap<>();
	private static long scanned = Long.MIN_VALUE;

	private CustomSkins() {
	}

	public static Path folder() {
		return FabricLoader.getInstance().getConfigDir().resolve(KingdomQuest.MOD_ID).resolve("skins");
	}

	public static int idOf(String fileName) {
		return KingdomNpc.CUSTOM_SKIN + Math.floorMod(fileName.toLowerCase(Locale.ROOT).hashCode(), 8_000_000);
	}

	/** The skins in the folder, read again if the folder changed. */
	public static synchronized List<Skin> all() {
		Path dir = folder();
		long stamp;
		try {
			Files.createDirectories(dir);
			stamp = Files.getLastModifiedTime(dir).toMillis();
		} catch (IOException e) {
			return List.of();
		}
		if (stamp != scanned) {
			scanned = stamp;
			SKINS.clear();
			try (Stream<Path> files = Files.list(dir)) {
				for (Path f : files.sorted().toList()) {
					String name = f.getFileName().toString();
					if (!name.toLowerCase(Locale.ROOT).endsWith(".png")) {
						continue;
					}
					try (InputStream in = Files.newInputStream(f); NativeImage img = NativeImage.read(in)) {
						if (img.getWidth() == 64 && img.getHeight() == 64) {
							int id = idOf(name);
							SKINS.put(id, new Skin(id, name.substring(0, name.length() - 4), f, slim(img)));
						}
					} catch (IOException | RuntimeException e) {
						KingdomQuest.LOG.warn("Skin {} could not be read: {}", name, e.toString());
					}
				}
			} catch (IOException e) {
				return List.of();
			}
			// textures of skins that changed are loaded again when next drawn
			Minecraft mc = Minecraft.getInstance();
			TEXTURES.values().forEach(t -> mc.getTextureManager().release(t));
			TEXTURES.clear();
		}
		List<Skin> out = new ArrayList<>(SKINS.values());
		out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
		return out;
	}

	/** Forgets the folder's state, so the next look at it reads it again (after files were copied in). */
	public static synchronized void rescan() {
		scanned = Long.MIN_VALUE;
	}

	public static synchronized Skin get(int id) {
		if (scanned == Long.MIN_VALUE) {
			all();
		}
		return SKINS.get(id);
	}

	/** The texture of a skin from the folder, or null if there is no such file (another computer's skin). */
	public static synchronized Identifier texture(int id) {
		Identifier have = TEXTURES.get(id);
		if (have != null || TEXTURES.containsKey(id)) {
			return have;
		}
		Skin s = get(id);
		Identifier tex = null;
		if (s != null) {
			try (InputStream in = Files.newInputStream(s.file())) {
				NativeImage img = NativeImage.read(in);
				tex = Identifier.fromNamespaceAndPath(KingdomQuest.MOD_ID, "skins/" + id);
				DynamicTexture dt = new DynamicTexture(() -> "kingdomquest skin " + s.name(), img);
				dt.upload();
				Minecraft.getInstance().getTextureManager().register(tex, dt);
			} catch (IOException | RuntimeException e) {
				KingdomQuest.LOG.warn("Skin {} could not be loaded: {}", s.name(), e.toString());
				tex = null;
			}
		}
		TEXTURES.put(id, tex);
		return tex;
	}

	/** A slim skin leaves the outer two pixel columns of the right arm's front empty. */
	static boolean slim(NativeImage img) {
		for (int y = 20; y < 32; y++) {
			for (int x = 54; x < 56; x++) {
				if ((img.getPixel(x, y) >>> 24) != 0) {
					return false;
				}
			}
		}
		return true;
	}

	/** Copies dropped PNG files into the folder; returns how many were taken. */
	public static int add(List<Path> files) {
		int n = 0;
		for (Path f : files) {
			String name = f.getFileName().toString();
			if (!name.toLowerCase(Locale.ROOT).endsWith(".png")) {
				continue;
			}
			try (InputStream in = Files.newInputStream(f); NativeImage img = NativeImage.read(in)) {
				if (img.getWidth() != 64 || img.getHeight() != 64) {
					continue; // old 64x32 skins and other pictures are left out
				}
			} catch (IOException | RuntimeException e) {
				continue;
			}
			try {
				Files.createDirectories(folder());
				Files.copy(f, folder().resolve(name.replaceAll("[^A-Za-z0-9._ -]", "_")), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				n++;
			} catch (IOException e) {
				KingdomQuest.LOG.warn("Skin {} could not be copied: {}", name, e.toString());
			}
		}
		rescan();
		return n;
	}
}
