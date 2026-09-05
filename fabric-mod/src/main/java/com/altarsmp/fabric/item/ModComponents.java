package com.altarsmp.fabric.item;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;

import com.mojang.serialization.Codec;

import com.altarsmp.fabric.AltarSMPMod;

/**
 * Data components registered by the port.
 *
 * <p>Identity itself deliberately lives in {@code minecraft:custom_data} using the
 * very same namespaced keys the Bukkit {@code PersistentDataContainer} wrote
 * ({@code altarsmp:altar_weapon}, {@code altarsmps2:s2_id}, ...) so items that
 * already exist on a migrated server are still recognised - see
 * {@link Identity}.  These registered components carry the <em>structured</em>
 * state that the original plugin also kept on the item (kill counters, charge
 * meters, trial ownership), which needs a stable, network-synchronised shape.</p>
 */
public final class ModComponents {

	/** Canonical AltarSMP content id, e.g. {@code paladinbattleaxe}. */
	public static final DataComponentType<String> IDENTITY = register("identity",
			DataComponentType.<String>builder().persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8).cacheEncoding().build());

	/**
	 * Structured per-item state: kill counters, charge levels, meter values,
	 * owner binding, morph captures.  Stored as NBT so a new key never requires a
	 * component migration.
	 */
	public static final DataComponentType<CompoundTag> STATE = register("state",
			DataComponentType.<CompoundTag>builder().persistent(CompoundTag.CODEC).networkSynchronized(ByteBufCodecs.COMPOUND_TAG).build());

	/** Trial / event data bound to a physical item (Copper Core, Bingo Book, shards). */
	public static final DataComponentType<CompoundTag> TRIAL = register("trial",
			DataComponentType.<CompoundTag>builder().persistent(CompoundTag.CODEC).networkSynchronized(ByteBufCodecs.COMPOUND_TAG).build());

	/** Marker for items produced by an altar craft, holding the altar/recipe id. */
	public static final DataComponentType<String> PROVENANCE = register("provenance",
			DataComponentType.<String>builder().persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8).build());

	private ModComponents() {
	}

	private static <T> DataComponentType<T> register(String path, DataComponentType<T> type) {
		Identifier id = AltarSMPMod.id(path);
		Registry<DataComponentType<?>> registry = BuiltInRegistries.DATA_COMPONENT_TYPE;
		@SuppressWarnings("unchecked")
		DataComponentType<T> registered = (DataComponentType<T>) Registry.register((Registry) registry, id, type);
		return registered;
	}

	public static void initialize() {
		AltarSMPMod.LOGGER.info("[AltarSMP] registered {} data components", 4);
	}
}
