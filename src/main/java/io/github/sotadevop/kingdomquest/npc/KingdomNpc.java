package io.github.sotadevop.kingdomquest.npc;

import io.github.sotadevop.kingdomquest.KingdomQuest;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.EnumSet;
import java.util.UUID;

/**
 * KingdomQuest's own human NPC: the vanilla player model with one of the default skins. The role decides what it
 * does: "architect" (stands and talks), "builder" (carries a tool and swings it while building), and the three
 * allies of the guild who follow their lord and fight: "sword", "archer", "mage".
 */
public class KingdomNpc extends PathfinderMob implements RangedAttackMob {
	public static final ResourceKey<EntityType<?>> KEY = ResourceKey.create(Registries.ENTITY_TYPE,
			Identifier.fromNamespaceAndPath(KingdomQuest.MOD_ID, "npc"));
	public static final EntityType<KingdomNpc> TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, KEY,
			EntityType.Builder.of(KingdomNpc::new, MobCategory.MISC).sized(0.6f, 1.8f).eyeHeight(1.62f).clientTrackingRange(10).build(KEY));
	public static final EntityDataAccessor<Integer> SKIN = SynchedEntityData.defineId(KingdomNpc.class, EntityDataSerializers.INT);
	public static final EntityDataAccessor<String> ROLE = SynchedEntityData.defineId(KingdomNpc.class, EntityDataSerializers.STRING);

	private UUID lord;
	private int spellCooldown;

	public KingdomNpc(EntityType<? extends KingdomNpc> type, Level level) {
		super(type, level);
		this.setPersistenceRequired();
	}

	public static void register() {
		FabricDefaultAttributeRegistry.register(TYPE, attributes());
	}

	private static AttributeSupplier.Builder attributes() {
		return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 40).add(Attributes.MOVEMENT_SPEED, 0.32)
				.add(Attributes.ATTACK_DAMAGE, 6).add(Attributes.FOLLOW_RANGE, 32).add(Attributes.ARMOR, 6);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(SKIN, 0);
		builder.define(ROLE, "");
	}

	public int skin() {
		return this.entityData.get(SKIN);
	}

	public String role() {
		return this.entityData.get(ROLE);
	}

	public UUID lord() {
		return this.lord;
	}

	/** Sets the role and its gear; allies follow and defend {@code lord}. */
	public void setup(String role, int skin, UUID lord) {
		this.entityData.set(ROLE, role);
		this.entityData.set(SKIN, skin);
		this.lord = lord;
		switch (role) {
			case "sword" -> {
				this.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
				this.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
				this.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
			}
			case "archer" -> {
				this.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
				this.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
			}
			case "mage" -> this.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.BLAZE_ROD));
			case "builder" -> this.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SHOVEL));
			default -> {
			}
		}
		this.setDropChance(net.minecraft.world.entity.EquipmentSlot.MAINHAND, 0);
		this.setDropChance(net.minecraft.world.entity.EquipmentSlot.CHEST, 0);
		this.setDropChance(net.minecraft.world.entity.EquipmentSlot.HEAD, 0);
		this.addRoleGoals();
	}

	private boolean ally() {
		String r = role();
		return r.equals("sword") || r.equals("archer") || r.equals("mage");
	}

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(0, new FloatGoal(this));
		this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8f));
		this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
	}

	private boolean roleGoals;

	private void addRoleGoals() {
		if (this.roleGoals || !ally()) {
			return;
		}
		this.roleGoals = true;
		if (role().equals("archer")) {
			this.goalSelector.addGoal(2, new ShootGoal(this));
		} else if (role().equals("sword")) {
			this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.2, true));
		}
		this.goalSelector.addGoal(3, new FollowLordGoal(this));
		this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
		this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, net.minecraft.world.entity.Mob.class, 10, true, false,
				(e, level) -> e instanceof Enemy));
	}

	@Override
	public void tick() {
		super.tick();
		if (!this.level().isClientSide() && ally()) {
			this.addRoleGoals();
			if (role().equals("mage") && --this.spellCooldown <= 0) {
				this.spellCooldown = 60;
				castSpell();
			}
		}
	}

	/** The mage heals his lord and strikes the current target with evoker fangs. */
	private void castSpell() {
		ServerLevel level = (ServerLevel) this.level();
		Player p = this.lord == null ? null : level.getPlayerByUUID(this.lord);
		if (p != null && p.getHealth() < p.getMaxHealth() && p.distanceToSqr(this) < 16 * 16) {
			p.heal(4);
			level.sendParticles(ParticleTypes.HEART, p.getX(), p.getY() + 2, p.getZ(), 3, 0.3, 0.3, 0.3, 0);
			this.swingArm();
		}
		LivingEntity target = this.getTarget();
		if (target != null && target.isAlive() && target.distanceToSqr(this) < 14 * 14) {
			for (int i = 0; i < 3; i++) {
				level.addFreshEntity(new net.minecraft.world.entity.projectile.EvokerFangs(level, target.getX() + (i - 1) * 0.8, target.getY(),
						target.getZ(), this.getYRot(), i * 3, this));
			}
			this.swingArm();
		}
	}

	@Override
	public void performRangedAttack(LivingEntity target, float power) {
		ItemStack bow = this.getMainHandItem();
		Arrow arrow = new Arrow(this.level(), this, new ItemStack(Items.ARROW), bow);
		double dx = target.getX() - this.getX();
		double dy = target.getY(0.333) - arrow.getY();
		double dz = target.getZ() - this.getZ();
		arrow.shoot(dx, dy + Math.sqrt(dx * dx + dz * dz) * 0.2, dz, 1.6f, 4f);
		arrow.pickup = net.minecraft.world.entity.projectile.arrow.AbstractArrow.Pickup.DISALLOWED;
		this.level().addFreshEntity(arrow);
		this.swingArm();
	}

	/** Arm swing seen by everyone nearby (builders at work, attacks, spells). */
	public void swingArm() {
		this.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT);
	}

	@Override
	public boolean removeWhenFarAway(double distance) {
		return false;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
		super.addAdditionalSaveData(out);
		out.putInt("Skin", skin());
		out.putString("Role", role());
		if (this.lord != null) {
			out.putString("Lord", this.lord.toString());
		}
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		super.readAdditionalSaveData(in);
		this.entityData.set(SKIN, in.getIntOr("Skin", 0));
		this.entityData.set(ROLE, in.getStringOr("Role", ""));
		String lord = in.getStringOr("Lord", "");
		this.lord = lord.isEmpty() ? null : UUID.fromString(lord);
	}

	/** The archer keeps his distance and shoots once a second at targets he can see within 16 blocks. */
	static final class ShootGoal extends Goal {
		private final KingdomNpc npc;
		private int cooldown;

		ShootGoal(KingdomNpc npc) {
			this.npc = npc;
			this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
		}

		@Override
		public boolean canUse() {
			LivingEntity t = this.npc.getTarget();
			return t != null && t.isAlive();
		}

		@Override
		public void tick() {
			LivingEntity t = this.npc.getTarget();
			if (t == null) {
				return;
			}
			this.npc.getLookControl().setLookAt(t, 30, 30);
			double d = this.npc.distanceToSqr(t);
			boolean sees = this.npc.getSensing().hasLineOfSight(t);
			if (d > 14 * 14 || !sees) {
				this.npc.getNavigation().moveTo(t, 1.0);
			} else {
				this.npc.getNavigation().stop();
			}
			if (--this.cooldown <= 0 && sees && d < 16 * 16) {
				this.cooldown = 20;
				this.npc.performRangedAttack(t, 1f);
			}
		}
	}

	/** Allies stay with their lord: walk to him when he is 6+ blocks away, appear next to him beyond 24. */
	static final class FollowLordGoal extends Goal {
		private final KingdomNpc npc;

		FollowLordGoal(KingdomNpc npc) {
			this.npc = npc;
			this.setFlags(EnumSet.of(Goal.Flag.MOVE));
		}

		private Player lord() {
			return this.npc.lord == null ? null : this.npc.level().getPlayerByUUID(this.npc.lord);
		}

		@Override
		public boolean canUse() {
			Player p = lord();
			return p != null && this.npc.getTarget() == null && p.distanceToSqr(this.npc) > 6 * 6;
		}

		@Override
		public void tick() {
			Player p = lord();
			if (p == null) {
				return;
			}
			if (p.distanceToSqr(this.npc) > 24 * 24) {
				this.npc.teleportTo(p.getX() + 1, p.getY(), p.getZ() + 1);
			} else {
				this.npc.getNavigation().moveTo(p, 1.1);
			}
		}
	}
}
