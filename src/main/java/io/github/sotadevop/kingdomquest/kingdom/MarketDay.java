package io.github.sotadevop.kingdomquest.kingdom;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Market day (19.11): every fourth game day, from morning to evening, travelling merchants set up in the covered
 * market hall and sell rare things for emeralds: goods of other lands, rare seeds, banner patterns, music discs.
 * Townsfolk come to the square to look. At dusk they pack up and leave. The merchants are vanilla wandering traders
 * standing at their stalls (they trade like any merchant), tagged so the mod can send them off again.
 */
final class MarketDay {
	static final String MERCHANT = "market";
	static final String VISITOR = "marketgoer";
	private static final int EVERY = 4;
	private static final String DONE = "market_day";

	private MarketDay() {
	}

	static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 100 == 37) {
				for (Settlement s : Settlements.all(server)) {
					tick(server, s);
				}
			}
		});
	}

	static boolean isMarketDay(long gameTime) {
		return (gameTime / 24000L) % EVERY == EVERY - 1;
	}

	private static void tick(MinecraftServer server, Settlement s) {
		ServerLevel level = server.overworld();
		if (s.level(Progression.MARKETHALL) <= 0 || !level.isLoaded(s.heart())) {
			return;
		}
		long time = level.getGameTime();
		long day = time / 24000L;
		List<Entity> here = tagged(level, s);
		boolean open = isMarketDay(time) && level.isBrightOutside();
		if (!open) {
			for (Entity e : here) {
				level.sendParticles(ParticleTypes.POOF, e.getX(), e.getY() + 1, e.getZ(), 6, 0.3, 0.5, 0.3, 0.02);
				e.discard();
			}
			return;
		}
		if (!here.isEmpty() || s.level(DONE) == (int) day + 1) {
			return;
		}
		ServerPlayer owner = server.getPlayerList().getPlayer(s.owner());
		if (owner == null || owner.level() != level || owner.distanceToSqr(Vec3.atCenterOf(s.heart())) > 200 * 200) {
			return; // they come when the lord is about, so he doesn't miss them
		}
		int[] b = Landmarks.bounds(s, Progression.MARKETHALL);
		if (b == null) {
			return;
		}
		Settlements.putState(server, s.withLevel(DONE, (int) day + 1));
		RandomSource r = RandomSource.create(day * 31 + s.heart().asLong());
		int[][] stalls = {{b[0] + 3, b[1] + 3}, {b[2] - 3, b[1] + 3}, {b[0] + 3, b[3] - 3}, {b[2] - 3, b[3] - 3}};
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("wandering_trader"));
		int n = 0;
		for (int[] st : stalls) {
			BlockPos at = floor(level, st[0], st[1], s.heart().getY());
			if (at == null || type == null) {
				continue;
			}
			Entity e = type.create(level, EntitySpawnReason.EVENT);
			if (!(e instanceof Mob m) || !(e instanceof Merchant merchant)) {
				continue;
			}
			m.snapTo(Vec3.atBottomCenterOf(at), r.nextFloat() * 360, 0);
			m.setNoAi(true);
			m.setPersistenceRequired();
			m.setAttached(People.ROLE, MERCHANT + "|" + s.id());
			m.setCustomName(Component.translatable("kingdomquest.market.merchant." + (n % 4)));
			stock(merchant.getOffers(), n, r);
			level.addFreshEntity(m);
			n++;
		}
		// the square fills: townsfolk come to look
		int[] sq = io.github.sotadevop.kingdomquest.building.Layout.square();
		for (int i = 0; i < 5; i++) {
			int bx = sq[0] + 2 + r.nextInt(Math.max(1, sq[2] - sq[0] - 4));
			int bz = sq[1] + 2 + r.nextInt(Math.max(1, sq[3] - sq[1] - 4));
			BlockPos col = s.heart().offset(io.github.sotadevop.kingdomquest.building.Layout.rel(net.minecraft.world.level.block.Rotation.values()[s.rotation()], bx, 0, bz));
			BlockPos at = floor(level, col.getX(), col.getZ(), s.heart().getY());
			if (at != null) {
				People.visitor(level, s, VISITOR, at);
			}
		}
		if (n > 0) {
			owner.sendSystemMessage(Component.translatable("kingdomquest.market.open", s.name()));
		}
	}

	private static List<Entity> tagged(ServerLevel level, Settlement s) {
		String a = MERCHANT + "|" + s.id(), v = VISITOR + "|" + s.id();
		return new ArrayList<>(level.getEntitiesOfClass(Entity.class, new AABB(s.heart()).inflate(200, 64, 200), e -> {
			String role = e.getAttached(People.ROLE);
			return role != null && (role.equals(a) || role.equals(v));
		}));
	}

	/** The first spot to stand on, from below the heart up: the floor of the hall, not its roof. */
	private static BlockPos floor(ServerLevel level, int x, int z, int y0) {
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int y = y0 - 12; y < y0 + 24; y++) {
			p.set(x, y, z);
			if (!level.getBlockState(p.below()).isAir() && level.getBlockState(p.below()).isCollisionShapeFullBlock(level, p.below())
					&& level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir()) {
				return p.immutable();
			}
		}
		return null;
	}

	private static MerchantOffer offer(Item item, int count, int emeralds) {
		return new MerchantOffer(new ItemCost(Items.EMERALD, emeralds), new ItemStack(item, count), 4, 2, 0.05f);
	}

	/** Four kinds of merchant: goods of far lands, a seed seller, a banner and pattern dealer, a minstrel with discs. */
	private static void stock(MerchantOffers offers, int kind, RandomSource r) {
		offers.clear();
		List<MerchantOffer> all = new ArrayList<>();
		switch (kind % 4) {
			case 0 -> {
				all.add(offer(Items.CACTUS, 8, 2));
				all.add(offer(Items.BAMBOO, 16, 2));
				all.add(offer(Items.PACKED_ICE, 16, 3));
				all.add(offer(Items.BLUE_ICE, 4, 6));
				all.add(offer(Items.RED_SAND, 32, 2));
				all.add(offer(Items.MOSS_BLOCK, 16, 2));
				all.add(offer(Items.MUD, 32, 2));
				all.add(offer(Items.CHERRY_SAPLING, 2, 4));
				all.add(offer(Items.MANGROVE_PROPAGULE, 2, 4));
				all.add(offer(Items.NAUTILUS_SHELL, 1, 12));
				all.add(offer(Items.POINTED_DRIPSTONE, 8, 3));
				all.add(offer(Items.GLOW_INK_SAC, 4, 4));
			}
			case 1 -> {
				all.add(offer(Items.TORCHFLOWER_SEEDS, 1, 10));
				all.add(offer(Items.PITCHER_POD, 1, 10));
				all.add(offer(Items.COCOA_BEANS, 8, 3));
				all.add(offer(Items.MELON_SEEDS, 8, 1));
				all.add(offer(Items.PUMPKIN_SEEDS, 8, 1));
				all.add(offer(Items.SWEET_BERRIES, 16, 2));
				all.add(offer(Items.GLOW_BERRIES, 8, 3));
				all.add(offer(Items.SPORE_BLOSSOM, 1, 8));
				all.add(offer(Items.AZALEA, 2, 3));
				all.add(offer(Items.LILY_OF_THE_VALLEY, 4, 2));
				all.add(offer(Items.WITHER_ROSE, 1, 16));
			}
			case 2 -> {
				all.add(offer(Items.FLOWER_BANNER_PATTERN, 1, 6));
				all.add(offer(Items.CREEPER_BANNER_PATTERN, 1, 8));
				all.add(offer(Items.SKULL_BANNER_PATTERN, 1, 8));
				all.add(offer(Items.MOJANG_BANNER_PATTERN, 1, 16));
				all.add(offer(Items.GLOBE_BANNER_PATTERN, 1, 8));
				all.add(offer(Items.PIGLIN_BANNER_PATTERN, 1, 8));
				all.add(offer(Items.FLOW_BANNER_PATTERN, 1, 10));
				all.add(offer(Items.GUSTER_BANNER_PATTERN, 1, 10));
				all.add(offer(Items.NAME_TAG, 1, 8));
				all.add(offer(Items.PAINTING, 2, 2));
			}
			default -> {
				all.add(offer(Items.MUSIC_DISC_CAT, 1, 10));
				all.add(offer(Items.MUSIC_DISC_BLOCKS, 1, 10));
				all.add(offer(Items.MUSIC_DISC_CHIRP, 1, 10));
				all.add(offer(Items.MUSIC_DISC_FAR, 1, 10));
				all.add(offer(Items.MUSIC_DISC_MALL, 1, 10));
				all.add(offer(Items.MUSIC_DISC_MELLOHI, 1, 10));
				all.add(offer(Items.MUSIC_DISC_STAL, 1, 10));
				all.add(offer(Items.MUSIC_DISC_STRAD, 1, 10));
				all.add(offer(Items.MUSIC_DISC_WARD, 1, 10));
				all.add(offer(Items.GOAT_HORN, 1, 12));
				all.add(offer(Items.NOTE_BLOCK, 4, 2));
			}
		}
		// each market brings a few of them, not always the same
		while (all.size() > 6) {
			all.remove(r.nextInt(all.size()));
		}
		offers.addAll(all);
	}
}
