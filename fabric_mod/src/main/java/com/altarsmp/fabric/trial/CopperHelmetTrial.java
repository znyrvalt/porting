package com.altarsmp.fabric.trial;

import com.altarsmp.fabric.AltarSMPFabric;
import com.altarsmp.fabric.item.CopperFragment;
import com.altarsmp.fabric.item.CopperChestplateFragment;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.entity.player.*;
import net.minecraftforge.event.level.*;
import net.minecraftforge.common.*;

import java.util.*;

public class CopperHelmetTrial {
    private static final int FRAGMENTS_REQUIRED = 5;
    private static final int DROP_CHANCE = 10; // 10% from ominous vaults

    public CopperHelmetTrial() {
        // Register event handlers
        registerEvents();
    }

    private void registerEvents() {
        // Ominous vault fragment drop event
        // Elytra restriction during trial
        // Lightning hazard tracking
    }

    // Start the helmet trial
    public static void startHelmetTrial(Player player) {
        // Give player a fragment tracking item
        ItemStack fragment = new ItemStack(ModItems.COPPER_FRAGMENT.get());
        // Set fragment properties
        fragment.setCount(1);
        player.getInventory().add(fragment);
        
        player.sendMessage(
            AltarSMPFabric.mm.deserialize(
                "<gold><bold>Copper Helmet Trial started!<br/>"
                + "<gray>Collect 5 fragments from ominous vaults to complete the helmet."
            )
        );
    }

    // Check if player has enough fragments
    public static boolean hasEnoughFragments(Player player) {
        int fragmentCount = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (CopperFragment.isFragment(stack)) {
                fragmentCount += stack.getAmount();
            }
        }
        return fragmentCount >= FRAGMENTS_REQUIRED;
    }

    // Consume fragments and give helmet
    public static void completeHelmetTrial(Player player) {
        if (!hasEnoughFragments(player)) {
            player.sendMessage(
                AltarSMPFabric.mm.deserialize(
                    "<red>You need <gold>5 <gray>copper fragments <red>to complete the helmet trial!"
                )
            );
            return;
        }

        // Consume fragments
        consumeFragments(player, FRAGMENTS_REQUIRED);

        // Give copper helmet
        ItemStack copperHelmet = new ItemStack(ModItems.COPPER_HELMET.get());
        // Set custom model data or other properties
        player.getInventory().add(copperHelmet);

        player.sendMessage(
            AltarSMPFabric.mm.deserialize(
                "<green><bold>Copper Helmet Trial Complete!<br/>"
                + "<gray>You have received the Copper Helmet."
            )
        );
    }

    // Consume specified number of fragments
    private static void consumeFragments(Player player, int count) {
        int consumed = 0;
        for (int i = 0; i < player.getInventory().getSizeInventory() && consumed < count; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (CopperFragment.isFragment(stack)) {
                int toConsume = Math.min(stack.getAmount(), count - consumed);
                stack.setAmount(stack.getAmount() - toConsume);
                if (stack.getAmount() <= 0) {
                    player.getInventory().setItem(i, ItemStack.EMPTY);
                }
                consumed += toConsume;
            }
        }
    }
}