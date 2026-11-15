package io.github.sotadevop.kingdomquest.client;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.sotadevop.kingdomquest.KingdomQuest;
import java.io.InputStream;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

/**
 * Whether the active resource packs make the game's windows dark (a dark GUI pack like
 * Recolourful Containers DARK). Read from the inventory window texture itself, the same texture
 * Sota Tweaks takes its panel colors from, so both mods follow the same pack and any dark pack
 * works, not only one by name. Checked again whenever the selected packs change.
 */
final class PackTheme {

	private static final Identifier INVENTORY = Identifier.withDefaultNamespace("textures/gui/container/inventory.png");
	/** The vanilla texture is laid out on a 256 pixel grid; packs may use a multiple of it. */
	private static final int GRID = 256;
	/** A clean spot of the window's background, left side, in vanilla and in packs. */
	private static final int SAMPLE_X = 4;
	private static final int SAMPLE_Y = 120;
	/** Below this brightness (0..1) the window counts as dark. Vanilla's grey is about 0.78. */
	private static final double DARK_BELOW = 0.4;

	private static String checkedPacks;
	private static boolean dark;

	private PackTheme() {
	}

	static boolean dark() {
		Minecraft minecraft = Minecraft.getInstance();
		String packs = String.join(",", minecraft.getResourcePackRepository().getSelectedIds());
		if (!packs.equals(checkedPacks)) {
			checkedPacks = packs;
			dark = read(minecraft);
		}
		return dark;
	}

	private static boolean read(Minecraft minecraft) {
		Optional<Resource> resource = minecraft.getResourceManager().getResource(INVENTORY);
		if (resource.isEmpty()) {
			return false;
		}
		try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
			int scale = Math.max(1, image.getWidth() / GRID);
			int argb = image.getPixel(SAMPLE_X * scale, SAMPLE_Y * scale);
			double r = (argb >> 16 & 0xFF) / 255.0;
			double g = (argb >> 8 & 0xFF) / 255.0;
			double b = (argb & 0xFF) / 255.0;
			return 0.2126 * r + 0.7152 * g + 0.0722 * b < DARK_BELOW;
		} catch (Exception e) {
			KingdomQuest.LOG.warn("Could not read {} to pick the journal theme", INVENTORY, e);
			return false;
		}
	}
}
