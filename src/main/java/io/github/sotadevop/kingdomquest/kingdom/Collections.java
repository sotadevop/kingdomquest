package io.github.sotadevop.kingdomquest.kingdom;

import io.github.sotadevop.kingdomquest.building.Template;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Things the kingdom keeps: pets from the kennel (dog, cat, parrot; tamed to the lord, they come back if they die)
 * and trophies in the trophy hall (one pedestal per milestone advancement).
 */
final class Collections {
	private record Pet(String type, String name) {
	}

	private static final List<Pet> PETS = List.of(new Pet("wolf", "Rex"), new Pet("cat", "Luna"), new Pet("parrot", "Kiwi"));

	private record Trophy(int x, int y, int z, String advancement, Block block) {
	}

	private static final List<Trophy> TROPHIES = List.of(
			new Trophy(-33, 2, 9, "end/kill_dragon", Blocks.DRAGON_HEAD),
			new Trophy(-31, 2, 9, "end/dragon_egg", Blocks.DRAGON_EGG),
			new Trophy(-29, 2, 9, "nether/summon_wither", Blocks.WITHER_SKELETON_SKULL),
			new Trophy(-27, 2, 9, "nether/create_beacon", Blocks.BEACON),
			new Trophy(-33, 2, 15, "nether/netherite_armor", Blocks.NETHERITE_BLOCK),
			new Trophy(-31, 2, 15, "story/mine_diamond", Blocks.DIAMOND_BLOCK),
			new Trophy(-29, 2, 15, "husbandry/obtain_sniffer_egg", Blocks.SNIFFER_EGG),
			new Trophy(-27, 2, 15, "adventure/hero_of_the_village", Blocks.BELL),
			new Trophy(-33, 2, 12, "adventure/avoid_vibration", Blocks.SCULK_SENSOR),
			new Trophy(-30, 2, 12, "adventure/kill_all_mobs", Blocks.ZOMBIE_HEAD));

	private Collections() {
	}

	private static BlockPos at(Settlement s, int bx, int by, int bz) {
		return s.heart().offset(Template.rotate(new BlockPos(bx - 7, by, bz - 10), Rotation.values()[s.rotation()]));
	}

	static String petKind(int kennelLevel) {
		return kennelLevel >= 1 && kennelLevel <= PETS.size() ? PETS.get(kennelLevel - 1).type() : null;
	}

	static String defaultName(String kind) {
		return PETS.stream().filter(p -> p.type().equals(kind)).map(Pet::name).findFirst().orElse("Buddy");
	}

	/** Gives a living pet its new name. */
	static void rename(MinecraftServer server, Settlement s, String kind, String name) {
		String tag = "pet|" + s.id() + "|" + kind;
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace(kind));
		for (ServerLevel level : server.getAllLevels()) {
			level.getEntities(type, e -> tag.equals(e.getAttached(People.ROLE))).forEach(e -> e.setCustomName(Component.literal(name)));
		}
	}

	/** Every minute: unlocked pets that are missing come home to the kennel, tamed to the lord. */
	static void pets(MinecraftServer server, Settlement s) {
		int unlocked = Math.min(PETS.size(), s.level(Progression.KENNEL));
		ServerPlayer owner = server.getPlayerList().getPlayer(s.owner());
		if (unlocked == 0 || owner == null) {
			return;
		}
		ServerLevel home = server.overworld();
		for (int i = 0; i < unlocked; i++) {
			Pet pet = PETS.get(i);
			String tag = "pet|" + s.id() + "|" + pet.type();
			EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace(pet.type()));
			boolean alive = false;
			for (ServerLevel level : server.getAllLevels()) {
				if (!level.getEntities(type, e -> e.isAlive() && tag.equals(e.getAttached(People.ROLE))).isEmpty()) {
					alive = true;
				}
			}
			if (alive || !home.isLoaded(s.heart())) {
				continue;
			}
			if (type.create(home, EntitySpawnReason.MOB_SUMMONED) instanceof TamableAnimal animal) {
				BlockPos spot = at(s, 50 + i * 2, 0, 17);
				spot = new BlockPos(spot.getX(), home.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
						spot.getX(), spot.getZ()), spot.getZ());
				animal.snapTo(Vec3.atBottomCenterOf(spot), 0, 0);
				animal.tame(owner);
				animal.setCustomName(Component.literal(s.names().getOrDefault("pet_" + pet.type(), pet.name())));
				animal.setOrderedToSit(true); // waits at its own spot in the kennel until called
				animal.setAttached(People.ROLE, tag);
				animal.setPersistenceRequired();
				home.addFreshEntity(animal);
				home.sendParticles(ParticleTypes.HEART, spot.getX() + 0.5, spot.getY() + 1, spot.getZ() + 0.5, 5, 0.3, 0.3, 0.3, 0);
			}
		}
	}

	/** The dragon egg comes home to the lair's nest once the lord has collected it. */
	static void lair(MinecraftServer server, Settlement s) {
		ServerPlayer owner = server.getPlayerList().getPlayer(s.owner());
		ServerLevel level = server.overworld();
		if (s.level(Progression.LAIR) < 2 || owner == null || !level.isLoaded(s.heart())) {
			return;
		}
		var holder = server.getAdvancements().get(Identifier.withDefaultNamespace("end/dragon_egg"));
		BlockPos nest = at(s, 9, 1, -32);
		if (holder != null && owner.getAdvancements().getOrStartProgress(holder).isDone() && level.getBlockState(nest).isAir()) {
			level.setBlock(nest, Blocks.DRAGON_EGG.defaultBlockState(), Block.UPDATE_ALL);
			level.sendParticles(ParticleTypes.PORTAL, nest.getX() + 0.5, nest.getY() + 0.5, nest.getZ() + 0.5, 60, 0.6, 0.6, 0.6, 0.4);
		}
	}

	/** Every minute: trophies of reached milestones appear on their pedestals. */
	static void trophies(MinecraftServer server, Settlement s) {
		ServerPlayer owner = server.getPlayerList().getPlayer(s.owner());
		ServerLevel level = server.overworld();
		if (s.level(Progression.TROPHY) == 0 || owner == null || !level.isLoaded(s.heart()) || Progression.TROPHY.equals(s.jobTrack())) {
			return;
		}
		for (Trophy t : TROPHIES) {
			var holder = server.getAdvancements().get(Identifier.withDefaultNamespace(t.advancement()));
			if (holder == null || !owner.getAdvancements().getOrStartProgress(holder).isDone()) {
				continue;
			}
			BlockPos pos = at(s, t.x(), t.y() + s.level("lift_" + Progression.TROPHY), t.z());
			BlockState now = level.getBlockState(pos);
			if (now.isAir()) {
				level.setBlock(pos, t.block().defaultBlockState(), Block.UPDATE_ALL);
				level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, pos.getX() + 0.5, pos.getY() + 0.8, pos.getZ() + 0.5, 20, 0.3, 0.4, 0.3, 0.1);
			}
		}
	}
}
