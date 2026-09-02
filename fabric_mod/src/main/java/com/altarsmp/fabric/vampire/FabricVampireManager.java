package com.altarsmp.fabric.vampire;

import com.altarsmp.fabric.AltarSMPFabric;
import com.altarsmp.fabric.faction.FactionManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.vehicle.*;
import net.minecraft.world.entity.decoration.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.attribute.*;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomModelDataComponent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.scores.*;
import net.minecraft.world.scores.team.*;
import net.minecraft.network.syncher.*;
import net.minecraft.core.*;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.*;

public class FabricVampireManager {
    private static final com.altarsmp.fabric.vfx.VFXManager vfx = new com.altarsmp.fabric.vfx.VFXManager();
    private final AltarSMPFabric mod;
    private boolean curseEnabled = true;
    
    public FabricVampireManager(AltarSMPFabric mod) {
        this.mod = mod;
        setupConfig();
    }
    
    private void setupConfig() {
        // Load config values
        curseEnabled = mod.getConfig().getBoolean("curses.enabled", true);
    }
    
    // Apply vampire effects based on time of day
    public void applyVampireEffects(Player player) {
        if (!curseEnabled) return;
        
        long worldTime = player.getLevel().getTime();
        boolean isNight = worldTime >= 0 && worldTime < 12300;
        boolean isDay = !isNight;
        
        // Daytime consequences - vampires take fire damage
        if (isDay && player.getLocation().getBlock().getLightFromSky() >= 15) {
            player.setFireTicks(40); // 2 seconds of fire
        }
        
        // Night effects - only if enabled
        if (isNight && mod.getConfig().getBoolean("vampire.enable_night_effects", true)) {
            int nightStrengthLevel = mod.getConfig().getInt("vampire.night_strength_level", 0);
            int nightSpeedLevel = mod.getConfig().getInt("vampire.night_speed_level", 1);
            
            // Strength effect
            player.addPotionEffect(new net.minecraft.potion.PotionEffect(
                net.minecraft.potion.PotionEffectType.STRENGTH, 
                40, // 2 seconds at 20tps
                nightStrengthLevel, 
                false, 
                false, 
                true
            ));
            
            // Speed effect
            player.addPotionEffect(new net.minecraft.potion.PotionEffect(
                net.minecraft.potion.PotionEffectType.SPEED, 
                40, 
                nightSpeedLevel, 
                false, 
                false, 
                true
            ));
            
            // Fire Resistance (configurable)
            if (mod.getConfig().getBoolean("vampire.night_fire_resistance", true)) {
                player.addPotionEffect(new net.minecraft.potion.PotionEffect(
                    net.minecraft.potion.PotionEffectType.FIRE_RESISTANCE, 
                    40, 
                    0, 
                    false, 
                    false, 
                    true
                ));
            }
        }
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
            boolean isInOpenAir = playerBlock.getType() == Material.AIR || 
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
            // Remove previous king/queen health modifiers
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
                modifierId = UUID.fromString("c3d4e5f6-a7b8-9012-cdef-234567890123");
                modifierName = "pale_health";
            }
            
            if (newHealth > 0.0 && modifierId != null) {
                maxHealth.addModifier(new AttributeModifier(modifierId, modifierName, newHealth, AttributeModifier.Operation.ADD_NUMBER));
            }
        }
    }
    
    // Handle player kill events for faction conversion
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Player killer = player.getKiller();
        
        if (killer == null || killer == player) return;
        
        if (!curseEnabled) return;
        
        // Nightpiercer kill conversion
        // If killed by Nightpiercer and config allows, convert to permanent vampire
        if (NightpiercerWeaponWasUsed(player, killer)) {
            if (mod.getConfig().getBoolean("nightpiercer.perma_vampire_on_kill", false)) {
                setPermaVampire(killer);
                broadcastMessage("<dark_red>" + killer.getName() + "'s soul has been eternally bound to the night...");
                killer.playSound(killer.getLocation(), net.minecraft.sound.SoundEvents.ENTITY_WITHER_SPAWN, 0.7F, 0.5F);
                return;
            }
        }
        
        // Pale crossbow kill conversion
        if (PaleCrossbowWeaponWasUsed(player, killer)) {
            setPermaPaleRot(killer);
            broadcastMessage("<dark_gray>" + killer.getName() + "'s soul has been eternally consumed by the pale...");
            killer.playSound(killer.getLocation(), net.minecraft.sound.SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 0.7F, 0.3F);
            return;
        }
        
        // Regular vampire conversion
        if (isVampire(killer) && !FactionManager.isPermaVampire(killer) && !FactionManager.isPermaPale(killer) && !FactionManager.isPermaHuman(killer)) {
            setVampire(killer);
            broadcastMessage("<red>" + killer.getName() + " has been turned into a vampire!");
            killer.playSound(killer.getLocation(), net.minecraft.sound.SoundEvents.ENTITY_WITHER_SPAWN, 0.5F, 1.2F);
        }
        
        // Pale conversion from kills
        if (isPale(killer) && !FactionManager.isPermaVampire(killer) && !FactionManager.isPermaPale(killer) && !FactionManager.isPermaHuman(killer)) {
            setPaleRot(killer);
            broadcastMessage("<gray>" + killer.getName() + " has succumbed to the pale...");
            killer.playSound(killer.getLocation(), net.minecraft.sound.SoundEvents.ENTITY_WARDEN_DEATH, 0.7F, 0.5F);
        }
        
        // Hyperion purification
        if (FactionManager.isHyperion(killer) && hasCurse(player) && !FactionManager.isPermaVampire(player) && !FactionManager.isPermaPale(player) && !FactionManager.isPermaHuman(player)) {
            setHuman(player);
            broadcastMessage("<gold>" + player.getName() + " has been eternally purified by the Hyperion!");
            player.playSound(player.getLocation(), net.minecraft.sound.SoundEvents.BLOCK_BEACON_ACTIVATE, 1.0F, 1.5F);
        }
        
        // Pale-to-pale conversion on kill
        if (FactionManager.isPale(killer) && FactionManager.isPale(player) && !FactionManager.isPermaPale(killer) && !FactionManager.isPermaHuman(player) && !FactionManager.isPermaVampire(player)) {
            setPaleRot(player);
            broadcastMessage("<dark_gray>" + player.getName() + " has been consumed by the pale...");
            killer.playSound(killer.getLocation(), net.minecraft.sound.SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 0.7F, 0.3F);
        }
        
        // If pale is active and victim has paleaffect tag
        if (FactionManager.isPaleActivated() && player.hasScoreboardTag("paleaffect")) {
            setPaleRot(player);
            player.removeScoreboardTag("paleaffect");
        }
    }
    
    // Check if Nightpiercer was used (simplified)
    private boolean NightpiercerWeaponWasUsed(Player victim, Player attacker) {
        // Check if victim was killed by nightpiercer ability
        return false; // Placeholder - would check NBT or event data
    }
    
    // Check if Pale Crossbow was used
    private boolean PaleCrossbowWeaponWasUsed(Player victim, Player attacker) {
        // Check if victim was killed by pale crossbow
        return false; // Placeholder
    }
    
    // Set player to vampire
    public void setVampire(Player player) {
        if (!isPermaVampire(player) && !isPermaPale(player)) {
            clearAllCurseTags(player);
            player.addScoreboardTag("vampire");
            player.addScoreboardTag("vampire_king"); // Default to king status, can be changed later
            updateKingHealth(player);
            player.sendMessage(com.altarsmp.fabric.AltarSMPFabric.mm.deserialize(
                "<dark_red>You feel the thirst for blood... You are a vampire."
            ));
            player.playSound(player.getLocation(), net.minecraft.sound.SoundEvents.ENTITY_WITHER_SPAWN, 0.7F, 0.5F);
        }
    }
    
    // Set player to pale rot
    public void setPaleRot(Player player) {
        if (!isPermaHuman(player) && !isPermaVampire(player)) {
            clearAllCurseTags(player);
            player.addScoreboardTag("pale");
            player.addScoreboardTag("paleaffect"); // For blood moon tracking
            updateKingHealth(player);
            player.sendMessage(com.altarsmp.fabric.AltarSMPFabric.mm.deserialize(
                "<gray>You feel rotted inside... You are a pale rot."
            ));
        }
    }
    
    // Set player to pale king
    public void setPaleKing(Player player) {
        if (!isPermaHuman(player) && !isPermaVampire(player)) {
            clearAllCurseTags(player);
            player.addScoreboardTag("pale");
            player.addScoreboardTag("pale_king");
            updateKingHealth(player);
            player.sendMessage(com.altarsmp.fabric.AltarSMPFabric.mm.deserialize(
                "<dark_gray>The pale still flows through you... You are the Pale King."
            ));
        }
    }
    
    // Set player to permanent vampire
    public void setPermaVampire(Player player) {
        if (!isPermaHuman(player) && !isPermaPale(player)) {
            clearAllCurseTags(player);
            player.addScoreboardTag("vampire");
            player.addScoreboardTag("perma_vampire");
            updateKingHealth(player);
            player.sendMessage(com.altarsmp.fabric.AltarSMPFabric.mm.deserialize(
                "<dark_red>Your veins run eternally cold... You are a True Vampire."
            ));
        }
    }
    
    // Set player to permanent pale rot
    public void setPermaPaleRot(Player player) {
        if (!isPermaHuman(player) && !isPermaVampire(player)) {
            clearAllCurseTags(player);
            player.addScoreboardTag("pale");
            player.addScoreboardTag("perma_pale");
            updateKingHealth(player);
            player.sendMessage(com.altarsmp.fabric.AltarSMPFabric.mm.deserialize(
                "<dark_gray>The pale has consumed your soul forever..."
            ));
        }
    }
    
    // Set player to pale (non-king)
    public void setPaleRot(Player player) {
        if (!isPermaHuman(player) && !isPermaVampire(player)) {
            clearAllCurseTags(player);
            player.addScoreboardTag("pale");
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
    
    // Set perma human
    public void setPermaHuman(Player player) {
        if (!isPermaVampire(player) && !isPermaPale(player)) {
            boolean allowPerma = mod.getConfig().getBoolean("curses.allow_perma", false);
            boolean allowPermaHuman = mod.getConfig().getBoolean("curses.allow_perma_human", false);
            
            if (allowPerma && allowPermaHuman) {
                clearAllCurseTags(player);
                player.addScoreboardTag("human");
                player.addScoreboardTag("perma_human");
                updateKingHealth(player);
                player.sendMessage(com.altarsmp.fabric.AltarSMPFabric.mm.deserialize(
                    "<gold>You have been purified by divine light. You are forever immune to curses."
                ));
            } else {
                setHuman(player);
            }
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
        player.removeScoreboardTag("paleaffect");
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
    
    // Activate pale system globally
    public void activatePaleSystem() {
        // This would be called when Blood Moon starts
        // Sets up the initial pale state for all players
    }
    
    // Broadcast message to all players
    private void broadcastMessage(String message) {
        for (Player player : mod.getServer().getOnlinePlayers()) {
            player.sendMessage(com.altarsmp.fabric.AltarSMPFabric.mm.deserialize(message), true);
        }
    }
    
    // Handle player join event
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        updatePlayerNameColor(player);
        updateKingHealth(player);
    }
    
    // Handle player respawn
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getEntity();
        updateKingHealth(player);
        
        if (isVampireKing(player)) {
            player.sendMessage(com.altarsmp.fabric.AltarSMPFabric.mm.deserialize(
                "<dark_red>You feel the eternal thirst... You are the Vampire King."
            ));
            player.getWorld().spawnParticle(net.minecraft.world.entity.ParticleOptions.PARTICLE, 
                net.minecraft.world.level.particle.ParticleTypes.SPELL_MOB, 
                player.getLocation(), 40, 1.0, 1.0, 1.0);
        } else if (isVampire(player)) {
            player.sendMessage(com.altarsmp.fabric.AltarSMPFabric.mm.deserialize(
                "<red>You feel the thirst for blood... You are a vampire."
            ));
            player.getWorld().spawnParticle(net.minecraft.world.entity.ParticleOptions.PARTICLE, 
                net.minecraft.world.level.particle.ParticleTypes.SPELL_MOB, 
                player.getLocation(), 30, 1.0, 1.0, 1.0);
        } else if (isPaleKing(player)) {
            player.sendMessage(com.altarsmp.fabric.AltarSMPFabric.mm.deserialize(
                "<dark_gray>The pale still flows through you... You are the Pale King."
            ));
        } else if (isPale(player)) {
            player.sendMessage(com.altarsmp.fabric.AltarSMPFabric.mm.deserialize(
                "<gray>You feel rotted inside... You are a pale rot."
            ));
        }
    }
    
    // Handle player move event (moss tracking)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Block mossBlock = player.getLevel().getBlockState(
            player.blockPosition().below()
        ).getBlock();
        
        String mossType = mossBlock.getRegistryName().toString();
        if (mossType.contains("moss")) {
            if (!player.getScoreboardTags().contains("paleaffect")) {
                player.addScoreboardTag("paleaffect");
            }
        } else {
            player.removeScoreboardTag("paleaffect");
        }
    }
    
    // Handle entity damage event (vampire night damage bonus)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            long worldTime = player.getLevel().getTime();
            boolean isNight = worldTime >= 0 && worldTime < 12300;
            boolean isDay = !isNight;
            
            // Hyperion bonus damage
            if (FactionManager.isHyperion(player) && isDay) {
                double bonus = mod.getConfig().getDouble("vampire.hyperion_bonus", 2.0);
                event.setNewDamage(event.getDamage() + bonus);
            }
            
            // Night damage bonus
            if (isNight && FactionManager.isVampire(player)) {
                boolean bloodMoonActive = mod.getBloodMoonManager() != null && mod.getBloodMoonManager().isBloodMoonActive();
                boolean disableNightDamageStack = mod.getConfig().getBoolean("bloodmoon.disable-night-damage-stack", false);
                
                if (!bloodMoonActive || !disableNightDamageStack) {
                    double nightDamage = mod.getConfig().getDouble("vampire.night_damage", 2.0);
                    event.setNewDamage(event.getDamage() + nightDamage);
                }
            }
            
            // Backstab damage (Pale)
            if (FactionManager.isPale(player)) {
                // Check if this is a backstab - would need to check player velocity/direction
                // relative to attacker
                double backstabMultiplier = mod.getConfig().getDouble("pale.backstab_multiplier", 1.4);
                // This would be applied in the actual damage event handler
            }
        }
    }
    
    // Handle blood moon effects on damage
    public void onBloodMoonDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            long worldTime = player.getLevel().getTime();
            boolean isNight = worldTime >= 0 && worldTime < 12300;
            
            if (isBloodMoonActive() && FactionManager.isVampire(player)) {
                // Vampires get stronger during blood moon
                double strengthBonus = 1.0; // Additional strength
                event.setNewDamage(event.getDamage() * strengthBonus);
            }
            
            if (isBloodMoonActive() && FactionManager.isPale(player)) {
                // Pale get resistance during blood moon
                event.setNewDamage(event.getDamage() * 0.5); // Reduced damage
            }
        }
    }
    
    // Check if blood moon is active
    private boolean isBloodMoonActive() {
        return mod.getBloodMoonManager() != null && mod.getBloodMoonManager().isBloodMoonActive();
    }
}