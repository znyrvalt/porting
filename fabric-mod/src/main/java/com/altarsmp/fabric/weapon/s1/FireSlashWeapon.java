package com.altarsmp.fabric.weapon.s1;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
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
import com.altarsmp.fabric.ability.TrueDamage;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.weapon.WeaponBehavior;

/**
 * Fire Slash - port of {@code com.altarsmp.weapons.FireSlashWeapon}.
 *
 * <p><b>Passive</b>: every melee hit ignites the victim for 100 ticks and bursts
 * a flame slash at their position.</p>
 *
 * <p><b>Flame Wave</b> (F): a travelling wall of fire block displays pushes out
 * along the player's look vector, dealing 8.0 true damage and igniting everyone
 * it touches. The plugin kept this on a hard 240 tick (12&nbsp;s) cooldown, which
 * the port keeps as the default and lets {@code abilities.fire_slash.cooldown}
 * override.</p>
 */
public final class FireSlashWeapon implements WeaponBehavior {

	static final String KEY_WAVE = "fireslash_wave";
	private static final int FIRE_TICKS = 100;
	private static final double WAVE_DAMAGE = 8.0D;

	private final AltarSMPMod mod;
	private final Map<UUID, Long> waveCooldown = new HashMap<>();

	public FireSlashWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "fire_slash";
	}

	@Override
	public String displayName() {
		return "Fire Slash";
	}

	@Override
	public void onAttack(AbilityContext ctx, LivingEntity target, float damage, boolean critical) {
		ServerLevel level = ctx.level();
		Vec3 pos = target.position().add(0.0D, 1.0D, 0.0D);
		target.setRemainingFireTicks(FIRE_TICKS);
		Fx.simple(level, "FLAME", pos, 20, 0.3D, 0.3D, 0.3D, 0.08D);
		Fx.simple(level, "LAVA", pos, 12, 0.4D, 0.4D, 0.4D, 0.0D);
		Fx.simple(level, "SMOKE", pos, 10, 0.2D, 0.2D, 0.2D, 0.04D);
		Fx.sound(level, pos, "ENTITY_BLAZE_SHOOT", 0.6F, 1.2F);
		Fx.sound(level, pos, "BLOCK_FIRE_EXTINGUISH", 0.5F, 1.5F);
	}

	@Override
	public void onPrimary(AbilityContext ctx) {
		useFlameWave(ctx);
	}

	@Override
	public void onSecondary(AbilityContext ctx) {
		useFlameWave(ctx);
	}

	private void useFlameWave(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		int cooldownSeconds = ctx.cfg("abilities.fire_slash.cooldown", 12);
		Long until = this.waveCooldown.get(player.getUUID());
		if (until != null && until > System.currentTimeMillis()) {
			Messaging.actionBar(player, "<red>Flame Wave <gray>on cooldown: <yellow>"
					+ ((until - System.currentTimeMillis() + 999L) / 1000L) + "s");
			return;
		}
		this.waveCooldown.put(player.getUUID(), System.currentTimeMillis() + cooldownSeconds * 1000L);
		CooldownBars.show(player, KEY_WAVE, "Flame Wave", BossEvent.BossBarColor.YELLOW, cooldownSeconds);
		Fx.sound(level, player.position(), "ITEM_FIRECHARGE_USE", 0.9F, 0.8F);
		Fx.sound(level, player.position(), "BLOCK_FIRE_AMBIENT", 1.0F, 0.5F);
		Messaging.actionBar(player, "<gold><bold>FLAME WAVE!");

		Vec3 direction = new Vec3(player.getLookAngle().x, 0.0D, player.getLookAngle().z).normalize();
		Vec3 origin = player.position().add(direction.scale(1.0D)).add(0.0D, 0.8D, 0.0D);
		double damage = ctx.cfgd("abilities.fire_slash.wave_damage", WAVE_DAMAGE);

		final int[] step = {0};
		this.mod.scheduler().timer(() -> {
			if (player.isRemoved()) {
				return;
			}
			step[0]++;
			Vec3 center = origin.add(direction.scale(step[0] * 3.5D));
			spawnFireDisplay(level, center);
			Fx.simple(level, "FLAME", center, 30, 0.5D, 0.5D, 0.5D, 0.08D);
			Fx.simple(level, "LAVA", center, 12, 0.4D, 0.4D, 0.4D, 0.0D);
			Fx.simple(level, "SOUL_FIRE_FLAME", center, 8, 0.3D, 0.3D, 0.3D, 0.03D);
			Fx.sound(level, center, "ENTITY_BLAZE_SHOOT", 1.0F, 0.7F);
			for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
					new AABB(center, center).inflate(2.5D), entity -> entity != player && entity.isAlive())) {
				TrueDamage.apply(target, damage, player, false);
				target.setRemainingFireTicks(FIRE_TICKS);
				Vec3 away = target.position().subtract(player.position()).normalize();
				target.setDeltaMovement(away.x * 0.6D, 0.35D, away.z * 0.6D);
				target.hurtMarked = true;
			}
			if (!level.getBlockState(net.minecraft.core.BlockPos.containing(center.add(0.0D, -1.0D, 0.0D))).isAir()
					&& ctx.canDestroyTerrain() && ctx.cfgb("abilities.fire_slash.leave_lava", false)) {
				level.setBlock(net.minecraft.core.BlockPos.containing(center.add(0.0D, -1.0D, 0.0D)),
						Blocks.LAVA.defaultBlockState(), 3);
			}
		}, 0L, 2L).cancelAfter(16L);
	}

	private void spawnFireDisplay(ServerLevel level, Vec3 pos) {
		Display.BlockDisplay display = Displays.block(level, pos, Blocks.FIRE.defaultBlockState());
		Displays.bright(display);
		Displays.setScale(display, 2.2F);
		this.mod.scheduler().later(() -> Displays.remove(display), 8L);
		Display.ItemDisplay ember = Displays.item(level, pos.add(0.0D, 0.4D, 0.0D), new ItemStack(Items.BLAZE_POWDER), 0.8F);
		Displays.bright(ember);
		this.mod.scheduler().later(() -> Displays.remove(ember), 10L);
	}

	@Override
	public void onPlayerQuit(ServerPlayer player) {
		this.waveCooldown.remove(player.getUUID());
		CooldownBars.hide(player, KEY_WAVE);
	}
}
