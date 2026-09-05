package com.altarsmp.fabric.weapon.s1;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.AbilityContext;
import com.altarsmp.fabric.ability.CooldownBars;
import com.altarsmp.fabric.item.Identity;
import com.altarsmp.fabric.item.MineableBlocks;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.weapon.WeaponBehavior;

/**
 * Copper Pickaxe - port of {@code com.altarsmp.items.CopperPickaxe}.
 *
 * <p><b>3x3 Mining</b>: while the mode is on (Shift+F toggles it, matching the
 * original's sneak-swap toggle) every mined block takes the whole 3x3x3 cube
 * with it. Mined blocks are counted into the pickaxe's own {@code blocks_mined}
 * component, and at {@code copper_pickaxe.blocks_required} (default 25000) the
 * holder is told the Copper Pickaxe Upgrade Altar will now accept them - the
 * item is rebuilt with the updated progress lore each time, exactly like
 * {@code createItemWithBlocks}.</p>
 *
 * <p>Milestone messages every 5000 blocks and the progress action bar are both
 * preserved. Bulk breaking goes through
 * {@link MineableBlocks#breakFor(ServerPlayer, BlockPos)} so each extra block
 * still fires the real block-break event and drops naturally.</p>
 */
public final class CopperPickaxeWeapon implements WeaponBehavior {

	static final String KEY_TOGGLE = "copper_pickaxe_toggle";
	/** Component keys mirroring the original {@code NamespacedKey}s. */
	/** Component key mirroring the original {@code altarsmp:blocks_mined} PDC entry. */
	static final String STATE_MINED = com.altarsmp.fabric.item.Identity.KEY_BLOCKS_MINED;

	private final AltarSMPMod mod;
	private final Set<UUID> bulkMining = new HashSet<>();
	private final Set<UUID> toggleOn = new HashSet<>();

	public CopperPickaxeWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "copperpickaxe";
	}

	@Override
	public String displayName() {
		return "Copper Pickaxe";
	}

	@Override
	public List<String> configFields() {
		return List.of("Blocks Required to Upgrade");
	}

	int upgradeRequirement(AbilityContext ctx) {
		return ctx.cfg("copper_pickaxe.blocks_required", 25000);
	}

	static String formatNumber(int value) {
		return value >= 1000 ? String.format("%.1fk", value / 1000.0D) : String.valueOf(value);
	}

	@Override
	public void onSecondary(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		UUID id = player.getUUID();
		boolean enabled = !this.toggleOn.remove(id);
		if (enabled) {
			this.toggleOn.add(id);
		}
		Fx.sound(ctx.level(), player.position(), enabled ? "BLOCK_NOTE_BLOCK_BASS" : "BLOCK_NOTE_BLOCK_PLING",
				1.0F, enabled ? 1.0F : 2.0F);
		Messaging.actionBar(player, enabled ? "<gold>3x3 Mining <green>ENABLED" : "<gold>3x3 Mining <red>DISABLED");
	}

	@Override
	public void onTick(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		if (this.mod.scheduler().currentTick() % 20L != 0L) {
			return;
		}
		ItemStack held = player.getMainHandItem();
		if (!Identity.is(held, id())) {
			return;
		}
		int mined = Identity.stateInt(held, STATE_MINED, 0);
		int required = upgradeRequirement(ctx);
		String color = mined >= required ? "<green>" : "<gold>";
		Messaging.actionBar(player, color + "Copper Pickaxe: " + formatNumber(mined) + "/" + formatNumber(required)
				+ (mined >= required ? " <bold>READY" : "")
				+ (this.toggleOn.contains(player.getUUID()) ? " <gray>| 3x3 ON" : " <gray>| 3x3 OFF"));
	}

	@Override
	public void onBlockBreak(AbilityContext ctx, BlockPos pos, BlockState state) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		UUID id = player.getUUID();
		if (!Identity.is(ctx.weapon(), id()) || this.bulkMining.contains(id)) {
			return;
		}
		int mined = 1;
		if (this.toggleOn.contains(id) && MineableBlocks.isMineable(level, pos, state)) {
			this.bulkMining.add(id);
			try {
				for (int dx = -1; dx <= 1; dx++) {
					for (int dy = -1; dy <= 1; dy++) {
						for (int dz = -1; dz <= 1; dz++) {
							BlockPos extra = pos.offset(dx, dy, dz);
							if (extra.equals(pos)) {
								continue;
							}
							BlockState extraState = level.getBlockState(extra);
							if (MineableBlocks.isMineable(level, extra, extraState)
									&& MineableBlocks.breakFor(player, extra)) {
								mined++;
							}
						}
					}
				}
			} finally {
				this.bulkMining.remove(id);
			}
		}
		updateBlocksMined(ctx, mined);
	}

	/** {@code CopperPickaxe#updateBlocksMined}: count, milestone, ready, rebuild lore. */
	void updateBlocksMined(AbilityContext ctx, int gained) {
		ServerPlayer player = ctx.player();
		ItemStack held = player.getMainHandItem();
		if (!Identity.is(held, id())) {
			return;
		}
		int before = Identity.stateInt(held, STATE_MINED, 0);
		int required = upgradeRequirement(ctx);
		int after = before + gained;
		Identity.setStateInt(held, STATE_MINED, after);
		if (before < required && after >= required) {
			Messaging.send(player, "<gold><bold>COPPER PICKAXE READY FOR UPGRADE!</bold></gold>");
			Messaging.send(player, "<yellow>You've mined " + formatNumber(required)
					+ " blocks! Visit an upgrade altar to get Copper Pickaxe II!");
			Fx.sound(ctx.level(), player.position(), "UI_TOAST_CHALLENGE_COMPLETE", 1.0F, 1.0F);
			CooldownBars.show(player, KEY_TOGGLE, "Pickaxe Ready", BossEvent.BossBarColor.GREEN, 10);
			ctx.record().strings().put("copper_pickaxe_ready", "true");
		} else if (after % 5000 == 0 && after < required) {
			Messaging.send(player, "<aqua>Copper Pickaxe: <yellow>" + formatNumber(after) + "/"
					+ formatNumber(required) + " blocks mined");
		}
	}

	@Override
	public void onPlayerQuit(ServerPlayer player) {
		this.toggleOn.remove(player.getUUID());
		this.bulkMining.remove(player.getUUID());
	}

	/** Whether 3x3 mode is currently on for this player (used by Copper Pickaxe II). */
	public boolean isToggleOn(ServerPlayer player) {
		return this.toggleOn.contains(player.getUUID());
	}
}
