package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.building.Template;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The drawbridge of gate level 3+: the lever inside the gatehouse raises or lowers it. It moves one row every
 * half second with piston and chain sounds: the four plank rows (five wide) over the moat fold up into a wall in front of
 * the gate, and fold back down. Its state is kept in the settlement ("drawbridge" = 1 when raised).
 */
final class Drawbridge {
	/** The gate opening in the base frame (heart at 7,0,10): x 6..8 at the wall's south side, z = 40. */
	private static final int GX = io.github.sotadevop.kingdomquest.building.Layout.gateX();
	private static final int GZ = io.github.sotadevop.kingdomquest.building.Layout.gateZ();
	static final String STATE = "drawbridge";
	private static final Map<UUID, int[]> MOVING = new HashMap<>(); // settlement -> {step, raising}

	private Drawbridge() {
	}

	/** A spot of the gate (base frame), at the gate's own level (below the heart on a hill castle). */
	private static BlockPos at(Settlement s, int bx, int by, int bz) {
		return s.heart().offset(io.github.sotadevop.kingdomquest.building.Layout.rel(Rotation.values()[s.rotation()], bx,
				by + Settlements.trackLift(s, Progression.GATE), bz));
	}

	static BlockPos lever(Settlement s) {
		return at(s, GX + 2, 1, GZ - 2);
	}

	/** The lever was used. Returns true if it belongs to a drawbridge. */
	static boolean toggle(MinecraftServer server, BlockPos pos) {
		for (Settlement s : Settlements.all(server)) {
			if (s.level(Progression.GATE) >= 3 && lever(s).equals(pos) && !MOVING.containsKey(s.id())) {
				MOVING.put(s.id(), new int[] {0, s.level(STATE) == 1 ? 0 : 1});
				return true;
			}
		}
		return false;
	}

	/** Bridge planks that are raised (or moving) are not damage. */
	static boolean moving(Settlement s, BlockPos pos) {
		if (s.level(Progression.GATE) < 3) {
			return false;
		}
		for (int x = GX - 2; x <= GX + 2; x++) {
			for (int k = 0; k < 4; k++) {
				if (pos.equals(at(s, x, -1, GZ + 3 + k))) {
					return s.level(STATE) == 1 || MOVING.containsKey(s.id());
				}
			}
		}
		return false;
	}

	static void tick(MinecraftServer server) {
		if (MOVING.isEmpty() || server.getTickCount() % 10 != 0) {
			return;
		}
		ServerLevel level = server.overworld();
		for (Settlement s : Settlements.all(server)) {
			int[] m = MOVING.get(s.id());
			if (m == null) {
				continue;
			}
			BlockState plank = Blocks.CRIMSON_PLANKS.defaultBlockState();
			BlockState air = Blocks.AIR.defaultBlockState();
			boolean raising = m[1] == 1;
			// row k (k = 0 near the gate .. 3 far out) lies over the moat at z = GZ+3+k and stands up at height k
			int k = raising ? m[0] : 3 - m[0];
			for (int x = GX - 2; x <= GX + 2; x++) {
				BlockPos flat = at(s, x, -1, GZ + 3 + k);
				BlockPos up = at(s, x, k, GZ + 2);
				level.setBlock(raising ? flat : up, air, Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
				level.setBlock(raising ? up : flat, plank, Block.UPDATE_ALL);
			}
			BlockPos sound = at(s, GX, 0, GZ + 2);
			level.playSound(null, sound, raising ? SoundEvents.PISTON_CONTRACT : SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 0.8f, 0.6f);
			level.playSound(null, sound, SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 0.6f, 0.5f);
			if (++m[0] >= 4) {
				MOVING.remove(s.id());
				Settlements.putState(server, s.withLevel(STATE, raising ? 1 : 0));
			}
		}
	}
}
