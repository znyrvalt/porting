package com.altarsmp.fabric.ability;

import java.util.Optional;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.util.GameRegistry;

/**
 * Potion-effect application by Bukkit effect name.
 *
 * <p>The plugin was written against {@code PotionEffectType.DAMAGE_RESISTANCE},
 * {@code SLOW_FALLING}, {@code GLOWING}, ... which are the vanilla registry ids
 * in disguise. {@link GameRegistry} resolves the name against the live registry
 * and records anything that cannot be found, so a missing effect is never a
 * silent no-op.</p>
 */
public final class Effects {

	private Effects() {
	}

	public static boolean apply(LivingEntity target, String bukkitEffect, int durationTicks, int amplifier) {
		return apply(target, bukkitEffect, durationTicks, amplifier, false, true, true);
	}

	public static boolean apply(LivingEntity target, String bukkitEffect, int durationTicks, int amplifier,
			boolean ambient, boolean visible, boolean showIcon) {
		Optional<Holder.Reference<MobEffect>> holder = GameRegistry.effect(bukkitEffect);
		if (holder.isEmpty()) {
			AltarSMPMod.LOGGER.warn("[AltarSMP] effect '{}' requested but not present in this Minecraft version", bukkitEffect);
			return false;
		}
		target.addEffect(new MobEffectInstance(holder.get(), durationTicks, amplifier, ambient, visible, showIcon));
		return true;
	}

	public static boolean remove(LivingEntity target, String bukkitEffect) {
		Optional<Holder.Reference<MobEffect>> holder = GameRegistry.effect(bukkitEffect);
		return holder.isPresent() && target.removeEffect(holder.get());
	}

	public static boolean has(LivingEntity target, String bukkitEffect) {
		Optional<Holder.Reference<MobEffect>> holder = GameRegistry.effect(bukkitEffect);
		return holder.isPresent() && target.hasEffect(holder.get());
	}

	public static int amplifier(LivingEntity target, String bukkitEffect) {
		Optional<Holder.Reference<MobEffect>> holder = GameRegistry.effect(bukkitEffect);
		if (holder.isEmpty()) {
			return -1;
		}
		MobEffectInstance instance = target.getEffect(holder.get());
		return instance == null ? -1 : instance.getAmplifier();
	}

	/** Duration in ticks of an active effect, or -1. */
	public static int duration(LivingEntity target, String bukkitEffect) {
		Optional<Holder.Reference<MobEffect>> holder = GameRegistry.effect(bukkitEffect);
		if (holder.isEmpty()) {
			return -1;
		}
		MobEffectInstance instance = target.getEffect(holder.get());
		return instance == null ? -1 : instance.getDuration();
	}
}
