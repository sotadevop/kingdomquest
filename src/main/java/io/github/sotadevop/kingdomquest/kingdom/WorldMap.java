package io.github.sotadevop.kingdomquest.kingdom;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * The land as a map: what the realm screen draws under its settlements, villages and roads. Every chunk a player
 * has been near is remembered as 4x4 colours (one per 4 blocks, shaded by height like a vanilla map), kept in
 * {@code kingdomquest_map.bin} in the world folder, so the map grows as the lord travels. A settlement's plan is
 * read fresh, block by block, when the screen opens. Colours travel as 16-bit RGB (0 = unknown), base64 in the
 * realm's JSON.
 */
final class WorldMap {
	private WorldMap() {
	}

	private static final Map<Long, short[]> TILES = new HashMap<>();
	private static MinecraftServer owner;
	private static boolean dirty;

	private static long key(int cx, int cz) {
		return (long) cx << 32 | cz & 0xFFFFFFFFL;
	}

	private static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("kingdomquest_map.bin");
	}

	private static void open(MinecraftServer server) {
		if (owner == server) {
			return;
		}
		owner = server;
		TILES.clear();
		dirty = false;
		Path file = file(server);
		if (!Files.exists(file)) {
			return;
		}
		try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(file)))) {
			int n = in.readInt();
			for (int i = 0; i < n; i++) {
				long k = in.readLong();
				short[] t = new short[16];
				for (int j = 0; j < 16; j++) {
					t[j] = in.readShort();
				}
				TILES.put(k, t);
			}
		} catch (IOException e) {
			TILES.clear(); // a broken file: the map is simply drawn again as the lord travels
		}
	}

	private static void save(MinecraftServer server) {
		if (!dirty) {
			return;
		}
		dirty = false;
		try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(file(server))))) {
			out.writeInt(TILES.size());
			for (Map.Entry<Long, short[]> e : TILES.entrySet()) {
				out.writeLong(e.getKey());
				for (short v : e.getValue()) {
					out.writeShort(v);
				}
			}
		} catch (IOException e) {
			dirty = true;
		}
	}

	private static short pack(int rgb, int shade) {
		int r = (rgb >> 16 & 255) * shade / 255;
		int g = (rgb >> 8 & 255) * shade / 255;
		int b = (rgb & 255) * shade / 255;
		int v = (r >> 3) << 11 | (g >> 2) << 5 | b >> 3;
		return (short) (v == 0 ? 1 : v);
	}

	/** Height and map colour of the top block of a loaded column: {y, rgb}. */
	private static int[] column(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int z) {
		int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
		pos.set(x, y, z);
		BlockState st = level.getBlockState(pos);
		int col = st.getMapColor(level, pos).col;
		for (int i = 0; i < 8 && col == 0 && y > level.getMinY(); i++) {
			y--;
			pos.set(x, y, z);
			st = level.getBlockState(pos);
			col = st.getMapColor(level, pos).col;
		}
		return new int[] {y, col};
	}

	private static int shade(int y, int north) {
		return y > north ? 255 : y == north ? 220 : 180;
	}

	private static boolean sample(ServerLevel level, int cx, int cz) {
		if (level.getChunkSource().getChunkNow(cx, cz) == null) {
			return false;
		}
		short[] t = new short[16];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int j = 0; j < 4; j++) {
			for (int i = 0; i < 4; i++) {
				int x = cx * 16 + i * 4 + 1;
				int z = cz * 16 + j * 4 + 2;
				int[] c = column(level, pos, x, z);
				int[] n = column(level, pos, x, z - 1);
				t[j * 4 + i] = pack(c[1], shade(c[0], n[0]));
			}
		}
		TILES.put(key(cx, cz), t);
		dirty = true;
		return true;
	}

	/** Called every tick: now and then the chunks around each player that are not on the map yet are drawn. */
	static void tick(MinecraftServer server) {
		if (server.getTickCount() % 40 != 0) {
			return;
		}
		open(server);
		ServerLevel level = server.overworld();
		int budget = 48;
		for (ServerPlayer p : level.players()) {
			int pcx = p.getBlockX() >> 4;
			int pcz = p.getBlockZ() >> 4;
			for (int r = 0; r <= 9 && budget > 0; r++) {
				for (int dx = -r; dx <= r && budget > 0; dx++) {
					for (int dz = -r; dz <= r && budget > 0; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) == r && !TILES.containsKey(key(pcx + dx, pcz + dz))
								&& sample(level, pcx + dx, pcz + dz)) {
							budget--;
						}
					}
				}
			}
		}
		if (server.getTickCount() % 1200 == 0) {
			save(server);
		}
	}

	/** Draws the loaded chunks around a place again (a settlement changes as it is built). */
	static void refresh(ServerLevel level, BlockPos center, int chunks) {
		open(level.getServer());
		for (int dx = -chunks; dx <= chunks; dx++) {
			for (int dz = -chunks; dz <= chunks; dz++) {
				sample(level, (center.getX() >> 4) + dx, (center.getZ() >> 4) + dz);
			}
		}
		save(level.getServer());
	}

	private static JsonObject json(int x0, int z0, int step, int w, int h, short[] px) {
		byte[] bytes = new byte[px.length * 2];
		for (int i = 0; i < px.length; i++) {
			bytes[i * 2] = (byte) (px[i] >> 8);
			bytes[i * 2 + 1] = (byte) px[i];
		}
		JsonObject j = new JsonObject();
		j.addProperty("x0", x0);
		j.addProperty("z0", z0);
		j.addProperty("step", step);
		j.addProperty("w", w);
		j.addProperty("h", h);
		j.addProperty("data", Base64.getEncoder().encodeToString(bytes));
		return j;
	}

	/** The remembered map of an area, at 4, 8 or 16 blocks per cell so it stays at most 320 cells wide. */
	static JsonObject layer(MinecraftServer server, int x0, int z0, int x1, int z1) {
		open(server);
		int step = 4;
		while (Math.max(x1 - x0, z1 - z0) / step > 320 && step < 16) {
			step *= 2;
		}
		x0 = Math.floorDiv(x0, 16) * 16;
		z0 = Math.floorDiv(z0, 16) * 16;
		int w = Math.min(320, (x1 - x0) / step + 1);
		int h = Math.min(320, (z1 - z0) / step + 1);
		short[] px = new short[w * h];
		for (int j = 0; j < h; j++) {
			for (int i = 0; i < w; i++) {
				int x = x0 + i * step;
				int z = z0 + j * step;
				short[] t = TILES.get(key(x >> 4, z >> 4));
				if (t != null) {
					px[j * w + i] = t[((z & 15) >> 2) * 4 + ((x & 15) >> 2)];
				}
			}
		}
		return json(x0, z0, step, w, h, px);
	}

	/** A settlement from above, one colour per block, read from the world now (unloaded chunks stay unknown). */
	static JsonObject plan(ServerLevel level, int x0, int z0, int x1, int z1) {
		int w = Math.min(200, x1 - x0 + 1);
		int h = Math.min(200, z1 - z0 + 1);
		short[] px = new short[w * h];
		int[] north = new int[w];
		java.util.Arrays.fill(north, Integer.MIN_VALUE);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		Map<Long, Boolean> loaded = new HashMap<>();
		for (int j = 0; j < h; j++) {
			for (int i = 0; i < w; i++) {
				int x = x0 + i;
				int z = z0 + j;
				if (!loaded.computeIfAbsent(key(x >> 4, z >> 4), k -> level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null)) {
					north[i] = Integer.MIN_VALUE;
					continue;
				}
				int[] c = column(level, pos, x, z);
				px[j * w + i] = pack(c[1], north[i] == Integer.MIN_VALUE ? 220 : shade(c[0], north[i]));
				north[i] = c[0];
			}
		}
		return json(x0, z0, 1, w, h, px);
	}
}
