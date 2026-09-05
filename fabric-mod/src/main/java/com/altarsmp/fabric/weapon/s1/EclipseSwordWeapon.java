package com.altarsmp.fabric.weapon.s1;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.AbilityContext;
import com.altarsmp.fabric.ability.AbilityTracker;
import com.altarsmp.fabric.ability.CooldownBars;
import com.altarsmp.fabric.ability.Displays;
import com.altarsmp.fabric.ability.Effects;
import com.altarsmp.fabric.ability.Motion;
import com.altarsmp.fabric.ability.TrueDamage;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.weapon.WeaponBehavior;

/**
 * Eclipse Sword - port of {@code com.altarsmp.weapons.EclipseSwordWeapon}.
 *
 * <p>The blade has two forms decided by the world clock, exactly like the
 * original ({@code isNight} = world time between 13000 and 23999):</p>
 * <ul>
 *   <li><b>Solar Form (day)</b> - <b>Solar Inferno</b> (F, 35s): a sphere of fire
 *       around the holder that ends in a real explosion. <b>Solar Leap</b>
 *       (Shift+F, 15-30s): launch where you look and deal area damage on
 *       landing.</li>
 *   <li><b>Lunar Form (night)</b> - <b>Moonlit Stars</b> (F,
 *       {@code abilities.eclipse.moonlit_stars.cooldown}s): a piercing ray of
 *       starlight dealing {@code moonlit_stars.damage} true damage to the first
 *       entity hit, out to {@code moonlit_stars.range}. <b>Moonlit Shadows</b>
 *       (Shift+F, {@code moonlit_shadows.cooldown}s): true invisibility for
 *       {@code moonlit_shadows.duration}s, broken by attacking.</li>
 * </ul>
 */
public final class EclipseSwordWeapon implements WeaponBehavior {

	static final String KEY_INFERNO = "eclipse_solar_inferno";
	static final String KEY_LEAP = "eclipse_solar_leap";
	static final String KEY_STARS = "eclipse_moonlit_stars";
	static final String KEY_SHADOWS = "eclipse_moonlit_shadows";

	private static final int SOLAR = 0xFFB347;
	private static final int LUNAR = 0x9AD7FF;

	private final AltarSMPMod mod;
	private final Map<UUID, Long> cloaked = new HashMap<>();

	public EclipseSwordWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "eclipsesword";
	}

	@Override
	public String displayName() {
		return "Eclipse Sword";
	}

	@Override
	public List<String> configFields() {
		return List.of("Moonlit Stars Cooldown (s)", "Moonlit Stars Damage", "Moonlit Shadows Cooldown (s)",
				"Moonlit Shadows Duration (s)");
	}

	/** {@code EclipseSwordWeapon#isNight}: world time 13000..23999. */
	public boolean isNight(ServerPlayer player) {
		long time = player.level().getDayTime() % 24000L;
		return time >= 13000L && time <= 23999L;
	}

	@Override
	public void onTick(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		long tick = this.mod.scheduler().currentTick();
		boolean night = isNight(player);
		int color = night ? LUNAR : SOLAR;
		if (tick % 6L == 0L) {
			Vec3 center = player.position().add(0.0D, 1.1D, 0.0D);
			double angle = Math.toRadians(tick * (night ? 10.0D : 18.0D));
			Fx.dust(ctx.level(), center.add(Math.cos(angle) * 0.7D, 0.0D, Math.sin(angle) * 0.7D), color, 0.8F, 1, 0.02D, 0.02D, 0.02D);
		}
		if (tick % 20L == 0L && night) {
			Effects.apply(player, "NIGHT_VISION", 400, 0, true, false, false);
		}
		Long until = this.cloaked.get(player.getUUID());
		if (until != null && until <= System.currentTimeMillis()) {
			endCloak(player);
		}
	}

	@Override
	public void onPrimary(AbilityContext ctx) {
		if (isNight(ctx.player())) {
			moonlitStars(ctx);
		} else {
			solarInferno(ctx);
		}
	}

	@Override
	public void onSecondary(AbilityContext ctx) {
		if (isNight(ctx.player())) {
			moonlitShadows(ctx);
		} else {
			solarLeap(ctx);
		}
	}

	// -------------------------------------------------------------- solar form

	private void solarInferno(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		int cooldown = ctx.cfg("abilities.eclipse.solar_inferno.cooldown", 35);
		if (!ctx.gate(KEY_INFERNO, "Solar Inferno")) {
			return;
		}
		ctx.startCooldown(KEY_INFERNO, cooldown);
		CooldownBars.show(player, KEY_INFERNO, "Solar Inferno", BossEvent.BossBarColor.YELLOW, cooldown);
		double radius = ctx.cfgd("abilities.eclipse.solar_inferno.radius", 6.0D);
		double damage = ctx.cfgd("abilities.eclipse.solar_inferno.damage", 8.0D);
		Vec3 center = player.position();

		Messaging.actionBar(player, "<gold><bold>SOLAR INFERNO!");
		Fx.sound(level, center, "ENTITY_BLAZE_AMBIENT", 1.2F, 0.7F);
		Fx.sound(level, center, "BLOCK_FIRE_AMBIENT", 1.4F, 0.6F);
		final int[] grown = {0};
		this.mod.scheduler().timer(() -> {
			grown[0]++;
			double current = radius * grown[0] / 8.0D;
			int points = (int) (current * 12.0D);
			for (int i = 0; i < points; i++) {
				double angle = Math.toRadians(360.0D / points * i);
				Vec3 point = center.add(Math.cos(angle) * current, 0.8D, Math.sin(angle) * current);
				Fx.simple(level, "FLAME", point, 2, 0.08D, 0.1D, 0.08D, 0.02D);
				Fx.dust(level, point, SOLAR, 1.0F, 1, 0.0D, 0.0D, 0.0D);
			}
		}, 0L, 2L).cancelAfter(16L);

		this.mod.scheduler().later(() -> {
			Fx.sound(level, center, "ENTITY_GENERIC_EXPLODE", 1.6F, 0.7F);
			Fx.simple(level, "EXPLOSION_EMITTER", center, 3, radius * 0.3D, 1.0D, radius * 0.3D, 0.0D);
			Fx.simple(level, "LAVA", center, 60, radius * 0.4D, 1.0D, radius * 0.4D, 0.05D);
			level.explode(null, center.x, center.y + 1.0D, center.z, (float) Math.min(6.0F, radius * 0.6F),
					ctx.canDestroyTerrain() ? ServerLevel.ExplosionInteraction.STANDARD : ServerLevel.ExplosionInteraction.NONE);
			for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
					new AABB(center, center).inflate(radius), e -> e != player && e.isAlive())) {
				TrueDamage.apply(entity, damage, player, false);
				entity.setRemainingFireTicks(120);
				Vec3 away = entity.position().subtract(center).normalize().scale(1.1D);
				entity.setDeltaMovement(away.x, 0.55D, away.z);
				entity.hurtMarked = true;
			}
		}, 16L);
	}

	private void solarLeap(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		int min = ctx.cfg("abilities.eclipse.solar_leap.cooldown_min", 15);
		int max = ctx.cfg("abilities.eclipse.solar_leap.cooldown_max", 30);
		if (!ctx.gate(KEY_LEAP, "Solar Leap")) {
			return;
		}
		int cooldown = max <= min ? min : min + ctx.random().nextInt(max - min + 1);
		ctx.startCooldown(KEY_LEAP, cooldown);
		CooldownBars.show(player, KEY_LEAP, "Solar Leap (" + cooldown + "s)", BossEvent.BossBarColor.YELLOW, cooldown);
		AbilityTracker.recordLeap(player);
		Motion.launch(player, ctx.cfgd("abilities.eclipse.solar_leap.velocity", 2.6D), 0.9D);
		Fx.sound(level, player.position(), "ENTITY_ENDER_DRAGON_FLAP", 1.0F, 1.2F);
		Messaging.actionBar(player, "<gold>Solar Leap - land to scorch");

		final boolean[] landed = {false};
		this.mod.scheduler().timer(() -> {
			if (player.isRemoved()) {
				return;
			}
			Fx.simple(level, "FLAME", player.position(), 6, 0.3D, 0.3D, 0.3D, 0.05D);
			Fx.dust(level, player.position(), SOLAR, 1.0F, 3, 0.2D, 0.2D, 0.2D);
			if (player.onGround() && !landed[0]) {
				landed[0] = true;
				double radius = ctx.cfgd("abilities.eclipse.solar_leap.landing_radius", 5.0D);
				double damage = ctx.cfgd("abilities.eclipse.solar_leap.landing_damage", 7.0D);
				Vec3 center = player.position();
				Fx.sound(level, center, "ENTITY_GENERIC_EXPLODE", 1.2F, 0.8F);
				Fx.simple(level, "EXPLOSION", center, 2, radius * 0.3D, 0.4D, radius * 0.3D, 0.0D);
				for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
						new AABB(center, center).inflate(radius), e -> e != player && e.isAlive())) {
					TrueDamage.apply(entity, damage, player, false);
					entity.setRemainingFireTicks(80);
					Vec3 away = entity.position().subtract(center).normalize();
					entity.setDeltaMovement(away.x * 0.9D, 0.5D, away.z * 0.9D);
					entity.hurtMarked = true;
				}
			}
		}, 0L, 2L).cancelAfter(200L);
	}

	// -------------------------------------------------------------- lunar form

	private void moonlitStars(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		int cooldown = ctx.cfg("abilities.eclipse.moonlit_stars.cooldown", 30);
		boolean revamp = ctx.cfgb("eclipse-revamp.enabled", true);
		String abilityKey = revamp ? "eclipse_starfall" : KEY_STARS;
		String abilityLabel = revamp ? "Starfall" : "Moonlit Stars";
		if (revamp) {
			cooldown = ctx.cfg("eclipse-revamp.starfall.cooldown", 60);
		}
		if (!ctx.gate(abilityKey, abilityLabel)) {
			return;
		}
		ctx.startCooldown(abilityKey, cooldown);
		CooldownBars.show(player, abilityKey, abilityLabel, BossEvent.BossBarColor.BLUE, cooldown);
		if (revamp) {
			starfall(ctx);
			return;
		}

		double range = ctx.cfg("abilities.eclipse.moonlit_stars.range", 50);
		double damage = ctx.cfgd("abilities.eclipse.moonlit_stars.damage", 6.0D);
		Vec3 direction = player.getLookAngle().normalize();
		Vec3 start = player.getEyePosition();
		Fx.sound(level, start, "BLOCK_AMETHYST_CLUSTER_BREAK", 1.0F, 1.6F);
		Fx.sound(level, start, "ENTITY_ENDERMAN_TELEPORT", 0.6F, 1.8F);

		LivingEntity struck = null;
		Vec3 end = start.add(direction.scale(range));
		for (double travelled = 1.0D; travelled <= range; travelled += 1.0D) {
			Vec3 point = start.add(direction.scale(travelled));
			Fx.dust(level, point, LUNAR, 1.1F, 3, 0.05D, 0.05D, 0.05D);
			Fx.simple(level, "END_ROD", point, 1, 0.02D, 0.02D, 0.02D, 0.0D);
			Fx.simple(level, "STAR", point, 1, 0.02D, 0.02D, 0.02D, 0.0D);
			if (!level.getBlockState(net.minecraft.core.BlockPos.containing(point)).getCollisionShape(level,
					net.minecraft.core.BlockPos.containing(point)).isEmpty()) {
				end = point;
				break;
			}
			List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class,
					new AABB(point, point).inflate(1.0D), e -> e != player && e.isAlive());
			if (!candidates.isEmpty()) {
				struck = candidates.get(0);
				end = point;
				break;
			}
		}
		Display.ItemDisplay star = Displays.item(level, end, new ItemStack(Items.NETHER_STAR), 0.7F);
		Displays.bright(star);
		this.mod.scheduler().later(() -> Displays.remove(star), 20L);
		Fx.dust(level, end, LUNAR, 1.8F, 40, 0.6D, 0.6D, 0.6D);
		if (struck != null) {
			TrueDamage.apply(struck, damage, player, false);
			Effects.apply(struck, "GLOWING", 100, 0);
			Effects.apply(struck, "SLOWNESS", 60, 1);
			Fx.sound(level, struck.position(), "ENTITY_PLAYER_ATTACK_CRIT", 1.0F, 1.4F);
			Messaging.actionBar(player, "<aqua>Moonlit Stars pierced <white>" + struck.getName().getString() + "</white>.");
		}
	}

	/**
	 * {@code eclipse-revamp.starfall}: a barrage of {@code impact-count} star
	 * strikes scattered over {@code radius} blocks around the aimed point.
	 * Anything inside {@code inner-radius} takes {@code inner-damage}, the rest
	 * takes {@code outer-damage}; the centre is carved to
	 * {@code center-crater-radius} x {@code center-crater-depth} when
	 * {@code abilities.terrain_destruction} allows it.
	 */
	private void starfall(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		int impacts = ctx.cfg("eclipse-revamp.starfall.impact-count", 40);
		double radius = ctx.cfgd("eclipse-revamp.starfall.radius", 40.0D);
		double innerRadius = ctx.cfgd("eclipse-revamp.starfall.inner-radius", 20.0D);
		double innerDamage = ctx.cfgd("eclipse-revamp.starfall.inner-damage", 8.0D);
		double outerDamage = ctx.cfgd("eclipse-revamp.starfall.outer-damage", 4.0D);
		int craterRadius = ctx.cfg("eclipse-revamp.starfall.center-crater-radius", 15);
		int craterDepth = ctx.cfg("eclipse-revamp.starfall.center-crater-depth", 10);

		Vec3 aim = StrikerWeapon.aimTarget(ctx, 80.0D);
		Vec3 center = aim == null ? player.position().add(player.getLookAngle().normalize().scale(20.0D)) : aim;

		Messaging.actionBar(player, "<aqua><bold>STARFALL!");
		Fx.sound(level, center, "BLOCK_AMETHYST_BLOCK_RESONATE", 1.4F, 0.8F);
		Fx.sound(level, center, "custom/nuke_incoming", 0.5F, 1.6F);

		for (int i = 0; i < impacts; i++) {
			final int index = i;
			final double angle = ctx.random().nextDouble() * Math.PI * 2.0D;
			final double distance = Math.sqrt(ctx.random().nextDouble()) * radius;
			this.mod.scheduler().later(() -> {
				Vec3 point = center.add(Math.cos(angle) * distance, 0.0D, Math.sin(angle) * distance);
				BlockPos column = net.minecraft.core.BlockPos.containing(point);
				// The star descends from above, then detonates on arrival.
				for (double y = 60.0D; y > 0.0D; y -= 8.0D) {
					Fx.dust(level, Vec3.atBottomCenterOf(column.above((int) y)), LUNAR, 1.4F, 3, 0.2D, 0.2D, 0.2D);
				}
				Fx.lightning(level, Vec3.atBottomCenterOf(column), true);
				Fx.simple(level, "STAR", Vec3.atBottomCenterOf(column), 20, 0.8D, 1.2D, 0.8D, 0.1D);
				Fx.simple(level, "END_ROD", Vec3.atBottomCenterOf(column), 15, 0.6D, 0.8D, 0.6D, 0.05D);
				Fx.sound(level, Vec3.atBottomCenterOf(column), "ENTITY_GENERIC_EXPLODE", 0.6F, 1.5F);
				for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
						new AABB(column).inflate(3.0D), e -> e != player && e.isAlive())) {
					boolean inner = entity.position().distanceTo(center) <= innerRadius;
					TrueDamage.apply(entity, inner ? innerDamage : outerDamage, player, false);
				}
				if (index % 8 == 0 && ctx.canDestroyTerrain()) {
					for (BlockPos pos : net.minecraft.core.BlockPos.betweenClosed(
							column.offset(-1, -1, -1), column.offset(1, 1, 1))) {
						if (level.getBlockState(pos).getDestroySpeed(level, pos) >= 0.0F) {
							level.removeBlock(pos, true);
						}
					}
				}
			}, 4L + index * 2L);
		}

		// Centre crater, carved layer by layer so the server is not stalled.
		if (ctx.canDestroyTerrain()) {
			BlockPos origin = net.minecraft.core.BlockPos.containing(center);
			for (int layer = 0; layer < craterDepth; layer++) {
				final int depth = layer;
				this.mod.scheduler().later(() -> {
					int radiusAtDepth = Math.max(1, craterRadius - depth);
					for (BlockPos pos : net.minecraft.core.BlockPos.betweenClosed(
							origin.offset(-radiusAtDepth, -depth, -radiusAtDepth),
							origin.offset(radiusAtDepth, -depth, radiusAtDepth))) {
						if (level.getBlockState(pos).getDestroySpeed(level, pos) >= 0.0F) {
							level.removeBlock(pos, true);
						}
					}
					Fx.sound(level, Vec3.atBottomCenterOf(origin.below(depth)), "ENTITY_GENERIC_EXPLODE", 0.4F, 0.9F);
				}, impacts * 2L + 8L + depth * 2L);
			}
		}
	}

	private void moonlitShadows(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		int cooldown = ctx.cfg("abilities.eclipse.moonlit_shadows.cooldown", 30);
		int duration = ctx.cfg("abilities.eclipse.moonlit_shadows.duration", 4);
		if (!ctx.gate(KEY_SHADOWS, "Moonlit Shadows")) {
			return;
		}
		ctx.startCooldown(KEY_SHADOWS, cooldown);
		CooldownBars.show(player, KEY_SHADOWS, "Moonlit Shadows", BossEvent.BossBarColor.BLUE, cooldown);
		this.cloaked.put(player.getUUID(), System.currentTimeMillis() + duration * 1000L);
		Effects.apply(player, "INVISIBILITY", duration * 20, 0, false, false, false);
		Fx.sound(ctx.level(), player.position(), "ENTITY_ENDERMAN_TELEPORT", 0.7F, 0.6F);
		Fx.dust(ctx.level(), player.position().add(0.0D, 1.0D, 0.0D), LUNAR, 1.3F, 30, 0.4D, 0.8D, 0.4D);
		Messaging.actionBar(player, "<dark_aqua>You melt into Moonlit Shadows.");
		this.mod.scheduler().later(() -> {
			if (this.cloaked.containsKey(player.getUUID())) {
				endCloak(player);
			}
		}, duration * 20L);
	}

	private void endCloak(ServerPlayer player) {
		if (this.cloaked.remove(player.getUUID()) == null) {
			return;
		}
		Effects.remove(player, "INVISIBILITY");
		CooldownBars.hide(player, KEY_SHADOWS);
		Fx.dust(player.serverLevel(), player.position().add(0.0D, 1.0D, 0.0D), LUNAR, 1.2F, 20, 0.4D, 0.6D, 0.4D);
		Fx.sound(player.serverLevel(), player.position(), "ENTITY_ENDERMAN_TELEPORT", 0.6F, 1.4F);
	}

	/** Attacking breaks the cloak (revealPlayer). */
	@Override
	public void onAttack(AbilityContext ctx, LivingEntity target, float damage, boolean critical) {
		if (this.cloaked.containsKey(ctx.player().getUUID())) {
			endCloak(ctx.player());
			Messaging.send(ctx.player(), "<dark_aqua>Moonlit Shadows broken by your attack.");
		}
	}

	@Override
	public void onPlayerQuit(ServerPlayer player) {
		this.cloaked.remove(player.getUUID());
		CooldownBars.hide(player, KEY_SHADOWS);
		CooldownBars.hide(player, KEY_STARS);
	}

	public boolean isCloaked(ServerPlayer player) {
		Long until = this.cloaked.get(player.getUUID());
		return until != null && until > System.currentTimeMillis();
	}
}
