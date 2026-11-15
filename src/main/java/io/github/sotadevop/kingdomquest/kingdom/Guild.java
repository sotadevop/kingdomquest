package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.npc.KingdomNpc;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * The guild of three allies, unlocked level by level (swordfighter, archer, mage). Blowing a goat horn calls them:
 * they appear next to their lord (or come over from wherever they are) and follow and defend him.
 */
final class Guild {
	static final String[] ROLES = {"sword", "archer", "mage"};
	private static final String[] NAMES = {"Swordfighter", "Archer", "Mage"};

	private Guild() {
	}

	static void register() {
		UseItemCallback.EVENT.register((player, world, hand) -> {
			if (player instanceof ServerPlayer sp && player.getItemInHand(hand).is(Items.GOAT_HORN)) {
				call(sp);
			}
			return InteractionResult.PASS; // the horn still sounds
		});
	}

	static boolean isAlly(KingdomNpc npc) {
		String r = npc.role();
		return r.equals("sword") || r.equals("archer") || r.equals("mage");
	}

	static void talk(ServerPlayer player, KingdomNpc npc) {
		String who = NAMES[List.of(ROLES).indexOf(npc.role())];
		People.say(player, who, player.getUUID().equals(npc.lord()) ? "At your command, my lord." : "I only serve my own lord.");
	}

	private static int unlocked(ServerPlayer player) {
		int best = 0;
		for (Settlement s : Settlements.all(player.level().getServer())) {
			if (s.owner().equals(player.getUUID())) {
				best = Math.max(best, s.level(Progression.GUILD));
			}
		}
		return best;
	}

	/** Brings every unlocked ally to the player: existing ones come over, missing ones (fallen) are replaced. */
	static void call(ServerPlayer player) {
		int n = unlocked(player);
		if (n == 0) {
			return;
		}
		ServerLevel here = (ServerLevel) player.level();
		List<KingdomNpc> mine = new ArrayList<>();
		for (ServerLevel level : player.level().getServer().getAllLevels()) {
			for (KingdomNpc npc : level.getEntities(KingdomNpc.TYPE, e -> e.isAlive() && player.getUUID().equals(e.lord()) && isAlly(e))) {
				if (level == here) {
					mine.add(npc);
				} else {
					npc.discard(); // left behind in another dimension: a new one comes instead
				}
			}
		}
		for (int i = 0; i < Math.min(n, ROLES.length); i++) {
			String role = ROLES[i];
			KingdomNpc ally = mine.stream().filter(m -> m.role().equals(role)).findFirst().orElse(null);
			double ox = player.getX() + (i - 1) * 1.5;
			double oz = player.getZ() + 1.5;
			if (ally == null) {
				ally = KingdomNpc.TYPE.create(here, EntitySpawnReason.MOB_SUMMONED);
				if (ally == null) {
					continue;
				}
				ally.setup(role, 3 + i * 2, player.getUUID());
				ally.setCustomName(Component.literal(NAMES[i]));
				ally.snapTo(ox, player.getY(), oz, player.getYRot(), 0);
				here.addFreshEntity(ally);
			} else {
				ally.teleportTo(ox, player.getY(), oz);
			}
			here.sendParticles(ParticleTypes.CLOUD, ox, player.getY() + 1, oz, 12, 0.3, 0.6, 0.3, 0.02);
		}
		for (KingdomNpc extra : mine) {
			if (List.of(ROLES).indexOf(extra.role()) >= n) {
				extra.discard();
			}
		}
		People.say(player, NAMES[0], n == 1 ? "You called, my lord!" : "The guild answers your call!");
	}
}
