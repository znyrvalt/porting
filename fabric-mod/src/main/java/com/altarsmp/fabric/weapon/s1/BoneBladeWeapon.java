package com.altarsmp.fabric.weapon.s1;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.AbilityContext;
import com.altarsmp.fabric.ability.AbilityTracker;
import com.altarsmp.fabric.ability.CooldownBars;
import com.altarsmp.fabric.ability.Displays;
import com.altarsmp.fabric.ability.Effects;
import com.altarsmp.fabric.ability.Motion;
import com.altarsmp.fabric.ability.Stun;
import com.altarsmp.fabric.ability.TrueDamage;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.util.TextFx;
import com.altarsmp.fabric.weapon.WeaponBehavior;

/**
 * Bone Blade - port of {@code com.altarsmp.weapons.BoneBladeWeapon}.
 *
 * <p><b>Skeletal Leap</b> (F): dashes the player along their look vector at
 * {@code abilities.boneblade.leap_velocity}, grants Speed
 * {@code leap_speed_level} for {@code leap_speed_duration} ticks, plays the
 * dragon-flap plus three skeleton-death sounds (0, 2 and 4 ticks), bursts 35 tan
 * dust particles and sprays 12 bone + 4 bone-block physics displays. Cooldown
 * {@code leap_cooldown}s behind a yellow boss bar.</p>
 *
 * <p><b>Bone Cage</b> (Shift+F or Shift + right-click): throws the marker
 * snowball {@code BoneSnowball} at {@code cage_speed}; on impact every player
 * inside {@code cage_hitbox} blocks is stunned for {@code stun_duration}s -
 * anchored in place, unable to attack, interact or swap hands, immune to fall
 * damage - while six bone displays orbit them and a red {@code !STUNNED!} bar
 * drains.</p>
 */
public final class BoneBladeWeapon implements WeaponBehavior {

	/** {@code Color.fromRGB(204, 176, 143)}. */
	static final int BONE_TAN = (204 << 16) | (176 << 8) | 143;

	static final String KEY_LEAP = "boneblade_leap";
	static final String KEY_CAGE = "boneblade_cage";
	static final String CAGE_PROJECTILE = "BoneSnowball";

	private final AltarSMPMod mod;
	private final Map<UUID, List<Display.ItemDisplay>> orbitals = new HashMap<>();

	public BoneBladeWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "boneblade";
	}

	@Override
	public String displayName() {
		return "Bone Blade";
	}

	@Override
	public List<String> configFields() {
		return List.of("Leap Cooldown (s)", "Bone Cage Cooldown (s)", "Bone Cage Stun Duration (s)");
	}

	@Override
	public void onPrimary(AbilityContext ctx) {
		useSkeletalLeap(ctx);
	}

	@Override
	public void onSecondary(AbilityContext ctx) {
		useBoneCage(ctx);
	}

	@Override
	public boolean onUse(AbilityContext ctx, InteractionHand hand) {
		if (ctx.player().isShiftKeyDown()) {
			useBoneCage(ctx);
			return true;
		}
		return false;
	}

	// ------------------------------------------------------------ skeletal leap

	private void useSkeletalLeap(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		int cooldown = ctx.cfg("abilities.boneblade.leap_cooldown", 30);
		if (!ctx.gate(KEY_LEAP, "Skeletal Leap")) {
			return;
		}
		ctx.startCooldown(KEY_LEAP, cooldown);
		CooldownBars.show(player, KEY_LEAP, "Skeletal Leap", BossEvent.BossBarColor.YELLOW, cooldown);
		AbilityTracker.recordLeap(player);

		Fx.sound(level, player.position(), "ENTITY_ENDER_DRAGON_FLAP", 1.0F, 1.0F);
		Fx.sound(level, player.position(), "ENTITY_SKELETON_DEATH", 1.0F, 0.8F);
		this.mod.scheduler().later(() -> Fx.sound(level, player.position(), "ENTITY_SKELETON_DEATH", 1.0F, 0.9F), 2L);
		this.mod.scheduler().later(() -> Fx.sound(level, player.position(), "ENTITY_SKELETON_DEATH", 1.0F, 1.0F), 4L);

		Fx.dust(level, player.position().add(0.0D, 1.0D, 0.0D), BONE_TAN, 1.5F, 35, 0.45D, 0.45D, 0.45D);
		Fx.blockParticles(level, "BONE_BLOCK", player.position().add(0.0D, 1.0D, 0.0D), 10, 0.3D, 0.4D, 0.3D, 0.02D);

		Vec3 shardBase = player.position().add(0.0D, 0.8D, 0.0D);
		for (int i = 0; i < 12; i++) {
			Displays.physicsItem(level, shardBase, new ItemStack(Items.BONE), randomShardVelocity(), 40);
		}
		for (int i = 0; i < 4; i++) {
			Displays.physicsItem(level, shardBase, new ItemStack(Items.BONE_BLOCK), randomShardVelocity(), 40);
		}

		Motion.launch(player, ctx.cfgd("abilities.boneblade.leap_velocity", 1.5D));
		Effects.apply(player, "SPEED", ctx.cfg("abilities.boneblade.leap_speed_duration", 160),
				ctx.cfg("abilities.boneblade.leap_speed_level", 2), true, false, true);
		startSkeletalRam(ctx);
	}

	/**
	 * Skeletal Ram - the damage aura that rides along with a leap
	 * ({@code abilities.boneblade.ram_*}): for {@code ram_duration_ticks} every
	 * living entity inside {@code ram_radius} takes {@code ram_damage} true damage
	 * once and is thrown with {@code ram_throw_strength}.
	 */
	private void startSkeletalRam(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		double radius = ctx.cfgd("abilities.boneblade.ram_radius", 2.0D);
		double damage = ctx.cfgd("abilities.boneblade.ram_damage", 7.0D);
		double throwStrength = ctx.cfgd("abilities.boneblade.ram_throw_strength", 1.4D);
		final java.util.Set<UUID> hit = new java.util.HashSet<>();
		this.mod.scheduler().timer(() -> {
			if (player.isRemoved()) {
				return;
			}
			Fx.dust(level, player.position().add(0.0D, 0.6D, 0.0D), BONE_TAN, 1.1F, 4, 0.3D, 0.2D, 0.3D);
			for (net.minecraft.world.entity.LivingEntity entity : level.getEntitiesOfClass(
					net.minecraft.world.entity.LivingEntity.class,
					player.getBoundingBox().inflate(radius), e -> e != player && e.isAlive())) {
				if (!hit.add(entity.getUUID())) {
					continue;
				}
				TrueDamage.apply(entity, damage, player, false);
				Vec3 away = entity.position().subtract(player.position()).normalize().scale(throwStrength);
				entity.setDeltaMovement(away.x, 0.35D, away.z);
				entity.hurtMarked = true;
				Fx.sound(level, entity.position(), "ENTITY_SKELETON_STEP", 1.0F, 1.0F);
			}
		}, 0L, 2L).cancelAfter(ctx.cfg("abilities.boneblade.ram_duration_ticks", 15));
	}

	private static Vec3 randomShardVelocity() {
		return new Vec3((Math.random() - 0.5D) * 0.5D, Math.random() * 0.35D + 0.1D, (Math.random() - 0.5D) * 0.5D);
	}

	// --------------------------------------------------------------- bone cage

	private void useBoneCage(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		int cooldown = ctx.cfg("abilities.boneblade.cage_cooldown", 60);
		if (!ctx.gate(KEY_CAGE, "Bone Cage")) {
			return;
		}
		ctx.startCooldown(KEY_CAGE, cooldown);
		CooldownBars.show(player, KEY_CAGE, "Bone Cage", BossEvent.BossBarColor.YELLOW, cooldown);

		Snowball projectile = new Snowball(EntityType.SNOWBALL, ctx.level());
		projectile.setOwner(player);
		projectile.setPos(player.getEyePosition());
		projectile.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F,
				(float) ctx.cfgd("abilities.boneblade.cage_speed", 1.5D), 0.0F);
		projectile.setCustomName(TextFx.plain(CAGE_PROJECTILE));
		projectile.setSilent(true);
		ctx.level().addFreshEntity(projectile);
		Fx.sound(ctx.level(), player.position(), "ENTITY_ENDER_DRAGON_FLAP", 1.0F, 2.0F);
	}

	/** True when a landed projectile is this weapon's bone cage. */
	public static boolean isCageProjectile(net.minecraft.world.entity.Entity projectile) {
		net.minecraft.network.chat.Component name = projectile.getCustomName();
		return name != null && CAGE_PROJECTILE.equals(name.getString());
	}

	/** Impact handling, invoked from the projectile mixin. */
	public void onCageImpact(ServerPlayer shooter, Vec3 impact) {
		ServerLevel level = shooter.serverLevel();
		double radius = this.mod.config().getDouble("abilities.boneblade.cage_hitbox", 2.0D);
		int stunSeconds = this.mod.config().getInt("abilities.boneblade.stun_duration", 4);

		Fx.dust(level, impact, BONE_TAN, 1.4F, 5, 0.3D, 0.3D, 0.3D);
		Fx.blockParticles(level, "BONE_BLOCK", impact, 10, 0.3D, 0.4D, 0.3D, 0.02D);
		Fx.sound(level, impact, "ENTITY_SKELETON_AMBIENT", 0.4F, 2.0F);

		int trapped = 0;
		for (ServerPlayer victim : level.getServer().getPlayerList().getPlayers()) {
			if (victim == shooter || victim.level() != level) {
				continue;
			}
			if (victim.position().distanceTo(impact) > radius) {
				continue;
			}
			Stun.stun(victim, stunSeconds);
			startCageOrbitals(victim, stunSeconds * 20);
			CooldownBars.show(victim, "boneblade_stun", "!STUNNED!", BossEvent.BossBarColor.RED, stunSeconds);
			Fx.sound(level, victim.position(), "ENTITY_ZOMBIE_ATTACK_IRON_DOOR", 2.0F, 0.7F);
			trapped++;
		}
		if (trapped > 0) {
			Messaging.send(shooter, "<gray>Bone Cage trapped <yellow>" + trapped + "</yellow> player(s).");
		} else {
			Messaging.send(shooter, "<gray>Bone Cage found nobody to trap.");
		}
	}

	private void startCageOrbitals(ServerPlayer victim, int lifeTicks) {
		clearOrbitals(victim.getUUID());
		List<Display.ItemDisplay> spawned = new ArrayList<>();
		ServerLevel level = victim.serverLevel();
		for (int i = 0; i < 6; i++) {
			double height = 0.4D + i * 0.28D;
			Display.ItemDisplay display = Displays.orbital(level, victim.position(), new ItemStack(Items.BONE),
					1.1D, height, 6.0D + i, lifeTicks, orbital -> {
						if (victim.isRemoved() || !Stun.isStunned(victim)) {
							Displays.remove(orbital);
							return;
						}
						Vec3 center = victim.position();
						orbital.snapTo(center.x, center.y, center.z, 0.0F, 0.0F);
						Fx.dust(level, orbital.position(), BONE_TAN, 0.75F, 2, 0.0D, 0.0D, 0.0D);
					});
			spawned.add(display);
		}
		this.orbitals.put(victim.getUUID(), spawned);
	}

	private void clearOrbitals(UUID uuid) {
		List<Display.ItemDisplay> existing = this.orbitals.remove(uuid);
		if (existing != null) {
			existing.forEach(Displays::remove);
		}
	}

	@Override
	public void onPlayerQuit(ServerPlayer player) {
		clearOrbitals(player.getUUID());
		Stun.release(player);
		CooldownBars.hide(player, "boneblade_stun");
	}

	@Override
	public void onTick(AbilityContext ctx) {
		// Passive: nothing. Bone Blade only acts on activation and on cage impact.
	}

	/** Diagnostics for {@code /altarsmp debug weapons}. */
	public int activeCages() {
		return this.orbitals.size();
	}
}
