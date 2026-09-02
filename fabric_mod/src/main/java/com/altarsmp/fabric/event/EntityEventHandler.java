package com.altarsmp.fabric.event;

import com.altarsmp.fabric.AltarSMPFabric;
import com.altarsmp.fabric.weapon.BaseWeapon;
import com.altarsmp.fabric.faction.FactionManager;
import com.altarsmp.fabric.vampire.FabricVampireManager;
import com.altarsmp.fabric.pale.FabricPaleManager;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.*;
import net.minecraftforge.event.entity.living.*;
import net.minecraftforge.common.*;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.item.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.*;
import net.minecraft.util.*;
import net.kyori.adventure.text.*;
import java.util.*;

public class EntityEventHandler {
    private final AltarSMPFabric mod;
    private final FabricVampireManager vampireManager;
    private final FabricPaleManager paleManager;
    
    public EntityEventHandler() {
        this.mod = AltarSMPFabric.getInstance();
        this.vampireManager = mod.getVampireManager();
        this.paleManager = mod.getPaleManager();
    }
    
    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.getEntity() instanceof Player player) {
            // Update faction effects on joining world
            vampireManager.onPlayerJoin(new net.minecraftforge.event.entity.player.PlayerJoinEvent(
                player.getRandom(),
                player,
                net.minecraftforge.event.entity.player.PlayerLoginEvent.Result.ALLOWED,
                player.connection != null
            ));
            
            // Update king health
            vampireManager.updateKingHealth(player);
            paleManager.updateKingHealth(player);
            
            // Set initial name color
            vampireManager.updatePlayerNameColor(player);
            paleManager.updatePlayerNameColor(player);
        }
    }
    
    @SubscribeEvent
    public void onEntityStruckByLightning(EntityStruckByLightningEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        
        // During blood moon, vampires might attract lightning
        if (mod.getBloodMoonManager() != null && mod.getBloodMoonManager().isBloodMoonActive()) {
            if (FactionManager.isVampire(player)) {
                // Vampiles take reduced lightning damage or gain effects
                event.setCanceled(true);
                // Could give strength/speed instead
            }
        }
    }
    
    @SubscribeEvent
    public void onEntityTravel(EntityTravelEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        
        // Pale speed boost on moss
        if (FactionManager.isPale(player)) {
            Block mossBlock = player.getLevel().getBlockState(
                player.blockPosition().below()
            ).getBlock();
            if (mossBlock.getRegistryName().toString().contains("moss")) {
                // Already handled by pale effects, but could modify motion here
            }
        }
        
        // Vampire speed/strength during night/blood moon
        if (FactionManager.isVampire(player)) {
            long worldTime = player.getLevel().getTime();
            boolean isNight = worldTime >= 0 && worldTime < 12300;
            
            if (isNight) {
                // Night effects already applied via potion effects
            }
        }
    }
    
    @SubscribeEvent
    public void onPlayerSpawn(PlayerEvent.SpawnEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        
        // Update on spawn
        vampireManager.onPlayerRespawn(new net.minecraftforge.event.entity.player.PlayerEvent.RespawnEvent(player));
        paleManager.onPlayerRespawn(new net.minecraftforge.event.entity.player.PlayerEvent.RespawnEvent(player));
        
        // Update name color and king health
        vampireManager.updatePlayerNameColor(player);
        paleManager.updatePlayerNameColor(player);
        vampireManager.updateKingHealth(player);
        paleManager.updateKingHealth(player);
    }
    
    @SubscribeEvent
    public void onPlayerSpawnItemPickupConfig(PlayerEvent.SpawnItemPickupConfig event) {
        // Config for item pickup - related to weapon protection
    }
    
    @SubscribeEvent
    public void onEntityDeathPickup(ItemPickupEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        
        // Check if player is picking up a legendary weapon
        ItemStack pickedItem = event.getStack();
        if (isLegendaryWeapon(pickedItem)) {
            // Apply protection - legendary weapons shouldn't be easily picked up/dropped
            // or should have special handling
        }
    }
    
    @SubscribeEvent
    public void onEntityStride(BlockStrideEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        
        // Pale behavior on different block types
        if (FactionManager.isPale(player)) {
            // Moss speed effect
            Block block = event.getBlock();
            if (block.getRegistryName().toString().contains("moss")) {
                // Speed effect already applied via potion effects
            }
        }
    }
    
    @SubscribeEvent
    public void onEntityAttack(EntityAttackEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) player;
        
        // Track weapon kills
        if (event.getTarget() instanceof Player) {
            // Check which weapon was used
            for (BaseWeapon weapon : AltarSMPFabric.getWeapons()) {
                if (weapon.isHoldingThisWeapon(player)) {
                    // Increment kill counter
                    plugin.getDataPersistence().getPlayerData(player).incrementKillCount(weapon.getWeaponId());
                    
                    // Check for ability triggers based on kills
                    weapon.hurtEnemy(player.getUseItem(), event.getTarget(), player);
                }
            }
        }
    }
    
    @SubscribeEvent
    public void onEntityHurtDamage(EntityHurtEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        
        // Apply blood moon effects on damage
        if (mod.getBloodMoonManager() != null && mod.getBloodMoonManager().isBloodMoonActive()) {
            // Apply blood moon damage modifiers
            vampireManager.onBloodMoonDamage(event);
            paleManager.onBloodMoonDamage(event);
        }
        
        // Apply vampire night damage
        if (FactionManager.isVampire(player)) {
            long worldTime = player.getLevel().getTime();
            boolean isNight = worldTime >= 0 && worldTime < 12300;
            
            if (isNight) {
                double nightDamage = mod.getConfig().getDouble("vampire.night_damage", 2.0);
                // This would increase damage dealt BY the vampire, not taken
                // The original code increases damage dealt: var1.setDamage(var1.getDamage() + var10)
            }
        }
        
        // Apply pale backstab multiplier
        if (FactionManager.isPale(player)) {
            // Backstab check would need to come from the attack event source
            // The original code checks: if (isPale(var2) && this.isBackstab(var2, var12))
        }
    }
    
    @SubscribeEvent
    public void onPlayerDimensionChange(PlayerChangedDimensionEvent event) {
        Player player = event.getEntity();
        
        // Preserve faction state across dimensions
        vampireManager.updateKingHealth(player);
        paleManager.updateKingHealth(player);
        
        // Update name color
        vampireManager.updatePlayerNameColor(player);
        paleManager.updatePlayerNameColor(player);
    }
    
    @SubscribeEvent
    public void onPlayerCommandPre(net.minecraftforge.event.CommandPreprocessEvent event) {
        // Handle commands related to the mod
        String command = event.getCommandLine().toLowerCase();
        
        if (command.startsWith("/vampire") || command.startsWith("/pale") || command.startsWith("/human")) {
            // Faction commands - could provide info or execute
        }
        
        if (command.startsWith("/bloodmoon")) {
            // Blood moon command
            if (event.getCommandSource().hasAltarsmpBloodmoonPermission()) {
                mod.getBloodMoonManager().startBloodMoon();
            }
        }
    }
    
    @SubscribeEvent
    public void onPlayerFileLoad(net.minecraftforge.event.world.WorldLoadEvent event) {
        // When world loads, ensure all systems are initialized
        if (mod.getBloodMoonManager() != null) {
            mod.getBloodMoonManager().onWorldLoad();
        }
    }
    
    @SubscribeEvent
    public void onPlayerCommandSource(net.minecraftforge.event.command.CommandEvent.CommandSourceInfo event) {
        // Handle command source permissions
    }
    
    private boolean isLegendaryWeapon(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return false;
        return stack.getTag() != null && stack.getTag().contains("weapon_id") && 
               (stack.getTag().getString("weapon_id").startsWith("paladin") || 
                stack.getTag().getString("weapon_id").startsWith("vulcan") ||
                stack.getTag().getString("weapon_id").startsWith("eclipse"));
    }
}