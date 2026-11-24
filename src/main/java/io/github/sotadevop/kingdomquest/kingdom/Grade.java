package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.KingdomQuest;
import io.github.sotadevop.kingdomquest.building.Layout;
import io.github.sotadevop.kingdomquest.building.Template;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.storage.LevelResource;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The grading plan of a site (design 19.10, user 2026-11-23): one height map for the whole site, planned once at the
 * founding for the final layout of every tier and kept with the settlement (in the world folder), never planned again.
 * Everything that needs a height (plots, streets, walls, moat, gate road, stairs, door steps) reads it.
 *
 * Inside the walls the land is one plateau at a height the mod picks (the median of the site, at most {@link #CAP} from
 * where the charter was used, never under the water of the site). Around it the land falls or climbs back to the
 * untouched land like a natural slope: gentle in sand, steeper in earth, steep and bare in rock; the edge wobbles. The
 * hill looks raise natural hills inside the plateau (a motte for the keep, the church and lord's hills of a hill town),
 * every plot on them a level terrace of its own.
 *
 * The whole site is shaped at the founding, but the builders only work where the game runs (near a player): the plan is
 * done in sections of 16 by 16 columns, the ones by the player first, and a building waits for the sections under it.
 * New ground gets the layers of the land it continues (snow, grass, sand, terracotta), and is covered again with the
 * biome's own trees, flowers, grass and stones, placed with the game's own features, so the earthwork can't be seen.
 */
final class Grade {
	static final int FLAT = 0;
	static final int HILL_CASTLE = 1;
	static final int HILL_TOWN = 2;
	/** Settlement level key of the site look (founding menu). */
	static final String LOOK = "site_look";
	/** Settlement level key of the castle shape (founding menu): 0 square, 1 round (not built yet). */
	static final String SHAPE = "castle_shape";
	/** Flat ground beyond the wall line: the moat, its bank and the landing in front of the gate. */
	static final int PAD = 16;
	/** How far beyond the flat ground the plan reaches with its slopes back to the untouched land. */
	static final int MARGIN = 60;
	/** The plateau lies at most this far above or below the ground where the charter was used. */
	static final int CAP = 12;
	/** Caves under made ground are filled this deep. */
	private static final int CAVES = 20;
	static final int SECTION = 16;
	/** Hill castle: the motte with the keep and the upper square stands this high over the bailey. */
	static final int MOUND = 9;
	/** Hill town: the church hill and the lord's hill. */
	static final int CHURCH_HILL = 12;
	static final int LORD_HILL = 9;
	/** Hills inside the walls climb at most this much per block (about 1 in 1.6: a natural hill, not a pyramid). */
	private static final double HILL_SLOPE = 0.62;

	/** Column kinds. */
	private static final byte UNTOUCHED = 0;
	private static final byte PLATEAU = 1;
	private static final byte MADE = 2;
	private static final byte SLOPE = 3;
	/** A hill inside the walls (hill looks), not on a plot: grown over like the land around until a building comes. */
	private static final byte HILL = 4;
	private static final int FORMAT = 0x4B514731; // "KQG1"

	private static final Map<UUID, Grade> GRADES = new HashMap<>();

	final int x0;
	final int z0;
	final int w;
	final int d;
	/** Top block of the plateau (the bailey), world y. */
	final int plateau;
	final int look;
	/** Tree trunks per column of the land as it was found: how densely the slopes are planted again. */
	final float trees;
	/** Planned top block per column, world y. */
	private final short[] top;
	private final byte[] kind;
	/** Sections shaped, and sections whose new ground got its plants. */
	private final BitSet shaped;
	private final BitSet planted;
	private final int sw;
	private final int sd;
	/** Sections with anything to shape. */
	private final BitSet need = new BitSet();
	private boolean done;
	private boolean dirty;
	private long savedAt;

	private Grade(int x0, int z0, int w, int d, int plateau, int look, float trees, short[] top, byte[] kind, BitSet shaped, BitSet planted) {
		this.x0 = x0;
		this.z0 = z0;
		this.w = w;
		this.d = d;
		this.plateau = plateau;
		this.look = look;
		this.trees = trees;
		this.top = top;
		this.kind = kind;
		this.shaped = shaped;
		this.planted = planted;
		this.sw = (w + SECTION - 1) / SECTION;
		this.sd = (d + SECTION - 1) / SECTION;
		for (int i = 0; i < w; i++) {
			for (int k = 0; k < d; k++) {
				if (kind[i * d + k] != UNTOUCHED) {
					this.need.set(i / SECTION * this.sd + k / SECTION);
				}
			}
		}
	}

	// ---- reading the plan ----

	private int index(int x, int z) {
		int i = x - this.x0, k = z - this.z0;
		return i < 0 || k < 0 || i >= this.w || k >= this.d ? -1 : i * this.d + k;
	}

	/** Planned top block (world y) of a column; MIN_VALUE outside the plan. */
	int topAt(int x, int z) {
		int i = index(x, z);
		return i < 0 ? Integer.MIN_VALUE : this.top[i];
	}

	/** True when the column lies on the plateau inside the walls (or its flat band outside them). */
	boolean onPlateau(int x, int z) {
		int i = index(x, z);
		return i >= 0 && (this.kind[i] == PLATEAU || this.kind[i] == MADE || this.kind[i] == HILL);
	}

	/** Share of the site that is shaped, 0..100. */
	int progress() {
		int need = 0, done = 0;
		for (int s = 0; s < this.sw * this.sd; s++) {
			if (needed(s)) {
				need++;
				if (this.shaped.get(s) && this.planted.get(s)) {
					done++;
				}
			}
		}
		return need == 0 ? 100 : done * 100 / need;
	}

	boolean complete() {
		if (!this.done) {
			BitSet left = (BitSet) this.need.clone();
			BitSet both = (BitSet) this.shaped.clone();
			both.and(this.planted);
			left.andNot(both);
			this.done = left.isEmpty();
		}
		return this.done;
	}

	// ---- the settlement's grade ----

	static void forgetAll(MinecraftServer server) {
		if (server != null) {
			for (Map.Entry<UUID, Grade> e : GRADES.entrySet()) {
				if (e.getValue().dirty) {
					e.getValue().save(server, e.getKey());
				}
			}
		}
		GRADES.clear();
	}

	private static Path file(MinecraftServer server, UUID id) {
		return server.getWorldPath(LevelResource.ROOT).resolve("kingdomquest_sites").resolve(id + ".grade");
	}

	/**
	 * The settlement's grade: remembered or read from the world folder. A settlement founded before grading existed
	 * gets a plan of its land as it is (nothing to shape), so everything that reads heights still works. Null while
	 * the site of such an old settlement isn't loaded.
	 */
	static Grade of(MinecraftServer server, Settlement s) {
		Grade g = GRADES.get(s.id());
		if (g != null) {
			return g;
		}
		Path file = file(server, s.id());
		if (Files.exists(file)) {
			try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(file)))) {
				if (in.readInt() == FORMAT) {
					int x0 = in.readInt(), z0 = in.readInt(), w = in.readInt(), d = in.readInt();
					int plateau = in.readInt(), look = in.readInt();
					float trees = in.readFloat();
					if (w > 0 && d > 0 && (long) w * d <= 1 << 21) {
						short[] top = new short[w * d];
						for (int i = 0; i < top.length; i++) {
							top[i] = in.readShort();
						}
						byte[] kind = new byte[w * d];
						in.readFully(kind);
						BitSet shaped = BitSet.valueOf(in.readNBytes(in.readInt()));
						BitSet planted = BitSet.valueOf(in.readNBytes(in.readInt()));
						g = new Grade(x0, z0, w, d, plateau, look, trees, top, kind, shaped, planted);
					}
				}
			} catch (IOException e) {
				g = null;
			}
		}
		if (g == null) {
			g = asFound(server.overworld(), s);
			if (g == null) {
				return null;
			}
			g.save(server, s.id());
		}
		GRADES.put(s.id(), g);
		return g;
	}

	void save(MinecraftServer server, UUID id) {
		Path file = file(server, id);
		try {
			Files.createDirectories(file.getParent());
			try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(file)))) {
				out.writeInt(FORMAT);
				out.writeInt(this.x0);
				out.writeInt(this.z0);
				out.writeInt(this.w);
				out.writeInt(this.d);
				out.writeInt(this.plateau);
				out.writeInt(this.look);
				out.writeFloat(this.trees);
				for (short t : this.top) {
					out.writeShort(t);
				}
				out.write(this.kind);
				byte[] a = this.shaped.toByteArray();
				out.writeInt(a.length);
				out.write(a);
				byte[] b = this.planted.toByteArray();
				out.writeInt(b.length);
				out.write(b);
			}
			this.dirty = false;
		} catch (IOException e) {
			KingdomQuest.LOG.warn("KingdomQuest: site plan of {} not saved", id, e);
		}
	}

	/** Puts a freshly planned grade in place for a new settlement and saves it. */
	void adopt(MinecraftServer server, UUID id) {
		GRADES.put(id, this);
		save(server, id);
	}

	// ---- planning ----

	/** The world rectangle of the wall ring for a heart and rotation: x0, z0, x1, z1. */
	static int[] ringBox(BlockPos heart, Rotation r) {
		int[] ring = Layout.ring();
		BlockPos a = heart.offset(Template.rotate(new BlockPos(ring[0], 0, ring[1]), r));
		BlockPos b = heart.offset(Template.rotate(new BlockPos(ring[2], 0, ring[3]), r));
		return new int[] {Math.min(a.getX(), b.getX()), Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()), Math.max(a.getZ(), b.getZ())};
	}

	/** A plan that leaves the land as it is (settlements founded before grading). */
	private static Grade asFound(ServerLevel level, Settlement s) {
		Rotation r = Rotation.values()[s.rotation()];
		int[] box = ringBox(s.heart(), r);
		int x0 = box[0] - PAD - 8, z0 = box[1] - PAD - 8;
		int w = box[2] - box[0] + 1 + 2 * (PAD + 8), d = box[3] - box[1] + 1 + 2 * (PAD + 8);
		for (int i = 0; i < w + 16; i += 16) {
			for (int k = 0; k < d + 16; k += 16) {
				if (!level.hasChunk((x0 + Math.min(i, w - 1)) >> 4, (z0 + Math.min(k, d - 1)) >> 4)) {
					return null;
				}
			}
		}
		Set<Long> built = new HashSet<>();
		s.built().values().forEach(built::addAll);
		short[] top = new short[w * d];
		for (int i = 0; i < w; i++) {
			for (int k = 0; k < d; k++) {
				top[i * d + k] = (short) SiteWork.groundDeep(level, x0 + i, z0 + k, s.heart().getY(), built);
			}
		}
		BitSet all = new BitSet();
		Grade g = new Grade(x0, z0, w, d, s.heart().getY() - 1, FLAT, 0, top, new byte[w * d], all, all);
		g.shaped.set(0, g.sw * g.sd);
		g.planted.set(0, g.sw * g.sd);
		return g;
	}

	/** The result of planning: the grade, the heart (moved to the plateau or the motte) and each plot's level. */
	record Planned(Grade grade, BlockPos heart, Map<String, Integer> lifts) {
	}

	/**
	 * Plans the site for a settlement founded at {@code clicked} (the block above the ground where the charter was used),
	 * with its gate side {@code r} and the look chosen in the founding menu. Reads the world where it is loaded and the
	 * world generator's own heights elsewhere.
	 */
	static Planned plan(ServerLevel level, BlockPos clicked, Rotation r, int look) {
		int[] ring = ringBox(clicked, r);
		int x0 = ring[0] - PAD - MARGIN, z0 = ring[1] - PAD - MARGIN;
		int w = ring[2] - ring[0] + 1 + 2 * (PAD + MARGIN), d = ring[3] - ring[1] + 1 + 2 * (PAD + MARGIN);
		int n = w * d;
		int[] bed = new int[n];
		int[] surf = new int[n];
		byte[] mat = new byte[n]; // 0 loose (sand, gravel, snow), 1 earth, 2 rock
		int trunks = 0, dry = 0;
		ChunkGenerator gen = level.getChunkSource().getGenerator();
		RandomState random = level.getChunkSource().randomState();
		Map<Long, int[]> sampled = new HashMap<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < w; i++) {
			for (int k = 0; k < d; k++) {
				int x = x0 + i, z = z0 + k, c = i * d + k;
				if (level.hasChunk(x >> 4, z >> 4)) {
					int[] l = land(level, x, z);
					bed[c] = l[0];
					surf[c] = l[1];
					BlockState st = level.getBlockState(pos.set(x, l[0], z));
					mat[c] = material(st);
					if (l[0] == l[1]) {
						dry++;
						if (level.getBlockState(pos.set(x, l[0] + 1, z)).is(BlockTags.LOGS)) {
							trunks++;
						}
					}
				} else {
					// the generator's heights, every 4 blocks and between them interpolated: cheap enough for a whole site
					int[] h = interpolated(gen, random, level, x, z, sampled);
					bed[c] = h[0];
					surf[c] = Math.max(h[0], h[1]);
					mat[c] = 1;
				}
			}
		}
		// the plateau: the median of the land inside the walls (water at its surface), near the charter's spot, dry
		List<Integer> heights = new ArrayList<>();
		int water = Integer.MIN_VALUE;
		for (int x = ring[0] - PAD; x <= ring[2] + PAD; x += 2) {
			for (int z = ring[1] - PAD; z <= ring[3] + PAD; z += 2) {
				int c = (x - x0) * d + (z - z0);
				if (x >= ring[0] && x <= ring[2] && z >= ring[1] && z <= ring[3]) {
					heights.add(surf[c]);
				}
				if (surf[c] > bed[c]) {
					water = Math.max(water, surf[c]);
				}
			}
		}
		java.util.Collections.sort(heights);
		int ground = clicked.getY() - 1;
		int p = Math.max(ground - CAP, Math.min(ground + CAP, heights.get(heights.size() / 2)));
		if (water != Integer.MIN_VALUE) {
			p = Math.max(p, water + 1); // a castle in the water stands on an island of its own
		}
		// the relief inside the walls (hill looks), relative to the plateau, and the level of every plot on it
		Rotation back = back(r);
		double[] relief = new double[n];
		boolean[] fixed = new boolean[n];
		Map<String, int[]> plots = new HashMap<>(Layout.plots());
		Layout.reserved().forEach(plots::put);
		List<double[]> hills = hills(look);
		int[] wall = Layout.wall();
		for (int i = 0; i < w; i++) {
			for (int k = 0; k < d; k++) {
				BlockPos b = Template.rotate(new BlockPos(x0 + i - clicked.getX(), 0, z0 + k - clicked.getZ()), back);
				int bx = b.getX() + Layout.HX, bz = b.getZ() + Layout.HZ;
				double hgt = 0;
				for (double[] h : hills) {
					double dx = Math.max(0, Math.max(h[0] - bx, bx - h[2]));
					double dz = Math.max(0, Math.max(h[1] - bz, bz - h[3]));
					double dist = Math.sqrt(dx * dx + dz * dz) + noise(x0 + i, z0 + k, 7) * 2.5;
					hgt = Math.max(hgt, h[4] - Math.max(0, dist) * h[5]);
				}
				// the walls and the moat lie on the plateau itself: no hill climbs them (a hill's back may fall steeply to them)
				int inWall = Math.min(Math.min(bx - wall[0], wall[1] - bx), Math.min(bz - wall[2], wall[3] - bz));
				hgt = Math.min(hgt, Math.max(0, inWall - 6));
				relief[i * d + k] = Math.max(0, hgt);
				if (inWall < 6) {
					fixed[i * d + k] = true;
				}
			}
		}
		Map<String, Integer> lifts = new HashMap<>();
		boolean[] plot = new boolean[n];
		if (!hills.isEmpty()) {
			// every plot is a level terrace: at the height of the hill where it stands (its median), two blocks around it
			for (Map.Entry<String, int[]> e : plots.entrySet()) {
				int[] pl = e.getValue();
				List<Integer> idx = cells(clicked, r, x0, z0, w, d, pl[0] - 2, pl[1] - 2, pl[2] + 2, pl[3] + 2);
				if (idx.isEmpty()) {
					continue;
				}
				double[] hs = idx.stream().mapToDouble(c -> relief[c]).sorted().toArray();
				int lvl = (int) Math.round(hs[hs.length / 2]);
				for (int c : idx) {
					relief[c] = lvl;
					fixed[c] = true;
					plot[c] = true;
				}
				lifts.put(e.getKey(), lvl);
			}
			// a street is level across its width wherever it crosses a hill: it climbs along, never sideways
			for (int[] st : Layout.streets()) {
				boolean alongZ = st[4] == 0;
				for (int a = alongZ ? st[1] : st[0]; a <= (alongZ ? st[3] : st[2]); a++) {
					List<Integer> across = alongZ ? cells(clicked, r, x0, z0, w, d, st[0], a, st[2], a) : cells(clicked, r, x0, z0, w, d, a, st[1], a, st[3]);
					if (across.isEmpty() || across.stream().anyMatch(c -> plot[c])) {
						continue;
					}
					double v = across.stream().mapToDouble(c -> relief[c]).average().orElse(0);
					for (int c : across) {
						relief[c] = v;
						fixed[c] = true;
					}
				}
			}
			smoothRelief(relief, fixed, w, d);
		}
		int heartLevel = look == HILL_CASTLE ? MOUND : 0;
		for (Map.Entry<String, Integer> e : lifts.entrySet()) {
			e.setValue(e.getValue() - heartLevel);
		}
		lifts.put(Progression.GATE, -heartLevel); // the gate stands in the wall, on the plateau
		// what stays flat: the ring and its band, its edge wobbling a little
		float[] cut = new float[n];
		float[] fill = new float[n];
		float[] hi = new float[n];
		float[] lo = new float[n];
		byte[] kind = new byte[n];
		for (int i = 0; i < w; i++) {
			for (int k = 0; k < d; k++) {
				int c = i * d + k, x = x0 + i, z = z0 + k;
				// the flat band's edge wanders (never closer than the moat and the landing need) and its corners are round
				double pad = PAD + 3 + noise(x, z, 16) * 6 + noise(x - 71, z + 29, 5) * 1.5;
				double ox = Math.max(0, Math.max(ring[0] - x, x - ring[2])), oz = Math.max(0, Math.max(ring[1] - z, z - ring[3]));
				boolean flat = Math.sqrt(ox * ox + oz * oz) <= Math.max(PAD - 3, pad);
				double vary = 0.65 + 0.7 * (noise(x + 913, z - 377, 9) * 0.5 + 0.5);
				cut[c] = (float) (vary * switch (mat[c]) { case 0 -> 0.7; case 2 -> 2.0; default -> 1.0; });
				fill[c] = (float) (vary * switch (mat[c]) { case 0 -> 0.7; case 2 -> 1.4; default -> 1.0; });
				if (flat) {
					hi[c] = lo[c] = (float) (p + relief[c]);
					kind[c] = PLATEAU;
				} else {
					hi[c] = Float.MAX_VALUE;
					lo[c] = -Float.MAX_VALUE;
				}
			}
		}
		envelope(hi, cut, kind, w, d, true);
		envelope(lo, fill, kind, w, d, false);
		short[] top = new short[n];
		for (int c = 0; c < n; c++) {
			int g = bed[c];
			int t;
			if (kind[c] == PLATEAU) {
				t = (int) Math.round(hi[c]);
			} else {
				// a column under water outside the plateau keeps its water: the bank only goes down to the bed
				t = (int) Math.max(Math.ceil(lo[c]), Math.min(Math.floor(hi[c]), g));
			}
			top[c] = (short) t;
			if (kind[c] == PLATEAU) {
				kind[c] = relief[c] >= 1 && !plot[c] ? HILL : t == g && surf[c] == g ? PLATEAU : MADE;
			} else if (t != g) {
				kind[c] = SLOPE;
			}
		}
		// water outside keeps its banks: land beside it is never cut below its surface (or it floods the cut)
		for (int i = 0; i < w; i++) {
			for (int k = 0; k < d; k++) {
				int c = i * d + k;
				if (kind[c] != SLOPE || surf[c] > bed[c]) {
					continue;
				}
				for (int a = -2; a <= 2; a++) {
					for (int b = -2; b <= 2; b++) {
						int ni = i + a, nk = k + b;
						if (ni >= 0 && nk >= 0 && ni < w && nk < d) {
							int nc = ni * d + nk;
							if (surf[nc] > bed[nc] && kind[nc] != PLATEAU && kind[nc] != MADE && top[c] < surf[nc]) {
								top[c] = (short) surf[nc];
							}
						}
					}
				}
			}
		}
		// the plan's own edge always meets the land as it is: near it a slope eases back to the land, no step at the edge
		for (int i = 0; i < w; i++) {
			for (int k = 0; k < d; k++) {
				int c = i * d + k, edge = Math.min(Math.min(i, k), Math.min(w - 1 - i, d - 1 - k));
				if (edge == 0) {
					top[c] = (short) bed[c];
					kind[c] = UNTOUCHED;
				} else if (kind[c] == SLOPE) {
					// a cone from the plan's edge back to the land, continuous with the slopes from the plateau
					int reach = (int) Math.floor(edge * 1.3);
					int t = Math.max(bed[c] - reach, Math.min(bed[c] + reach, top[c]));
					top[c] = (short) t;
					if (t == bed[c]) {
						kind[c] = UNTOUCHED;
					}
				}
			}
		}
		float trees = dry == 0 ? 0 : Math.min(0.08f, (float) trunks / dry);
		Grade grade = new Grade(x0, z0, w, d, p, look, trees, top, kind, new BitSet(), new BitSet());
		BlockPos heart = new BlockPos(clicked.getX(), p + heartLevel + 1, clicked.getZ());
		return new Planned(grade, heart, lifts);
	}

	/** Hills of a look in the base frame: x0, z0, x1, z1 of the flat top, its height over the plateau, its flanks' slope. */
	private static List<double[]> hills(int look) {
		List<double[]> out = new ArrayList<>();
		if (look == HILL_CASTLE) {
			// the motte: the keep, the upper square and its houses on top, the bailey around it
			int[] main = Layout.plot(Settlement.MAIN);
			int[] sq = Layout.square();
			if (main != null) {
				// a motte's flanks are steeper than a natural hill's (about 1 in 1.25)
				out.add(new double[] {Math.min(main[0], sq[0]) - 3, Math.min(main[1], sq[1]) - 3, Math.max(main[2], sq[2]) + 3,
						Math.max(main[3], sq[3]) + 3, MOUND, 0.8});
			}
		} else if (look == HILL_TOWN) {
			// Bamberg: the church on the highest hill, the lord's house on the second, the market in the valley between
			int[] church = Layout.reserved().get("church");
			int[] lord = Layout.plot(Progression.ROYAL);
			if (church != null) {
				out.add(new double[] {church[0] - 3, church[1] - 3, church[2] + 3, church[3] + 3, CHURCH_HILL, HILL_SLOPE});
			}
			if (lord != null) {
				out.add(new double[] {lord[0] - 3, lord[1] - 3, lord[2] + 3, lord[3] + 3, LORD_HILL, HILL_SLOPE});
			}
		}
		return out;
	}

	/** Plan cells of a base-frame rectangle. */
	private static List<Integer> cells(BlockPos heart, Rotation r, int x0, int z0, int w, int d, int bx0, int bz0, int bx1, int bz1) {
		List<Integer> out = new ArrayList<>();
		for (int bx = bx0; bx <= bx1; bx++) {
			for (int bz = bz0; bz <= bz1; bz++) {
				BlockPos q = heart.offset(Layout.rel(r, bx, 0, bz));
				int i = q.getX() - x0, k = q.getZ() - z0;
				if (i >= 0 && k >= 0 && i < w && k < d) {
					out.add(i * d + k);
				}
			}
		}
		return out;
	}

	/** Free relief between fixed terraces climbs no more than {@link #HILL_SLOPE} per block either way. */
	private static void smoothRelief(double[] h, boolean[] fixed, int w, int d) {
		for (int pass = 0; pass < 3; pass++) {
			for (int dir = 0; dir < 2; dir++) {
				for (int a = 0; a < w; a++) {
					int i = dir == 0 ? a : w - 1 - a;
					for (int b = 0; b < d; b++) {
						int k = dir == 0 ? b : d - 1 - b;
						int c = i * d + k;
						if (fixed[c]) {
							continue;
						}
						double lo = -1e9, hi = 1e9;
						for (int[] o : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
							int ni = i + o[0], nk = k + o[1];
							if (ni < 0 || nk < 0 || ni >= w || nk >= d) {
								continue;
							}
							double v = h[ni * d + nk];
							lo = Math.max(lo, v - HILL_SLOPE * 1.6);
							hi = Math.min(hi, v + HILL_SLOPE * 1.6);
						}
						if (lo <= hi) {
							h[c] = Math.max(lo, Math.min(hi, h[c]));
						}
					}
				}
			}
		}
	}

	/**
	 * The cone of allowed heights from the plateau: up (cut side) at most {@code slope} per block, or down (fill side).
	 * Chamfer sweeps with diagonal steps of 1.41, a few times over.
	 */
	private static void envelope(float[] h, float[] slope, byte[] kind, int w, int d, boolean up) {
		int[][] fwd = {{-1, 0}, {0, -1}, {-1, -1}, {-1, 1}};
		for (int round = 0; round < 3; round++) {
			for (int pass = 0; pass < 2; pass++) {
				int sgn = pass == 0 ? 1 : -1;
				for (int a = 0; a < w; a++) {
					int i = pass == 0 ? a : w - 1 - a;
					for (int b = 0; b < d; b++) {
						int k = pass == 0 ? b : d - 1 - b;
						int c = i * d + k;
						if (kind[c] == PLATEAU) {
							continue; // the plateau and its hills are the source, not shaped by the cone
						}
						for (int[] o : fwd) {
							int ni = i + o[0] * sgn, nk = k + o[1] * sgn;
							if (ni < 0 || nk < 0 || ni >= w || nk >= d) {
								continue;
							}
							float v = h[ni * d + nk];
							if (v == Float.MAX_VALUE || v == -Float.MAX_VALUE) {
								continue;
							}
							float step = slope[c] * (o[0] != 0 && o[1] != 0 ? 1.414f : 1f);
							h[c] = up ? Math.min(h[c], v + step) : Math.max(h[c], v - step);
						}
					}
				}
			}
		}
	}

	private static Rotation back(Rotation r) {
		return switch (r) {
			case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
			case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
			default -> r;
		};
	}

	/** Smooth value noise in -1..1 with features about {@code scale} blocks across. */
	static double noise(int x, int z, int scale) {
		int gx = Math.floorDiv(x, scale), gz = Math.floorDiv(z, scale);
		double fx = (x - gx * scale) / (double) scale, fz = (z - gz * scale) / (double) scale;
		fx = fx * fx * (3 - 2 * fx);
		fz = fz * fz * (3 - 2 * fz);
		double a = hash(gx, gz), b = hash(gx + 1, gz), c = hash(gx, gz + 1), e = hash(gx + 1, gz + 1);
		return (a + (b - a) * fx) * (1 - fz) + (c + (e - c) * fx) * fz;
	}

	private static double hash(int x, int z) {
		long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL;
		h ^= h >>> 29;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 32;
		return (h & 0xFFFF) / 32767.5 - 1;
	}

	private static int[] interpolated(ChunkGenerator gen, RandomState random, ServerLevel level, int x, int z, Map<Long, int[]> cache) {
		int gx = Math.floorDiv(x, 4) * 4, gz = Math.floorDiv(z, 4) * 4;
		double fx = (x - gx) / 4.0, fz = (z - gz) / 4.0;
		int[] a = genAt(gen, random, level, gx, gz, cache), b = genAt(gen, random, level, gx + 4, gz, cache);
		int[] c = genAt(gen, random, level, gx, gz + 4, cache), e = genAt(gen, random, level, gx + 4, gz + 4, cache);
		int[] out = new int[2];
		for (int j = 0; j < 2; j++) {
			out[j] = (int) Math.round((a[j] + (b[j] - a[j]) * fx) * (1 - fz) + (c[j] + (e[j] - c[j]) * fx) * fz);
		}
		return out;
	}

	private static int[] genAt(ChunkGenerator gen, RandomState random, ServerLevel level, int x, int z, Map<Long, int[]> cache) {
		return cache.computeIfAbsent(BlockPos.asLong(x, 0, z), k -> new int[] {
				gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random) - 1,
				gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, random) - 1});
	}

	/** The natural land in a loaded column: {top of the solid ground, top of the water over it (or the ground)}. */
	static int[] land(ServerLevel level, int x, int z) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		int min = level.getMinY();
		while (y > min) {
			BlockState st = level.getBlockState(pos.set(x, y, z));
			if (st.isAir() || st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES) || SiteWork.stalk(st) || st.canBeReplaced() && st.getFluidState().isEmpty()
					|| st.is(Blocks.MANGROVE_ROOTS) || st.is(Blocks.MUDDY_MANGROVE_ROOTS)
					|| st.is(Blocks.BROWN_MUSHROOM_BLOCK) || st.is(Blocks.RED_MUSHROOM_BLOCK) || st.is(Blocks.MUSHROOM_STEM) || st.is(Blocks.BEE_NEST)) {
				y--;
			} else {
				break;
			}
		}
		int surface = y;
		while (y > min && !level.getFluidState(pos.set(x, y, z)).isEmpty()) {
			y--;
		}
		return new int[] {y, surface};
	}

	private static byte material(BlockState st) {
		if (st.is(BlockTags.SAND) || st.is(Blocks.GRAVEL) || st.is(Blocks.SNOW_BLOCK) || st.is(Blocks.POWDER_SNOW)) {
			return 0;
		}
		if (st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(BlockTags.TERRACOTTA) || st.is(Blocks.PACKED_ICE) || st.is(Blocks.CALCITE)
				|| st.is(Blocks.SANDSTONE) || st.is(Blocks.RED_SANDSTONE)) {
			return 2;
		}
		return 1;
	}

	// ---- shaping ----

	private boolean needed(int s) {
		return this.need.get(s);
	}

	private boolean loaded(ServerLevel level, int s) {
		int si = s / this.sd, sk = s % this.sd;
		int ax = this.x0 + si * SECTION, az = this.z0 + sk * SECTION;
		// the section and a ring of chunks around it: features and cut trees reach a little beyond the section
		for (int x = ax - 16; x <= ax + SECTION + 16; x += 16) {
			for (int z = az - 16; z <= az + SECTION + 16; z += 16) {
				if (!level.hasChunk(x >> 4, z >> 4)) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Shapes the sections that cover a world box (and plants those whose neighbours are done) as far as they are loaded,
	 * at most {@code budget} sections now. True when every section of the box is shaped.
	 */
	boolean ready(ServerLevel level, Settlement s, int bx0, int bz0, int bx1, int bz1, int budget) {
		boolean all = true;
		int si0 = Math.max(0, (bx0 - this.x0) / SECTION), si1 = Math.min(this.sw - 1, Math.floorDiv(bx1 - this.x0, SECTION));
		int sk0 = Math.max(0, (bz0 - this.z0) / SECTION), sk1 = Math.min(this.sd - 1, Math.floorDiv(bz1 - this.z0, SECTION));
		for (int si = si0; si <= si1; si++) {
			for (int sk = sk0; sk <= sk1; sk++) {
				int sec = si * this.sd + sk;
				if (this.shaped.get(sec) || !needed(sec)) {
					continue;
				}
				if (budget > 0 && loaded(level, sec)) {
					shape(level, s, sec);
					budget--;
				} else {
					all = false;
				}
			}
		}
		plantReady(level, s, Integer.MAX_VALUE);
		return all;
	}

	/** The whole site at once (field test). */
	void shapeAll(ServerLevel level, Settlement s) {
		for (int sec = 0; sec < this.sw * this.sd; sec++) {
			if (!this.shaped.get(sec) && needed(sec) && loaded(level, sec)) {
				shape(level, s, sec);
			}
		}
		plantReady(level, s, Integer.MAX_VALUE);
	}

	/**
	 * Every other tick: the builders shape the section nearest to the lord (or any player), then plant one. Minecraft
	 * only runs the land around a player, so the rest of the site follows when someone comes near.
	 */
	static void work(MinecraftServer server, Settlement s) {
		Grade g = GRADES.get(s.id());
		if (g == null) {
			if (server.getTickCount() % 100 != 0) {
				return;
			}
			g = of(server, s);
			if (g == null) {
				return;
			}
		}
		if (g.complete() || server.getTickCount() % 2 != 0) {
			return; // a section every other tick: about 10 ms of work, the server keeps its pace
		}
		ServerLevel level = server.overworld();
		List<ServerPlayer> players = level.players();
		int best = -1;
		double bestD = 128 * 128;
		for (int sec = 0; sec < g.sw * g.sd; sec++) {
			if (g.shaped.get(sec) || !g.needed(sec)) {
				continue;
			}
			double cx = g.x0 + (sec / g.sd) * SECTION + SECTION / 2.0, cz = g.z0 + (sec % g.sd) * SECTION + SECTION / 2.0;
			for (ServerPlayer p : players) {
				double dd = (p.getX() - cx) * (p.getX() - cx) + (p.getZ() - cz) * (p.getZ() - cz);
				if (dd < bestD && g.loaded(level, sec)) {
					bestD = dd;
					best = sec;
				}
			}
		}
		if (best >= 0) {
			g.shape(level, s, best);
		}
		g.plantReady(level, s, 1);
		if (g.dirty && server.getTickCount() - g.savedAt > 200) {
			g.savedAt = server.getTickCount();
			g.save(server, s.id());
		}
		ServerPlayer owner = server.getPlayerList().getPlayer(s.owner());
		if (owner != null && server.getTickCount() % 40 == 0 && s.job().isEmpty()
				&& owner.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(s.heart())) < 200 * 200) {
			owner.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("kingdomquest.site.shaping", s.name(), g.progress()));
		}
	}

	/** Plants the sections whose neighbours are all shaped (features reach into them). */
	private void plantReady(ServerLevel level, Settlement s, int budget) {
		for (int sec = 0; sec < this.sw * this.sd && budget > 0; sec++) {
			if (this.planted.get(sec) || !this.shaped.get(sec) && needed(sec)) {
				continue;
			}
			if (!needed(sec)) {
				this.planted.set(sec);
				continue;
			}
			int si = sec / this.sd, sk = sec % this.sd;
			boolean ok = true;
			for (int a = -1; a <= 1 && ok; a++) {
				for (int b = -1; b <= 1 && ok; b++) {
					int ni = si + a, nk = sk + b;
					if (ni >= 0 && nk >= 0 && ni < this.sw && nk < this.sd) {
						int n = ni * this.sd + nk;
						ok = this.shaped.get(n) || !needed(n);
					}
				}
			}
			if (ok && loaded(level, sec)) {
				plant(level, s, sec);
				budget--;
			}
		}
	}

	/** Shapes one section: every column to its planned height, with the land's own layers, caves under it filled. */
	private void shape(ServerLevel level, Settlement s, int sec) {
		int si = sec / this.sd, sk = sec % this.sd;
		Set<Long> built = new HashSet<>();
		s.built().values().forEach(built::addAll);
		BlockState surface = SiteWork.surface(level, s.heart());
		List<BlockState> fallback = List.of(surface, SiteWork.subsoil(surface), SiteWork.subsoil(surface), SiteWork.subsoil(surface));
		int ax = this.x0 + si * SECTION, az = this.z0 + sk * SECTION;
		Map<Long, List<BlockState>> nearby = new HashMap<>(); // layers of the nearest dry land, per 8 x 8 cell
		for (int i = si * SECTION; i < Math.min(this.w, (si + 1) * SECTION); i++) {
			for (int k = sk * SECTION; k < Math.min(this.d, (sk + 1) * SECTION); k++) {
				byte kd = this.kind[i * this.d + k];
				if (kd != UNTOUCHED) {
					column(level, this.x0 + i, this.z0 + k, this.top[i * this.d + k], kd, built, fallback, nearby);
				}
			}
		}
		SiteWork.dropFloating(level, ax - 4, az - 4, ax + SECTION + 3, az + SECTION + 3, built);
		SiteWork.stumps(level, ax, az, ax + SECTION - 1, az + SECTION - 1, built, surface);
		// nobody is left inside new ground; whoever stands where the land was cut away floats down
		for (net.minecraft.world.entity.LivingEntity e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
				new net.minecraft.world.phys.AABB(ax, level.getMinY(), az, ax + SECTION, level.getMinY() + level.getHeight(), az + SECTION))) {
			BlockPos at = e.blockPosition();
			int t = topAt(at.getX(), at.getZ());
			if (t == Integer.MIN_VALUE) {
				continue;
			}
			if (at.getY() <= t) {
				e.teleportTo(e.getX(), t + 1, e.getZ());
			} else if (at.getY() > t + 4 && e instanceof ServerPlayer p) {
				p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.SLOW_FALLING, 160));
			}
		}
		this.shaped.set(sec);
		this.dirty = true;
	}

	/** True for what the grading may take away or build over: the land and its plants, never anything built. */
	private static boolean loose(BlockState st, BlockPos p, Set<Long> built) {
		return !built.contains(p.asLong()) && (st.isAir() || SiteWork.natural(st) || SiteWork.earthwork(st));
	}

	/** One column to its planned top {@code t}. */
	private void column(ServerLevel level, int x, int z, int t, byte kd, Set<Long> built, List<BlockState> fallback, Map<Long, List<BlockState>> nearby) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int[] l = land(level, x, z);
		int g = l[0], water = l[1];
		boolean wet = water > g;
		// made ground where there was water: inside the walls the site's own ground (a filled lake is no beach), outside
		// the layers of the nearest dry land
		List<BlockState> stack = wet ? kd == SLOPE ? nearby.computeIfAbsent(BlockPos.asLong(x >> 3, 0, z >> 3), c -> reference(level, x, z, fallback)) : fallback
				: layers(level, x, g, z, fallback);
		boolean snowed = !wet && level.getBlockState(pos.set(x, g + 1, z)).is(Blocks.SNOW);
		// trees standing on this column go whole: on the plateau, and wherever the ground under them moves
		if (kd != SLOPE || t != g) {
			for (int y = g + 1; y <= g + 3; y++) {
				BlockState st = level.getBlockState(pos.set(x, y, z));
				if ((SiteWork.trunk(st) || st.is(BlockTags.LEAVES)) && !built.contains(pos.asLong())) {
					SiteWork.removeTreeAt(level, pos.immutable(), built);
				}
			}
		}
		for (int y = g + 1; y <= g + 3; y++) {
			BlockState st = level.getBlockState(pos.set(x, y, z));
			if (st.is(Blocks.MUSHROOM_STEM) && !built.contains(pos.asLong())) {
				removeMushroom(level, pos.immutable(), built); // a huge mushroom goes whole, or its cap is left hanging
			}
		}
		boolean steep = steepness(x, z) >= 2;
		if (t < g) {
			// cut: everything natural above the new top goes; the top gets the land's layers, bare rock where steep
			for (int y = wet ? g : g + 2; y > t; y--) {
				BlockState st = level.getBlockState(pos.set(x, y, z));
				if (!st.isAir() && st.getFluidState().isEmpty() && loose(st, pos, built) && !(st.is(BlockTags.LEAVES) && y > g)) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), SiteWork.FLAGS);
				}
			}
			BlockState was = level.getBlockState(pos.set(x, t, z));
			boolean rock = material(was) == 2;
			if (!(steep && rock)) {
				for (int j = 0; j < 3; j++) {
					pos.set(x, t - j, z);
					BlockState st = level.getBlockState(pos);
					if (loose(st, pos, built) && (j > 0 || !rock || !steep)) {
						level.setBlock(pos, stack.get(Math.min(j, stack.size() - 1)), SiteWork.FLAGS);
					}
				}
			}
			sealWater(level, x, z, t, g, stack, built);
		} else if (t > g) {
			// fill: the land's own layers, under water only its bed
			boolean underWater = t < water;
			for (int y = g + 1; y <= t; y++) {
				pos.set(x, y, z);
				BlockState st = level.getBlockState(pos);
				if (!loose(st, pos, built)) {
					continue;
				}
				int depth = t - y;
				BlockState fill = underWater ? bedOf(stack) : stack.get(Math.min(depth, stack.size() - 1));
				level.setBlock(pos, fill, SiteWork.FLAGS);
			}
			if (!underWater) {
				for (int y = t + 1; y <= water; y++) { // the water the fill pushed out of the column
					pos.set(x, y, z);
					if (!level.getFluidState(pos).isEmpty() && !built.contains(pos.asLong())) {
						level.setBlock(pos, Blocks.AIR.defaultBlockState(), SiteWork.FLAGS);
					}
				}
			}
		} else if (wet && kd != SLOPE) {
			// a plateau column level with a water bed: dry it
			for (int y = g + 1; y <= water; y++) {
				pos.set(x, y, z);
				if (!level.getFluidState(pos).isEmpty() && !built.contains(pos.asLong())) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), SiteWork.FLAGS);
				}
			}
			level.setBlock(pos.set(x, t, z), stack.get(0), SiteWork.FLAGS);
		}
		if (kd != SLOPE) {
			// inside the walls one meadow of the site's own ground: no patches of mud, sand or gravel (rock stays rock)
			BlockState here = level.getBlockState(pos.set(x, t, z));
			if (!built.contains(pos.asLong()) && here.getBlock() != fallback.get(0).getBlock() && (here.is(Blocks.MUD) || here.is(BlockTags.SAND)
					|| here.is(Blocks.GRAVEL) || here.is(Blocks.DIRT) || here.is(Blocks.COARSE_DIRT) || here.is(Blocks.ROOTED_DIRT) || here.is(Blocks.CLAY)
					|| here.is(Blocks.PODZOL) && !fallback.get(0).is(Blocks.GRASS_BLOCK))) {
				level.setBlock(pos, fallback.get(0), SiteWork.FLAGS);
			}
		} else if (wet && t >= water && t <= water + 1) {
			// land made out of the water outside the walls meets it with a beach, as natural shores do
			BlockState top0 = level.getBlockState(pos.set(x, t, z));
			if (!built.contains(pos.asLong()) && (top0.is(Blocks.GRASS_BLOCK) || top0.is(Blocks.DIRT) || top0.is(Blocks.PODZOL) || top0.is(Blocks.SNOW_BLOCK))) {
				level.setBlock(pos, (fallback.get(0).is(Blocks.SNOW_BLOCK) ? Blocks.GRAVEL : Blocks.SAND).defaultBlockState(), SiteWork.FLAGS);
			}
		}
		if (t != g) {
			// plants and snow that stood on the old ground
			for (int y = t + 1; y <= t + 3; y++) {
				BlockState st = level.getBlockState(pos.set(x, y, z));
				if (!st.isAir() && st.getFluidState().isEmpty() && SiteWork.natural(st) && !SiteWork.terrain(st) && !st.is(BlockTags.LOGS)
						&& !st.is(BlockTags.LEAVES) && !built.contains(pos.asLong())) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), SiteWork.FLAGS);
				}
			}
			if (snowed && t >= water && level.getBlockState(pos.set(x, t + 1, z)).isAir()) {
				level.setBlock(pos, Blocks.SNOW.defaultBlockState(), SiteWork.FLAGS);
			}
		}
		// caves and holes under made ground and the plateau are filled, so nothing caves in or opens later
		{
			BlockState deep = stack.get(stack.size() - 1);
			int depth = kd == SLOPE && t < g ? 6 : CAVES; // a cut slope only closes the cave mouths it opened
			for (int y = t - 1; y >= t - depth; y--) {
				pos.set(x, y, z);
				BlockState st = level.getBlockState(pos);
				if ((st.isAir() || !st.getFluidState().isEmpty() || st.canBeReplaced()) && !built.contains(pos.asLong())) {
					level.setBlock(pos, deep, SiteWork.FLAGS);
				}
			}
		}
	}

	/** Every block of a huge mushroom connected to {@code start} (stem and caps). */
	private static void removeMushroom(ServerLevel level, BlockPos start, Set<Long> built) {
		java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>(List.of(start));
		Set<Long> seen = new HashSet<>(List.of(start.asLong()));
		int n = 0;
		while (!queue.isEmpty() && n++ < 400) {
			BlockPos p = queue.poll();
			BlockState st = level.getBlockState(p);
			if (!(st.is(Blocks.MUSHROOM_STEM) || st.is(Blocks.RED_MUSHROOM_BLOCK) || st.is(Blocks.BROWN_MUSHROOM_BLOCK)) || built.contains(p.asLong())) {
				continue;
			}
			level.setBlock(p, Blocks.AIR.defaultBlockState(), SiteWork.FLAGS);
			for (BlockPos q : BlockPos.betweenClosed(p.offset(-1, -1, -1), p.offset(1, 1, 1))) {
				if (seen.add(q.asLong())) {
					queue.add(q.immutable());
				}
			}
		}
	}

	/** Water beside a cut column, standing higher than its new top, is walled off by a bank: nothing floods the cut. */
	private static void sealWater(ServerLevel level, int x, int z, int t, int g, List<BlockState> stack, Set<Long> built) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = t + 1; y <= g + 1; y++) {
			for (Direction dir : Direction.Plane.HORIZONTAL) {
				pos.set(x + dir.getStepX(), y, z + dir.getStepZ());
				if (!level.getFluidState(pos).isEmpty() && !built.contains(pos.asLong())) {
					BlockPos here = new BlockPos(x, y, z);
					level.setBlock(here, stack.get(Math.min(2, stack.size() - 1)), SiteWork.FLAGS);
				}
			}
		}
	}

	/** How steep the plan is at a column: the biggest step to a neighbour. */
	private int steepness(int x, int z) {
		int t = topAt(x, z), most = 0;
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			int n = topAt(x + dir.getStepX(), z + dir.getStepZ());
			if (n != Integer.MIN_VALUE) {
				most = Math.max(most, Math.abs(n - t));
			}
		}
		return most;
	}

	/** The top layers of a dry natural column, top down (4 blocks; the deepest is what made ground is filled with). */
	private static List<BlockState> layers(ServerLevel level, int x, int g, int z, List<BlockState> fallback) {
		List<BlockState> out = new ArrayList<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int j = 0; j < 4; j++) {
			BlockState st = level.getBlockState(pos.set(x, g - j, z));
			if (!SiteWork.isGround(st) || !st.getFluidState().isEmpty()) {
				break;
			}
			// ores and odd blocks in a layer become the plain rock or earth around them
			out.add(plain(st));
		}
		if (out.isEmpty()) {
			return fallback;
		}
		while (out.size() < 4) {
			out.add(out.get(out.size() - 1));
		}
		if (out.get(0).is(Blocks.GRASS_BLOCK) || out.get(0).is(Blocks.PODZOL) || out.get(0).is(Blocks.MYCELIUM)) {
			// turf only on top: what lies under it is earth
			for (int j = 1; j < 4; j++) {
				if (out.get(j).is(out.get(0).getBlock())) {
					out.set(j, Blocks.DIRT.defaultBlockState());
				}
			}
		}
		return out;
	}

	/** The layers of the nearest dry natural column (for ground made where there was water). */
	private static List<BlockState> reference(ServerLevel level, int x, int z, List<BlockState> fallback) {
		for (int r = 2; r <= 14; r += 2) {
			for (int a = -r; a <= r; a += 2) {
				for (int[] c : new int[][] {{x + a, z - r}, {x + a, z + r}, {x - r, z + a}, {x + r, z + a}}) {
					if (!level.hasChunk(c[0] >> 4, c[1] >> 4)) {
						continue;
					}
					int[] l = land(level, c[0], c[1]);
					if (l[0] == l[1]) {
						List<BlockState> st = layers(level, c[0], l[0], c[1], fallback);
						if (st != fallback) {
							return st;
						}
					}
				}
			}
		}
		return fallback;
	}

	/** Under water the ground is no turf: sand, or the earth under the turf. */
	private static BlockState bedOf(List<BlockState> stack) {
		BlockState top = stack.get(0);
		if (top.is(Blocks.GRASS_BLOCK) || top.is(Blocks.PODZOL) || top.is(Blocks.MYCELIUM) || top.is(Blocks.SNOW_BLOCK) || top.is(Blocks.MOSS_BLOCK)) {
			return stack.get(1).is(Blocks.DIRT) ? Blocks.DIRT.defaultBlockState() : stack.get(1);
		}
		return top;
	}

	// ---- planting ----

	/**
	 * Covers the new ground of a section with what grows on the land around: the biome's own features (its trees, flower
	 * patches, grass and bushes, placed by the game itself), as densely as the land around was wooded; stones on rocky
	 * slopes. On the plateau only meadow: free plots stay grass and flowers until they are bought.
	 */
	private void plant(ServerLevel level, Settlement s, int sec) {
		int si = sec / this.sd, sk = sec % this.sd;
		RandomSource random = level.getRandom();
		ChunkGenerator gen = level.getChunkSource().getGenerator();
		Set<Long> built = new HashSet<>();
		s.built().values().forEach(built::addAll);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = si * SECTION; i < Math.min(this.w, (si + 1) * SECTION); i++) {
			for (int k = sk * SECTION; k < Math.min(this.d, (sk + 1) * SECTION); k++) {
				byte kd = this.kind[i * this.d + k];
				if (kd != MADE && kd != SLOPE && kd != HILL) {
					continue;
				}
				int x = this.x0 + i, z = this.z0 + k, t = this.top[i * this.d + k];
				BlockState ground = level.getBlockState(pos.set(x, t, z));
				BlockState over = level.getBlockState(pos.set(x, t + 1, z));
				if (!over.isAir() || built.contains(BlockPos.asLong(x, t, z))) {
					continue;
				}
				BlockPos at = new BlockPos(x, t + 1, z);
				boolean soil = ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.PODZOL) || ground.is(Blocks.DIRT) || ground.is(Blocks.COARSE_DIRT)
						|| ground.is(Blocks.MYCELIUM) || ground.is(Blocks.MOSS_BLOCK) || ground.is(Blocks.MUD);
				boolean sandy = ground.is(BlockTags.SAND) || ground.is(BlockTags.TERRACOTTA);
				double r = random.nextDouble();
				boolean wild = kd == SLOPE || kd == HILL; // free land: trees and stones; on the plateau's plots only meadow
				double woods = kd == HILL ? Math.max(this.trees * 1.1, 0.012) : this.trees * 1.1;
				if (wild && (material(ground) == 2 || kd == HILL) && r < (steepness(x, z) >= 2 ? 1 / 70.0 : 1 / 160.0)) {
					boulder(level, at, ground, random, built);
				} else if (wild && soil && r < woods) {
					place(level, gen, random, at, true, s.heart(), false);
				} else if ((soil || sandy) && r < (kd == MADE ? 1 / 90.0 : 1 / 36.0)) {
					place(level, gen, random, at, false, s.heart(), kd == MADE);
				}
			}
		}
		int ax = this.x0 + si * SECTION, az = this.z0 + sk * SECTION;
		SiteWork.snowCover(level, ax, az, ax + SECTION - 1, az + SECTION - 1, built);
		this.planted.set(sec);
		this.dirty = true;
	}

	/** One of the biome's own vegetation features at {@code at}: a tree, or a patch of flowers, grass or bushes. */
	private static void place(ServerLevel level, ChunkGenerator gen, RandomSource random, BlockPos at, boolean tree, BlockPos heart, boolean meadow) {
		// the land's biome: a filled river or beach is grown over like the land around the site
		Holder<net.minecraft.world.level.biome.Biome> biome = level.getBiome(at);
		String b = biome.getRegisteredName();
		if (b.contains("river") || b.contains("ocean") || b.contains("beach") || b.contains("shore")) {
			biome = level.getBiome(heart);
		}
		List<HolderSet<PlacedFeature>> steps = biome.value().getGenerationSettings().features();
		int step = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
		if (steps.size() <= step) {
			return;
		}
		// grass, ferns and flowers make up most of a meadow; pumpkins, melons and berries are a rare find, as in the wild
		List<Holder<PlacedFeature>> pick = new ArrayList<>();
		for (Holder<PlacedFeature> h : steps.get(step).stream().toList()) {
			String name = h.getRegisteredName();
			if (name.contains("sea") || name.contains("kelp") || name.contains("sugar_cane") || name.contains("waterlily") || name.contains("lily")) {
				continue; // water plants
			}
			boolean isTree = isTree(h.value().feature(), 0);
			boolean common = name.contains("flower") || name.contains("grass") || name.contains("fern") || name.contains("dead_bush");
			// inside the walls only grass and flowers: no leaf litter, pumpkins or berry bushes on the plots
			boolean ground = !isTree && !name.contains("leaf_litter") && (common || !meadow && (name.contains("bush") || name.contains("patch") || name.contains("berr")));
			if (tree ? isTree : ground) {
				for (int w = tree || common ? 12 : 1; w > 0; w--) {
					pick.add(h);
				}
			}
		}
		if (pick.isEmpty()) {
			return;
		}
		Holder<PlacedFeature> h = pick.get(random.nextInt(pick.size()));
		try {
			h.value().feature().value().place(level, gen, random, at);
		} catch (RuntimeException e) {
			KingdomQuest.LOG.debug("KingdomQuest: feature {} not placed at {}", h.getRegisteredName(), at, e);
		}
	}

	private static boolean isTree(Holder<Feature> f, int depth) {
		if (f.value() instanceof TreeFeature) {
			return true;
		}
		return depth < 3 && f.value().getSubFeatures().anyMatch(sub -> isTree(sub, depth + 1));
	}

	/** A small stone lying on a slope, of the land's own rock, mossy in green land. */
	private static void boulder(ServerLevel level, BlockPos at, BlockState ground, RandomSource random, Set<Long> built) {
		BlockState rock = ground.is(BlockTags.TERRACOTTA) ? ground
				: ground.is(Blocks.SANDSTONE) || ground.is(BlockTags.SAND) ? Blocks.SANDSTONE.defaultBlockState()
				: random.nextInt(3) == 0 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState()
				: random.nextBoolean() ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.ANDESITE.defaultBlockState();
		int size = 1 + random.nextInt(2);
		for (int dx = 0; dx < size; dx++) {
			for (int dz = 0; dz < size; dz++) {
				for (int dy = 0; dy < (dx + dz == 0 ? size : 1); dy++) {
					BlockPos p = at.offset(dx, dy, dz);
					BlockState st = level.getBlockState(p);
					if ((st.isAir() || st.canBeReplaced() && st.getFluidState().isEmpty()) && !built.contains(p.asLong())
							&& !level.getBlockState(p.below()).isAir()) {
						level.setBlock(p, rock, Block.UPDATE_CLIENTS);
					}
				}
			}
		}
	}

	/** Plain blocks for the layers of made ground: ores become the rock around them, clay and mud earth. */
	private static BlockState plain(BlockState st) {
		String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
		if (path.endsWith("_ore")) {
			return path.startsWith("deepslate") ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
		}
		if (st.is(Blocks.CLAY) || st.is(Blocks.MUD)) {
			return Blocks.DIRT.defaultBlockState();
		}
		return st.getBlock().defaultBlockState();
	}

	/** Every column of the plan, for the map views of the field test: world x, z and planned top. */
	int[] dims() {
		return new int[] {this.x0, this.z0, this.w, this.d};
	}

}
