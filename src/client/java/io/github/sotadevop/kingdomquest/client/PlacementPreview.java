package io.github.sotadevop.kingdomquest.client;

import io.github.sotadevop.kingdomquest.building.Template;
import io.github.sotadevop.kingdomquest.charter.RoyalCharter;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * While a sealed charter is held and aimed at the top of a block, outlines the plot with particles and marks
 * the heart and the entrance side, rotated the way the player faces (the camp opens toward the player).
 */
public final class PlacementPreview {
	private static int ticks;

	private PlacementPreview() {
	}

	public static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null || mc.isPaused() || ++ticks % 4 != 0) {
			return;
		}
		ItemStack held = mc.player.getMainHandItem().is(RoyalCharter.ITEM) ? mc.player.getMainHandItem() : mc.player.getOffhandItem();
		if (!held.is(RoyalCharter.ITEM) || held.get(RoyalCharter.DATA) == null || mc.player.isShiftKeyDown()) {
			return;
		}
		if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK || hit.getDirection() != Direction.UP) {
			return;
		}
		Template t = Template.get("temperate/tent_3"); // largest encampment: the cleared area
		if (t == null) {
			return;
		}
		BlockPos heart = hit.getBlockPos().above();
		Rotation rotation = switch (mc.player.getDirection()) {
			case EAST -> Rotation.CLOCKWISE_90;
			case SOUTH -> Rotation.CLOCKWISE_180;
			case WEST -> Rotation.COUNTERCLOCKWISE_90;
			default -> Rotation.NONE;
		};
		for (int x = 0; x < t.sizeX; x++) {
			for (int z = 0; z < t.sizeZ; z++) {
				boolean edge = x == 0 || z == 0 || x == t.sizeX - 1 || z == t.sizeZ - 1;
				if (!edge) {
					continue;
				}
				BlockPos rel = Template.rotate(new BlockPos(x - t.heart.getX(), 0, z - t.heart.getZ()), rotation);
				BlockPos p = heart.offset(rel.getX(), 0, rel.getZ());
				int y = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, p.getX(), p.getZ());
				boolean front = z == t.sizeZ - 1;
				mc.level.addParticle(front ? ParticleTypes.FLAME : ParticleTypes.HAPPY_VILLAGER, p.getX() + 0.5, Math.max(y, heart.getY()) + 0.2,
						p.getZ() + 0.5, 0, 0, 0);
			}
		}
		for (int i = 0; i < 3; i++) {
			mc.level.addParticle(ParticleTypes.END_ROD, heart.getX() + 0.5, heart.getY() + 0.3 + i * 0.6, heart.getZ() + 0.5, 0, 0.01, 0);
		}
	}
}
