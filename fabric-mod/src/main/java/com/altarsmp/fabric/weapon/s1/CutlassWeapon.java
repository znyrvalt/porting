package com.altarsmp.fabric.weapon.s1;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.AbilityContext;
import com.altarsmp.fabric.ability.CooldownBars;
import com.altarsmp.fabric.ability.Effects;
import com.altarsmp.fabric.ability.TrueDamage;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.weapon.WeaponBehavior;

/**
 * Cutlass - port of {@code com.altarsmp.weapons.CutlassWeapon}.
 *
 * <p><b>Thousand Cuts</b> (F): {@code cuts_slash_count} rapid slashes in a cone
 * in front of the player. Each slash draws a dust arc, plays the sweep sound at a
 * rising pitch and deals {@code cut_damage} true damage to everything within
 * {@code cuts_hit_radius} blocks. Cooldown {@code cuts_cooldown}s.</p>
 *
 * <p><b>Parry</b> (Shift+F): for {@code parry_duration} ticks the player blocks
 * up to {@code parry_block_count} incoming hits. A blocked hit is cancelled
 * outright, the attacker is knocked back ({@code parry_knockback},
 * {@code parry_knockback_y}) and the parrying player takes Slowness
 * {@code parry_slowness_level} for the window. Cooldown {@code parry_cooldown}s.</p>
 */
public final class CutlassWeapon implements WeaponBehavior {

	static final String KEY_CUTS = "cutlass_cuts";
	static final String KEY_PARRY = "cutlass_parry";

	/** {@code Color.fromRGB(100, 200, 255)} - the pale blue of the slash arc. */
	private static final int ARC_BLUE = (100 << 16) | (200 << 8) | 255;
	private static final int WHITE = 0xFFFFFF;

	private final AltarSMPMod mod;
	private final Map<UUID, Parry> parries = new HashMap<>();

	public CutlassWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "cutlass";
	}

	@Override
	public String displayName() {
		return "Cutlass";
	}

	@Override
	public List<String> configFields() {
		return List.of("Cuts Cooldown (s)", "Parry Cooldown (s)", "Cut Damage", "Parry Blocks");
	}

	private record Parry(int blocksLeft, long expiresAt) {
	}

	// ------------------------------------------------------------ thousand cuts

	@Override
	public void onPrimary(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		int cooldown = ctx.cfg("abilities.cutlass.cuts_cooldown", 15);
		int slashCount = ctx.cfg("abilities.cutlass.cuts_slash_count", 10);
		if (!ctx.gate(KEY_CUTS, "Thousand Cuts")) {
			return;
		}
		ctx.startCooldown(KEY_CUTS, cooldown);
		CooldownBars.show(player, KEY_CUTS, "Thousand Cuts", BossEvent.BossBarColor.YELLOW, cooldown);
		Messaging.actionBar(player, "<yellow>Thousand Cuts!");
		Fx.sound(ctx.level(), player.position(), "ENTITY_BREEZE_CHARGE", 1.0F, 1.0F);
		Fx.dust(ctx.level(), player.position().add(0.0D, 1.0D, 0.0D), ARC_BLUE, 1.2F, 25, 0.5D, 0.5D, 0.5D);
		Fx.simple(ctx.level(), "CRIT", player.position().add(0.0D, 1.0D, 0.0D), 15, 0.4D, 0.4D, 0.4D, 0.05D);

		final int[] slash = {0};
		this.mod.scheduler().timer(() -> {
			if (player.isRemoved()) {
				return;
			}
			slash[0]++;
			slashArc(ctx, slash[0]);
		}, 0L, 2L).cancelAfter(slashCount * 2L + 2L);
	}

	private void slashArc(AbilityContext ctx, int index) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		double damage = ctx.cfgd("abilities.cutlass.cut_damage", 2.0D);
		double radius = ctx.cfg("abilities.cutlass.cuts_hit_radius", 3);
		Vec3 direction = player.getLookAngle().normalize();
		Vec3 center = player.getEyePosition();

		// Visual arc: dust points fanned across the swing.
		double spread = 1.2D;
		for (int i = -3; i <= 3; i++) {
			Vec3 side = new Vec3(-direction.z, 0.0D, direction.x).scale(i * spread / 3.0D);
			Vec3 point = center.add(direction.scale(1.0D + index * 0.15D)).add(side).add(0.0D, -0.2D * Math.abs(i), 0.0D);
			Fx.dust(level, point, index % 2 == 0 ? ARC_BLUE : WHITE, 0.9F, 1, 0.0D, 0.0D, 0.0D);
		}
		Fx.sound(level, player.position(), "ENTITY_PLAYER_ATTACK_SWEEP", 1.0F, 1.2F + index * 0.1F);

		Vec3 reach = center.add(direction.scale(radius));
		for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
				new AABB(center, reach).inflate(radius), entity -> entity != player && entity.isAlive())) {
			Vec3 toTarget = target.position().subtract(player.position()).normalize();
			if (toTarget.dot(direction) < 0.35D) {
				continue;
			}
			if (target.position().distanceTo(player.position()) > radius + 1.0D) {
				continue;
			}
			TrueDamage.apply(target, damage, player, true);
			Fx.simple(level, "DAMAGE_INDICATOR", target.position().add(0.0D, 1.0D, 0.0D), 3, 0.2D, 0.2D, 0.2D, 0.0D);
		}
	}

	// -------------------------------------------------------------------- parry

	@Override
	public void onSecondary(AbilityContext ctx) {
		useParry(ctx);
	}

	private void useParry(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		int cooldown = ctx.cfg("abilities.cutlass.parry_cooldown", 30);
		int durationTicks = ctx.cfg("abilities.cutlass.parry_duration", 7);
		int blocks = ctx.cfg("abilities.cutlass.parry_block_count", 3);
		if (!ctx.gate(KEY_PARRY, "Parry")) {
			return;
		}
		ctx.startCooldown(KEY_PARRY, cooldown);
		CooldownBars.show(player, KEY_PARRY, "Parry", BossEvent.BossBarColor.WHITE, cooldown);
		this.parries.put(player.getUUID(), new Parry(blocks, System.currentTimeMillis() + durationTicks * 50L));
		Effects.apply(player, "SLOWNESS", durationTicks, ctx.cfg("abilities.cutlass.parry_slowness_level", 1), false, false, false);
		Fx.sound(ctx.level(), player.position(), "ITEM_SHIELD_BLOCK", 1.0F, 0.8F);
		Fx.dust(ctx.level(), player.position().add(0.0D, 1.0D, 0.0D), WHITE, 0.8F, 8, 0.3D, 0.3D, 0.3D);
		Messaging.actionBar(player, "<white><bold>PARRY!</bold> <gray>" + blocks + " hit(s) blocked");
		this.mod.scheduler().later(() -> this.parries.remove(player.getUUID()), durationTicks);
	}

	@Override
	public boolean onDamaged(AbilityContext ctx, DamageSource source, float amount, LivingEntity attacker) {
		ServerPlayer player = ctx.player();
		Parry parry = this.parries.get(player.getUUID());
		if (parry == null || System.currentTimeMillis() > parry.expiresAt()) {
			return false;
		}
		if (parry.blocksLeft() <= 0) {
			this.parries.remove(player.getUUID());
			return false;
		}
		this.parries.put(player.getUUID(), new Parry(parry.blocksLeft() - 1, parry.expiresAt()));
		Fx.sound(ctx.level(), player.position(), "ITEM_SHIELD_BLOCK", 1.0F, 1.0F);
		Fx.sound(ctx.level(), player.position(), "BLOCK_ANVIL_LAND", 1.0F, 1.2F);
		Fx.dust(ctx.level(), player.position().add(0.0D, 1.0D, 0.0D), WHITE, 1.0F, 12, 0.4D, 0.4D, 0.4D);
		Messaging.actionBar(player, "<white><bold>PARRIED!</bold> <gray>" + (parry.blocksLeft() - 1) + " block(s) left");
		if (attacker != null) {
			Vec3 away = attacker.position().subtract(player.position()).normalize();
			Vec3 knockback = new Vec3(away.x * ctx.cfgd("abilities.cutlass.parry_knockback", 1.8D),
					ctx.cfgd("abilities.cutlass.parry_knockback_y", 0.4D),
					away.z * ctx.cfgd("abilities.cutlass.parry_knockback", 1.8D));
			attacker.setDeltaMovement(knockback);
			attacker.hurtMarked = true;
			attacker.fallDistance = 0.0F;
			Fx.sound(ctx.level(), attacker.position(), "ENTITY_PLAYER_ATTACK_STRONG", 1.0F, 0.9F);
			if (attacker instanceof ServerPlayer attackerPlayer) {
				Messaging.actionBar(attackerPlayer, "<red>Your attack was parried!");
			}
		}
		Fx.sound(ctx.level(), player.position(), "ENTITY_EXPERIENCE_ORB_PICKUP", 0.5F, 1.5F);
		return true;
	}

	@Override
	public void onPlayerQuit(ServerPlayer player) {
		this.parries.remove(player.getUUID());
		CooldownBars.hide(player, KEY_PARRY);
		CooldownBars.hide(player, KEY_CUTS);
	}

	public boolean isParrying(ServerPlayer player) {
		Parry parry = this.parries.get(player.getUUID());
		return parry != null && System.currentTimeMillis() <= parry.expiresAt() && parry.blocksLeft() > 0;
	}
}
