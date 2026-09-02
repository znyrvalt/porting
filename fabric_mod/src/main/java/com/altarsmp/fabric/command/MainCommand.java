package com.altarsmp.fabric.command;

import com.altarsmp.fabric.AltarSMPFabric;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

public class MainCommand {
    private final AltarSMPFabric mod;

    public MainCommand(AltarSMPFabric mod) {
        this.mod = mod;
    }

    public void register() {
        // Main /altarsmp command with subcommands
        Commands.literal("altarsmp")
            .then(Commands.literal("status")
                .executes(context -> {
                    Player player = context.getSource().getPlayerOrException();
                    int weaponCount = AltarSMPFabric.WEAPON_REGISTRY.size();
                    int altarCount = AltarSMPFabric.ALTAR_REGISTRY.size();
                    player.sendMessage(net.kyori.adventure.text.Component.translatable("chat.altarsmp.status", weaponCount, altarCount), true);
                    return 1;
                }))
            .then(Commands.literal("recipes")
                .executes(context -> {
                    Player player = context.getSource().getPlayerOrException();
                    RecipeManager.displayRecipes(player);
                    return 1;
                }))
            .then(Commands.literal("give")
                .then(Commands.argument("weapon", net.minecraft.commands.arguments.StringArgumentStringArgType.string())
                    .executes(context -> {
                        String weaponId = net.minecraft.commands.arguments.StringArgumentStringArgType.getString(context, "weapon");
                        Player player = context.getSource().getPlayerOrException();
                        giveWeapon(player, weaponId);
                        return 1;
                    })))
            .then(Commands.literal("craft")
                .then(Commands.argument("recipe", net.minecraft.commands.arguments.StringArgumentStringArgType.string())
                    .executes(context -> {
                        String recipeId = net.minecraft.commands.arguments.StringArgumentStringArgType.getString(context, "recipe");
                        Player player = context.getSource().getPlayerOrException();
                        craftRecipe(player, recipeId);
                        return 1;
                    })));
    }

    private void giveWeapon(Player player, String weaponId) {
        BaseWeapon weapon = AltarSMPFabric.getWeapon(weaponId);
        if (weapon != null) {
            ItemStack weaponItem = weapon.createWeapon();
            player.getInventory().add(weaponItem);
            player.sendMessage(net.kyori.adventure.text.Component.translatable("chat.altarsmp.given", weapon.getWeaponName()), true);
        } else {
            player.sendMessage(net.kyori.adventure.text.Component.translatable("chat.altarsmp.unknown_weapon", weaponId), true);
        }
    }

    private void craftRecipe(Player player, String recipeId) {
        RecipeManager.craftRecipe(player, recipeId);
    }
}