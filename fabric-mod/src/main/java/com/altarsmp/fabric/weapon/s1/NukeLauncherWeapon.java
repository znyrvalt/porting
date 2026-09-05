package com.altarsmp.fabric.weapon.s1;

import java.util.List;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.AbilityContext;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.weapon.WeaponBehavior;

/**
 * Nuke Launcher - port of {@code com.altarsmp.weapons.NukeLauncherWeapon}.
 *
 * <p><b>Nuclear Strike</b> (F): paints a target up to
 * {@code abilities.nukelauncher.target_range} blocks away and hands it to
 * {@link com.altarsmp.fabric.event.NukeZoneManager}, which runs the shared
 * countdown ({@code countdown}s of boss-bar warnings plus
 * {@code custom/nuke_incoming}), the {@code custom/nuke_explosion} blast, the
 * {@code flash_range}/{@code flash_duration} blindness flash, the
 * {@code kill_radius} instant kill, the {@code damage_radius} outer damage and
 * the layered {@code crater_radius} carve at {@code layers_per_tick} blocks per
 * tick (only when {@code abilities.terrain_destruction} allows it).</p>
 *
 * <p><b>Abort Strike</b> (Shift+F): cancels a nuke the holder launched, provided
 * it is still counting down.</p>
 */
public final class NukeLauncherWeapon implements WeaponBehavior {

	static final String KEY_STRIKE = "nuke_launcher_strike";

	private final AltarSMPMod mod;

	public NukeLauncherWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "nukelauncher";
	}

	@Override
	public String displayName() {
		return "Nuke Launcher";
	}

	@Override
	public List<String> configFields() {
		return List.of("Cooldown (s)", "Countdown (s)", "Target Range (blocks)", "Crater Radius (blocks)",
				"Kill Radius (blocks)", "Damage Radius (blocks)");
	}

	@Override
	public void onPrimary(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		int cooldown = ctx.cfg("abilities.nukelauncher.cooldown", 300);
		double range = ctx.cfgd("abilities.nukelauncher.target_range", 200.0D);
		if (!ctx.gate(KEY_STRIKE, "Nuclear Strike")) {
			return;
		}
		Vec3 target = StrikerWeapon.aimTarget(ctx, range);
		if (target == null) {
			Messaging.send(player, "<red>No surface in range to target (" + (int) range + " blocks).");
			return;
		}
		if (!this.mod.nukeZone().requestStrike(player, target, "nukelauncher")) {
			Messaging.send(player, "<red>That area is already being struck.");
			return;
		}
		ctx.startCooldown(KEY_STRIKE, cooldown);
		com.altarsmp.fabric.ability.CooldownBars.show(player, KEY_STRIKE, "Nuclear Strike",
				net.minecraft.world.BossEvent.BossBarColor.RED, cooldown);
	}

	@Override
	public void onSecondary(AbilityContext ctx) {
		if (this.mod.nukeZone().abort(ctx.player())) {
			Messaging.send(ctx.player(), "<yellow>Nuclear strike aborted.");
		} else {
			Messaging.send(ctx.player(), "<red>You have no strike in progress to abort.");
		}
	}

	@Override
	public void onPlayerQuit(ServerPlayer player) {
		this.mod.nukeZone().onOwnerQuit(player);
	}
}
