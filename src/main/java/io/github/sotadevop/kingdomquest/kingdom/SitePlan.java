package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.building.Template;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.storage.LevelResource;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The site plan of a settlement: the height the land should have in every column inside the wall ring and a
 * margin around it. It is worked out once from the land as it was found and kept in the world folder.
 *
 * The plan is the natural land with its roughness taken out (a wide blur): a hilltop smaller than the castle
 * becomes a broader hill, a hollow is filled and its rim eased, a slope stays a slope. It never asks for more
 * than {@link #MAX_FILL} blocks of fill or {@link #MAX_CUT} of cut (a cliff stays a cliff and gets terrace
 * walls), and beyond the walls it fades back into the untouched land. Water counts as land at the water line.
 *
 * Buildings take their terrace height from the plan and the land around each building is graded toward it when
 * the building goes up, so a site shaped piece by piece still ends up as one coherent landform.
 */
final class SitePlan {
	/** How far beyond the ring the plan reaches while fading out. */
	static final int MARGIN = 16;
	static final int MAX_FILL = 16;
	static final int MAX_CUT = 12;
	private static final int BLUR = 6;
	private static final Map<UUID, SitePlan> PLANS = new HashMap<>();

	private final int x0;
	private final int z0;
	private final int w;
	private final int d;
	/** Height of the ground top relative to the block under the heart (0 = level with the square). */
	private final byte[] h;

	private SitePlan(int x0, int z0, int w, int d, byte[] h) {
		this.x0 = x0;
		this.z0 = z0;
		this.w = w;
		this.d = d;
		this.h = h;
	}

	/** Planned ground height at a world column, relative to the block under the heart; MIN_VALUE outside the plan. */
	int at(int x, int z) {
		int i = x - this.x0;
		int k = z - this.z0;
		return i < 0 || k < 0 || i >= this.w || k >= this.d ? Integer.MIN_VALUE : this.h[i * this.d + k];
	}

	static void forgetAll() {
		PLANS.clear();
	}

	private static Path file(MinecraftServer server, Settlement s) {
		return server.getWorldPath(LevelResource.ROOT).resolve("kingdomquest_sites").resolve(s.id() + ".bin");
	}

	/** The settlement's plan: remembered, read from the world folder, or surveyed now. Null while the site isn't fully loaded. */
	static SitePlan of(MinecraftServer server, Settlement s) {
		SitePlan plan = PLANS.get(s.id());
		if (plan != null) {
			return plan;
		}
		Path file = file(server, s);
		if (Files.exists(file)) {
			try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(file)))) {
				int x0 = in.readInt();
				int z0 = in.readInt();
				int w = in.readInt();
				int d = in.readInt();
				if (w > 0 && d > 0 && w * d <= 1 << 20) {
					byte[] h = new byte[w * d];
					in.readFully(h);
					plan = new SitePlan(x0, z0, w, d, h);
				}
			} catch (IOException e) {
				plan = null; // unreadable: surveyed again below
			}
		}
		if (plan == null) {
			plan = survey(server.overworld(), s);
			if (plan == null) {
				return null;
			}
			try {
				Files.createDirectories(file.getParent());
				try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(file)))) {
					out.writeInt(plan.x0);
					out.writeInt(plan.z0);
					out.writeInt(plan.w);
					out.writeInt(plan.d);
					out.write(plan.h);
				}
			} catch (IOException e) {
				// not saved: it is surveyed again after a restart
			}
		}
		PLANS.put(s.id(), plan);
		return plan;
	}

	private static SitePlan survey(ServerLevel level, Settlement s) {
		Rotation r = Rotation.values()[s.rotation()];
		// the wall ring of tools/buildings.py (WALL), relative to the heart
		BlockPos a = s.heart().offset(Template.rotate(new BlockPos(-63, 0, -56), r));
		BlockPos b = s.heart().offset(Template.rotate(new BlockPos(69, 0, 40), r));
		int rx0 = Math.min(a.getX(), b.getX());
		int rz0 = Math.min(a.getZ(), b.getZ());
		int rx1 = Math.max(a.getX(), b.getX());
		int rz1 = Math.max(a.getZ(), b.getZ());
		int x0 = rx0 - MARGIN;
		int z0 = rz0 - MARGIN;
		int w = rx1 - rx0 + 1 + 2 * MARGIN;
		int d = rz1 - rz0 + 1 + 2 * MARGIN;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < w + 16; i += 16) {
			for (int k = 0; k < d + 16; k += 16) {
				if (!level.isLoaded(pos.set(x0 + Math.min(i, w - 1), s.heart().getY(), z0 + Math.min(k, d - 1)))) {
					return null; // part of the site isn't loaded: surveyed when it is
				}
			}
		}
		Set<Long> built = new HashSet<>();
		s.built().values().forEach(built::addAll);
		int heartY = s.heart().getY();
		float[] land = new float[w * d];
		for (int i = 0; i < w; i++) {
			for (int k = 0; k < d; k++) {
				// the land itself: what the settlement built is looked through; water counts at its surface
				land[i * d + k] = SiteWork.groundDeep(level, x0 + i, z0 + k, heartY, built) - (heartY - 1);
			}
		}
		float[] smooth = land.clone();
		float[] tmp = new float[w * d];
		for (int pass = 0; pass < 3; pass++) { // three box blurs come close to a bell curve
			blur(smooth, tmp, w, d, true);
			blur(tmp, smooth, w, d, false);
		}
		byte[] h = new byte[w * d];
		for (int i = 0; i < w; i++) {
			for (int k = 0; k < d; k++) {
				int ox = Math.max(0, Math.max(MARGIN - i, i - (w - 1 - MARGIN)));
				int oz = Math.max(0, Math.max(MARGIN - k, k - (d - 1 - MARGIN)));
				double out = Math.sqrt(ox * ox + oz * oz);
				double weight = Math.max(0, 1 - out / MARGIN); // inside the walls the plan rules, outside it fades away
				float here = land[i * d + k];
				double want = here + weight * (smooth[i * d + k] - here);
				want = Math.max(here - MAX_CUT, Math.min(here + MAX_FILL, want));
				h[i * d + k] = (byte) Math.max(-100, Math.min(100, Math.round(want)));
			}
		}
		return new SitePlan(x0, z0, w, d, h);
	}

	/** One box blur along x (or z) with radius {@link #BLUR}; the border repeats the edge value. */
	private static void blur(float[] from, float[] to, int w, int d, boolean alongX) {
		int n = alongX ? w : d;
		int lines = alongX ? d : w;
		for (int line = 0; line < lines; line++) {
			float sum = 0;
			for (int q = -BLUR; q <= BLUR; q++) {
				int c = Math.max(0, Math.min(n - 1, q));
				sum += alongX ? from[c * d + line] : from[line * d + c];
			}
			for (int c = 0; c < n; c++) {
				if (alongX) {
					to[c * d + line] = sum / (2 * BLUR + 1);
				} else {
					to[line * d + c] = sum / (2 * BLUR + 1);
				}
				int add = Math.min(n - 1, c + BLUR + 1);
				int drop = Math.max(0, c - BLUR);
				sum += (alongX ? from[add * d + line] : from[line * d + add]) - (alongX ? from[drop * d + line] : from[line * d + drop]);
			}
		}
	}
}
