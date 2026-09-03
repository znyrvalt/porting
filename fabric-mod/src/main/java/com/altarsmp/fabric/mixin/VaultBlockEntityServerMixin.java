package com.altarsmp.fabric.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultConfig;
import net.minecraft.world.level.block.state.BlockState;

import com.altarsmp.fabric.AltarSMPMod;

/**
 * {@code OminousVaultListener#onBlockDispenseLoot} - the Copper Helmet trial's fragments.
 *
 * <p>Paper fired {@code BlockDispenseLootEvent} when a vault threw its reward out and let
 * the listener add to the dispensed list. Vanilla resolves that list in
 * {@code VaultBlockEntity.Server#resolveItemsToEject}, so the fragment is added to the very
 * list the vault then ejects - the player sees it fly out with the rest of the loot, exactly
 * as before.
 *
 * <p>Only <em>ominous</em> vaults count, which is the {@code VaultBlock#OMINOUS} block state
 * the listener checked with {@code Vault#isOminous()}. The drop chance, the fragment counter,
 * the sounds and the two warning lines all live in
 * {@code CopperTrialService#onOminousVaultLoot}, which answers {@code false} when the trial
 * is not running or every fragment has been collected - and then the loot is left alone.
 */
@Mixin(VaultBlockEntity.Server.class)
public abstract class VaultBlockEntityServerMixin {

	@Inject(method = "resolveItemsToEject", at = @At("RETURN"), cancellable = true)
	private static void altarsmp$addCopperFragment(ServerLevel level, VaultConfig config, BlockPos pos, Player player,
			ItemStack keyItem, CallbackInfoReturnable<List<ItemStack>> cir) {
		AltarSMPMod mod = AltarSMPMod.get();
		if (mod == null || !(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		BlockState state = level.getBlockState(pos);
		if (!state.is(Blocks.VAULT) || !state.getValue(VaultBlock.OMINOUS).booleanValue()) {
			return;
		}
		List<ItemStack> loot = new ArrayList<>(cir.getReturnValue());
		if (mod.trials().onOminousVaultLoot(serverPlayer, loot)) {
			cir.setReturnValue(loot);
		}
	}
}
