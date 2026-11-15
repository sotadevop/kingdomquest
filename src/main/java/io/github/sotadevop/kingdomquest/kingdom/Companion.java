package io.github.sotadevop.kingdomquest.kingdom;

import com.mojang.serialization.Codec;
import io.github.sotadevop.kingdomquest.KingdomQuest;
import io.github.sotadevop.kingdomquest.npc.KingdomNpc;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * The companion: a traveller who joins the player after the first night's sleep (on day 3 at the latest), follows
 * him, helps in fights and goes on little errands, bringing back supplies every ten minutes. If he falls, he comes
 * back the next morning.
 */
final class Companion {
	/** Game time the player was first seen; -1 once the companion has joined. */
	static final AttachmentType<Long> STATE = AttachmentRegistry.<Long>builder()
			.persistent(Codec.LONG).copyOnDeath()
			.buildAndRegister(Identifier.fromNamespaceAndPath(KingdomQuest.MOD_ID, "companion"));
	private static final List<ItemStack> ERRANDS = List.of(new ItemStack(Items.BREAD, 4), new ItemStack(Items.TORCH, 12),
			new ItemStack(Items.ARROW, 12), new ItemStack(Items.COAL, 6), new ItemStack(Items.IRON_INGOT, 2), new ItemStack(Items.COOKED_BEEF, 3),
			new ItemStack(Items.OAK_LOG, 16), new ItemStack(Items.STRING, 4));

	private Companion() {
	}

	static void register() {
		EntitySleepEvents.STOP_SLEEPING.register((entity, pos) -> {
			if (entity instanceof ServerPlayer p) {
				arrive(p, false); // after a night's sleep: joins (first time) or comes back (if he fell)
			}
		});
	}

	private static KingdomNpc find(ServerPlayer p) {
		for (ServerLevel level : p.level().getServer().getAllLevels()) {
			for (KingdomNpc npc : level.getEntities(KingdomNpc.TYPE, e -> e.isAlive() && "companion".equals(e.role()) && p.getUUID().equals(e.lord()))) {
				return npc;
			}
		}
		return null;
	}

	static void arrive(ServerPlayer p, boolean failsafe) {
		Long state = p.getAttached(STATE);
		boolean first = state == null || state >= 0;
		if (find(p) != null) {
			return;
		}
		ServerLevel level = (ServerLevel) p.level();
		KingdomNpc c = KingdomNpc.TYPE.create(level, EntitySpawnReason.MOB_SUMMONED);
		if (c == null) {
			return;
		}
		c.setup("companion", 6, p.getUUID());
		c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
		c.setCustomName(Component.literal("Aldric"));
		c.snapTo(p.getX() + 2, p.getY(), p.getZ() + 1, p.getYRot() + 180, 0);
		level.addFreshEntity(c);
		level.sendParticles(ParticleTypes.CLOUD, c.getX(), c.getY() + 1, c.getZ(), 10, 0.3, 0.5, 0.3, 0.02);
		p.setAttached(STATE, -1L);
		People.say(p, "Aldric", first
				? "Good morning! I'm Aldric, a traveller. I saw your camp and thought you could use a hand. I'll come along, if you'll have me."
				: "I'm back on my feet. Let's go on.");
	}

	/** Every minute: the day-3 failsafe and the errands. */
	static void tick(MinecraftServer server) {
		long time = server.overworld().getGameTime();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Long state = p.getAttached(STATE);
			if (state == null) {
				p.setAttached(STATE, time);
			} else if (state >= 0 && time - state >= 3 * 24000L) {
				arrive(p, true);
			} else if (state < 0 && time % 12000 < 1200) {
				KingdomNpc c = find(p);
				if (c != null && c.distanceToSqr(p) < 32 * 32) {
					ItemStack gift = ERRANDS.get(p.getRandom().nextInt(ERRANDS.size())).copy();
					People.say(p, "Aldric", "I went out and found this for you: " + gift.getCount() + " " + gift.getHoverName().getString() + ".");
					if (!p.getInventory().add(gift)) {
						p.level().addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(p.level(), p.getX(), p.getY(), p.getZ(), gift));
					}
				}
			}
		}
	}
}
