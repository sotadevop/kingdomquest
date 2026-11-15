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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mechanisms run by the mod (more reliable than redstone in a generated build):
 * the lava curtain over the gate (gate level 5, lever on the gatehouse roof) and the secret door of the vault
 * (cellar level 3, button in the cellar). Both close again by themselves.
 */
final class Traps {
	private static final int GX = 7;
	private static final int GZ = 40;
	private record Timer(long until, List<BlockPos> blocks, BlockState restore) {
	}

	private static final Map<String, Timer> OPEN = new HashMap<>();

	private Traps() {
	}

	private static BlockPos at(Settlement s, int bx, int by, int bz) {
		return s.heart().offset(Template.rotate(new BlockPos(bx - 7, by, bz - 10), Rotation.values()[s.rotation()]));
	}

	/** A lever or button was used at {@code pos}; returns true if it was one of ours. */
	static boolean use(MinecraftServer server, BlockPos pos) {
		ServerLevel level = server.overworld();
		for (Settlement s : Settlements.all(server)) {
			if (s.level(Progression.GATE) >= 5 && pos.equals(at(s, GX, 8, GZ - 1)) && !OPEN.containsKey(s.id() + "lava")) {
				List<BlockPos> lava = new ArrayList<>();
				for (int x = GX - 1; x <= GX + 1; x++) {
					BlockPos p = at(s, x, 8, GZ + 1);
					level.setBlock(p, Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
					lava.add(p);
				}
				level.playSound(null, lava.get(1), SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 1f, 0.7f);
				level.playSound(null, lava.get(1), SoundEvents.BUCKET_EMPTY_LAVA, SoundSource.BLOCKS, 1f, 1f);
				OPEN.put(s.id() + "lava", new Timer(level.getGameTime() + 160, lava, Blocks.AIR.defaultBlockState()));
				return true;
			}
			BlockState wall = Template.get(s.style() + "/cellar_3") == null ? null : level.getBlockState(at(s, 2, -5, 1));
			if (s.level(Progression.CELLAR) >= 3 && pos.equals(at(s, 3, -4, 3)) && !OPEN.containsKey(s.id() + "door") && wall != null) {
				List<BlockPos> door = new ArrayList<>();
				for (int z = 1; z <= 2; z++) {
					for (int y = -5; y <= -4; y++) {
						BlockPos p = at(s, 2, y, z);
						door.add(p);
						level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
					}
				}
				level.playSound(null, door.get(0), SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 1f, 0.8f);
				OPEN.put(s.id() + "door", new Timer(level.getGameTime() + 100, door, wall));
				return true;
			}
		}
		return false;
	}

	static void tick(MinecraftServer server) {
		if (OPEN.isEmpty()) {
			return;
		}
		ServerLevel level = server.overworld();
		OPEN.entrySet().removeIf(e -> {
			Timer t = e.getValue();
			if (level.getGameTime() < t.until()) {
				return false;
			}
			for (BlockPos p : t.blocks()) {
				if (t.restore().isAir() || level.getBlockState(p).isAir()) {
					level.setBlock(p, t.restore(), Block.UPDATE_ALL);
				}
			}
			level.playSound(null, t.blocks().get(0), SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 1f, 0.8f);
			return true;
		});
	}
}
