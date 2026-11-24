package io.github.sotadevop.kingdomquest.kingdom;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.sotadevop.kingdomquest.building.Layout;
import io.github.sotadevop.kingdomquest.building.Template;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;

/**
 * The tram (19.11): rails in the street from the gate up the main street to the square and the castle, with a branch
 * along the south cross street to the storehouse. The lectern at a stop opens a small menu of the other stops; the
 * mod brings a minecart, seats the lord in it and drives it along the rails to the chosen stop.
 * <p>
 * The rails lie on the street plan's heights. Only the part reachable from the gate is laid: a step of more than one
 * block, a half step (a slab in the street) or a gap in the street ends the line, and stops past the end are left out
 * (a hill castle's motte stairs, for example). The lines are kept in step with tools/buildings.py (TRAM_MAIN,
 * TRAM_BRANCH, TRAM_STOPS).
 */
public final class Tram {
	/** Base-frame columns of the main line, gate end first. */
	private static final int[][] MAIN = line(9, 94, 9, 17);
	/** Base-frame columns of the branch, from beside the junction westward. */
	private static final int[][] BRANCH = line(8, 58, -22, 58);
	/** Index on the main line where the branch leaves. */
	private static final int JUNCTION = 94 - 58;
	/** Stop keys and where they lie: {line (0 main, 1 branch), index}. */
	static final String[] STOPS = {"gate", "square", "castle", "storehouse"};
	private static final int[][] STOP_AT = {{0, 94 - 91}, {0, 94 - 32}, {0, 94 - 20}, {1, 8 + 19}};
	private static final double SPEED = 0.5;

	private static final Map<UUID, Trip> TRIPS = new HashMap<>();

	private Tram() {
	}

	private static int[][] line(int x0, int z0, int x1, int z1) {
		int n = Math.max(Math.abs(x1 - x0), Math.abs(z1 - z0)) + 1;
		int[][] out = new int[n][];
		for (int i = 0; i < n; i++) {
			out[i] = new int[] {x0 + Integer.signum(x1 - x0) * i, z0 + Integer.signum(z1 - z0) * i};
		}
		return out;
	}

	static void register() {
		PayloadTypeRegistry.clientboundPlay().register(TramPayload.TYPE, TramPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(TramGoPayload.TYPE, TramGoPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(TramGoPayload.TYPE, (payload, context) -> go(context.player(), payload));
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!(player instanceof ServerPlayer sp) || level.dimension() != Level.OVERWORLD
					|| !(level.getBlockState(hit.getBlockPos()).getBlock() instanceof LecternBlock)) {
				return InteractionResult.PASS;
			}
			MinecraftServer server = sp.level().getServer();
			for (Settlement s : Settlements.all(server)) {
				if (s.level(Progression.TRAM) <= 0 || s.heart().distManhattan(hit.getBlockPos()) > 400) {
					continue;
				}
				Line l = Line.of(server, s);
				int here = l == null ? -1 : l.stopNear(hit.getBlockPos());
				if (here < 0) {
					continue;
				}
				if (hand == InteractionHand.MAIN_HAND) {
					open(sp, s, l, here);
				}
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});
		ServerTickEvents.END_SERVER_TICK.register(Tram::tick);
	}

	// ---- the line as laid ----

	/** The reachable part of a settlement's line: world rail positions by line and index. */
	static final class Line {
		final BlockPos[] main;
		final BlockPos[] branch;
		final Settlement s;

		private Line(Settlement s, BlockPos[] main, BlockPos[] branch) {
			this.s = s;
			this.main = main;
			this.branch = branch;
		}

		static Line of(MinecraftServer server, Settlement s) {
			StreetPlan streets = StreetPlan.of(server, s);
			if (streets == null) {
				return null;
			}
			Rotation r = Rotation.values()[s.rotation()];
			BlockPos[] main = reach(s, streets, r, MAIN, null);
			BlockPos[] branch = main[JUNCTION] == null ? new BlockPos[BRANCH.length] : reach(s, streets, r, BRANCH, main[JUNCTION]);
			return new Line(s, main, branch);
		}

		BlockPos stop(int i) {
			int[] at = STOP_AT[i];
			return (at[0] == 0 ? this.main : this.branch)[at[1]];
		}

		/** The stop whose platform the position is on (within 4 blocks of its rail), or -1. */
		int stopNear(BlockPos pos) {
			for (int i = 0; i < STOPS.length; i++) {
				BlockPos p = stop(i);
				if (p != null && Math.abs(p.getX() - pos.getX()) <= 4 && Math.abs(p.getZ() - pos.getZ()) <= 4 && Math.abs(p.getY() - pos.getY()) <= 3) {
					return i;
				}
			}
			return -1;
		}

		/** The rails from one stop to another, in order. */
		List<BlockPos> route(int from, int to) {
			List<BlockPos> out = new ArrayList<>();
			int[] a = STOP_AT[from];
			int[] b = STOP_AT[to];
			if (a[0] == b[0]) {
				BlockPos[] l = a[0] == 0 ? this.main : this.branch;
				walk(l, a[1], b[1], out);
			} else if (a[0] == 0) {
				walk(this.main, a[1], JUNCTION, out);
				walk(this.branch, 0, b[1], out);
			} else {
				walk(this.branch, a[1], 0, out);
				walk(this.main, JUNCTION, b[1], out);
			}
			return out;
		}

		private static void walk(BlockPos[] l, int i, int j, List<BlockPos> out) {
			int d = Integer.signum(j - i);
			for (int k = i; ; k += d) {
				out.add(l[k]);
				if (k == j) {
					break;
				}
			}
		}
	}

	/**
	 * World rail positions along a line from its first column while the street allows it: a street column, not a half
	 * step, at most one block up or down from the one before. The rest is null.
	 */
	private static BlockPos[] reach(Settlement s, StreetPlan streets, Rotation r, int[][] line, BlockPos from) {
		BlockPos[] out = new BlockPos[line.length];
		BlockPos prev = from;
		for (int i = 0; i < line.length; i++) {
			BlockPos col = s.heart().offset(Layout.rel(r, line[i][0], 0, line[i][1]));
			long key = BlockPos.asLong(col.getX(), 0, col.getZ());
			Integer f = streets.feetAt(key);
			if (f == null || streets.slab(key)) {
				break;
			}
			BlockPos here = col.atY(s.heart().getY() + f);
			if (prev != null && Math.abs(here.getY() - prev.getY()) > 1) {
				break;
			}
			out[i] = here;
			prev = here;
		}
		return out;
	}

	/**
	 * The tram's blocks as placed: only what stands on the reachable line (rails, and stops whose rail is reached), and
	 * every rail turned to run along its line, sloping where the next rail is one block up.
	 */
	static void trim(Settlement s, StreetPlan streets, List<Template.Placement> out) {
		Rotation r = Rotation.values()[s.rotation()];
		BlockPos[] main = reach(s, streets, r, MAIN, null);
		BlockPos[] branch = main[JUNCTION] == null ? new BlockPos[BRANCH.length] : reach(s, streets, r, BRANCH, main[JUNCTION]);
		Set<Long> kept = new HashSet<>();
		Map<Long, RailShape> shapes = new HashMap<>();
		for (BlockPos[] l : new BlockPos[][] {main, branch}) {
			for (int i = 0; i < l.length && l[i] != null; i++) {
				BlockPos here = l[i];
				BlockPos next = i + 1 < l.length ? l[i + 1] : null;
				BlockPos prev = i > 0 ? l[i - 1] : l == branch ? main[JUNCTION] : null;
				BlockPos along = next != null ? next : prev;
				RailShape shape;
				if (next != null && next.getY() == here.getY() + 1) {
					shape = ascending(next.getX() - here.getX(), next.getZ() - here.getZ());
				} else if (prev != null && prev.getY() == here.getY() + 1) {
					shape = ascending(prev.getX() - here.getX(), prev.getZ() - here.getZ());
				} else {
					shape = along != null && along.getX() != here.getX() ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
				}
				BlockPos rel = here.subtract(s.heart());
				long k = BlockPos.asLong(rel.getX(), 0, rel.getZ());
				kept.add(k);
				shapes.put(k, shape);
			}
		}
		out.removeIf(p -> !kept.contains(BlockPos.asLong(p.anchor().getX(), 0, p.anchor().getZ())));
		out.replaceAll(p -> {
			if (!(p.state().getBlock() instanceof BaseRailBlock) || p.offset().getX() != p.anchor().getX() || p.offset().getZ() != p.anchor().getZ()) {
				return p;
			}
			RailShape shape = shapes.get(BlockPos.asLong(p.anchor().getX(), 0, p.anchor().getZ()));
			BlockState st = p.state();
			if (st.hasProperty(BlockStateProperties.RAIL_SHAPE)) {
				st = st.setValue(BlockStateProperties.RAIL_SHAPE, shape);
			} else if (st.hasProperty(BlockStateProperties.RAIL_SHAPE_STRAIGHT)) {
				st = st.setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT, shape);
			}
			return new Template.Placement(p.offset(), st, p.segment(), p.anchor());
		});
	}

	private static RailShape ascending(int dx, int dz) {
		return dx > 0 ? RailShape.ASCENDING_EAST : dx < 0 ? RailShape.ASCENDING_WEST : dz > 0 ? RailShape.ASCENDING_SOUTH : RailShape.ASCENDING_NORTH;
	}

	// ---- the menu and the ride ----

	private static void open(ServerPlayer player, Settlement s, Line l, int here) {
		JsonObject o = new JsonObject();
		o.addProperty("x", s.heart().getX());
		o.addProperty("y", s.heart().getY());
		o.addProperty("z", s.heart().getZ());
		o.addProperty("here", here);
		o.addProperty("name", s.name());
		JsonArray stops = new JsonArray();
		for (int i = 0; i < STOPS.length; i++) {
			if (l.stop(i) != null) {
				stops.add(i);
			}
		}
		o.add("stops", stops);
		ServerPlayNetworking.send(player, new TramPayload(o.toString()));
	}

	private static void go(ServerPlayer player, TramGoPayload p) {
		MinecraftServer server = player.level().getServer();
		Settlement s = Settlements.byHeart(server, p.heart());
		if (s == null || s.level(Progression.TRAM) <= 0 || p.from() == p.to() || p.from() < 0 || p.to() < 0
				|| p.from() >= STOPS.length || p.to() >= STOPS.length || player.isPassenger()) {
			return;
		}
		Line l = Line.of(server, s);
		if (l == null || l.stop(p.from()) == null || l.stop(p.to()) == null || l.stop(p.from()).distSqr(player.blockPosition()) > 64) {
			return;
		}
		List<BlockPos> rails = l.route(p.from(), p.to());
		List<Vec3> path = new ArrayList<>();
		for (BlockPos b : rails) {
			path.add(new Vec3(b.getX() + 0.5, b.getY() + 0.0625, b.getZ() + 0.5));
		}
		ServerLevel level = (ServerLevel) player.level();
		// looked up by id: the field's name changed between versions
		EntityType<?> type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(
				net.minecraft.resources.Identifier.withDefaultNamespace("minecart"));
		Entity cart = type == null ? null : type.create(level, EntitySpawnReason.EVENT);
		if (cart == null) {
			return;
		}
		Vec3 start = path.get(0);
		cart.setPos(start.x, start.y, start.z);
		level.addFreshEntity(cart);
		player.startRiding(cart);
		TRIPS.put(cart.getUUID(), new Trip(level, cart, player.getUUID(), path));
		level.playSound(null, cart.blockPosition(), net.minecraft.sounds.SoundEvents.MINECART_RIDING, net.minecraft.sounds.SoundSource.NEUTRAL, 0.6F, 1.0F);
	}

	private static final class Trip {
		final ServerLevel level;
		final Entity cart;
		final UUID rider;
		final List<Vec3> path;
		int at;
		double into;

		Trip(ServerLevel level, Entity cart, UUID rider, List<Vec3> path) {
			this.level = level;
			this.cart = cart;
			this.rider = rider;
			this.path = path;
		}
	}

	/** Drives every running ride a little further along its rails; at the end the lord steps out and the cart goes. */
	private static void tick(MinecraftServer server) {
		Iterator<Trip> it = TRIPS.values().iterator();
		while (it.hasNext()) {
			Trip t = it.next();
			Entity rider = t.level.getEntity(t.rider);
			if (t.cart.isRemoved() || rider == null || rider.getVehicle() != t.cart) {
				t.cart.discard();
				it.remove();
				continue;
			}
			double left = SPEED;
			while (left > 0 && t.at < t.path.size() - 1) {
				Vec3 a = t.path.get(t.at);
				Vec3 b = t.path.get(t.at + 1);
				double seg = a.distanceTo(b);
				if (t.into + left < seg) {
					t.into += left;
					left = 0;
				} else {
					left -= seg - t.into;
					t.into = 0;
					t.at++;
				}
			}
			if (t.at >= t.path.size() - 1) {
				Vec3 end = t.path.get(t.path.size() - 1);
				t.cart.setPos(end.x, end.y, end.z);
				rider.stopRiding();
				t.cart.discard();
				it.remove();
				continue;
			}
			Vec3 a = t.path.get(t.at);
			Vec3 b = t.path.get(t.at + 1);
			double seg = a.distanceTo(b);
			Vec3 pos = a.add(b.subtract(a).scale(seg == 0 ? 0 : t.into / seg));
			Vec3 dir = b.subtract(a).normalize();
			t.cart.setDeltaMovement(dir.scale(SPEED));
			t.cart.setPos(pos.x, pos.y, pos.z);
			t.cart.setYRot((float) (Math.toDegrees(Math.atan2(dir.z, dir.x))));
		}
	}
}
