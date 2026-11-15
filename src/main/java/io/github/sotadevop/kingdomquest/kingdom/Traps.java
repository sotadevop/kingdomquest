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
 * the lava curtain over the gate (gate level 5, stands while its roof lever is on), the arrow slits (gate level 4,
 * volleys while their roof lever is on) and the secret door of the vault (cellar level 3, button; closes by itself).
 */
final class Traps {
	private static final int GX = 7;
	private static final int GZ = 50;
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
			if (s.level(Progression.GATE) >= 5 && pos.equals(lavaLever(s))) {
				return true; // the lava follows the lever in tick()
			}
			if (s.level(Progression.GATE) >= 4 && pos.equals(arrowLever(s))) {
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

	static BlockPos lavaLever(Settlement s) {
		return at(s, GX, 8, GZ - 1);
	}

	static BlockPos arrowLever(Settlement s) {
		return at(s, GX - 2, 8, GZ);
	}

	private static boolean on(ServerLevel level, BlockPos lever) {
		BlockState st = level.getBlockState(lever);
		return st.getBlock() instanceof net.minecraft.world.level.block.LeverBlock && st.getValue(net.minecraft.world.level.block.LeverBlock.POWERED);
	}

	/** Every half second: the lava curtain stands while its lever is on; the arrow slits fire volleys while theirs is. */
	private static void mechanisms(MinecraftServer server) {
		ServerLevel level = server.overworld();
		for (Settlement s : Settlements.all(server)) {
			if (s.level(Progression.GATE) < 4 || !level.isLoaded(s.heart())) {
				continue;
			}
			if (s.level(Progression.GATE) >= 5) {
				boolean lava = on(level, lavaLever(s));
				for (int x = GX - 1; x <= GX + 1; x++) {
					BlockPos p = at(s, x, 8, GZ + 1);
					BlockState now = level.getBlockState(p);
					if (lava && now.isAir()) {
						level.setBlock(p, Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
						if (x == GX) {
							level.playSound(null, p, SoundEvents.BUCKET_EMPTY_LAVA, SoundSource.BLOCKS, 1f, 1f);
						}
					} else if (!lava && now.is(Blocks.LAVA)) {
						level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
						if (x == GX) {
							level.playSound(null, p, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 1f, 0.8f);
						}
					}
				}
			}
			if (on(level, arrowLever(s))) {
				for (int x : new int[] {GX - 3, GX - 1, GX + 1, GX + 3}) {
					BlockPos d = at(s, x, 5, GZ + 1);
					BlockPos out = at(s, x, 5, GZ + 3);
					net.minecraft.world.phys.Vec3 dir = net.minecraft.world.phys.Vec3.atCenterOf(out).subtract(net.minecraft.world.phys.Vec3.atCenterOf(d));
					net.minecraft.world.entity.projectile.arrow.Arrow arrow = new net.minecraft.world.entity.projectile.arrow.Arrow(level,
							d.getX() + 0.5 + dir.x * 0.35, d.getY() + 0.5, d.getZ() + 0.5 + dir.z * 0.35,
							new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW), null);
					arrow.shoot(dir.x, -0.35, dir.z, 1.4f, 9f);
					arrow.pickup = net.minecraft.world.entity.projectile.arrow.AbstractArrow.Pickup.DISALLOWED;
					level.addFreshEntity(arrow);
				}
				level.playSound(null, at(s, GX, 5, GZ + 1), SoundEvents.DISPENSER_LAUNCH, SoundSource.BLOCKS, 1f, 1f);
			}
		}
	}

	static void tick(MinecraftServer server) {
		if (server.getTickCount() % 10 == 0) {
			mechanisms(server);
		}
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
