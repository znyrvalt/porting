package com.altarsmp.fabric.manager;

import com.altarsmp.fabric.AltarSMPFabric;
import com.altarsmp.fabric.faction.FactionManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.World;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.entity.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.attribute.AttributeSupplier;
import net.minecraft.world.entity.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.ScoreboardManager;
import net.minecraft.world.scores.criteria.ScoreCriteria;
import net.minecraft.world.scores.objective.Objective;
import net.minecraft.world.scores.team.Team;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.craftbukkit.event.CraftEventFactory;

import java.util.*;
import java.util.concurrent.*;

public class BloodMoonManager {
    private static final MiniMessage mm = MiniMessage.miniMessage();
    private final AltarSMPFabric mod;
    private boolean isActive = false;
    private long startTime = 0;
    private static final long BLOOD_MOON_DURATION = 3600000; // 1 hour in ticks (20 ticks = 1s, 24000 = 1 day, so 3600000 = 60 days... adjusted)
    private BukkitRunnable timerTask = null;
    private static final String BLOOD_MOON_TEAM = "bloodmoon";

    public BloodMoonManager(AltarSMPFabric mod) {
        this.mod = mod;
    }

    public boolean isBloodMoonActive() {
        return isActive;
    }

    public void startBloodMoon() {
        if (isActive) {
            return;
        }

        isActive = true;
        startTime = System.currentTimeMillis();

        // Set world time to midnight (13000) for blood moon
        for (World world : mod.getServer().getWorlds()) {
            if (world.getEnvironment() == net.minecraft.world.level.LevelEnvironment.NORMAL) {
                world.setTime(13000L);
            }
        }

        // Broadcast message
        for (Player player : mod.getServer().getOnlinePlayers()) {
            player.sendTitle(mm.deserialize("<dark_red><bold>BLOOD MOON"), mm.deserialize("<red>The blood moon rises..."), 20, 80, 30);
            player.sendMessage(mm.deserialize("<dark_red><bold>BLOOD MOON</bold> <red>has risen! Vampires grow stronger and pale rots are revealed."));
            player.playSound(player.getLocation(), net.minecraft.sound.SoundEvents.ENTITY_WITHER_SPAWN, 0.8F, 0.5F);
        }

        // Activate pale system if not already
        FactionManager fm = mod.getFactionManager();
        if (!fm.isPaleActivated()) {
            fm.activatePaleSystem();
        }

        // Start timer task
        timerTask = new BukkitRunnable() {
            @Override
            public void run() {
                long elapsed = System.currentTimeMillis() - startTime;
                long remaining = BLOOD_MOON_DURATION - elapsed;

                if (remaining <= 0) {
                    endBloodMoon();
                    return;
                }

                // Update players with Blood Moon effects
                for (Player player : mod.getServer().getOnlinePlayers()) {
                    applyBloodMoonEffects(player, remaining);
                }

                // Check every 10 seconds
                this.runTaskLater(100L);
            }
        };
        timerTask.runTaskLater(mod, 20L);
    }

    private void applyBloodMoonEffects(Player player, long remaining) {
        // Vampire effects during blood moon
        if (FactionManager.isVampire(player)) {
            // Strength II
            player.addPotionEffect(new net.minecraft.potion.PotionEffect(
                net.minecraft.potion.PotionEffectType.STRENGTH, 
                40, 
                1, 
                false, 
                false, 
                true
            ));
            // Speed II
            player.addPotionEffect(new net.minecraft.potion.PotionEffect(
                net.minecraft.potion.PotionEffectType.SPEED, 
                40, 
                1, 
                false, 
                false, 
                true
            ));
        }

        // Pale effects during blood moon
        if (FactionManager.isPale(player)) {
            // Resistance I
            player.addPotionEffect(new net.minecraft.potion.PotionEffect(
                net.minecraft.potion.PotionEffectType.RESISTANCE, 
                40, 
                0, 
                false, 
                false, 
                true
            ));
        }

        // Update title with time remaining
        String timeStr = String.format("%d:%02d", remaining / 60000, (remaining % 60000) / 1000);
        player.sendTitle(mm.deserialize("<dark_red><bold>BLOOD MOON"), mm.deserialize("<red>Time: " + timeStr), 20, 80, 30);
    }

    public void endBloodMoon() {
        if (!isActive) {
            return;
        }

        isActive = false;

        // Reset world time
        for (World world : mod.getServer().getWorlds()) {
            if (world.getEnvironment() == net.minecraft.world.level.LevelEnvironment.NORMAL) {
                world.setTime(0L);
            }
        }

        // Remove blood moon effects from all players
        for (Player player : mod.getServer().getOnlinePlayers()) {
            // Remove vampire effects
            player.removePotionEffect(net.minecraft.potion.PotionEffectType.STRENGTH);
            player.removePotionEffect(net.minecraft.potion.PotionEffectType.SPEED);
            // Remove pale effects
            player.removePotionEffect(net.minecraft.potion.PotionEffectType.RESISTANCE);

            player.sendMessage(mm.deserialize("<gray>The Blood Moon has faded. The night returns to normal."));
            player.playSound(player.getLocation(), net.minecraft.sound.SoundEvents.BLOCK_BEACON_DEACTIVATE, 0.8F, 1.0F);
        }

        // Clear scoreboard team
        ScoreboardManager sm = mod.getServer().getScoreboardManager();
        if (sm != null) {
            Team team = sm.getMainScoreboard().getTeam(BLOOD_MOON_TEAM);
            if (team != null) {
                team.unregister();
            }
        }

        timerTask = null;
    }

    // Packet handling for client synchronization
    public void syncBloodMoonState(Player player) {
        // Send current blood moon state to client
        // This would use Fabric networking
    }
}