package com.altarsmp.fabric.weapon.s1;

import java.util.List;

import net.minecraft.server.level.ServerPlayer;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.AbilityContext;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.weapon.WeaponBehavior;

/**
 * Contagion Signal - port of {@code com.altarsmp.weapons.ContagionSignalWeapon}.
 *
 * <p>The original class is deliberately small: the weapon is a role marker for
 * the Pale/Hyperion/Nightpiercer event, so all it does is report the holder's
 * role and let the
 * {@link com.altarsmp.fabric.event.ContagionSignalManager} drive the gameplay.</p>
 */
public final class ContagionSignalWeapon implements WeaponBehavior {

	static final String KEY_SIGNAL = "contagion_signal_beacon";

	private final AltarSMPMod mod;

	public ContagionSignalWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "contagionsignal";
	}

	@Override
	public String displayName() {
		return "Contagion Signal";
	}

	@Override
	public List<String> configFields() {
		return List.of();
	}

	/** {@code ContagionSignalWeapon#getPlayerRole} - the faction role this holder plays. */
	public String getPlayerRole(ServerPlayer player) {
		return this.mod.factions().roleOf(player);
	}

	@Override
	public void onPrimary(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		if (!ctx.gate(KEY_SIGNAL, "Contagion Signal")) {
			return;
		}
		ctx.startCooldown(KEY_SIGNAL, ctx.cfg("abilities.contagion.beacon_cooldown", 30));
		this.mod.contagion().broadcast(player);
		Messaging.actionBar(player, "<light_purple>Contagion Signal broadcast.");
	}

	@Override
	public void onSecondary(AbilityContext ctx) {
		Messaging.send(ctx.player(), "<light_purple>Your role: <white>" + getPlayerRole(ctx.player())
				+ "</white>. Use <yellow>/contagion</yellow> for event status.");
	}

	@Override
	public void onTick(AbilityContext ctx) {
		if (this.mod.scheduler().currentTick() % 40L == 0L) {
			com.altarsmp.fabric.util.Fx.dust(ctx.level(), ctx.player().position().add(0.0D, 1.6D, 0.0D),
					0xC080FF, 0.7F, 2, 0.15D, 0.15D, 0.15D);
		}
	}
}
