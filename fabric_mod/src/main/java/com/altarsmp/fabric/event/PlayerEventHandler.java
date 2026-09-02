package com.altarsmp.fabric.event;

import com.altarsmp.fabric.AltarSMPFabric;
import com.altarsmp.fabric.faction.FactionManager;
import com.altarsmp.fabric.vampire.FabricVampireManager;
import com.altarsmp.fabric.pale.FabricPaleManager;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.*;
import net.minecraftforge.event.level.*;
import net.minecraftforge.common.*;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.attribute.*;
import net.minecraft.world.item.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.*;
import net.minecraft.util.*;
import net.minecraft.network.syncher.*;
import net.kyori.adventure.text.*;

import java.util.*;

public class PlayerEventHandler {
    private final AltarSMPFabric mod;
    private final FabricVampireManager vampireManager;
    private final FabricPaleManager paleManager;
    
    public PlayerEventHandler() {
        this.mod = AltarSMPFabric.getInstance();
        this.vampireManager = mod.getVampireManager();
        this.paleManager = mod.getPaleManager();
    }
    
    @SubscribeEvent
    public void onPlayerLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        
        // Update player name color based on faction
        vampireManager.updatePlayerNameColor(player);
        paleManager.updatePlayerNameColor(player);
        
        // Update king health
        vampireManager.updateKingHealth(player);
        paleManager.updateKingHealth(player);
        
        // Send join messages
        if (FactionManager.isVampire(player)) {
            player.sendMessage(
                com.altarsmp.fabric.AltarSMPFabric.mm.deserialize(
                    "<red>You feel the thirst for blood... You are a vampire."
                ),
                true
            );
        } else if (FactionManager.isPale(player)) {
            player.sendMessage(
                com.altarsmp.fabric.AltarSMPFabric.mm.deserialize(
                    "<gray>You feel rotted inside... You are a pale rot."
                ),
                true
            );
        }
    }
    
    @SubscribeEvent
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // Update on join
        onPlayerLogin(new PlayerLoginEvent(
            event.getPlayer().getProfile(),
            event.getPlayer(),
            net.minecraftforge.event.entity.player.PlayerLoginEvent.Result.ALLOWED,
            event.getPlayer().connection != null
        ));
        
        // Update king health on join
        vampireManager.updateKingHealth(player);
        paleManager.updateKingHealth(player);
    }
    
    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.RespawnEvent event) {
        Player player = event.getEntity();
        
        // Update on respawn
        vampireManager.onPlayerRespawn(event);
        paleManager.onPlayerRespawn(event);
    }
    
    @SubscribeEvent
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        
        // Handle death effects
        vampireManager.onPlayerDeath(event);
        paleManager.onPlayerDeath(event);
        
        // Drop items based on protection settings
        if (!mod.getConfig().getBoolean("weapon-protection.enabled", true)) {
            // Drop legendary weapons on death if protection disabled
            for (ItemStack stack : player.getInventory().getContents()) {
                if (isLegendaryWeapon(stack)) {
                    event.getDrops().add(stack.copy());
                    player.getInventory().setItem(player.getInventory().getSlotFor(stack), ItemStack.EMPTY);
                }
            }
        }
    }
    
    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent event) {
        Player player = event.getPlayer();
        
        // Apply vampire effects
        vampireManager.applyVampireEffects(player);
        
        // Apply pale effects
        paleManager.applyPaleEffects(player);
        
        // Update king health periodically
        vampireManager.updateKingHealth(player);
        paleManager.updateKingHealth(player);
        
        // Update name color
        vampireManager.updatePlayerNameColor(player);
        paleManager.updatePlayerNameColor(player);
    }
    
    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerChangedEvent event) {
        Player player = event.getEntity();
        
        // Update faction state on dimension change
        vampireManager.updateKingHealth(player);
        paleManager.updateKingHealth(player);
        
        // Remove potion effects that don't transfer
        // (vampire/pale effects are world-dependent)
    }
    
    @SubscribeEvent
    public void onPlayerSleepInBed(PlayerSleepInBedEvent event) {
        Player player = event.getEntity();
        
        // Blood moon can start while players sleep
        // Check if blood moon should start
        if (mod.getBloodMoonManager() != null) {
            // Blood moon logic would check if enough players are awake, etc.
        }
    }
    
    @SubscribeEvent
    public void onPlayerSetBedSpawnPoint(PlayerSetBedSpawnPointEvent event) {
        Player player = event.getEntity();
        
        // Vampires might have special bed interactions
        // or restrictions based on faction
    }
    
    @SubscribeEvent
    public void onPlayerUpdate(PlayerEvent.Update event) {
        Player player = event.getEntity();
        
        // Check for faction-specific updates
        if (FactionManager.isVampire(player)) {
            // Vampire daytime check
            long worldTime = player.getLevel().getTime();
            boolean isDay = worldTime >= 12000 && worldTime < 24000; // Noon to midnight
            
            // Actually: day is 12000-24000, night is 0-12000
            // But the original code checks: worldTime >= 0 && worldTime < 12300 for night
            if (worldTime >= 0 && worldTime < 12300) {
                // Night time - vampire effects active
                // Already handled by applyVampireEffects
            }
        }
        
        if (FactionManager.isPale(player)) {
            // Pale moss/rain checks already handled
        }
    }
    
    @SubscribeEvent
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        Player player = event.getEntity();
        
        // When swapping hands, check if weapon abilities should trigger
        // This is where right-click vs shift-click distinction is made
        
        // Check if holding a weapon
        for (BaseWeapon weapon : AltarSMPFabric.getWeapons()) {
            if (weapon.isHoldingThisWeapon(player)) {
                // Weapon-specific swap handling
                weapon.onSwapHandItems(event);
            }
        }
    }
    
    @SubscribeEvent
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getEntity();
        
        // Check if player is holding a weapon
        for (BaseWeapon weapon : AltarSMPFabric.getWeapons()) {
            if (weapon.isHoldingThisWeapon(player)) {
                // Handle right-click abilities
                weapon.onRightClickInteract(player.getItemInHand(), player.getLevel(), player, event.getHand());
            }
        }
        
        // Check if player is interacting with an altar
        if (event.getHand() == net.minecraft.hand.Hands.MAIN_HAND) {
            if (event.getUseItem() != null && event.getUseItem().getItem() instanceof AltarBlock) {
                // Handle altar interaction
                AltarBlock altar = (AltarBlock) event.getUseItem().getItem();
                altar.onInteract(player, event.getWorld(), event.getPos(), event.getHand());
            }
        }
    }
    
    @SubscribeEvent
    public void onPlayerEquipmentChange(PlayerEquipmentChangeEvent event) {
        Player player = event.getEntity();
        
        // When equipment changes, update faction effects
        vampireManager.updateKingHealth(player);
        paleManager.updateKingHealth(player);
        
        // Re-apply effects based on new equipment
        vampireManager.applyVampireEffects(player);
        paleManager.applyPaleEffects(player);
    }
    
    @SubscribeEvent
    public void onPlayerHurt(HurtEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        
        // Apply weapon protection if legendary weapon is being hurt
        for (BaseWeapon weapon : AltarSMPFabric.getWeapons()) {
            if (weapon.isHoldingThisWeapon(player) && isLegendaryWeapon(player.getInventory().getItemInMainHand())) {
                // Check weapon protection config
                if (mod.getConfig().getBoolean("weapon-protection.enabled", true)) {
                    // Protect weapon from being destroyed
                    event.setCanceled(true); // This is simplified - actual protection is more complex
                }
            }
        }
    }
    
    private boolean isLegendaryWeapon(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return false;
        // Check if the item is a legendary weapon based on its tag or registry name
        return stack.getTag() != null && stack.getTag().contains("weapon_id") && 
               (stack.getTag().getString("weapon_id").startsWith("paladin") || 
                stack.getTag().getString("weapon_id").startsWith("vulcan") ||
                stack.getTag().getString("weapon_id").startsWith("eclipse"));
    }
}