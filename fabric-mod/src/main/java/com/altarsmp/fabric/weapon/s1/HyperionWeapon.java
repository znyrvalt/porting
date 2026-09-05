package com.altarsmp.fabric.weapon.s1;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.AbilityContext;
import com.altarsmp.fabric.ability.CooldownBars;
import com.altarsmp.fabric.ability.Displays;
import com.altarsmp.fabric.ability.Effects;
import com.altarsmp.fabric.ability.Motion;
import com.altarsmp.fabric.ability.TrueDamage;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.weapon.WeaponBehavior;

/**
 * Hyperion - port of {@code com.altarsmp.weapons.HyperionWeapon}.
 *
 * <p><b>Passive - Hallowed Flames</b>: the holder is wreathed in holy fire (fire
 * resistance while held) and anything that damages them takes
 * {@code abilities.hyperion.hallowed_flames_damage} true damage back.</p>
 *
 * <p><b>Holy Lance</b> (F): a {@code holy_lance_charge_ticks} charge gathers light
 * around the holder, then a beam of golden displays is fired along the look
 * vector. Everything inside {@code holy_lance_radius} of the beam takes
 * {@code holy_lance_damage} true damage (+{@code vampire_bonus} against vampires),
 * is set alight for {@code holy_lance_fire_ticks} and a white circle marks the
 * impact. Cooldown {@code holy_lance_cooldown}s.</p>
 *
 * <p><b>Scorching Blade</b> (Shift+F): a forward dash that cuts everything inside
 * {@code scorching_hitbox} for {@code scorching_blade_damage}, ends in a
 * {@code scorching_radius} fire burst and leaves real lava behind for
 * {@code scorching_lava_duration} ticks before reverting it. Cooldown
 * {@code scorching_cooldown}s.</p>
 */
public final class HyperionWeapon implements WeaponBehavior {

	static final String KEY_LANCE = "hyperion_holy_lance";
	static final String KEY_SCORCH = "hyperion_scorching";
	private static final int HOLY = 0xFFE9A8;
	private static final int FIRE = 0xFF6A00;

	private final AltarSMPMod mod;

	public HyperionWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "hyperion";
	}

	@Override
	public String displayName() {
		return "Hyperion";
	}

	@Override
	public List<String> configFields() {
		return List.of("Holy Lance Cooldown (s)", "Holy Lance Damage", "Holy Lance Radius", "Scorching Cooldown (s)",
				"Scorching Damage", "Scorching Radius", "Scorching Lava Duration (ticks)");
	}

	@Override
	public void onTick(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		long tick = this.mod.scheduler().currentTick();
		if (tick % 20L == 0L) {
			Effects.apply(player, "FIRE_RESISTANCE", 40, 0, true, false, false);
		}
		if (tick % 3L == 0L) {
			Vec3 center = player.position().add(0.0D, 1.0D, 0.0D);
			double angle = Math.toRadians(tick * 20.0D);
			Fx.simple(ctx.level(), "FLAME", center.add(Math.cos(angle) * 0.8D, 0.0D, Math.sin(angle) * 0.8D), 2, 0.05D, 0.1D, 0.05D, 0.0D);
			Fx.dust(ctx.level(), center, HOLY, 0.7F, 1, 0.2D, 0.3D, 0.2D);
		}
	}

	@Override
	public boolean onDamaged(AbilityContext ctx, DamageSource source, float amount, LivingEntity attacker) {
		if (attacker == null || attacker == ctx.player()) {
			return false;
		}
		double flames = ctx.cfgd("abilities.hyperion.hallowed_flames_damage", 7.0D);
		TrueDamage.apply(attacker, flames, ctx.player(), false);
		attacker.setRemainingFireTicks(ctx.cfg("abilities.hyperion.holy_lance_fire_ticks", 100));
		Fx.sound(ctx.level(), attacker.position(), "BLOCK_FIRE_EXTINGUISH", 0.8F, 1.2F);
		Fx.simple(ctx.level(), "FLAME", attacker.position().add(0.0D, 1.0D, 0.0D), 20, 0.3D, 0.5D, 0.3D, 0.05D);
		Fx.dust(ctx.level(), attacker.position().add(0.0D, 1.0D, 0.0D), HOLY, 1.3F, 15, 0.3D, 0.4D, 0.3D);
		return false;
	}

	@Override
	public void onPrimary(AbilityContext ctx) {
		holyLance(ctx);
	}

	@Override
	public void onSecondary(AbilityContext ctx) {
		scorchingBlade(ctx);
	}

	// -------------------------------------------------------------- holy lance

	private void holyLance(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		int cooldown = ctx.cfg("abilities.hyperion.holy_lance_cooldown", 60);
		int chargeTicks = ctx.cfg("abilities.hyperion.holy_lance_charge_ticks", 20);
		if (!ctx.gate(KEY_LANCE, "Holy Lance")) {
			return;
		}
		ctx.startCooldown(KEY_LANCE, cooldown);
		CooldownBars.show(player, KEY_LANCE, "Holy Lance", BossEvent.BossBarColor.YELLOW, cooldown);

		Vec3 center = player.position().add(0.0D, 1.0D, 0.0D);
		Fx.sound(level, center, "BLOCK_BEACON_ACTIVATE", 1.0F, 1.4F);
		Messaging.actionBar(player, "<gold>Charging Holy Lance...");
		final int[] gathered = {0};
		this.mod.scheduler().timer(() -> {
			if (player.isRemoved()) {
				return;
			}
			gathered[0]++;
			double radius = 3.0D - gathered[0] * (3.0D / chargeTicks);
			for (int i = 0; i < 8; i++) {
				double angle = Math.toRadians(45.0D * i + gathered[0] * 20.0D);
				Vec3 point = center.add(Math.cos(angle) * radius, Math.sin(angle) * radius * 0.4D, Math.sin(angle) * radius);
				Fx.dust(level, point, HOLY, 0.9F, 1, 0.0D, 0.0D, 0.0D);
				Fx.simple(level, "END_ROD", point, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			}
			Fx.sound(level, center, "BLOCK_AMETHYST_CLUSTER_BREAK", 0.4F, 1.6F);
		}, 0L, 1L).cancelAfter(chargeTicks);

		this.mod.scheduler().later(() -> {
			if (player.isRemoved()) {
				return;
			}
			fireLance(new AbilityContext(this.mod, player, player.getMainHandItem(), id()));
		}, chargeTicks);
	}

	private void fireLance(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		double damage = ctx.cfgd("abilities.hyperion.holy_lance_damage", 6.0D);
		double vampireBonus = ctx.cfgd("abilities.hyperion.vampire_bonus", 3.0D);
		double radius = ctx.cfgd("abilities.hyperion.holy_lance_radius", 3.0D);
		int fireTicks = ctx.cfg("abilities.hyperion.holy_lance_fire_ticks", 100);
		double range = ctx.cfgd("abilities.hyperion.holy_lance_range", 30.0D);

		Vec3 direction = player.getLookAngle().normalize();
		Vec3 start = player.getEyePosition();
		Vec3 end = start.add(direction.scale(range));
		BlockPos endPos = BlockPos.containing(end);
		for (double travelled = 1.0D; travelled <= range; travelled += 1.0D) {
			Vec3 point = start.add(direction.scale(travelled));
			if (!level.getBlockState(BlockPos.containing(point)).getCollisionShape(level, BlockPos.containing(point)).isEmpty()) {
				end = point;
				endPos = BlockPos.containing(point);
				break;
			}
		}

		Fx.sound(level, start, "BLOCK_BEACON_POWER_SELECT", 1.2F, 1.6F);
		Fx.sound(level, end, "ENTITY_GENERIC_EXPLODE", 0.9F, 1.4F);

		// Beam segments: golden displays along the lance that fade shortly after.
		List<Display> beam = new ArrayList<>();
		double length = end.subtract(start).length();
		int segments = Math.max(1, (int) (length / 1.5D));
		for (int i = 0; i <= segments; i++) {
			Vec3 point = start.add(end.subtract(start).scale((double) i / segments));
			Display.ItemDisplay part = Displays.item(level, point, new ItemStack(Items.GLOWSTONE), 0.55F);
			Displays.bright(part);
			Displays.rotate(part, (float) (i * 24.0D), 0.55F);
			beam.add(part);
			Fx.dust(level, point, HOLY, 1.1F, 3, 0.08D, 0.08D, 0.08D);
			Fx.simple(level, "END_ROD", point, 2, 0.05D, 0.05D, 0.05D, 0.0D);
		}
		this.mod.scheduler().later(() -> beam.forEach(Displays::remove), 12L);

		spawnCircle(level, end, radius, HOLY);
		Fx.lightning(level, end, true);

		int hits = 0;
		for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
				new AABB(start, end).inflate(radius), e -> e != player && e.isAlive())) {
			double amount = damage;
			if (entity instanceof ServerPlayer victim && this.mod.factions().isVampire(victim)) {
				amount += vampireBonus;
			}
			TrueDamage.apply(entity, amount, player, false);
			entity.setRemainingFireTicks(fireTicks);
			Fx.simple(level, "FLAME", entity.position().add(0.0D, 1.0D, 0.0D), 15, 0.3D, 0.4D, 0.3D, 0.05D);
			hits++;
		}
		Messaging.actionBar(player, "<gold>Holy Lance struck <white>" + hits + "</white> target(s)");
	}

	private void spawnCircle(ServerLevel level, Vec3 center, double radius, int color) {
		int points = (int) (radius * 16.0D);
		for (int i = 0; i < points; i++) {
			double angle = Math.toRadians(360.0D / points * i);
			Vec3 point = center.add(Math.cos(angle) * radius, 0.1D, Math.sin(angle) * radius);
			Fx.dust(level, point, color, 1.2F, 1, 0.0D, 0.0D, 0.0D);
		}
	}

	// ---------------------------------------------------------- scorching blade

	private void scorchingBlade(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		int cooldown = ctx.cfg("abilities.hyperion.scorching_cooldown", 30);
		if (!ctx.gate(KEY_SCORCH, "Scorching Blade")) {
			return;
		}
		ctx.startCooldown(KEY_SCORCH, cooldown);
		CooldownBars.show(player, KEY_SCORCH, "Scorching Blade", BossEvent.BossBarColor.RED, cooldown);

		double damage = ctx.cfgd("abilities.hyperion.scorching_blade_damage", 6.0D);
		double hitbox = ctx.cfgd("abilities.hyperion.scorching_hitbox", 1.5D);
		double radius = ctx.cfgd("abilities.hyperion.scorching_radius", 5.0D);
		int lavaTicks = ctx.cfg("abilities.hyperion.scorching_lava_duration", 40);
		Motion.recordDash(player);
		Motion.launch(player, 2.2D, 0.15D);
		Fx.sound(level, player.position(), "ENTITY_BLAZE_SHOOT", 1.0F, 0.8F);
		Messaging.actionBar(player, "<gold><bold>SCORCHING BLADE!");

		final java.util.Set<java.util.UUID> cut = new java.util.HashSet<>();
		final List<BlockPos> lava = new ArrayList<>();
		this.mod.scheduler().timer(() -> {
			if (player.isRemoved()) {
				return;
			}
			Vec3 center = player.position();
			Fx.simple(level, "FLAME", center.add(0.0D, 1.0D, 0.0D), 12, 0.35D, 0.35D, 0.35D, 0.06D);
			Fx.dust(level, center.add(0.0D, 1.0D, 0.0D), FIRE, 1.2F, 8, 0.3D, 0.3D, 0.3D);
			for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
					player.getBoundingBox().inflate(hitbox), e -> e != player && e.isAlive())) {
				if (!cut.add(entity.getUUID())) {
					continue;
				}
				TrueDamage.apply(entity, damage, player, false);
				entity.setRemainingFireTicks(100);
				Fx.sound(level, entity.position(), "ENTITY_PLAYER_ATTACK_SWEEP", 1.0F, 1.1F);
			}
			if (ctx.canDestroyTerrain()) {
				BlockPos below = BlockPos.containing(center).below();
				if (level.getBlockState(below).isAir() || level.getBlockState(below).is(Blocks.FIRE)) {
					BlockPos floor = BlockPos.containing(center.below(1.0D));
					if (!level.getBlockState(floor.below()).isAir() && level.getBlockState(floor).isAir() && !lava.contains(floor)) {
						level.setBlock(floor, Blocks.LAVA.defaultBlockState(), 3);
						lava.add(floor);
					}
				}
			}
		}, 0L, 1L).cancelAfter(12L);

		this.mod.scheduler().later(() -> {
			Vec3 impact = player.position();
			Fx.sound(level, impact, "ENTITY_GENERIC_EXPLODE", 1.0F, 0.7F);
			Fx.simple(level, "LAVA", impact, 40, radius * 0.3D, 0.4D, radius * 0.3D, 0.05D);
			spawnCircle(level, impact, radius, FIRE);
			for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
					new AABB(impact, impact).inflate(radius), e -> e != player && e.isAlive())) {
				TrueDamage.apply(entity, damage, player, false);
				entity.setRemainingFireTicks(100);
				Vec3 away = entity.position().subtract(impact).normalize().scale(0.7D);
				entity.setDeltaMovement(away.x, 0.4D, away.z);
				entity.hurtMarked = true;
			}
			// Revert the scorch lava exactly like the plugin did.
			this.mod.scheduler().later(() -> lava.forEach(pos -> {
				if (level.isLoaded(pos) && level.getBlockState(pos).is(Blocks.LAVA)) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
					Fx.sound(level, Vec3.atCenterOf(pos), "BLOCK_FIRE_EXTINGUISH", 0.6F, 1.0F);
				}
			}), lavaTicks);
		}, 12L);
	}
}
