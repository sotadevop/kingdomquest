package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.building.Template;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Picks the side the gate faces when the lord leaves it on "Auto" (user, 2026-11-19, option A): the side with the
 * gentlest way in, as real castles put their gate on the easiest approach. The land in front of each possible gate is
 * read 8 to 40 blocks out; a road can climb about one block every two, so only what lies beyond that slope counts,
 * plus water to bridge. Runs on the client (placement preview) and the server (founding) with the same result.
 */
public final class GateSite {
	/** Template z of the gate line (relative to the heart); the approach starts beyond the moat. */
	private static final int GATE_Z = io.github.sotadevop.kingdomquest.building.Layout.gateZ() - io.github.sotadevop.kingdomquest.building.Layout.HZ;
	private static final int[] OUT = {8, 12, 16, 20, 26, 32, 40};
	private static final int[] ACROSS = {-6, 0, 6};
	/** Above this on every side there is no gentle way in at all (a hollow 30 deep scores about 480, open plains under 20). */
	public static final double STEEP_ALL_ROUND = 150;

	private GateSite() {
	}

	/** The gate side with the gentlest approach; {@code preferred} (the way the lord faces) wins a near tie. */
	public static Rotation best(LevelReader level, BlockPos heart, Rotation preferred) {
		Rotation best = preferred;
		double bestCost = cost(level, heart, preferred) * 0.9; // a near tie keeps the lord's own facing
		for (Rotation r : Rotation.values()) {
			double c = cost(level, heart, r);
			if (c < bestCost) {
				bestCost = c;
				best = r;
			}
		}
		return best;
	}

	/** How hard the way in is on this side: land above or below what a 1:2 road reaches, and water to cross. */
	public static double cost(LevelReader level, BlockPos heart, Rotation r) {
		int floor = heart.getY() - 1;
		double cost = 0;
		int known = 0;
		for (int out : OUT) {
			for (int across : ACROSS) {
				BlockPos p = heart.offset(Template.rotate(new BlockPos(across, 0, GATE_Z + out), r));
				if (!level.hasChunk(p.getX() >> 4, p.getZ() >> 4)) {
					continue; // not loaded on the client: unknown, no cost
				}
				known++;
				int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()) - 1;
				int diff = Math.abs(top - floor);
				cost += Math.max(0, diff - out / 2.0); // more than a gentle road can take up
				cost += out <= 16 ? diff * 0.3 : 0; // the landing in front of the bridge wants level land
				if (!level.getFluidState(new BlockPos(p.getX(), top, p.getZ())).isEmpty()) {
					cost += 3; // a bridge or causeway over water
				}
			}
		}
		return known == 0 ? 1e9 : cost * OUT.length * ACROSS.length / known;
	}

	/** The compass side the gate looks out to for a settlement rotation (template gate faces +z = south). */
	public static String side(Rotation r) {
		return switch (r) {
			case NONE -> "south";
			case CLOCKWISE_90 -> "west";
			case CLOCKWISE_180 -> "north";
			case COUNTERCLOCKWISE_90 -> "east";
		};
	}
}
