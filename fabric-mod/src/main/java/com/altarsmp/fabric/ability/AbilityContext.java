package com.altarsmp.fabric.ability;

import java.util.Optional;

import javax.annotation.Nullable;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.config.AltarConfig;
import com.altarsmp.fabric.config.ConfigView;
import com.altarsmp.fabric.data.PlayerRecord;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.util.TextFx;
import com.altarsmp.fabric.util.TickScheduler;

/**
 * Everything one weapon ability needs, gathered in one place: the acting player,
 * the level, the weapon stack, config lookups with the original's default
 * values, cooldowns, messages, particles, sounds, true damage and persistence.
 *
 * <p>Weapon behaviours stay readable because of this facade - a 40 line ability
 * in the port is the same 40 lines the plugin had, minus Bukkit boilerplate.</p>
 */
public final class AbilityContext {

	private final AltarSMPMod mod;
	private final ServerPlayer player;
	private final ServerLevel level;
	private final ItemStack weapon;
	private final String weaponId;
	/** Season of the behaviour this context was built for - decides which config file wins. */
	private final int season;

	public AbilityContext(AltarSMPMod mod, ServerPlayer player, ItemStack weapon, String weaponId) {
		this.mod = mod;
		this.player = player;
		this.level = player.level() instanceof ServerLevel serverLevel ? serverLevel : player.serverLevel();
		this.weapon = weapon;
		this.weaponId = weaponId;
		com.altarsmp.fabric.weapon.WeaponBehavior behavior =
				mod.weapons() == null ? null : mod.weapons().get(weaponId);
		this.season = behavior == null ? 1 : behavior.season();
	}

	public AltarSMPMod mod() { return this.mod; }
	public ServerPlayer player() { return this.player; }
	public ServerLevel level() { return this.level; }
	public MinecraftServer server() { return this.level.getServer(); }
	public ItemStack weapon() { return this.weapon; }
	public String weaponId() { return this.weaponId; }
	public RandomSource random() { return this.level.getRandom(); }
	public Vec3 pos() { return this.player.position(); }
	public Vec3 eye() { return this.player.getEyePosition(); }
	public Vec3 look() { return this.player.getLookAngle(); }

	/** Persistent per-player record (kill counters, morph locks, trust, ...). */
	public PlayerRecord record() {
		return this.mod.store().player(this.player.getUUID());
	}

	// ------------------------------------------------------------------- config

	/**
	 * Season 1 code read {@code config.yml} and season 2 code read {@code s2.yml}
	 * (the plugin's {@code r#n()}), so the same lookup has to prefer a different
	 * document per season. The two files overlap almost completely and agree
	 * everywhere except {@code abilities.withersymbiote.rampage.lifesteal} and
	 * {@code bow-of-deception.enabled}, where {@code s2.yml} is the one the season 2
	 * classes actually saw. {@link AltarConfig#s2()} already falls back to the merged
	 * main document, so shared keys resolve either way.
	 */
	private ConfigView view(String path) {
		AltarConfig config = this.mod.config();
		if (this.season == 2) {
			return config.s2();
		}
		return config.main().contains(path) ? config.main() : config.s2();
	}

	public int cfg(String path, int def) { return this.view(path).getInt(path, def); }
	public double cfgd(String path, double def) { return this.view(path).getDouble(path, def); }
	public float cfgf(String path, float def) { return this.view(path).getFloat(path, def); }
	public boolean cfgb(String path, boolean def) { return this.view(path).getBoolean(path, def); }
	public String cfgs(String path, String def) { return this.view(path).getString(path, def); }
	public boolean cfgHas(String path) { return this.view(path).contains(path); }

	/**
	 * {@code abilities.terrain_destruction} - the master switch the config uses to
	 * keep block-destroying abilities (Striker's column, the Nuke crater, Echo's
	 * sonic crater, Vulcan's magma crater) from altering the world. When it is
	 * false the visuals and the damage stay, the blocks do not.
	 */
	public boolean canDestroyTerrain() {
		return cfgb("abilities.terrain_destruction", true);
	}

	// ---------------------------------------------------------------- cooldowns

	/** {@code "<weapon>.<ability>"} - the key format the plugin's cooldown manager used. */
	public String key(String ability) {
		return this.weaponId + "." + ability;
	}

	public boolean ready(String ability) {
		return !this.mod.cooldowns().isOnCooldown(this.player, this.key(ability));
	}

	public long remainingMillis(String ability) {
		return this.mod.cooldowns().getRemainingCooldown(this.player, this.key(ability));
	}

	public String remaining(String ability) {
		return this.mod.cooldowns().getRemainingCooldownFormatted(this.player, this.key(ability));
	}

	/** Starts a cooldown; returns {@code false} if it was still running. */
	public boolean startCooldown(String ability, int seconds) {
		if (!this.ready(ability)) {
			return false;
		}
		this.mod.cooldowns().setCooldownSeconds(this.player, this.key(ability), seconds);
		return true;
	}

	public boolean startCooldownMillis(String ability, long millis) {
		if (!this.ready(ability)) {
			return false;
		}
		this.mod.cooldowns().setCooldown(this.player, this.key(ability), millis);
		return true;
	}

	public void clearCooldown(String ability) {
		this.mod.cooldowns().clearCooldown(this.player, this.key(ability));
	}

	/**
	 * Cooldown gate used by nearly every ability: when the ability is on cooldown
	 * it tells the player how long is left and returns {@code false}.
	 */
	public boolean gate(String ability, String label) {
		if (this.ready(ability)) {
			return true;
		}
		Messaging.actionBar(this.player, "<red>" + label + " <gray>on cooldown: <yellow>" + this.remaining(ability));
		return false;
	}

	public boolean gate(String ability, String label, int seconds) {
		if (!this.gate(ability, label)) {
			return false;
		}
		this.mod.cooldowns().setCooldownSeconds(this.player, this.key(ability), seconds);
		return true;
	}

	// ----------------------------------------------------------------- messages

	public void say(String markup) { Messaging.send(this.player, markup); }
	public void actionBar(String markup) { Messaging.actionBar(this.player, markup); }
	public void title(String title, String subtitle) { Messaging.title(this.player, title, subtitle, 10, 70, 20); }
	public void title(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
		Messaging.title(this.player, title, subtitle, fadeIn, stay, fadeOut);
	}
	public void broadcast(String markup) { Messaging.broadcast(this.server(), markup); }

	// ----------------------------------------------------------------------- fx

	public void sound(String soundName, float volume, float pitch) {
		Fx.sound(this.level, this.player.position(), com.altarsmp.fabric.util.GameRegistry.soundId(soundName), volume, pitch);
	}

	public void sound(Identifier soundId, float volume, float pitch) {
		Fx.sound(this.level, this.player.position(), soundId, volume, pitch);
	}

	public void soundAt(Vec3 pos, String soundName, float volume, float pitch) {
		Fx.sound(this.level, pos, com.altarsmp.fabric.util.GameRegistry.soundId(soundName), volume, pitch);
	}

	public void particles(ParticleType<?> type, Vec3 pos, int count, double dx, double dy, double dz, double speed) {
		Fx.simple(this.level, type, pos, count, dx, dy, dz, speed);
	}

	public void particles(String bukkitParticle, Vec3 pos, int count, double dx, double dy, double dz, double speed) {
		ParticleType<?> type = com.altarsmp.fabric.util.GameRegistry.particle(bukkitParticle);
		if (type == null) {
			return;
		}
		Fx.simple(this.level, type, pos, count, dx, dy, dz, speed);
	}

	public void dust(Vec3 pos, int rgb, float size, int count) {
		Fx.dust(this.level, pos, rgb, size, count, 0.3D, 0.3D, 0.3D);
	}

	public void lightning(Vec3 pos, boolean cosmetic) {
		Fx.lightning(this.level, pos, cosmetic);
	}

	// -------------------------------------------------------------------- combat

	public void trueDamage(LivingEntity target, double amount) {
		TrueDamage.apply(target, amount, this.player, false);
	}

	public void trueDamage(LivingEntity target, double amount, boolean bypassImmunity) {
		TrueDamage.apply(target, amount, this.player, bypassImmunity);
	}

	/** Victim-immunity / attacker-cooldown gate for player targets. */
	public boolean abilityAllowedOn(@Nullable ServerPlayer victim) {
		return victim == null || this.mod.immunity().allowAbilityHit(this.player, victim);
	}

	public void effect(LivingEntity target, String bukkitEffect, int durationTicks, int amplifier) {
		Effects.apply(target, bukkitEffect, durationTicks, amplifier);
	}

	public void effect(String bukkitEffect, int durationTicks, int amplifier) {
		Effects.apply(this.player, bukkitEffect, durationTicks, amplifier);
	}

	public void removeEffect(String bukkitEffect) {
		Effects.remove(this.player, bukkitEffect);
	}

	// ------------------------------------------------------------------ timing

	public TickScheduler.Task later(Runnable body, long delayTicks) {
		return this.mod.scheduler().later(body, delayTicks);
	}

	public TickScheduler.Task timer(Runnable body, long delayTicks, long periodTicks) {
		return this.mod.scheduler().timer(body, delayTicks, periodTicks);
	}

	public Optional<ItemStack> content(String id) {
		return com.altarsmp.fabric.item.ItemFactory.content(id, this.player);
	}

	public String markup(String text) {
		return TextFx.strip(text);
	}
}
