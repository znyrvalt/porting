package com.altarsmp.fabric.ability;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.ItemStack;

/**
 * Serialises an {@link ItemStack} to (and back from) its SNBT string form using
 * the vanilla {@code ItemStack.CODEC}.
 *
 * <p>This is what lets the gacha weapons (Crazy Slots, Minor Crazy Slots) keep
 * their held inventory and their admin-defined prize pools inside the mod's own
 * string components and JSON store instead of inventing a parallel item format.
 * Failures are logged and reported as empty optionals rather than swallowed, so
 * a corrupt entry can never silently hand a player an empty stack.</p>
 */
public final class StackSnbt {

	private static final Logger LOGGER = LoggerFactory.getLogger("AltarSMP/StackSnbt");

	private StackSnbt() {
	}

	/** Returns the SNBT representation of {@code stack}, or {@code null} when empty. */
	public static String serialize(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		Optional<Tag> encoded = ItemStack.CODEC.encodeStart(NbtOps.INSTANCE, stack).resultOrPartial(error ->
				LOGGER.error("[AltarSMP] failed to serialise {}: {}", stack, error));
		return encoded.map(Tag::toString).orElse(null);
	}

	/** Parses a previously serialised stack; empty/invalid input yields an empty optional. */
	public static Optional<ItemStack> deserialize(String snbt) {
		if (snbt == null || snbt.isBlank()) {
			return Optional.empty();
		}
		try {
			Tag tag = TagParser.parseTag(snbt);
			return ItemStack.CODEC.parse(NbtOps.INSTANCE, tag).resultOrPartial(error ->
					LOGGER.error("[AltarSMP] failed to parse stored stack: {}", error)).map(stack -> stack);
		} catch (Exception failure) {
			LOGGER.error("[AltarSMP] unreadable stored stack data ({} characters): {}", snbt.length(), failure.getMessage());
			return Optional.empty();
		}
	}
}
