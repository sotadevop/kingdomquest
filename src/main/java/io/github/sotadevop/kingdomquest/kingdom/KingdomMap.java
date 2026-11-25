package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.KingdomQuest;
import java.util.List;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * The Kingdom Map (user, 2026-11-25: a map to carry, and to go from building to building): used anywhere it opens the
 * realm screen of the nearest own kingdom. Given at the founding; made from a map and an emerald. In the realm map's
 * buildings and in the kingdom menu each building can be visited: the lord stands in front of its door at once
 * (in survival too).
 */
public final class KingdomMap {
	public static final ResourceKey<Item> KEY = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(KingdomQuest.MOD_ID, "kingdom_map"));
	public static final Item ITEM = Registry.register(BuiltInRegistries.ITEM, KEY,
			new Item(new Item.Properties().setId(KEY).stacksTo(1).rarity(Rarity.UNCOMMON)) {
				@Override
				public InteractionResult use(Level level, Player player, InteractionHand hand) {
					if (player instanceof ServerPlayer sp) {
						Settlement s = Settlements.nearestOwned(sp);
						if (s == null) {
							sp.sendOverlayMessage(Component.translatable("kingdomquest.map.none"));
						} else {
							Realm.send(sp, s);
						}
					}
					return InteractionResult.SUCCESS;
				}
			});

	private KingdomMap() {
	}

	public static void register() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(output -> output.accept(ITEM));
	}

	/** A new lord gets his map with the founding. */
	static void give(ServerPlayer player) {
		ItemStack map = new ItemStack(ITEM);
		if (!player.getInventory().add(map)) {
			player.level().addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(player.level(), player.getX(), player.getY(), player.getZ(), map));
		}
	}

	/** Takes the lord to the front of a building's door (or onto its plot when it has none). */
	static void visit(ServerPlayer player, Settlement s, String track) {
		if (s == null || !s.owner().equals(player.getUUID()) || player.level().dimension() != Level.OVERWORLD || player.isPassenger()) {
			return;
		}
		ServerLevel level = player.level().getServer().overworld();
		List<Long> built = s.built().getOrDefault(track, List.of());
		if (built.isEmpty()) {
			return;
		}
		int[] b = Landmarks.bounds(s, track);
		BlockPos centre = new BlockPos((b[0] + b[2]) / 2, s.heart().getY(), (b[1] + b[3]) / 2);
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (long l : built) {
			BlockPos p = BlockPos.of(l);
			BlockState st = level.getBlockState(p);
			if (!(st.getBlock() instanceof DoorBlock) || st.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
				continue;
			}
			Direction f = st.getValue(DoorBlock.FACING);
			for (Direction d : new Direction[] {f, f.getOpposite()}) {
				BlockPos spot = stand(level, p.relative(d, 2));
				if (spot != null && level.canSeeSky(spot.above())) { // the outside of the door
					double dd = spot.distSqr(centre);
					if (dd < bestD) {
						bestD = dd;
						best = spot;
					}
				}
			}
		}
		if (best == null) {
			for (int r = 0; r < 12 && best == null; r++) {
				for (Direction d : Direction.Plane.HORIZONTAL) {
					BlockPos spot = stand(level, centre.relative(d, r + (b[2] - b[0]) / 2 + 2));
					if (spot != null) {
						best = spot;
						break;
					}
				}
			}
		}
		if (best == null) {
			return;
		}
		level.playSound(null, player.blockPosition(), net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, net.minecraft.sounds.SoundSource.PLAYERS, 0.6f, 1.3f);
		float yaw = (float) Math.toDegrees(Math.atan2(-(centre.getX() - best.getX()), centre.getZ() - best.getZ()));
		player.teleport(new net.minecraft.world.level.portal.TeleportTransition(level, net.minecraft.world.phys.Vec3.atBottomCenterOf(best),
				net.minecraft.world.phys.Vec3.ZERO, yaw, 0, net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
		player.resetFallDistance();
		level.playSound(null, best, net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, net.minecraft.sounds.SoundSource.PLAYERS, 0.6f, 1.3f);
	}

	/** Where one can stand in this column, near the given height: feet and head free, solid below. */
	private static BlockPos stand(ServerLevel level, BlockPos near) {
		level.getChunk(near.getX() >> 4, near.getZ() >> 4);
		for (int dy : new int[] {0, 1, -1, 2, -2, 3, -3}) {
			BlockPos feet = near.above(dy);
			if (level.getBlockState(feet).getCollisionShape(level, feet).isEmpty() && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
					&& level.getFluidState(feet).isEmpty() && level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)) {
				return feet;
			}
		}
		return null;
	}
}
