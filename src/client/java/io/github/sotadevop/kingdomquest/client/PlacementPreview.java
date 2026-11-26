package io.github.sotadevop.kingdomquest.client;

import io.github.sotadevop.kingdomquest.building.Layout;
import io.github.sotadevop.kingdomquest.building.Template;
import io.github.sotadevop.kingdomquest.charter.CharterData;
import io.github.sotadevop.kingdomquest.charter.RoyalCharter;
import io.github.sotadevop.kingdomquest.kingdom.GateSite;
import io.github.sotadevop.kingdomquest.kingdom.SiteWork;
import io.github.sotadevop.kingdomquest.kingdom.Style;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * While a sealed charter is held and aimed at the top of a block, draws the future kingdom on the land (user,
 * 2026-11-26: a more professional preview), with clean lines of coloured dust that follow the ground:
 * <ul>
 * <li>the first camp's plot (white), where the land is cleared at once;</li>
 * <li>the castle's wall line in the kingdom's colour and the outer edge of its moat (pale), so one sees how big it gets;</li>
 * <li>the gate (gold arrow pointing out) and the main street from the heart to it;</li>
 * <li>the heart (a rising column).</li>
 * </ul>
 * The action bar sums it up: name, region style, gate side and what the ground is like. Red smoke where it can't go.
 */
public final class PlacementPreview {
	private static final int WHITE = 0xF2F2F2;
	private static final int GOLD = 0xFFC83C;
	private static final int PALE = 0x9FC6E8;
	private static final int RED = 0xE03C3C;
	/** At most this many dots per drawing (every 3 ticks): enough for unbroken lines, light on the client. */
	private static final int BUDGET = 700;
	private static int ticks;
	private static int drawn;

	private PlacementPreview() {
	}

	public static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null || mc.isPaused() || ++ticks % 3 != 0 || !ClientConfig.placementPreview()) {
			return;
		}
		ItemStack held = mc.player.getMainHandItem().is(RoyalCharter.ITEM) ? mc.player.getMainHandItem() : mc.player.getOffhandItem();
		CharterData data = held.is(RoyalCharter.ITEM) ? held.get(RoyalCharter.DATA) : null;
		if (data == null || mc.player.isShiftKeyDown()) {
			return;
		}
		if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK || hit.getDirection() != Direction.UP) {
			return;
		}
		BlockPos heart = hit.getBlockPos().above();
		drawn = 0;
		if (!SiteWork.isGround(mc.level.getBlockState(hit.getBlockPos()))) {
			// not natural ground (a tree, a building, water): a red ring at the spot and why
			ring(mc, heart, 1.5, new DustParticleOptions(RED, 1.4f));
			if (ticks % 12 == 0) {
				mc.player.sendOverlayMessage(Component.translatable("kingdomquest.preview.bad"));
			}
			return;
		}
		Rotation facing = switch (mc.player.getDirection()) {
			case EAST -> Rotation.CLOCKWISE_90;
			case SOUTH -> Rotation.CLOCKWISE_180;
			case WEST -> Rotation.COUNTERCLOCKWISE_90;
			default -> Rotation.NONE;
		};
		// the side set in the charter's menu, or on Auto the gentlest way in, as the server will pick it
		Rotation rotation = data.gate() < 0 ? GateSite.best(mc.level, heart, facing) : Rotation.values()[Math.min(data.gate(), 3)];
		boolean round = data.shape() == 1;
		int color = DyeColor.byId(data.color()).getTextureDiffuseColor() & 0xFFFFFF;
		DustParticleOptions wallDust = new DustParticleOptions(color, 1.6f);
		DustParticleOptions moatDust = new DustParticleOptions(PALE, 1.0f);
		DustParticleOptions campDust = new DustParticleOptions(WHITE, 1.1f);
		DustParticleOptions gateDust = new DustParticleOptions(GOLD, 1.8f);
		int moat = Layout.moat();
		int phase = ticks / 3 % 2;
		// the camp's plot
		Template t = Template.get("temperate/tent_3");
		if (t != null) {
			for (int x = 0; x < t.sizeX; x++) {
				for (int z = 0; z < t.sizeZ; z++) {
					if (x == 0 || z == 0 || x == t.sizeX - 1 || z == t.sizeZ - 1) {
						BlockPos rel = Template.rotate(new BlockPos(x - t.heart.getX(), 0, z - t.heart.getZ()), rotation);
						dot(mc, heart.getX() + rel.getX(), heart.getZ() + rel.getZ(), heart.getY(), campDust);
					}
				}
			}
		}
		// the wall line and the moat's outer edge, every other block (alternating, so the line looks whole)
		int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
		boolean wet = false;
		int[] w = Layout.wall(round);
		if (round) {
			int[] e = Layout.ellipse();
			int steps = (int) (Math.PI * (e[2] + e[3]));
			for (int i = phase; i < steps; i += 2) {
				double a = 2 * Math.PI * i / steps;
				int[] h = traced(mc, heart, rotation, e[0] + e[2] * Math.cos(a), e[1] + e[3] * Math.sin(a), wallDust);
				dotBase(mc, heart, rotation, e[0] + (e[2] + moat) * Math.cos(a), e[1] + (e[3] + moat) * Math.sin(a), moatDust);
				if (h != null) {
					lo = Math.min(lo, h[0]);
					hi = Math.max(hi, h[0]);
					wet |= h[1] == 1;
				}
			}
		} else {
			for (int[] side : new int[][] {{w[0], w[2], w[1], w[2]}, {w[1], w[2], w[1], w[3]}, {w[1], w[3], w[0], w[3]}, {w[0], w[3], w[0], w[2]}}) {
				int len = Math.max(Math.abs(side[2] - side[0]), Math.abs(side[3] - side[1]));
				for (int i = phase; i <= len; i += 2) {
					double f = len == 0 ? 0 : (double) i / len;
					double bx = side[0] + (side[2] - side[0]) * f, bz = side[1] + (side[3] - side[1]) * f;
					int[] h = traced(mc, heart, rotation, bx, bz, wallDust);
					// outward from the ring: away from its middle
					double ox = bx < (w[0] + w[1]) / 2.0 ? -moat : bx > (w[0] + w[1]) / 2.0 ? moat : 0;
					double oz = bz < (w[2] + w[3]) / 2.0 ? -moat : bz > (w[2] + w[3]) / 2.0 ? moat : 0;
					dotBase(mc, heart, rotation, bx + (side[0] == side[2] ? ox : 0), bz + (side[1] == side[3] ? oz : 0), moatDust);
					if (h != null) {
						lo = Math.min(lo, h[0]);
						hi = Math.max(hi, h[0]);
						wet |= h[1] == 1;
					}
				}
			}
		}
		// the gate: an arrow pointing out of the wall, and the main street to it from the heart
		int gx = Layout.gateX(), gz = Layout.gateZ(round);
		for (int i = 0; i <= 6; i++) {
			dotBase(mc, heart, rotation, gx, gz + i, gateDust);
			if (i >= 3) {
				dotBase(mc, heart, rotation, gx - (6 - i), gz + i - 1, gateDust);
				dotBase(mc, heart, rotation, gx + (6 - i), gz + i - 1, gateDust);
			}
		}
		for (int z = Layout.HZ + 3; z < gz; z += 3) {
			dotBase(mc, heart, rotation, gx, z, campDust);
		}
		for (int i = 0; i < 4; i++) {
			mc.level.addParticle(ParticleTypes.END_ROD, heart.getX() + 0.5, heart.getY() + 0.3 + i * 0.7, heart.getZ() + 0.5, 0, 0.02, 0);
		}
		if (ticks % 12 == 0) {
			String style = Style.forBiome(mc.level.getBiome(heart).getRegisteredName());
			Component ground = Component.translatable(wet ? "kingdomquest.preview.ground_water"
					: hi - lo > 10 ? "kingdomquest.preview.ground_steep" : "kingdomquest.preview.ground_ok");
			Direction out = rotation.rotate(Direction.SOUTH);
			mc.player.sendOverlayMessage(Component.translatable("kingdomquest.preview.info", data.name(),
					Component.translatable("kingdomquest.style." + style), Component.translatable("kingdomquest.dir." + out.getSerializedName()), ground));
		}
	}

	/** A dot of the wall line at a base-frame point; returns {ground height, 1 if water} or null if the land isn't there. */
	private static int[] traced(Minecraft mc, BlockPos heart, Rotation r, double bx, double bz, ParticleOptions p) {
		BlockPos rel = Layout.rel(r, (int) Math.round(bx), 0, (int) Math.round(bz));
		int x = heart.getX() + rel.getX(), z = heart.getZ() + rel.getZ();
		int y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		if (y <= mc.level.getMinY() + 1) {
			return null;
		}
		dot(mc, x, z, heart.getY(), p);
		boolean water = !mc.level.getFluidState(new BlockPos(x, y - 1, z)).isEmpty();
		return new int[] {y, water ? 1 : 0};
	}

	private static void dotBase(Minecraft mc, BlockPos heart, Rotation r, double bx, double bz, ParticleOptions p) {
		BlockPos rel = Layout.rel(r, (int) Math.round(bx), 0, (int) Math.round(bz));
		dot(mc, heart.getX() + rel.getX(), heart.getZ() + rel.getZ(), heart.getY(), p);
	}

	/** A dot on the ground of a column (seen from afar: the wall line is longer than particles are usually drawn). */
	private static void dot(Minecraft mc, int x, int z, int floor, ParticleOptions p) {
		if (drawn >= BUDGET) {
			return;
		}
		int y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		if (y <= mc.level.getMinY() + 1) {
			return; // land not loaded here
		}
		drawn++;
		mc.level.addAlwaysVisibleParticle(p, x + 0.5, y + 0.15, z + 0.5, 0, 0, 0);
	}

	private static void ring(Minecraft mc, BlockPos at, double r, ParticleOptions p) {
		for (int i = 0; i < 12; i++) {
			double a = Math.PI * 2 * i / 12;
			mc.level.addParticle(p, at.getX() + 0.5 + Math.cos(a) * r, at.getY() + 0.2, at.getZ() + 0.5 + Math.sin(a) * r, 0, 0, 0);
		}
	}
}
