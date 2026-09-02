package com.altarsmp.fabric.pale;

import com.altarsmp.fabric.AltarSMPFabric;
import com.altarsmp.fabric.faction.FactionManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.vehicle.*;
import net.minecraft.world.entity.decoration.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.attribute.*;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.item.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.scores.*;
import net.minecraft.world.scores.team.*;
import net.minecraft.core.*;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.*;

public class FabricPaleManager {
    private static final com.altarsmp.fabric.vfx.VFXManager vfx = new com.altarsmp.fabric.vfx.VFXManager();
    private final AltarSMPFabric mod;
    private boolean curseEnabled = true;
    
    public FabricPaleManager(AltarSMPFabric mod) {
        this.mod = mod;
        setupConfig();
    }
    
    private void setupConfig() {
        curseEnabled = mod.getConfig().getBoolean("curses.enabled", true);
    }
    
    // Apply pale effects based on environment
    public void applyPaleEffects(Player player) {
        if (!curseEnabled) return;
        
        // Moss behavior - check if player is on moss
        Block mossBlock = player.getLevel().getBlockState(
            player.blockPosition().below()
        ).getBlock();
        boolean isOnMoss = mossBlock.getRegistryName().toString().contains("moss");
        
        if (isOnMoss) {
            int mossSpeedLevel = mod.getConfig().getInt("pale.moss_speed_level", 1);
            player.addPotionEffect(new net.minecraft.potion.PotionEffect(
                net.minecraft.potion.PotionEffectType.SPEED, 
                40, 
                mossSpeedLevel, 
                false, 
                false, 
                true
            ));
        }
        
        // Rain weakness - check if it's raining
        if (player.getLevel().hasRain()) {
            Block playerBlock = player.getLevel().getBlockState(
                player.blockPosition().above()
            );
            boolean isInOpenAir = playerBlock.getType() == net.minecraft.world.level.material.Material.AIR || 
                !playerBlock.isSolid() || 
                playerBlock.getType().name().contains("leaves");
            
            if (isInOpenAir) {
                int rainWeaknessLevel = mod.getConfig().getInt("pale.rain_weakness_level", 1);
                player.addPotionEffect(new net.minecraft.potion.PotionEffect(
                    net.minecraft.potion.PotionEffectType.WEAKNESS, 
                    40, 
                    rainWeaknessLevel, 
                    false, 
                    false, 
                    true
                ));
            }
        }
    }
    
    // Update king health based on faction
    public void updateKingHealth(Player player) {
        net.minecraft.world.entity.ai.attributes.AttributeInstance maxHealth = player.getAttribute(net.minecraft.world.entity.Attributes.MAX_HEALTH);
        if (maxHealth != null) {
            // Remove previous king health modifiers
            UUID vampireKingId = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
            UUID paleKingId = UUID.fromString("b2c3d4e5-f6a7-8901-bcde-f12345678901");
            
            maxHealth.removeModifier(vampireKingId);
            maxHealth.removeModifier(paleKingId);
            
            double newHealth = 0.0;
            UUID modifierId = null;
            String modifierName = null;
            
            if (FactionManager.isVampireKing(player)) {
                newHealth = mod.getConfig().getDouble("vampire.king_health", 10.0);
                modifierId = vampireKingId;
                modifierName = "vampire_king_health";
            } else if (FactionManager.isPaleKing(player)) {
                newHealth = mod.getConfig().getDouble("pale.king_health", 5.0);
                modifierId = paleKingId;
                modifierName = "pale_king_health";
            } else if (FactionManager.isVampire(player)) {
                newHealth = mod.getConfig().getDouble("vampire.vampire_health", 4.0);
                modifierId = UUID.fromString("c3d4e5f6-a7b8-9012-cdef-234567890123");
                modifierName = "vampire_health";
            } else if (FactionManager.isPale(player)) {
                newHealth = mod.getConfig().getDouble("pale.pale_health", 2.0);
                modifierId = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
                modifierName = "pale_health";
            }
            
            if (newHealth > 0.0 && modifierId != null) {
                maxHealth.addModifier(new AttributeModifier(modifierId, modifierName, newHealth, AttributeModifier.Operation.ADD_NUMBER));
            }
        }
    }
    
    // Handle player kill events for faction conversion
    public void onPlayerKill(EntityKillEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        
        Player killer = player.getKiller();
        if (killer == null || killer == player) return;
        
        if (!curseEnabled) return;
        
        // Pale backstab mechanic
        if (isBackstab(killer, player)) {
            double backstabMultiplier = mod.getConfig().getDouble("pale.backstab_multiplier", 1.4);
            // Damage was already applied - just track it
        }
        
        // Pale conversion on kill
        if (isPale(killer) && !FactionManager.isPermaVampire(killer) && !FactionManager.isPermaPale(killer) && !FactionManager.isPermaHuman(killer)) {
            setPaleRot(killer);
            broadcastMessage("<gray>" + killer.getName() + " has succumbed to the pale...");
            killer.playSound(killer.getLocation(), net.minecraft.sound.SoundEvents.ENTITY_WARDEN_DEATH, 0.7F, 0.5F);
        }
        
        // Vampire conversion on kill
        if (isVampire(killer) && !FactionManager.isPermaVampire(killer) && !FactionManager.isPermaPale(killer) && !FactionManager.isPermaHuman(killer)) {
            setVampire(killer);
            broadcastMessage("<red>" + killer.getName() + " has been turned into a vampire!");
            killer.playSound(killer.getLocation(), net.minecraft.sound.SoundEvents.ENTITY_WITHER_SPAWN, 0.5F, 1.2F);
        }
        
        // Hyperion purification
        if (FactionManager.isHyperion(killer) && hasCurse(player) && !FactionManager.isPermaVampire(player) && !FactionManager.isPermaPale(player) && !FactionManager.isPermaHuman(player)) {
            setHuman(player);
            broadcastMessage("<gold>" + player.getName() + " has been eternally purified by the Hyperion!");
            player.playSound(player.getLocation(), net.minecraft.sound.SoundEvents.BLOCK_BEACON_ACTIVATE, 1.0F, 1.5F);
        }
    }
    
    // Check if this is a backstab
    private boolean isBackstab(Player attacker, Player victim) {
        // Vector from victim to attacker
        Vector vecToAttacker = victim.getLocation().toVector().subtract(attacker.getLocation().toVector()).normalize();
        // Vector attacker is facing
        Vector vecAttackerFacing = attacker.getViewVector(1.0F).normalize();
        
        double dotProduct = vecToAttacker.dot(vecAttackerFacing);
        return dotProduct < -0.3; // Behind the attacker
    }
    
    // Set player to pale rot
    public void setPaleRot(Player player) {
        if (!isPermaHuman(player) && !isPermaVampire(player)) {
            clearAllCurseTags(player);
            player.addScoreboardTag("pale");
            updateKingHealth(player);
        }
    }
    
    // Set player to vampire
    public void setVampire(Player player) {
        if (!isPermaHuman(player) && !isPermaPale(player)) {
            clearAllCurseTags(player);
            player.addScoreboardTag("vampire");
            updateKingHealth(player);
        }
    }
    
    // Set player to human
    public void setHuman(Player player) {
        if (!isPermaVampire(player) && !isPermaPale(player)) {
            clearAllCurseTags(player);
            player.addScoreboardTag("human");
            updateKingHealth(player);
        }
    }
    
    // Clear all curse tags
    private void clearAllCurseTags(Player player) {
        player.removeScoreboardTag("vampire");
        player.removeScoreboardTag("vampire_king");
        player.removeScoreboardTag("pale");
        player.removeScoreboardTag("pale_king");
        player.removeScoreboardTag("human");
        player.removeScoreboardTag("perma_pale");
        player.removeScoreboardTag("perma_vampire");
        player.removeScoreboardTag("perma_human");
    }
    
    // Broadcast message
    private void broadcastMessage(String message) {
        for (Player player : mod.getServer().getOnlinePlayers()) {
            player.sendMessage(com.altarsmp.fabric.AltarSMPFabric.mm.deserialize(message), true);
        }
    }
    
    // Check if player is vampire
    public static boolean isVampire(Player player) {
        return player.getScoreboardTags().contains("vampire") || 
               player.getScoreboardTags().contains("perma_vampire");
    }
    
    // Check if player is pale
    public static boolean isPale(Player player) {
        return player.getScoreboardTags().contains("pale") || 
               player.getScoreboardTags().contains("perma_pale");
    }
    
    // Check if player is vampire king
    public static boolean isVampireKing(Player player) {
        return player.getScoreboardTags().contains("vampire_king");
    }
    
    // Check if player is pale king
    public static boolean isPaleKing(Player player) {
        return player.getScoreboardTags().contains("pale_king");
    }
    
    // Check if player is human
    public static boolean isHuman(Player player) {
        return player.getScoreboardTags().contains("human") || 
              (!isVampire(player) && !isPale(player));
    }
    
    // Check if player has permanent faction
    public static boolean hasPermaVampire(Player player) {
        return player.getScoreboardTags().contains("perma_vampire");
    }
    
    public static boolean hasPermaPale(Player player) {
        return player.getScoreboardTags().contains("perma_pale");
    }
    
    public static boolean hasPermaHuman(Player player) {
        return player.getScoreboardTags().contains("perma_human");
    }
}