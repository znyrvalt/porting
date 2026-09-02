package com.altarsmp.fabric.faction;

import com.altarsmp.fabric.AltarSMPFabric;
import net.minecraft.world.entity.player.Player;
import net.minecraft.scoreboard.Team;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.world.scoreboard.ScoredTeam;

import java.util.*;

public class FactionManager {
    public static final String FACTION_HUMAN = "human";
    public static final String FACTION_VAMPIRE = "vampire";
    public static final String FACTION_PALE = "pale";
    public static final String FACTION_HYPERION = "hyperion";

    private Map<String, Faction> factions = new HashMap<>();
    private Map<UUID, String> playerFactions = new HashMap<>();
    private Map<UUID, Integer> playerKillCounters = new HashMap<>();

    public FactionManager() {
        // Initialize default factions
        initFactions();
    }

    private void initFactions() {
        factions.put(FACTION_HUMAN, new Faction(FACTION_HUMAN, "Human"));
        factions.put(FACTION_VAMPIRE, new Faction(FACTION_VAMPIRE, "Vampire"));
        factions.put(FACTION_PALE, new Faction(FACTION_PALE, "Pale"));
        factions.put(FACTION_HYPERION, new Faction(FACTION_HYPERION, "Hyperion"));
    }

    public Faction getFaction(String id) {
        return factions.get(id);
    }

    public Faction getPlayerFaction(UUID playerId) {
        return factions.get(playerFactions.get(playerId));
    }

    public String getPlayerFactionId(UUID playerId) {
        return playerFactions.get(playerId);
    }

    public void setPlayerFaction(UUID playerId, String factionId) {
        // Remove from previous faction
        String oldFactionId = playerFactions.remove(playerId);
        if (oldFactionId != null) {
            Faction oldFaction = factions.get(oldFactionId);
            if (oldFaction != null) {
                oldFaction.removeMember(playerId);
            }
        }

        // Add to new faction
        Faction newFaction = factions.get(factionId);
        if (newFaction != null) {
            newFaction.addMember(playerId);
            playerFactions.put(playerId, factionId);
            AltarSMPFabric.LOGGER.info("Player {} set to faction {}", playerId, factionId);
        } else {
            // Default to human
            Faction humanFaction = factions.get(FACTION_HUMAN);
            if (humanFaction != null) {
                humanFaction.addMember(playerId);
                playerFactions.put(playerId, FACTION_HUMAN);
            }
        }
    }

    public void addKill(UUID playerId, UUID killerId) {
        // Add kill counter for the killer
        playerKillCounters.merge(killerId, 1L, Long::sum);

        // Handle faction-based kill effects
        Faction killerFaction = getFaction(playerKillCounters.keySet());
        // ... handle kill effects based on faction
    }

    public long getKillCount(UUID playerId) {
        return playerKillCounters.getOrDefault(playerId, 0L);
    }

    public void grantVampire(Player player) {
        setPlayerFaction(player.getUUID(), FACTION_VAMPIRE);
        // Apply vampire effects
        player.addPotionEffect(new net.minecraft.potion.PotionEffect(
            net.minecraft.potion.PotionEffectType.NIGHT_VISION, 
            600,  // 30 seconds at 20 tps
            0, 
            false, 
            false, 
            true
        ));
    }

    public void grantPale(Player player) {
        setPlayerFaction(player.getUUID(), FACTION_PALE);
        // Apply pale effects
        player.addPotionEffect(new net.minecraft.potion.PotionEffect(
            net.minecraft.potion.PotionEffectType.SPEED, 
            600, 
            1, 
            false, 
            false, 
            true
        ));
    }

    public void convertToHuman(Player player) {
        setPlayerFaction(player.getUUID(), FACTION_HUMAN);
        // Remove all curse effects
        player.removePotionEffect(net.minecraft.potion.PotionEffectType.STRENGTH);
        player.removePotionEffect(net.minecraft.potion.PotionEffectType.SPEED);
        player.removePotionEffect(net.minecraft.potion.PotionEffectType.FIRE_RESISTANCE);
        player.removePotionEffect(net.minecraft.potion.PotionEffectType.WEAKNESS);
        player.removePotionEffect(net.minecraft.potion.PotionEffectType.RESISTANCE);
    }

    public boolean isVampire(Player player) {
        return FACTION_VAMPIRE.equals(playerFactions.get(player.getUUID()));
    }

    public boolean isPale(Player player) {
        return FACTION_PALE.equals(playerFactions.get(player.getUUID()));
    }

    public boolean isHuman(Player player) {
        return FACTION_HUMAN.equals(playerFactions.get(player.getUUID()));
    }

    public boolean isHyperion(Player player) {
        return FACTION_HYPERION.equals(playerFactions.get(player.getUUID()));
    }

    public Faction getFactionById(String id) {
        return factions.get(id);
    }

    public Collection<Faction> getAllFactions() {
        return factions.values();
    }

    public static class Faction {
        private final String id;
        private final String name;
        private final Set<UUID> members = new HashSet<>();

        public Faction(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public Set<UUID> getMembers() { return members; }
        public int getMemberCount() { return members.size(); }

        public void addMember(UUID playerId) {
            members.add(playerId);
        }

        public void removeMember(UUID playerId) {
            members.remove(playerId);
        }

        public boolean hasMember(UUID playerId) {
            return members.contains(playerId);
        }
    }
}