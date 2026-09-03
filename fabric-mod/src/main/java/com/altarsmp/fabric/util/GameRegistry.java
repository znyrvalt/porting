package com.altarsmp.fabric.util;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.particles.ParticleType;

import com.altarsmp.fabric.AltarSMPMod;

/**
 * Name-based resolution against the <em>live</em> Minecraft registries.
 *
 * <p>The original plugin was written against the Bukkit enums
 * ({@code Material.NETHERITE_SWORD}, {@code Sound.BLOCK_ANVIL_LAND},
 * {@code Particle.SQUID_INK}, {@code PotionEffectType.DAMAGE_RESISTANCE},
 * {@code Enchantment.SWEEPING_EDGE}).  Every one of those enum constants is
 * derived mechanically from the vanilla registry id:</p>
 *
 * <pre>
 *   Material.NETHERITE_SWORD          -&gt; minecraft:netherite_sword
 *   Sound.BLOCK_ANVIL_LAND            -&gt; minecraft:block.anvil.land
 *   Sound.ENTITY_WARDEN_SONIC_CHARGE  -&gt; minecraft:entity.warden.sonic_charge
 *   Particle.BLOCK_CRUMBLE            -&gt; minecraft:block_crumble
 *   PotionEffectType.DAMAGE_RESISTANCE-&gt; minecraft:resistance   (alias table)
 *   Enchantment.SWEEPING_EDGE         -&gt; minecraft:sweeping_edge
 * </pre>
 *
 * <p>Rather than hard-coding a 26.2 constant name for each of the ~150 sounds,
 * ~55 particles, ~40 effects and ~20 enchantments the plugin uses (which is
 * exactly how old bytecode descriptors sneak into a new port), this class builds
 * a reverse index from the registry itself the first time it is needed.  If a
 * name cannot be resolved it is recorded and reported by
 * {@code /altarsmp debug registry} - never silently ignored.</p>
 */
public final class GameRegistry {

	private static final Map<String, Identifier> SOUND_INDEX = new HashMap<>();
	private static final Map<String, String> EFFECT_ALIASES = new HashMap<>();
	private static final Map<String, String> MATERIAL_ALIASES = new HashMap<>();
	private static final Set<String> UNRESOLVED = new LinkedHashSet<>();

	@Nullable
	private static RegistryAccess registryAccess;

	static {
		// Bukkit PotionEffectType -> vanilla effect id, for the handful that differ.
		EFFECT_ALIASES.put("damage_resistance", "resistance");
		EFFECT_ALIASES.put("slow", "slowness");
		EFFECT_ALIASES.put("fast_digging", "haste");
		EFFECT_ALIASES.put("slow_digging", "mining_fatigue");
		EFFECT_ALIASES.put("increase_damage", "strength");
		EFFECT_ALIASES.put("heal", "instant_health");
		EFFECT_ALIASES.put("harm", "instant_damage");
		EFFECT_ALIASES.put("jump", "jump_boost");
		EFFECT_ALIASES.put("confusion", "nausea");
		EFFECT_ALIASES.put("damage_resistance_2", "resistance");
		// Bukkit Material -> vanilla item/block id, for the handful that differ.
		MATERIAL_ALIASES.put("grass", "short_grass");
		MATERIAL_ALIASES.put("snow", "snow_block");
		MATERIAL_ALIASES.put("tnt", "tnt");
		MATERIAL_ALIASES.put("mob_spawner", "spawner");
		MATERIAL_ALIASES.put("minecart_with_tnt", "tnt_minecart");
		MATERIAL_ALIASES.put("redstone_torch_on", "redstone_torch");
		MATERIAL_ALIASES.put("sign", "oak_sign");
		MATERIAL_ALIASES.put("iron_spade", "iron_shovel");
		MATERIAL_ALIASES.put("diamond_spade", "diamond_shovel");
		MATERIAL_ALIASES.put("netherite_spade", "netherite_shovel");
		MATERIAL_ALIASES.put("golden_spade", "golden_shovel");
		MATERIAL_ALIASES.put("stone_spade", "stone_shovel");
		MATERIAL_ALIASES.put("wood_spade", "wooden_shovel");
		MATERIAL_ALIASES.put("wood_sword", "wooden_sword");
		MATERIAL_ALIASES.put("wood_axe", "wooden_axe");
		MATERIAL_ALIASES.put("wood_pickaxe", "wooden_pickaxe");
		MATERIAL_ALIASES.put("wood_hoe", "wooden_hoe");
		MATERIAL_ALIASES.put("stone_sword_2", "stone_sword");
		MATERIAL_ALIASES.put("pork", "porkchop");
	}

	private GameRegistry() {
	}

	public static void bind(MinecraftServer server) {
		registryAccess = server == null ? null : server.registryAccess();
		SOUND_INDEX.clear();
		Registry<SoundEvent> sounds = BuiltInRegistries.SOUND_EVENT;
		for (Identifier id : sounds.keySet()) {
			SOUND_INDEX.put(bukkitName(id), id);
		}
		AltarSMPMod.LOGGER.info("[AltarSMP] registry index built: {} sound events", SOUND_INDEX.size());
	}

	public static void unbind() {
		registryAccess = null;
	}

	/** {@code minecraft:block.anvil.land} -&gt; {@code BLOCK_ANVIL_LAND}. */
	private static String bukkitName(Identifier id) {
		return id.toString().replace('.', '_').replace('/', '_').replace(':', '_').toUpperCase(Locale.ROOT).replaceFirst("^MINECRAFT_", "");
	}

	public static Set<String> unresolved() {
		return UNRESOLVED;
	}

	private static void miss(String kind, String name) {
		if (UNRESOLVED.add(kind + ":" + name)) {
			AltarSMPMod.LOGGER.warn("[AltarSMP] unresolved {} '{}'", kind, name);
		}
	}

	// ------------------------------------------------------------------- items

	@Nullable
	public static Item item(String bukkitMaterial) {
		Identifier id = itemId(bukkitMaterial);
		if (id == null) {
			return null;
		}
		Item item = BuiltInRegistries.ITEM.get(id);
		if (item == null || item == net.minecraft.world.item.Items.AIR) {
			miss("item", bukkitMaterial);
			return null;
		}
		return item;
	}

	@Nullable
	public static Identifier itemId(String bukkitMaterial) {
		if (bukkitMaterial == null || bukMaterialBlank(bukkitMaterial)) {
			return null;
		}
		String lower = bukkitMaterial.toLowerCase(Locale.ROOT);
		String mapped = MATERIAL_ALIASES.getOrDefault(lower, lower);
		Identifier id;
		int colon = mapped.indexOf(':');
		if (colon > 0) {
			id = Identifier.fromNamespaceAndPath(mapped.substring(0, colon), mapped.substring(colon + 1));
		} else {
			id = Identifier.fromNamespaceAndPath("minecraft", mapped);
		}
		if (!BuiltInRegistries.ITEM.containsKey(id)) {
			miss("item", bukkitMaterial);
			return null;
		}
		return id;
	}

	private static boolean bukMaterialBlank(String s) {
		return s.isBlank() || s.equalsIgnoreCase("air");
	}

	// ------------------------------------------------------------------ blocks

	@Nullable
	public static Block block(String bukkitMaterial) {
		Identifier id = blockId(bukkitMaterial);
		if (id == null) {
			return null;
		}
		Block block = BuiltInRegistries.BLOCK.get(id);
		if (block == null || block == net.minecraft.world.level.block.Blocks.AIR) {
			miss("block", bukkitMaterial);
			return null;
		}
		return block;
	}

	@Nullable
	public static Identifier blockId(String bukkitMaterial) {
		if (bukkitMaterial == null || bukMaterialBlank(bukkitMaterial)) {
			return null;
		}
		String lower = bukkitMaterial.toLowerCase(Locale.ROOT);
		String mapped = MATERIAL_ALIASES.getOrDefault(lower, lower);
		Identifier id = Identifier.fromNamespaceAndPath("minecraft", mapped);
		if (!BuiltInRegistries.BLOCK.containsKey(id)) {
			miss("block", bukkitMaterial);
			return null;
		}
		return id;
	}

	// ------------------------------------------------------------------ sounds

	@Nullable
	public static SoundEvent sound(String bukkitSound) {
		if (bukkitSound == null) {
			return null;
		}
		Identifier id = soundId(bukkitSound);
		if (id == null) {
			return null;
		}
		SoundEvent event = BuiltInRegistries.SOUND_EVENT.get(id);
		return event != null ? event : SoundEvent.createVariableRangeEvent(id);
	}

	@Nullable
	public static Identifier soundId(String bukkitSound) {
		if (bukkitSound == null || bukkitSound.isBlank()) {
			return null;
		}
		String raw = bukkitSound.trim();
		if (raw.indexOf(':') > 0) {
			Identifier direct = Fx.parseId(raw);
			if (direct != null) {
				return direct;
			}
		}
		String key = raw.toUpperCase(Locale.ROOT).replace('.', '_');
		Identifier id = SOUND_INDEX.get(key);
		if (id == null) {
			// Fall back to the mechanical rule (registry may not be indexed yet).
			id = Identifier.fromNamespaceAndPath("minecraft", key.toLowerCase(Locale.ROOT).replace('_', '.'));
			if (!BuiltInRegistries.SOUND_EVENT.containsKey(id)) {
				miss("sound", bukkitSound);
				return null;
			}
		}
		return id;
	}

	// --------------------------------------------------------------- particles

	@Nullable
	@SuppressWarnings("unchecked")
	public static ParticleType<?> particle(String bukkitParticle) {
		if (bukkitParticle == null) {
			return null;
		}
		Identifier id = Identifier.fromNamespaceAndPath("minecraft", bukkitParticle.toLowerCase(Locale.ROOT));
		if (!BuiltInRegistries.PARTICLE_TYPE.containsKey(id)) {
			miss("particle", bukkitParticle);
			return null;
		}
		return (ParticleType<?>) BuiltInRegistries.PARTICLE_TYPE.get(id);
	}

	// ----------------------------------------------------------------- effects

	public static Optional<Holder.Reference<MobEffect>> effect(String bukkitEffect) {
		if (bukkitEffect == null) {
			return Optional.empty();
		}
		String lower = bukkitEffect.toLowerCase(Locale.ROOT);
		String mapped = EFFECT_ALIASES.getOrDefault(lower, lower);
		Identifier id = Identifier.fromNamespaceAndPath("minecraft", mapped);
		Optional<Holder.Reference<MobEffect>> holder = registryAccess == null
				? BuiltInRegistries.MOB_EFFECT.get(id)
				: registryAccess.lookupOrThrow(Registries.MOB_EFFECT).get(id);
		if (holder.isEmpty()) {
			miss("effect", bukkitEffect);
		}
		return holder;
	}

	// ------------------------------------------------------------ enchantments

	public static Optional<Holder.Reference<Enchantment>> enchantment(String bukkitEnchantment) {
		if (bukkitEnchantment == null) {
			return Optional.empty();
		}
		String lower = bukkitEnchantment.toLowerCase(Locale.ROOT);
		Identifier id = Identifier.fromNamespaceAndPath("minecraft", lower);
		Optional<Holder.Reference<Enchantment>> holder = registryAccess == null
				? BuiltInRegistries.ENCHANTMENT.get(id)
				: registryAccess.lookupOrThrow(Registries.ENCHANTMENT).get(id);
		if (holder.isEmpty()) {
			miss("enchantment", bukkitEnchantment);
		}
		return holder;
	}

	// ------------------------------------------------------------- entity types

	@Nullable
	public static EntityType<?> entityType(String bukkitEntityType) {
		if (bukkitEntityType == null) {
			return null;
		}
		Identifier id = Identifier.fromNamespaceAndPath("minecraft", bukkitEntityType.toLowerCase(Locale.ROOT));
		if (!BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
			miss("entity_type", bukkitEntityType);
			return null;
		}
		return BuiltInRegistries.ENTITY_TYPE.get(id);
	}

	public static RegistryAccess registryAccess() {
		return registryAccess;
	}
}
