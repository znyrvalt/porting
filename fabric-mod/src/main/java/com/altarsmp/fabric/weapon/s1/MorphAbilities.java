package com.altarsmp.fabric.weapon.s1;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Endermite;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.AbilityContext;
import com.altarsmp.fabric.ability.Effects;
import com.altarsmp.fabric.ability.Motion;
import com.altarsmp.fabric.ability.TrueDamage;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;

/**
 * The active mob abilities of {@code WandOfIllusionWeapon#useMobAbility}, one
 * method per case of the original switch, with the same cooldowns
 * ({@code wand_ability} 30s for most, 15s for Bee/Pufferfish,
 * {@code disguise_cooldown} for Iron Golem) and the same numbers.
 *
 * <p>Projectiles are real vanilla entities (small fireball, charged wither skull,
 * ghast fireball, splash potion, trident) so they collide, damage and render
 * exactly as the plugin's Bukkit projectiles did.</p>
 */
public final class MorphAbilities {

	static final String KEY_ABILITY = "wand_ability";
	private static final long DEFAULT_COOLDOWN_MILLIS = 30000L;
	private static final long STING_COOLDOWN_MILLIS = 15000L;

	private final AltarSMPMod mod;
	private final WandOfIllusionWeapon wand;

	public MorphAbilities(AltarSMPMod mod, WandOfIllusionWeapon wand) {
		this.mod = mod;
		this.wand = wand;
	}

	/** {@code WandOfIllusionWeapon#useMobAbility} dispatcher. */
	public void use(AbilityContext ctx, String storedMob) {
		ServerPlayer player = ctx.player();
		EntityType<?> type = MorphEffects.typeOf(storedMob);
		if (type == null) {
			Messaging.send(player, "<light_purple>[Wand of Illusion] <red>No mob ability available!");
			return;
		}
		if (ctx.mod().cooldowns().isOnCooldown(player, KEY_ABILITY)) {
			Messaging.actionBar(player, "<light_purple>Mob ability recharging: <yellow>" + ctx.remaining(KEY_ABILITY));
			return;
		}
		switch (BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath()) {
			case "spider", "cave_spider" -> spider(ctx);
			case "shulker" -> shulker(ctx);
			case "bee" -> bee(ctx);
			case "pufferfish" -> pufferfish(ctx);
			case "enderman" -> enderman(ctx);
			case "warden" -> warden(ctx);
			case "elder_guardian" -> elderGuardian(ctx);
			case "ender_dragon" -> enderDragon(ctx);
			case "blaze" -> blaze(ctx);
			case "wither" -> wither(ctx);
			case "drowned" -> drowned(ctx);
			case "wither_skeleton" -> witherSkeleton(ctx);
			case "creeper" -> creeper(ctx);
			case "iron_golem" -> ironGolem(ctx);
			case "ghast" -> ghast(ctx);
			case "witch" -> witch(ctx);
			case "phantom" -> phantom(ctx);
			case "slime", "magma_cube" -> slime(ctx);
			case "ravager" -> ravager(ctx);
			case "evoker" -> evoker(ctx);
			case "guardian" -> guardian(ctx);
			default -> Messaging.send(player,
					"<light_purple>[Wand of Illusion] <gray>This mob has no special ability.");
		}
	}

	private void cooldown(AbilityContext ctx, long millis) {
		ctx.mod().cooldowns().setCooldown(ctx.player(), KEY_ABILITY, millis);
	}

	// ------------------------------------------------------------------ abilities

	/** {@code useEndermanAbility} - teleport to the block you are looking at. */
	private void enderman(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		cooldown(ctx, DEFAULT_COOLDOWN_MILLIS);
		Vec3 aim = StrikerWeapon.aimTarget(ctx, 50.0D);
		if (aim == null) {
			aim = player.position().add(player.getLookAngle().normalize().scale(16.0D));
		}
		Vec3 target = aim.add(0.0D, 1.0D, 0.0D);
		BlockPos at = BlockPos.containing(target);
		if (!level.getBlockState(at).getCollisionShape(level, at).isEmpty()) {
			target = target.add(0.0D, 1.0D, 0.0D);
		}
		Fx.simple(level, "PORTAL", player.position(), 50, 0.5D, 1.0D, 0.5D, 0.5D);
		Motion.teleport(player, target);
		Fx.simple(level, "PORTAL", target, 50, 0.5D, 1.0D, 0.5D, 0.5D);
		Fx.sound(level, target, "ENTITY_ENDERMAN_TELEPORT", 1.0F, 1.0F);
	}

	/** {@code useWardenAbility} - sonic charge, then a piercing sonic boom. */
	private void warden(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		cooldown(ctx, DEFAULT_COOLDOWN_MILLIS);
		double damage = ctx.cfgd("abilities.wandofillusion.morphs.warden.ability_damage", 5.0D);
		double range = ctx.cfgd("abilities.wandofillusion.morphs.warden.ability_range", 20.0D);
		Fx.sound(level, player.position(), "ENTITY_WARDEN_SONIC_CHARGE", 2.0F, 1.0F);
		this.mod.scheduler().later(() -> {
			if (player.isRemoved() || !this.wand.isDisguised(player)) {
				return;
			}
			Vec3 eye = player.getEyePosition();
			Vec3 direction = player.getLookAngle().normalize();
			Fx.simple(level, "SONIC_BOOM", eye, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			Fx.sound(level, eye, "ENTITY_WARDEN_SONIC_BOOM", 2.0F, 1.0F);
			for (double travelled = 1.0D; travelled <= range; travelled += 0.8D) {
				Vec3 probe = eye.add(direction.scale(travelled));
				for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class,
						new AABB(BlockPos.containing(probe)).inflate(0.8D), e -> e != player && e.isAlive())) {
					if (!ctx.abilityAllowedOn(victim instanceof ServerPlayer target ? target : null)) {
						continue;
					}
					TrueDamage.apply(victim, damage, player, true);
					Motion.setVelocity(victim, new Vec3(direction.x * 0.8D, 0.3D, direction.z * 0.8D));
					Fx.sound(level, victim.position(), "ENTITY_WARDEN_ATTACK_IMPACT", 1.5F, 0.8F);
					return;
				}
			}
		}, 17L);
	}

	/** {@code useElderGuardianAbility} - Mining Fatigue III curse within 50 blocks. */
	private void elderGuardian(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		cooldown(ctx, DEFAULT_COOLDOWN_MILLIS);
		Fx.sound(level, player.position(), "ENTITY_ELDER_GUARDIAN_CURSE", 2.0F, 1.0F);
		for (ServerPlayer other : level.getServer().getPlayerList().getPlayers()) {
			if (other == player || other.level() != level
					|| other.position().distanceTo(player.position()) > 50.0D) {
				continue;
			}
			Effects.apply(other, "MINING_FATIGUE", 1200, 2);
			Messaging.send(other, "<dark_aqua>[Elder Guardian] <gray>You feel exhausted...");
			Fx.sound(level, other.position(), "ENTITY_ELDER_GUARDIAN_CURSE", 1.0F, 1.0F);
		}
	}

	/** {@code useEnderDragonAbility} - a dragon breath cloud 3 blocks ahead. */
	private void enderDragon(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		cooldown(ctx, DEFAULT_COOLDOWN_MILLIS);
		Fx.sound(level, player.position(), "ENTITY_ENDER_DRAGON_GROWL", 2.0F, 1.0F);
		Vec3 at = player.position().add(player.getLookAngle().normalize().scale(3.0D));
		AreaEffectCloud cloud = EntityType.AREA_EFFECT_CLOUD.create(level);
		if (cloud == null) {
			return;
		}
		cloud.snapTo(at.x, at.y, at.z);
		cloud.setParticle(net.minecraft.core.particles.ParticleTypes.DRAGON_BREATH);
		cloud.setRadius(4.0F);
		cloud.setDuration(100);
		cloud.setRadiusOnUse(0.0F);
		cloud.setRadiusPerTick(-0.01F);
		cloud.addEffect(new MobEffectInstance(MobEffects.HARM, 1, 1));
		cloud.setOwner(player);
		level.addFreshEntity(cloud);
	}

	/** {@code useBlazeAbility} - three small fireballs, five ticks apart. */
	private void blaze(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		cooldown(ctx, DEFAULT_COOLDOWN_MILLIS);
		Fx.sound(level, player.position(), "ENTITY_BLAZE_SHOOT", 1.0F, 1.0F);
		final int[] shot = {0};
		this.mod.scheduler().timer(() -> {
			if (shot[0] >= 3 || player.isRemoved()) {
				return;
			}
			shot[0]++;
			Vec3 direction = player.getLookAngle().normalize();
			AbstractHurtingProjectile fireball =
					(AbstractHurtingProjectile) MorphEffects.spawn(level, EntityType.SMALL_FIREBALL,
							player.getEyePosition().add(direction.scale(0.8D)));
			if (fireball != null) {
				fireball.setOwner(player);
				fireball.setDeltaMovement(direction.scale(1.5D));
			}
			Fx.sound(level, player.position(), "ENTITY_BLAZE_SHOOT", 0.5F, 1.2F);
		}, 0L, 5L).cancelAfter(20L);
	}

	/** {@code useWitherAbility} - a charged (blue) wither skull. */
	private void wither(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		cooldown(ctx, DEFAULT_COOLDOWN_MILLIS);
		Fx.sound(level, player.position(), "ENTITY_WITHER_SHOOT", 1.0F, 1.0F);
		Vec3 direction = player.getLookAngle().normalize();
		Entity skull = MorphEffects.spawn(level, EntityType.WITHER_SKULL,
				player.getEyePosition().add(direction.scale(0.8D)));
		if (skull instanceof WitherSkull witherSkull) {
			witherSkull.setOwner(player);
			witherSkull.setCharged(true);
			witherSkull.setDeltaMovement(direction.scale(1.5D));
		}
	}

	/** {@code useGhastAbility} - a ghast fireball at 2.0 speed. */
	private void ghast(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		cooldown(ctx, DEFAULT_COOLDOWN_MILLIS);
		Fx.sound(level, player.position(), "ENTITY_GHAST_SHOOT", 1.0F, 1.0F);
		Vec3 direction = player.getLookAngle().normalize();
		Entity fireball = MorphEffects.spawn(level, EntityType.FIREBALL,
				player.getEyePosition().add(direction.scale(1.0D)));
		if (fireball instanceof AbstractHurtingProjectile projectile) {
			projectile.setOwner(player);
			projectile.setDeltaMovement(direction.scale(2.0D));
		}
	}

	/** {@code useDrownedAbility} - a thrown trident that despawns after 100 ticks. */
	private void drowned(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		cooldown(ctx, DEFAULT_COOLDOWN_MILLIS);
		Fx.sound(level, player.position(), "ITEM_TRIDENT_THROW", 1.0F, 1.0F);
		Vec3 direction = player.getLookAngle().normalize();
		Entity trident = MorphEffects.spawn(level, EntityType.TRIDENT,
				player.getEyePosition().add(direction.scale(0.6D)));
		if (trident instanceof ThrownTrident thrown) {
			thrown.setOwner(player);
			thrown.setDeltaMovement(direction.scale(2.5D));
			final int[] age = {0};
			this.mod.scheduler().timer(() -> {
				if (thrown.isRemoved() || age[0] > 100) {
					thrown.discard();
					return;
				}
				age[0]++;
			}, 20L, 1L).cancelAfter(125L);
		}
	}

	/** {@code useCreeperAbility} - 30 ticks of priming, then a no-block-damage blast. */
	private void creeper(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		if (!ctx.cfgb("abilities.wandofillusion.morphs.creeper.ability_enabled", true)) {
			Messaging.send(player, "<light_purple>[Wand of Illusion] <gray>Creeper explosions are disabled.");
			return;
		}
		cooldown(ctx, DEFAULT_COOLDOWN_MILLIS);
		Fx.sound(level, player.position(), "ENTITY_CREEPER_PRIMED", 1.0F, 1.0F);
		Messaging.send(player, "<light_purple>[Wand of Illusion] <green>Charging explosion...");
		final int[] tick = {0};
		this.mod.scheduler().timer(() -> {
			if (player.isRemoved()) {
				return;
			}
			tick[0]++;
			Fx.simple(level, "SMOKE", player.position().add(0.0D, 1.0D, 0.0D), 5, 0.3D, 0.3D, 0.3D, 0.01D);
			if (tick[0] < 30) {
				return;
			}
			Vec3 center = player.position();
			level.explode(null, center.x, center.y, center.z, 4.0F, ServerLevel.ExplosionInteraction.NONE);
			Fx.simple(level, "EXPLOSION", center, 5, 1.0D, 1.0D, 1.0D, 0.0D);
			for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class,
					new AABB(BlockPos.containing(center)).inflate(5.0D), e -> e != player && e.isAlive())) {
				double distance = victim.position().distanceTo(center);
				double damage = distance <= 1.0D ? 30.0D : distance <= 2.0D ? 25.0D : distance <= 3.0D ? 20.0D
						: distance <= 4.0D ? 15.0D : 10.0D;
				TrueDamage.apply(victim, damage, player, true);
			}
		}, 0L, 1L).cancelAfter(40L);
	}

	/** {@code useIronGolemAbility} - a 26 damage launch around the holder. */
	private void ironGolem(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		cooldown(ctx, ctx.cfg("abilities.wandofillusion.disguise_cooldown", 60) * 1000L);
		Fx.sound(level, player.position(), "ENTITY_IRON_GOLEM_ATTACK", 2.0F, 0.5F);
		Vec3 center = player.position();
		Fx.simple(level, "EXPLOSION", center, 3, 1.0D, 0.5D, 1.0D, 0.0D);
		for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class,
				new AABB(BlockPos.containing(center)).inflate(5.0D, 3.0D, 5.0D), e -> e != player && e.isAlive())) {
			TrueDamage.apply(victim, 26.0D, player, true);
			Vec3 away = victim.position().subtract(center).normalize().scale(1.5D);
			Motion.setVelocity(victim, new Vec3(away.x, 0.5D, away.z));
		}
	}

	/** {@code usePhantomAbility} - a diving swoop that damages whatever it touches. */
	private void phantom(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		cooldown(ctx, DEFAULT_COOLDOWN_MILLIS);
		Fx.sound(level, player.position(), "ENTITY_PHANTOM_SWOOP", 1.0F, 1.0F);
		Vec3 direction = player.getLookAngle().normalize().scale(2.0D);
		double vertical = Math.min(direction.y, -0.5D);
		Motion.setVelocity(player, new Vec3(direction.x, vertical, direction.z));
		final int[] tick = {0};
		this.mod.scheduler().timer(() -> {
			if (player.isRemoved() || player.onGround() || tick[0] > 20) {
				return;
			}
			tick[0]++;
			for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class,
					player.getBoundingBox().inflate(1.5D), e -> e != player && e.isAlive())) {
				TrueDamage.apply(victim, 10.0D, player, true);
				return;
			}
		}, 0L, 1L).cancelAfter(25L);
	}

	/** {@code useWitchAbility} - a random harmful splash potion. */
	private void witch(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		cooldown(ctx, DEFAULT_COOLDOWN_MILLIS);
		Fx.sound(level, player.position(), "ENTITY_WITCH_THROW", 1.0F, 1.0F);
		int choice = ctx.random().nextInt(4);
		ItemStack potion = new ItemStack(Items.SPLASH_POTION);
		MobEffectInstance effect = switch (choice) {
			case 0 -> new MobEffectInstance(MobEffects.POISON, 200, 1);
			case 1 -> new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 200, 2);
			case 2 -> new MobEffectInstance(MobEffects.WEAKNESS, 200, 1);
			default -> new MobEffectInstance(MobEffects.HARM, 1, 1);
		};
		int color = switch (choice) {
			case 0 -> 0x4E9331;
			case 1 -> 0x5A6C81;
			case 2 -> 0x484D48;
			default -> 0x430A09;
		};
		potion.set(DataComponents.POTION_CONTENTS,
				new PotionContents(Optional.empty(), Optional.of(color), List.of(effect), Optional.empty()));
		Vec3 direction = player.getLookAngle().normalize();
		Entity thrown = MorphEffects.spawn(level, EntityType.SPLASH_POTION,
				player.getEyePosition().add(direction.scale(0.6D)));
		if (thrown instanceof ThrownSplashPotion splash) {
			splash.setOwner(player);
			splash.setItem(potion);
			splash.setDeltaMovement(direction.scale(1.2D));
		}
	}

	/** {@code useWitherSkeletonAbility} - Wither II plus damage in a 4x3x4 box. */
	private void witherSkeleton(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		cooldown(ctx, DEFAULT_COOLDOWN_MILLIS);
		Fx.sound(level, player.position(), "ENTITY_WITHER_SKELETON_AMBIENT", 1.0F, 0.8F);
		Vec3 center = player.position().add(0.0D, 1.0D, 0.0D);
		Fx.simple(level, "SMOKE", center, 30, 1.0D, 1.0D, 1.0D, 0.05D);
		for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class,
				new AABB(BlockPos.containing(center)).inflate(4.0D, 3.0D, 4.0D), e -> e != player && e.isAlive())) {
			Effects.apply(victim, "WITHER", 200, 1);
			TrueDamage.apply(victim, 4.0D, player, true);
		}
	}

	/** {@code useShulkerAbility} - a slow bullet that levitates what it hits. */
	private void shulker(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		cooldown(ctx, DEFAULT_COOLDOWN_MILLIS);
		Fx.sound(level, player.position(), "ENTITY_SHULKER_SHOOT", 1.0F, 1.0F);
		Vec3 direction = player.getLookAngle().normalize();
		final Vec3[] bullet = {player.getEyePosition()};
		final int[] tick = {0};
		this.mod.scheduler().timer(() -> {
			if (player.isRemoved() || tick[0] > 60) {
				return;
			}
			tick[0]++;
			bullet[0] = bullet[0].add(direction.scale(0.8D));
			Fx.simple(level, "END_ROD", bullet[0], 3, 0.1D, 0.1D, 0.1D, 0.0D);
			for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class,
					new AABB(BlockPos.containing(bullet[0])).inflate(1.0D), e -> e != player && e.isAlive())) {
				Effects.apply(victim, "LEVITATION", 60, 1);
				TrueDamage.apply(victim, 4.0D, player, true);
				Fx.simple(level, "END_ROD", victim.position(), 20, 0.5D, 0.5D, 0.5D, 0.1D);
				tick[0] = 61;
				return;
			}
		}, 0L, 1L).cancelAfter(65L);
	}

	/** {@code useSpiderAbility} - a web shot that places a temporary cobweb. */
	private void spider(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		cooldown(ctx, DEFAULT_COOLDOWN_MILLIS);
		Fx.sound(level, player.position(), "ENTITY_SPIDER_AMBIENT", 1.0F, 1.5F);
		Vec3 direction = player.getLookAngle().normalize();
		final Vec3[] web = {player.getEyePosition()};
		final int[] tick = {0};
		this.mod.scheduler().timer(() -> {
			if (player.isRemoved() || tick[0] > 30) {
				return;
			}
			web[0] = web[0].add(direction.scale(1.5D));
			Fx.itemParticles(level, "COBWEB", web[0], 3, 0.1D, 0.1D, 0.1D, 0.0D);
			BlockPos at = BlockPos.containing(web[0]);
			if (!level.getBlockState(at).getCollisionShape(level, at).isEmpty()) {
				BlockPos before = BlockPos.containing(web[0].subtract(direction.scale(1.5D)));
				if (level.getBlockState(before).isAir()) {
					level.setBlock(before, Blocks.COBWEB.defaultBlockState(), 3);
					this.mod.scheduler().later(() -> {
						if (level.getBlockState(before).is(Blocks.COBWEB)) {
							level.setBlock(before, Blocks.AIR.defaultBlockState(), 3);
						}
					}, 100L);
				}
				tick[0] = 31;
				return;
			}
			for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class,
					new AABB(at).inflate(1.0D), e -> e != player && e.isAlive())) {
				Effects.apply(victim, "SLOWNESS", 100, 2);
				tick[0] = 31;
				return;
			}
			tick[0]++;
		}, 0L, 1L).cancelAfter(35L);
	}

	/** {@code useBeeAbility} - arms the poison sting for the next melee hit. */
	private void bee(AbilityContext ctx) {
		cooldown(ctx, STING_COOLDOWN_MILLIS);
		ServerPlayer player = ctx.player();
		Fx.sound(ctx.level(), player.position(), "ENTITY_BEE_LOOP_AGGRESSIVE", 1.0F, 1.2F);
		this.wand.armSting(player);
		Fx.simple(ctx.level(), "ANGRY_VILLAGER", player.position().add(0.0D, 2.0D, 0.0D), 5, 0.3D, 0.3D, 0.3D, 0.0D);
	}

	/** {@code usePufferfishAbility} - same sting arming, underwater flavour. */
	private void pufferfish(AbilityContext ctx) {
		cooldown(ctx, STING_COOLDOWN_MILLIS);
		ServerPlayer player = ctx.player();
		Fx.sound(ctx.level(), player.position(), "ENTITY_PUFFER_FISH_BLOW_UP", 1.0F, 1.2F);
		this.wand.armSting(player);
		Fx.simple(ctx.level(), "BUBBLE", player.position().add(0.0D, 1.0D, 0.0D), 15, 0.5D, 0.5D, 0.5D, 0.1D);
	}

	/** {@code useSlimeAbility} - leap, then slam on landing. */
	private void slime(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		cooldown(ctx, DEFAULT_COOLDOWN_MILLIS);
		Fx.sound(level, player.position(), "ENTITY_SLIME_JUMP", 1.0F, 0.8F);
		Motion.setVelocity(player, new Vec3(0.0D, 1.5D, 0.0D));
		final boolean[] airborne = {false};
		this.mod.scheduler().timer(() -> {
			if (player.isRemoved()) {
				return;
			}
			if (!airborne[0] && !player.onGround()) {
				airborne[0] = true;
				return;
			}
			if (airborne[0] && player.onGround()) {
				Vec3 center = player.position();
				Fx.sound(level, center, "ENTITY_SLIME_SQUISH", 2.0F, 0.5F);
				Fx.itemParticles(level, "SLIME_BALL", center, 30, 2.0D, 0.5D, 2.0D, 0.1D);
				for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class,
						new AABB(BlockPos.containing(center)).inflate(4.0D, 2.0D, 4.0D),
						e -> e != player && e.isAlive())) {
					TrueDamage.apply(victim, 6.0D, player, true);
					Vec3 away = victim.position().subtract(center).normalize().scale(1.2D);
					Motion.setVelocity(victim, new Vec3(away.x, 0.4D, away.z));
				}
				airborne[0] = false;
				player.setDeltaMovement(Vec3.ZERO);
			}
		}, 5L, 1L).cancelAfter(200L);
	}

	/** {@code useRavagerAbility} - roar, speed burst, then a delayed 22 damage slam. */
	private void ravager(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		cooldown(ctx, DEFAULT_COOLDOWN_MILLIS);
		Fx.sound(level, player.position(), "ENTITY_RAVAGER_ROAR", 2.0F, 1.0F);
		Effects.apply(player, "SPEED", 40, 5, false, false, false);
		this.mod.scheduler().later(() -> {
			if (player.isRemoved()) {
				return;
			}
			Vec3 center = player.position().add(0.0D, 1.0D, 0.0D);
			Fx.simple(level, "EXPLOSION", center, 5, 1.0D, 1.0D, 1.0D, 0.0D);
			Fx.sound(level, player.position(), "ENTITY_RAVAGER_ATTACK", 2.0F, 0.8F);
			for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class,
					new AABB(BlockPos.containing(player.position())).inflate(5.0D, 3.0D, 5.0D),
					e -> e != player && e.isAlive())) {
				TrueDamage.apply(victim, 22.0D, player, true);
				Vec3 away = victim.position().subtract(player.position()).normalize().scale(1.5D);
				Motion.setVelocity(victim, new Vec3(away.x, 0.5D, away.z));
			}
		}, 40L);
	}

	/** {@code useEvokerAbility} - three bound-less vexes that hunt the nearest mob. */
	private void evoker(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		cooldown(ctx, DEFAULT_COOLDOWN_MILLIS);
		Fx.sound(level, player.position(), "ENTITY_EVOKER_PREPARE_SUMMON", 1.0F, 1.0F);
		Vec3 center = player.position();
		for (int index = 0; index < 3; index++) {
			Vec3 at = center.add((ctx.random().nextDouble() - 0.5D) * 2.0D, 1.0D,
					(ctx.random().nextDouble() - 0.5D) * 2.0D);
			Entity vex = MorphEffects.spawn(level, EntityType.VEX, at);
			if (vex instanceof Vex summoned) {
				LivingEntity target = MorphEffects.nearestMobTarget(player, summoned);
				if (target != null) {
					summoned.setTarget(target);
				}
				summoned.setPersistenceRequired();
				this.mod.scheduler().later(() -> {
					Fx.simple(level, "SMOKE", summoned.position(), 10, 0.3D, 0.3D, 0.3D, 0.05D);
					summoned.discard();
				}, 600L);
			}
		}
		Fx.simple(level, "LARGE_SMOKE", center.add(0.0D, 1.0D, 0.0D), 30, 0.5D, 0.5D, 0.5D, 0.1D);
	}

	/** {@code useGuardianAbility} - a 30 tick beam, then 12 damage. */
	private void guardian(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		cooldown(ctx, DEFAULT_COOLDOWN_MILLIS);
		Fx.sound(level, player.position(), "ENTITY_GUARDIAN_ATTACK", 1.0F, 1.0F);
		Vec3 eye = player.getEyePosition();
		Vec3 direction = player.getLookAngle().normalize();
		LivingEntity locked = null;
		for (int step = 1; step <= 15 && locked == null; step++) {
			Vec3 probe = eye.add(direction.scale(step));
			for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class,
					new AABB(BlockPos.containing(probe)).inflate(1.5D), e -> e != player && e.isAlive())) {
				locked = candidate;
				break;
			}
		}
		if (locked == null) {
			Messaging.actionBar(player, "<gray>No target in sight for the guardian beam.");
			return;
		}
		final LivingEntity victim = locked;
		final int[] tick = {0};
		this.mod.scheduler().timer(() -> {
			if (tick[0] >= 30 || !victim.isAlive() || player.isRemoved()) {
				if (victim.isAlive() && !player.isRemoved()) {
					TrueDamage.apply(victim, 12.0D, player, true);
					Fx.sound(level, player.position(), "ENTITY_GUARDIAN_HURT", 1.0F, 0.5F);
					Fx.simple(level, "BUBBLE", victim.position().add(0.0D, 1.0D, 0.0D), 20, 0.5D, 0.5D, 0.5D, 0.1D);
				}
				return;
			}
			tick[0]++;
			Vec3 from = player.getEyePosition();
			Vec3 to = victim.position().add(0.0D, 1.0D, 0.0D);
			Vec3 beam = to.subtract(from).normalize();
			double distance = from.distanceTo(to);
			for (double travelled = 0.0D; travelled < distance; travelled += 0.5D) {
				Fx.simple(level, "ELECTRIC_SPARK", from.add(beam.scale(travelled)), 1, 0.0D, 0.0D, 0.0D, 0.0D);
			}
		}, 0L, 1L).cancelAfter(36L);
	}

	// ------------------------------------------------------------- on-hit extras

	/**
	 * {@code WandOfIllusionWeapon#onMorphDamageDealt} - the melee extras each morph
	 * applies to whatever the disguised player hits.
	 *
	 * @return {@code true} when the hit must be cancelled entirely (bat, horse and
	 *         blaze morphs cannot attack)
	 */
	public boolean onMeleeHit(AbilityContext ctx, LivingEntity victim) {
		ServerPlayer player = ctx.player();
		String stored = this.wand.disguiseOf(player);
		if (stored == null || "PLAYER".equals(stored)) {
			return false;
		}
		if (this.wand.isBatMorph(player) || this.wand.isHorseMorph(player) || this.wand.isBlazeMorph(player)) {
			return true;
		}
		EntityType<?> type = MorphEffects.typeOf(stored);
		if (type == null) {
			return false;
		}
		ServerLevel level = ctx.level();
		switch (BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath()) {
			case "husk" -> {
				if (ctx.random().nextInt(100) < 25) {
					Effects.apply(victim, "HUNGER", 40, 0);
				}
			}
			case "cave_spider" -> {
				if (ctx.random().nextInt(100) < 15) {
					Effects.apply(victim, "POISON", 60, 0);
				}
			}
			case "vindicator" -> TrueDamage.apply(victim, 1.0D, player, true);
			case "hoglin", "zoglin" -> {
				this.mod.scheduler().later(() -> Motion.addVelocity(victim, new Vec3(0.0D, 0.4D, 0.0D)), 1L);
				if (BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath().equals("zoglin")) {
					Effects.apply(victim, "SLOWNESS", 60, 0);
				}
			}
			case "shulker" -> {
				for (int attempt = 0; attempt < 16; attempt++) {
					Vec3 candidate = victim.position().add(
							(ctx.random().nextDouble() - 0.5D) * 16.0D,
							ctx.random().nextInt(8) - 4.0D,
							(ctx.random().nextDouble() - 0.5D) * 16.0D);
					BlockPos at = BlockPos.containing(candidate);
					if (level.getBlockState(at).isAir() && level.getBlockState(at.above()).isAir()) {
						if (victim instanceof ServerPlayer target) {
							Motion.teleport(target, candidate);
						} else {
							victim.teleportTo((ServerLevel) victim.level(), candidate.x, candidate.y, candidate.z,
									java.util.Set.of(net.minecraft.world.entity.Relative.MOVEMENT),
									victim.getYRot(), victim.getXRot());
						}
						Fx.simple(level, "PORTAL", candidate, 30, 0.5D, 1.0D, 0.5D, 0.5D);
						Fx.sound(level, candidate, "ITEM_CHORUS_FRUIT_TELEPORT", 1.0F, 1.0F);
						break;
					}
				}
			}
			case "bee", "pufferfish" -> {
				if (this.wand.consumeSting(player)) {
					Effects.apply(victim, "POISON", 100, 0);
				}
			}
			case "endermite" -> {
				Entity mite = MorphEffects.spawn(level, EntityType.ENDERMITE, victim.position());
				if (mite instanceof Endermite endermite) {
					endermite.setTarget(victim);
					endermite.setPersistenceRequired();
					this.mod.scheduler().later(endermite::discard, 200L);
				}
			}
			default -> {
				// most morphs add nothing on hit
			}
		}
		return false;
	}

	/**
	 * {@code WandOfIllusionWeapon#onMorphBowShot} - arrow bonuses for the skeleton
	 * family, and no arrows at all while morphed as a blaze.
	 */
	public void onArrowHit(AbilityContext ctx, LivingEntity victim, boolean arrowProjectile) {
		ServerPlayer player = ctx.player();
		String stored = this.wand.disguiseOf(player);
		if (stored == null || "PLAYER".equals(stored)) {
			return;
		}
		EntityType<?> type = MorphEffects.typeOf(stored);
		if (type == null || !arrowProjectile) {
			return;
		}
		switch (BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath()) {
			case "skeleton" -> TrueDamage.apply(victim, 2.0D, player, true);
			case "stray" -> {
				if (ctx.random().nextInt(100) < 50) {
					Effects.apply(victim, "SLOWNESS", 100, 1);
				}
			}
			case "pillager" -> TrueDamage.apply(victim, 4.0D, player, true);
			default -> {
			}
		}
	}
}
