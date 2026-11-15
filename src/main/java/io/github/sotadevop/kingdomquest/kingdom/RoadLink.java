package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.building.Template;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Roads between places: from a settlement's gate to another of the player's settlements, or to the nearest
 * vanilla village. A 3 wide gravel road follows the ground, fells trees in the way, crosses water on a plank
 * bridge and gets a lantern post every 24 blocks. It is built a few blocks per tick.
 */
final class RoadLink {
	private record Job(UUID player, List<BlockPos> line, int[] index) {
	}

	private static final Map<UUID, Job> JOBS = new HashMap<>();
	private static final int PER_TICK = 6;
	private static final int MAX_LENGTH = 1200;

	private RoadLink() {
	}

	/** The point just outside a settlement's gate (beyond the moat), in world coordinates. */
	static BlockPos gateExit(Settlement s) {
		return s.heart().offset(Template.rotate(new BlockPos(0, 0, 38), Rotation.values()[s.rotation()]));
	}

	static void connect(ServerPlayer player, Settlement from, boolean village) {
		MinecraftServer server = player.level().getServer();
		ServerLevel level = server.overworld();
		if (JOBS.containsKey(from.id())) {
			return;
		}
		BlockPos start = gateExit(from);
		BlockPos end = null;
		String name = null;
		if (village) {
			end = level.findNearestMapStructure(StructureTags.VILLAGE, start, 64, false);
			name = "the nearest village";
		} else {
			double best = Double.MAX_VALUE;
			for (Settlement o : Settlements.all(server)) {
				if (!o.id().equals(from.id()) && o.owner().equals(player.getUUID())) {
					double d = o.heart().distSqr(from.heart());
					if (d < best) {
						best = d;
						end = gateExit(o);
						name = o.name();
					}
				}
			}
		}
		if (end == null || Math.sqrt(end.distSqr(start)) > MAX_LENGTH) {
			player.sendOverlayMessage(Component.translatable(village ? "kingdomquest.link.no_village" : "kingdomquest.link.no_kingdom"));
			return;
		}
		List<BlockPos> line = new ArrayList<>();
		int dx = end.getX() - start.getX();
		int dz = end.getZ() - start.getZ();
		int steps = Math.max(Math.abs(dx), Math.abs(dz));
		for (int i = 0; i <= steps; i++) {
			line.add(new BlockPos(start.getX() + Math.round((float) dx * i / steps), from.heart().getY(), start.getZ() + Math.round((float) dz * i / steps)));
		}
		JOBS.put(from.id(), new Job(player.getUUID(), line, new int[] {0}));
		People.say(player, "Architect", "My workers will build a road to " + name + ". It's " + steps + " blocks long.");
	}

	static void tick(MinecraftServer server) {
		if (JOBS.isEmpty()) {
			return;
		}
		ServerLevel level = server.overworld();
		JOBS.entrySet().removeIf(e -> {
			Job job = e.getValue();
			int[] i = job.index();
			for (int n = 0; n < PER_TICK && i[0] < job.line().size(); n++, i[0]++) {
				BlockPos c = job.line().get(i[0]);
				boolean diagonalX = i[0] + 1 < job.line().size() && job.line().get(i[0] + 1).getX() != c.getX();
				for (int w = -1; w <= 1; w++) {
					BlockPos cell = diagonalX ? c.offset(0, 0, w) : c.offset(w, 0, 0);
					pave(level, cell);
				}
				if (i[0] % 24 == 12) {
					post(level, diagonalX ? c.offset(0, 0, 2) : c.offset(2, 0, 0));
				}
			}
			if (i[0] >= job.line().size()) {
				ServerPlayer p = server.getPlayerList().getPlayer(job.player());
				if (p != null) {
					People.say(p, "Architect", "The road is finished.");
				}
				return true;
			}
			return false;
		});
	}

	private static void pave(ServerLevel level, BlockPos cell) {
		int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, cell.getX(), cell.getZ());
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(cell.getX(), top, cell.getZ());
		// walk down through air, plants and trees to the ground or the water surface
		while (pos.getY() > level.getMinY()) {
			BlockState st = level.getBlockState(pos);
			if (st.getBlock() instanceof LiquidBlock) {
				level.setBlock(pos, Blocks.SPRUCE_PLANKS.defaultBlockState(), Block.UPDATE_CLIENTS); // bridge
				return;
			}
			if (st.is(BlockTags.LOGS)) {
				SiteWork.fellTree(level, pos.immutable(), java.util.Set.of());
			} else if (!st.isAir() && !st.canBeReplaced() && !st.is(BlockTags.LEAVES) && st.isSolid()) {
				break;
			} else if (!st.isAir()) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
			}
			pos.move(0, -1, 0);
		}
		BlockState ground = level.getBlockState(pos);
		if (SiteWork.natural(ground) || ground.is(Blocks.DIRT_PATH)) {
			level.setBlock(pos, (Math.floorMod(pos.getX() * 31 + pos.getZ() * 17, 5) == 0 ? Blocks.COBBLESTONE : Blocks.GRAVEL).defaultBlockState(),
					Block.UPDATE_CLIENTS);
			BlockPos below = pos.below();
			if (level.getBlockState(below).isAir() || level.getBlockState(below).getBlock() instanceof LiquidBlock) {
				level.setBlock(below, Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_CLIENTS); // gravel must not fall
			}
		}
	}

	private static void post(ServerLevel level, BlockPos cell) {
		int top = SiteWork.groundTop(level, cell.getX(), cell.getZ(), level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cell.getX(), cell.getZ()));
		BlockPos base = new BlockPos(cell.getX(), top + 1, cell.getZ());
		if (!level.getBlockState(base).isAir() && !level.getBlockState(base).canBeReplaced()) {
			return;
		}
		level.setBlock(base, Blocks.SPRUCE_FENCE.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(base.above(), Blocks.SPRUCE_FENCE.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(base.above(2), Blocks.LANTERN.defaultBlockState(), Block.UPDATE_ALL);
	}
}
