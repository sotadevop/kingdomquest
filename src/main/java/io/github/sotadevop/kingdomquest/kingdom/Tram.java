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
 * The tram (19.11): a loop of rails around the town just inside the wall (user, 2026-11-25: not straight through the
 * city), with stops by the gate and in the middle of the west, north and east sides. The lectern at a stop opens a
 * small menu of the other stops; the mod brings a minecart, seats the lord in it and drives it along the rails the
 * shorter way round to the chosen stop.
 * <p>
 * The rails lie on the plan's height (the plateau's flat band along the wall), on the street's where they cross one.
 * The loop and its stops come from layout.json (tools/buildings.py tram_loop).
 */
public final class Tram {
	private static final double SPEED = 0.5;

	private static final Map<UUID, Trip> TRIPS = new HashMap<>();

	private Tram() {
	}

	/** The stop names of a settlement's loop. */
	static String[] stops(Settlement s) {
		return Layout.tramStops(Settlements.round(s));
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

	/** The loop as laid: world rail positions in order (a closed loop). */
	static final class Line {
		final BlockPos[] loop;
		final int[] stopAt;
		final Settlement s;

		private Line(Settlement s, BlockPos[] loop, int[] stopAt) {
			this.s = s;
			this.loop = loop;
			this.stopAt = stopAt;
		}

		static Line of(MinecraftServer server, Settlement s) {
			Grade plan = Grade.of(server, s);
			StreetPlan streets = StreetPlan.of(server, s);
			if (plan == null) {
				return null;
			}
			boolean round = Settlements.round(s);
			int[][] cells = Layout.tramLoop(round);
			if (cells.length == 0) {
				return null;
			}
			return new Line(s, positions(s, plan, streets, cells), Layout.tramStopAt(round));
		}

		BlockPos stop(int i) {
			return i < this.stopAt.length ? this.loop[this.stopAt[i]] : null;
		}

		/** The stop whose platform the position is on (within 4 blocks of its rail), or -1. */
		int stopNear(BlockPos pos) {
			for (int i = 0; i < this.stopAt.length; i++) {
				BlockPos p = stop(i);
				if (p != null && Math.abs(p.getX() - pos.getX()) <= 4 && Math.abs(p.getZ() - pos.getZ()) <= 4 && Math.abs(p.getY() - pos.getY()) <= 3) {
					return i;
				}
			}
			return -1;
		}

		/** The rails from one stop to another, the shorter way round the loop. */
		List<BlockPos> route(int from, int to) {
			int n = this.loop.length, a = this.stopAt[from], b = this.stopAt[to];
			int fwd = Math.floorMod(b - a, n);
			int step = fwd <= n - fwd ? 1 : -1;
			List<BlockPos> out = new ArrayList<>();
			for (int k = a; ; k = Math.floorMod(k + step, n)) {
				out.add(this.loop[k]);
				if (k == b) {
					break;
				}
			}
			return out;
		}
	}

	/** World rail positions: on the street where the loop crosses one, else on the plan's ground. */
	private static BlockPos[] positions(Settlement s, Grade plan, StreetPlan streets, int[][] cells) {
		Rotation r = Rotation.values()[s.rotation()];
		BlockPos[] out = new BlockPos[cells.length];
		for (int i = 0; i < cells.length; i++) {
			BlockPos col = s.heart().offset(Layout.rel(r, cells[i][0], 0, cells[i][1]));
			Integer f = streets == null ? null : streets.feetAt(BlockPos.asLong(col.getX(), 0, col.getZ()));
			int top = plan.topAt(col.getX(), col.getZ());
			int y = f != null ? s.heart().getY() + f : top != Integer.MIN_VALUE ? top + 1 : s.heart().getY();
			out[i] = col.atY(y);
		}
		return out;
	}

	/**
	 * The tram's blocks as placed: every rail turned to run along the loop, round its corners and up where the next
	 * rail is a block higher.
	 */
	static void trim(Settlement s, StreetPlan streets, List<Template.Placement> out) {
		Grade plan = Settlements.server() == null ? null : Grade.of(Settlements.server(), s);
		int[][] cells = Layout.tramLoop(Settlements.round(s));
		if (plan == null || cells.length == 0) {
			out.clear();
			return;
		}
		BlockPos[] loop = positions(s, plan, streets, cells);
		int n = loop.length;
		Map<Long, RailShape> shapes = new HashMap<>();
		for (int i = 0; i < n; i++) {
			BlockPos here = loop[i], prev = loop[Math.floorMod(i - 1, n)], next = loop[(i + 1) % n];
			RailShape shape;
			if (next.getY() == here.getY() + 1) {
				shape = ascending(next.getX() - here.getX(), next.getZ() - here.getZ());
			} else if (prev.getY() == here.getY() + 1) {
				shape = ascending(prev.getX() - here.getX(), prev.getZ() - here.getZ());
			} else {
				shape = joining(prev.getX() - here.getX(), prev.getZ() - here.getZ(), next.getX() - here.getX(), next.getZ() - here.getZ());
			}
			BlockPos rel = here.subtract(s.heart());
			shapes.put(BlockPos.asLong(rel.getX(), 0, rel.getZ()), shape);
		}
		out.replaceAll(p -> {
			if (!(p.state().getBlock() instanceof BaseRailBlock) || p.offset().getX() != p.anchor().getX() || p.offset().getZ() != p.anchor().getZ()) {
				return p;
			}
			RailShape shape = shapes.get(BlockPos.asLong(p.anchor().getX(), 0, p.anchor().getZ()));
			BlockState st = p.state();
			if (shape == null) {
				return p;
			}
			if (st.hasProperty(BlockStateProperties.RAIL_SHAPE)) {
				st = st.setValue(BlockStateProperties.RAIL_SHAPE, shape);
			} else if (st.hasProperty(BlockStateProperties.RAIL_SHAPE_STRAIGHT) && BlockStateProperties.RAIL_SHAPE_STRAIGHT.getPossibleValues().contains(shape)) {
				st = st.setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT, shape);
			}
			return new Template.Placement(p.offset(), st, p.segment(), p.anchor());
		});
	}

	/** The rail shape joining the two neighbours (dx, dz each): straight, or one of the four curves. */
	private static RailShape joining(int ax, int az, int bx, int bz) {
		boolean n = az < 0 || bz < 0, so = az > 0 || bz > 0, e = ax > 0 || bx > 0, w = ax < 0 || bx < 0;
		if (n && so) {
			return RailShape.NORTH_SOUTH;
		}
		if (e && w) {
			return RailShape.EAST_WEST;
		}
		if (n && e) {
			return RailShape.NORTH_EAST;
		}
		if (n && w) {
			return RailShape.NORTH_WEST;
		}
		if (so && e) {
			return RailShape.SOUTH_EAST;
		}
		if (so && w) {
			return RailShape.SOUTH_WEST;
		}
		return ax != 0 || bx != 0 ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
	}

	private static RailShape ascending(int dx, int dz) {
		return dx > 0 ? RailShape.ASCENDING_EAST : dx < 0 ? RailShape.ASCENDING_WEST : dz > 0 ? RailShape.ASCENDING_SOUTH : RailShape.ASCENDING_NORTH;
	}

	/** Field test: which stops the line reaches, and whether every rail of it stands in the world. */
	static List<String> fieldTest(ServerLevel level, Settlement s, StringBuilder report) {
		List<String> problems = new ArrayList<>();
		if (s == null || s.level(Progression.TRAM) <= 0) {
			return problems;
		}
		Line l = Line.of(level.getServer(), s);
		if (l == null) {
			problems.add("tram: no street plan");
			return problems;
		}
		List<String> stops = new ArrayList<>(List.of(stops(s)));
		int rails = 0;
		List<String> missing = new ArrayList<>();
		for (BlockPos p : l.loop) {
			rails++;
			if (!(level.getBlockState(p).getBlock() instanceof BaseRailBlock) && missing.size() < 4) {
				missing.add(p.subtract(s.heart()).toShortString());
			}
		}
		report.append("\nTram: ").append(rails).append(" rails, stops ").append(stops).append('\n');
		if (!missing.isEmpty()) {
			problems.add("tram: rails missing at " + missing);
		}
		if (stops.size() >= 2 && net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(
				net.minecraft.resources.Identifier.withDefaultNamespace("minecart")) == null) {
			problems.add("tram: no minecart entity type");
		}
		return problems;
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
		JsonArray names = new JsonArray();
		for (int i = 0; i < l.stopAt.length; i++) {
			stops.add(i);
			names.add(stops(s)[i]);
		}
		o.add("stops", stops);
		o.add("names", names);
		ServerPlayNetworking.send(player, new TramPayload(o.toString()));
	}

	private static void go(ServerPlayer player, TramGoPayload p) {
		MinecraftServer server = player.level().getServer();
		Settlement s = Settlements.byHeart(server, p.heart());
		if (s == null || s.level(Progression.TRAM) <= 0 || p.from() == p.to() || p.from() < 0 || p.to() < 0
				|| p.from() >= stops(s).length || p.to() >= stops(s).length || player.isPassenger()) {
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

	/** A minecart waits at every stop on the line (one is brought if it went missing). */
	private static void waitingCarts(MinecraftServer server) {
		ServerLevel level = server.overworld();
		EntityType<?> type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.withDefaultNamespace("minecart"));
		if (type == null) {
			return;
		}
		for (Settlement s : Settlements.all(server)) {
			ServerPlayer owner = server.getPlayerList().getPlayer(s.owner());
			if (s.level(Progression.TRAM) <= 0 || owner == null || owner.level() != level || owner.distanceToSqr(Vec3.atCenterOf(s.heart())) > 160 * 160) {
				continue;
			}
			Line l = Line.of(server, s);
			if (l == null) {
				continue;
			}
			for (int i = 0; i < l.stopAt.length; i++) {
				BlockPos stop = l.stop(i);
				if (stop == null || !level.isLoaded(stop) || !(level.getBlockState(stop).getBlock() instanceof BaseRailBlock)) {
					continue;
				}
				boolean there = !level.getEntities((Entity) null, new net.minecraft.world.phys.AABB(stop).inflate(10, 4, 10),
						e -> e.getType() == type && !TRIPS.containsKey(e.getUUID())).isEmpty();
				if (!there) {
					Entity cart = type.create(level, EntitySpawnReason.EVENT);
					if (cart != null) {
						cart.setPos(stop.getX() + 0.5, stop.getY() + 0.0625, stop.getZ() + 0.5);
						level.addFreshEntity(cart);
					}
				}
			}
		}
	}

	/** Drives every running ride a little further along its rails; at the end the lord steps out and the cart goes. */
	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % 400 == 123) {
			waitingCarts(server);
		}
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
