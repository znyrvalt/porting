package com.altarsmp.fabric.command;

import com.altarsmp.fabric.AltarSMPFabric;
import net.minecraft.world.entity.player.Player;
import net.kyori.adventure.text.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class CommandControlsManager {
    // Per-player settings
    private final Map<UUID, PlayerControls> playerControls = new HashMap<>();
    
    public CommandControlsManager() {
        // Initialize default settings
    }
    
    public PlayerControls getPlayerControls(Player player) {
        UUID uuid = player.getUUID();
        return playerControls.computeIfAbsent(uuid, k -> new PlayerControls());
    }
    
    public boolean shouldSkipDefaultActivation(Player player) {
        PlayerControls controls = getPlayerControls(player);
        return controls.skipDefaultActivation;
    }
    
    public void setAbilityTrust(Player player, UUID trustedPlayer) {
        PlayerControls controls = getPlayerControls(player);
        controls.trustedPlayers.add(trustedPlayer);
    }
    
    public void removeAbilityTrust(Player player, UUID untrustedPlayer) {
        PlayerControls controls = getPlayerControls(player);
        controls.trustedPlayers.remove(untrustedPlayer);
    }
    
    public boolean isTrusted(Player player, UUID targetPlayer) {
        PlayerControls controls = getPlayerControls(player);
        return controls.trustedPlayers.contains(targetPlayer.getUUID());
    }
    
    // Player-specific controls configuration
    public static class PlayerControls {
        public boolean skipDefaultActivation = false; // When true, right-click uses ability instead of default
        public final Set<UUID> trustedPlayers = new HashSet<>();
        
        public PlayerControls() {
            // Default: skipDefaultActivation is false
            // This means right-click behaves normally unless weapon ability is explicitly triggered
        }
    }
    
    // Reset all player controls (e.g., on login/logout)
    public void resetPlayer(UUID playerId) {
        playerControls.remove(playerId);
    }
    
    // Reset all players
    public void resetAll() {
        playerControls.clear();
    }
}