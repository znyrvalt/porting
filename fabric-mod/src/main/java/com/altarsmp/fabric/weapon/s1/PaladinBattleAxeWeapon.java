package com.altarsmp.fabric.weapon.s1;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.AbilityContext;
import com.altarsmp.fabric.ability.CooldownBars;
import com.altarsmp.fabric.ability.Displays;
import com.altarsmp.fabric.ability.Effects;
import com.altarsmp.fabric.ability.TrueDamage;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.weapon.WeaponBehavior;

/**
 * Paladin's Battle Axe - port of
 * {@code com.altarsmp.weapons.PaladinBattleAxeWeapon}.
 *
 * <p><b>Passive</b>: Haste {@code abilities.paladinbattleaxe.passive_haste_level}
 * while held.</p>
 *
 * <p><b>Earth Shatter</b> (F, {@code shatter_cooldown}s): the axe is slammed into
 * the ground. The port keeps the original's full VFX stack - a golden lightning
 * strike on the impact, five expanding shockwave rings of dust and crit
 * particles, cracked-ground block displays that fade after a second, a rising
 * pillar of flame at the epicentre and the anvil/dragon/explosion sound layer -
 * then deals {@code earth_shatter_damage} true damage inside
 * {@code shatter_radius} ({@code shatter_impact_damage} to whoever is closest)
 * with {@code shatter_knockback} throw.</p>
 *
 * <p><b>Stalwart Shield</b> (Shift+F, {@code stalwart_cooldown}s): for
 * {@code stalwart_duration} ticks a golden shield orbits the holder. Hits of at
 * least {@code stalwart_min_hit_damage} are partially absorbed
 * ({@code stalwart_absorption_ratio}) and the absorbed total accumulates up to
 * {@code stalwart_damage_cap}; when the shield drops - or the cap is reached - the
 * stored holy damage is released in a {@code stalwart_release_radius} burst with
 * {@code stalwart_knockback} throw.</p>
 */
public final class PaladinBattleAxeWeapon implements WeaponBehavior {

	static final String KEY_SHATTER = "paladin_shatter";
	static final String KEY_STALWART = "paladin_stalwart";
	static final String KEY_SHIELD = "paladin_shield";

	private static final int HOLY_GOLD = 0xFFD700;
	private static final int HOLY_WHITE = 0xFFF8DC;

	private final AltarSMPMod mod;
	private final Map<UUID, Shield> shields = new HashMap<>();

	public PaladinBattleAxeWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "paladinbattleaxe";
	}

	@Override
	public String displayName() {
		return "Paladin's Battle Axe";
	}

	@Override
	public List<String> configFields() {
		return List.of("Shatter Cooldown (s)", "Earth Shatter Damage", "Shatter Radius", "Stalwart Cooldown (s)",
				"Stalwart Duration (ticks)", "Stalwart Damage Cap");
	}

	private record Shield(double absorbed, long expiresAt, List<Display> visuals) {
	}

	// ------------------------------------------------------------------ passive

	@Override
	public void onTick(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		long tick = this.mod.scheduler().currentTick();
		if (tick % 20L == 0L) {
			Effects.apply(player, "HASTE", 40, ctx.cfg("abilities.paladinbattleaxe.passive_haste_level", 0), true, false, false);
			Effects.apply(player, "REGENERATION", 40, 0, true, false, false);
		}
		if (tick % 4L == 0L) {
			Vec3 center = player.position().add(0.0D, 1.0D, 0.0D);
			double angle = Math.toRadians(tick * 12.0D);
			Vec3 point = center.add(Math.cos(angle) * 0.9D, Math.sin(angle * 0.5D) * 0.25D, Math.sin(angle) * 0.9D);
			Fx.dust(ctx.level(), point, HOLY_GOLD, 0.7F, 1, 0.02D, 0.02D, 0.02D);
		}
		Shield shield = this.shields.get(player.getUUID());
		if (shield != null) {
			spinShield(player, shield);
			if (System.currentTimeMillis() > shield.expiresAt()) {
				releaseShield(ctx, shield.absorbed());
			}
		}
	}

	// ------------------------------------------------------------ earth shatter

	@Override
	public void onPrimary(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		int cooldown = ctx.cfg("abilities.paladinbattleaxe.shatter_cooldown", 45);
		if (!ctx.gate(KEY_SHATTER, "Earth Shatter")) {
			return;
		}
		ctx.startCooldown(KEY_SHATTER, cooldown);
		CooldownBars.show(player, KEY_SHATTER, "Earth Shatter", BossEvent.BossBarColor.YELLOW, cooldown);

		double radius = ctx.cfg("abilities.paladinbattleaxe.shatter_radius", 6);
		double damage = ctx.cfgd("abilities.paladinbattleaxe.earth_shatter_damage", 7.0D);
		double impactDamage = ctx.cfgd("abilities.paladinbattleaxe.shatter_impact_damage", 6.0D);
		double knockback = ctx.cfgd("abilities.paladinbattleaxe.shatter_knockback", 0.85D);
		Vec3 impact = player.position();

		Messaging.actionBar(player, "<gold><bold>EARTH SHATTER!");
		Fx.lightning(level, impact, true);
		Fx.sound(level, impact, "BLOCK_ANVIL_LAND", 1.4F, 0.7F);
		Fx.sound(level, impact, "ENTITY_GENERIC_EXPLODE", 1.0F, 0.6F);
		Fx.sound(level, impact, "ENTITY_ENDER_DRAGON_FLAP", 0.9F, 0.6F);
		Fx.sound(level, impact, "ENTITY_IRON_GOLEM_ATTACK", 1.0F, 0.8F);

		// Five expanding holy rings - the signature Paladin shockwave.
		for (int ring = 0; ring < 5; ring++) {
			final double ringRadius = radius * (ring + 1) / 5.0D;
			final int delay = ring * 3;
			this.mod.scheduler().later(() -> {
				int points = (int) Math.max(16, ringRadius * 12.0D);
				for (int i = 0; i < points; i++) {
					double angle = Math.toRadians(360.0D / points * i);
					Vec3 point = impact.add(Math.cos(angle) * ringRadius, 0.15D, Math.sin(angle) * ringRadius);
					Fx.dust(level, point, ring % 2 == 0 ? HOLY_GOLD : HOLY_WHITE, 1.3F - ring * 0.15F, 1, 0.0D, 0.0D, 0.0D);
					if (i % 3 == 0) {
						Fx.simple(level, "CRIT", point, 1, 0.05D, 0.05D, 0.05D, 0.02D);
					}
					if (i % 7 == 0) {
						Fx.simple(level, "FLAME", point, 1, 0.05D, 0.1D, 0.05D, 0.02D);
					}
				}
				Fx.sound(level, impact, "ENTITY_PLAYER_ATTACK_SWEEP", 0.7F, 1.6F - ring * 0.15F);
			}, delay);
		}

		// Cracked ground displays rising at the epicentre.
		List<Display> cracks = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			double angle = Math.toRadians(45.0D * i);
			Vec3 point = impact.add(Math.cos(angle) * 1.2D, 0.1D, Math.sin(angle) * 1.2D);
			Display.BlockDisplay crack = Displays.block(level, point,
					net.minecraft.world.level.block.Blocks.CRACKED_DEEPSLATE_TILES.defaultBlockState());
			Displays.setScale(crack, 0.35F);
			Displays.bright(crack);
			cracks.add(crack);
		}
		Display.ItemDisplay pillar = Displays.item(level, impact.add(0.0D, 1.2D, 0.0D), new ItemStack(Items.GOLDEN_SWORD), 1.4F);
		Displays.bright(pillar);
		cracks.add(pillar);
		this.mod.scheduler().later(() -> cracks.forEach(Displays::remove), 22L);

		// Flame pillar + dust column.
		this.mod.scheduler().timer(() -> {
			Fx.simple(level, "FLAME", impact.add(0.0D, 1.0D, 0.0D), 12, 0.35D, 0.9D, 0.35D, 0.08D);
			Fx.dust(level, impact.add(0.0D, 1.4D, 0.0D), HOLY_GOLD, 1.6F, 8, 0.3D, 0.7D, 0.3D);
		}, 0L, 2L).cancelAfter(20L);

		// Damage: closest target takes the impact bonus.
		List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class,
				new AABB(impact, impact).inflate(radius), e -> e != player && e.isAlive());
		LivingEntity closest = null;
		double closestDistance = Double.MAX_VALUE;
		for (LivingEntity entity : targets) {
			double distance = entity.position().distanceTo(impact);
			if (distance < closestDistance) {
				closestDistance = distance;
				closest = entity;
			}
		}
		for (LivingEntity entity : targets) {
			double amount = entity == closest ? damage + impactDamage : damage;
			TrueDamage.apply(entity, amount, player, false);
			Vec3 away = entity.position().subtract(impact).normalize();
			double falloff = Math.max(0.35D, 1.0D - entity.position().distanceTo(impact) / radius);
			entity.setDeltaMovement(away.x * knockback * falloff, 0.45D * falloff + 0.15D, away.z * knockback * falloff);
			entity.hurtMarked = true;
			entity.setRemainingFireTicks(60);
			Fx.dust(level, entity.position().add(0.0D, 1.0D, 0.0D), HOLY_GOLD, 1.2F, 10, 0.3D, 0.4D, 0.3D);
		}
		if (!targets.isEmpty()) {
			Messaging.send(player, "<gold>Earth Shatter smote <white>" + targets.size() + "</white> foe(s).");
		}
	}

	// ------------------------------------------------------------ stalwart shield

	@Override
	public void onSecondary(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		int cooldown = ctx.cfg("abilities.paladinbattleaxe.stalwart_cooldown", 45);
		int duration = ctx.cfg("abilities.paladinbattleaxe.stalwart_duration", 100);
		if (!ctx.gate(KEY_STALWART, "Stalwart Shield")) {
			return;
		}
		ctx.startCooldown(KEY_STALWART, cooldown);
		CooldownBars.show(player, KEY_STALWART, "Stalwart Shield", BossEvent.BossBarColor.YELLOW, cooldown);

		List<Display> visuals = new ArrayList<>();
		Display.ItemDisplay shieldDisplay = Displays.item(ctx.level(), player.position().add(0.0D, 1.2D, 0.0D),
				new ItemStack(Items.SHIELD), 1.1F);
		Displays.bright(shieldDisplay);
		visuals.add(shieldDisplay);
		this.shields.put(player.getUUID(), new Shield(0.0D, System.currentTimeMillis() + duration * 50L, visuals));

		Fx.sound(ctx.level(), player.position(), "ITEM_SHIELD_BLOCK", 1.2F, 0.7F);
		Fx.sound(ctx.level(), player.position(), "BLOCK_BEACON_ACTIVATE", 0.8F, 1.3F);
		Fx.dust(ctx.level(), player.position().add(0.0D, 1.0D, 0.0D), HOLY_GOLD, 1.5F, 30, 0.5D, 0.8D, 0.5D);
		Messaging.actionBar(player, "<gold><bold>STALWART SHIELD</bold> <gray>- absorb and release");
		this.mod.scheduler().later(() -> {
			Shield shield = this.shields.remove(player.getUUID());
			if (shield != null) {
				shield.visuals().forEach(Displays::remove);
				CooldownBars.hide(player, KEY_SHIELD);
				if (shield.absorbed() > 0.0D) {
					releaseShield(ctx, shield.absorbed());
				}
			}
		}, duration);
	}

	private void spinShield(ServerPlayer player, Shield shield) {
		if (shield.visuals().isEmpty()) {
			return;
		}
		Display display = shield.visuals().get(0);
		if (display.isRemoved()) {
			return;
		}
		double angle = Math.toRadians(this.mod.scheduler().currentTick() * 8.0D);
		Vec3 point = player.position().add(Math.cos(angle) * 1.15D, 1.25D, Math.sin(angle) * 1.15D);
		display.snapTo(point.x, point.y, point.z, 0.0F, 0.0F);
		Displays.rotate(display, (float) (angle * 180.0D / Math.PI), 1.1F);
		Fx.dust(player.serverLevel(), point, HOLY_GOLD, 0.8F, 1, 0.03D, 0.03D, 0.03D);
	}

	@Override
	public boolean onDamaged(AbilityContext ctx, DamageSource source, float amount, LivingEntity attacker) {
		ServerPlayer player = ctx.player();
		Shield shield = this.shields.get(player.getUUID());
		if (shield == null || System.currentTimeMillis() > shield.expiresAt()) {
			return false;
		}
		double minHit = ctx.cfgd("abilities.paladinbattleaxe.stalwart_min_hit_damage", 4.0D);
		if (amount < minHit) {
			// Small hits pass straight through, exactly as the config comment says.
			return false;
		}
		double ratio = ctx.cfgd("abilities.paladinbattleaxe.stalwart_absorption_ratio", 0.5D);
		double cap = ctx.cfgd("abilities.paladinbattleaxe.stalwart_damage_cap", 60.0D);
		double absorbed = Math.min(amount * ratio, Math.max(0.0D, cap - shield.absorbed()));
		this.shields.put(player.getUUID(), new Shield(shield.absorbed() + absorbed, shield.expiresAt(), shield.visuals()));

		Fx.sound(ctx.level(), player.position(), "ITEM_SHIELD_BLOCK", 1.0F, 1.2F);
		Fx.sound(ctx.level(), player.position(), "BLOCK_ANVIL_LAND", 0.6F, 1.5F);
		Fx.dust(ctx.level(), player.position().add(0.0D, 1.2D, 0.0D), HOLY_WHITE, 1.2F, 18, 0.4D, 0.5D, 0.4D);
		CooldownBars.show(player, KEY_SHIELD, "Stalwart " + (int) (shield.absorbed() + absorbed) + "/" + (int) cap,
				BossEvent.BossBarColor.YELLOW, Math.max(1L, shield.expiresAt() - System.currentTimeMillis()));
		Messaging.actionBar(player, "<gold>Stalwart absorbed <white>" + String.format(java.util.Locale.ROOT, "%.1f", absorbed)
				+ "</white> (stored <yellow>" + String.format(java.util.Locale.ROOT, "%.1f", shield.absorbed() + absorbed) + "</yellow>)");

		if (attacker != null) {
			Vec3 away = attacker.position().subtract(player.position()).normalize()
					.scale(ctx.cfgd("abilities.paladinbattleaxe.stalwart_knockback", 1.2D));
			attacker.setDeltaMovement(away.x, 0.35D, away.z);
			attacker.hurtMarked = true;
		}
		if (shield.absorbed() + absorbed >= cap) {
			this.shields.remove(player.getUUID());
			shield.visuals().forEach(Displays::remove);
			releaseShield(ctx, cap);
		}
		return absorbed >= amount;
	}

	private void releaseShield(AbilityContext ctx, double stored) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		if (stored <= 0.0D) {
			return;
		}
		double radius = ctx.cfg("abilities.paladinbattleaxe.stalwart_release_radius", 5);
		double knockback = ctx.cfgd("abilities.paladinbattleaxe.stalwart_knockback", 1.2D);
		Vec3 center = player.position();

		Fx.sound(level, center, "BLOCK_BEACON_DEACTIVATE", 1.0F, 1.2F);
		Fx.sound(level, center, "ENTITY_GENERIC_EXPLODE", 0.9F, 1.4F);
		Fx.lightning(level, center, true);
		for (int ring = 0; ring < 3; ring++) {
			double ringRadius = radius * (ring + 1) / 3.0D;
			int points = (int) (ringRadius * 14.0D);
			for (int i = 0; i < points; i++) {
				double angle = Math.toRadians(360.0D / points * i);
				Vec3 point = center.add(Math.cos(angle) * ringRadius, 0.6D + ring * 0.4D, Math.sin(angle) * ringRadius);
				Fx.dust(level, point, HOLY_GOLD, 1.4F, 1, 0.0D, 0.0D, 0.0D);
				Fx.simple(level, "END_ROD", point, 1, 0.03D, 0.03D, 0.03D, 0.0D);
			}
		}
		int hits = 0;
		for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
				new AABB(center, center).inflate(radius), e -> e != player && e.isAlive())) {
			TrueDamage.apply(entity, stored, player, true);
			Vec3 away = entity.position().subtract(center).normalize().scale(knockback);
			entity.setDeltaMovement(away.x, 0.5D, away.z);
			entity.hurtMarked = true;
			hits++;
		}
		Messaging.send(player, "<gold>Stalwart Shield released <white>" + String.format(java.util.Locale.ROOT, "%.1f", stored)
				+ "</white> holy damage into <yellow>" + hits + "</yellow> foe(s).");
		CooldownBars.hide(player, KEY_SHIELD);
	}

	@Override
	public void onPlayerQuit(ServerPlayer player) {
		Shield shield = this.shields.remove(player.getUUID());
		if (shield != null) {
			shield.visuals().forEach(Displays::remove);
		}
		CooldownBars.hide(player, KEY_SHIELD);
		CooldownBars.hide(player, KEY_STALWART);
		CooldownBars.hide(player, KEY_SHATTER);
	}

	public int activeShields() {
		return this.shields.size();
	}
}
