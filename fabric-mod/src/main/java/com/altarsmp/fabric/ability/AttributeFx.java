package com.altarsmp.fabric.ability;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

import com.altarsmp.fabric.AltarSMPMod;

/**
 * Attribute manipulation with save/restore.
 *
 * <p>The plugin resized players during the Blood Trail ({@code Attributes.SCALE}
 * to {@code abilities.bloodlust.trail_scale}) and raised their step height so the
 * shrunken model could still climb blocks, then restored both. Keeping the
 * originals in one map means a crash or a relog can never leave a player stuck at
 * half size.</p>
 *
 * <p><b>Note for maintainers:</b> the attribute holder class is
 * {@link MobAttributes} in modern Minecraft (it was {@code Attributes} before
 * 1.21.5). This file is the only place that references it.</p>
 */
public final class AttributeFx {

	private static final Map<UUID, Map<String, Double>> ORIGINALS = new HashMap<>();

	private AttributeFx() {
	}

	public static void set(Player player, String attribute, double value) {
		AttributeInstance instance = instance(player, attribute);
		if (instance == null) {
			return;
		}
		ORIGINALS.computeIfAbsent(player.getUUID(), k -> new HashMap<>()).putIfAbsent(attribute, instance.getBaseValue());
		instance.setBaseValue(value);
	}

	public static void restore(Player player, String attribute) {
		Map<String, Double> originals = ORIGINALS.get(player.getUUID());
		if (originals == null) {
			return;
		}
		Double value = originals.remove(attribute);
		AttributeInstance instance = instance(player, attribute);
		if (value != null && instance != null) {
			instance.setBaseValue(value);
		}
		if (originals.isEmpty()) {
			ORIGINALS.remove(player.getUUID());
		}
	}

	public static void restoreAll(Player player) {
		Map<String, Double> originals = ORIGINALS.remove(player.getUUID());
		if (originals == null) {
			return;
		}
		for (Map.Entry<String, Double> entry : originals.entrySet()) {
			AttributeInstance instance = instance(player, entry.getKey());
			if (instance != null) {
				instance.setBaseValue(entry.getValue());
			}
		}
	}

	public static boolean isModified(Player player, String attribute) {
		Map<String, Double> originals = ORIGINALS.get(player.getUUID());
		return originals != null && originals.containsKey(attribute);
	}

	public static final String SCALE = "scale";
	public static final String STEP_HEIGHT = "step_height";
	public static final String MOVEMENT_SPEED = "movement_speed";
	public static final String GRAVITY = "gravity";

	private static AttributeInstance instance(Player player, String attribute) {
		var holder = switch (attribute) {
			case SCALE -> Attributes.SCALE;
			case STEP_HEIGHT -> Attributes.STEP_HEIGHT;
			case MOVEMENT_SPEED -> Attributes.MOVEMENT_SPEED;
			case GRAVITY -> Attributes.GRAVITY;
			default -> null;
		};
		if (holder == null) {
			AltarSMPMod.LOGGER.warn("[AltarSMP] unknown attribute '{}'", attribute);
			return null;
		}
		AttributeInstance instance = player.getAttribute(holder);
		if (instance == null) {
			AltarSMPMod.LOGGER.warn("[AltarSMP] player {} has no '{}' attribute instance", player.getGameProfile().getName(), attribute);
		}
		return instance;
	}
}
