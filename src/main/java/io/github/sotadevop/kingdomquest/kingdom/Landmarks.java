package io.github.sotadevop.kingdomquest.kingdom;

import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Helps the lord find his buildings: a post with a hanging name sign in front of every add-on, fireworks over a
 * building when it is finished, and a chat line with where it stands.
 */
final class Landmarks {
	private Landmarks() {
	}

	/** Min x, min z, max x, max z of a track's built blocks (null if nothing is built). */
	static int[] bounds(Settlement s, String track) {
		List<Long> list = s.built().getOrDefault(track, List.of());
		if (list.isEmpty()) {
			return null;
		}
		int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		for (long l : list) {
			int x = BlockPos.getX(l);
			int z = BlockPos.getZ(l);
			b[0] = Math.min(b[0], x);
			b[1] = Math.min(b[1], z);
			b[2] = Math.max(b[2], x);
			b[3] = Math.max(b[3], z);
		}
		return b;
	}

	/**
	 * The name sign: on the side of the building that faces the square, two blocks out, a spruce post with an arm
	 * and a hanging sign. Returns the blocks placed (so they count as part of the building).
	 */
	static List<Long> sign(ServerLevel level, Settlement s, String track, Component title, Set<Long> taken) {
		int[] b = bounds(s, track);
		List<Long> placed = new ArrayList<>();
		if (b == null) {
			return placed;
		}
		int cx = (b[0] + b[2]) / 2;
		int cz = (b[1] + b[3]) / 2;
		int dx = s.heart().getX() - cx;
		int dz = s.heart().getZ() - cz;
		Direction face = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
		int ex = face == Direction.EAST ? b[2] + 2 : face == Direction.WEST ? b[0] - 2 : cx;
		int ez = face == Direction.SOUTH ? b[3] + 2 : face == Direction.NORTH ? b[1] - 2 : cz;
		Direction along = face.getClockWise();
		for (int k : new int[] {2, -2, 3, -3, 4, -4, 0, 1, -1}) {
			BlockPos col = new BlockPos(ex, 0, ez).relative(along, k);
			int y = SiteWork.groundTop(level, col.getX(), col.getZ(), s.heart().getY() + Settlements.lift(s, Settlements.template(s, track + "_1"))) + 1;
			BlockPos base = new BlockPos(col.getX(), y, col.getZ());
			BlockPos arm = base.above(2).relative(along);
			if (!free(level, base, taken) || !free(level, base.above(), taken) || !free(level, base.above(2), taken) || !free(level, arm, taken)
					|| !free(level, arm.below(), taken) || !level.getBlockState(base.below()).isSolid()) {
				continue;
			}
			BlockState fence = Blocks.SPRUCE_FENCE.defaultBlockState();
			for (BlockPos p : List.of(base, base.above(), base.above(2), arm)) {
				level.setBlock(p, fence, Block.UPDATE_ALL);
				placed.add(p.asLong());
			}
			for (BlockPos p : List.of(base, base.above(), base.above(2), arm)) {
				level.setBlock(p, Block.updateFromNeighbourShapes(fence, level, p), Block.UPDATE_ALL);
			}
			BlockPos signPos = arm.below();
			BlockState sign = Blocks.SPRUCE_HANGING_SIGN.defaultBlockState().setValue(CeilingHangingSignBlock.ROTATION,
					RotationSegment.convertToSegment(face)).setValue(CeilingHangingSignBlock.ATTACHED, false);
			level.setBlock(signPos, sign, Block.UPDATE_ALL);
			placed.add(signPos.asLong());
			if (level.getBlockEntity(signPos) instanceof SignBlockEntity be) {
				List<Component> lines = List.of(Component.empty(), title, Component.empty(), Component.empty());
				SignText text = new SignText(lines, lines, DyeColor.BLACK, false);
				for (net.minecraft.world.level.block.entity.SignTextSlot slot : net.minecraft.world.level.block.entity.SignTextSlot.values()) {
					be.setText(text, slot); // front and back
				}
				be.setWaxed(true);
				be.setChanged();
				level.sendBlockUpdated(signPos, sign, sign, Block.UPDATE_ALL);
			}
			return placed;
		}
		return placed;
	}

	private static boolean free(ServerLevel level, BlockPos p, Set<Long> taken) {
		BlockState st = level.getBlockState(p);
		return !taken.contains(p.asLong()) && (st.isAir() || st.canBeReplaced());
	}

	/** Three rockets in the kingdom's color over the finished building, and a chat line with where it is. */
	static void celebrate(ServerLevel level, Settlement s, String track, Component title, ServerPlayer owner) {
		int[] b = bounds(s, track);
		if (b == null) {
			return;
		}
		int cx = (b[0] + b[2]) / 2;
		int cz = (b[1] + b[3]) / 2;
		int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, cx, cz) + 1;
		int color = DyeColor.byId(s.color()).getFireworkColor();
		for (int i = 0; i < 3; i++) {
			ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
			rocket.set(DataComponents.FIREWORKS, new Fireworks(1 + i % 2, List.of(new FireworkExplosion(
					i == 1 ? FireworkExplosion.Shape.STAR : FireworkExplosion.Shape.LARGE_BALL, IntList.of(color), IntList.of(0xFFFFFF), true, true))));
			level.addFreshEntity(new FireworkRocketEntity(level, cx + 0.5 + (i - 1) * 2, y, cz + 0.5, rocket));
		}
		if (owner != null) {
			BlockPos at = new BlockPos(cx, y, cz);
			owner.sendSystemMessage(Component.translatable("kingdomquest.build.where", title, at.getX(), at.getY(), at.getZ(), direction(owner, at))
					.withStyle(ChatFormatting.GREEN));
		}
	}

	/** "120 blocks north-east" from the player. */
	static String direction(ServerPlayer p, BlockPos to) {
		double dx = to.getX() + 0.5 - p.getX();
		double dz = to.getZ() + 0.5 - p.getZ();
		int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
		if (dist < 6) {
			return "right here";
		}
		String[] names = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
		double ang = Math.toDegrees(Math.atan2(-dx, dz)); // 0 = south, 90 = west
		int i = Math.floorMod((int) Math.round(ang / 45.0), 8);
		return dist + " blocks " + names[i];
	}
}
