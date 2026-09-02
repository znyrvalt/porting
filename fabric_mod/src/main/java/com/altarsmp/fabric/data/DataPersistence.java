package com.altarsmp.fabric.data;

import com.altarsmp.fabric.AltarSMPFabric;
import com.altarsmp.fabric.weapon.BaseWeapon;
import com.altarsmp.fabric.faction.FactionManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.util.INBTWritable;
import net.minecraftforge.common.util.INBTReader;

import java.util.UUID;
import java.util.Map;
import java.util.WeakHashMap;

public class DataPersistence implements INBTWritable, INBTReader {
    private final AltarSMPFabric mod;
    private final Map<UUID, PlayerData> playerDataMap = new WeakHashMap<>();
    
    public DataPersistence(AltarSMPFabric mod) {
        this.mod = mod;
        loadExistingData();
    }
    
    private void loadExistingData() {
        // Load any existing persistent data from previous sessions
        // This would read from world NBT or a data storage file
        AltarSMPFabric.LOGGER.info("Loading existing player data...");
    }
    
    // Player-specific data
    public static class PlayerData implements INBTWritable, INBTReader {
        private final UUID playerId;
        private final Map<String, Integer> weaponKillCounters = new HashMap<>();
        private final Map<String, Integer> factionKillCounters = new HashMap<>();
        private boolean permanentFaction = false; // True Vampire/Pale/Human
        private String permanentFactionType = ""; // vampire, pale, human
        private long lastLogout = 0;
        private long lastLogin = 0;
        
        public PlayerData(UUID playerId) {
            this.playerId = playerId;
        }
        
        public UUID getPlayerId() {
            return playerId;
        }
        
        // Weapon kill counters
        public int getKillCount(String weaponId) {
            return weaponKillCounters.getOrDefault(weaponId, 0);
        }
        
        public void incrementKillCount(String weaponId) {
            weaponKillCounters.merge(weaponId, 1, Integer::sum);
        }
        
        public void setKillCount(String weaponId, int count) {
            weaponKillCounters.put(weaponId, count);
        }
        
        // Faction kill counters
        public int getFactionKillCount(String faction) {
            return factionKillCounters.getOrDefault(faction, 0);
        }
        
        public void incrementFactionKillCount(String faction) {
            factionKillCounters.merge(faction, 1, Integer::sum);
        }
        
        // Permanent faction state
        public boolean isPermanentFaction() {
            return permanentFaction;
        }
        
        public String getPermanentFactionType() {
            return permanentFactionType;
        }
        
        public void setPermanentFaction(boolean permanent, String factionType) {
            this.permanentFaction = permanent;
            this.permanentFactionType = factionType;
        }
        
        // Login/logout tracking
        public long getLastLogout() {
            return lastLogout;
        }
        
        public void setLastLogout(long timestamp) {
            this.lastLogout = timestamp;
        }
        
        public long getLastLogin() {
            return lastLogin;
        }
        
        public void setLastLogin(long timestamp) {
            this.lastLogin = timestamp;
        }
        
        // Serialize to NBT
        @Override
        public CompoundTag writeNBT(CompoundTag tag) {
            tag.putUUID("player_id", playerId);
            
            // Weapon kill counters
            CompoundTag killsTag = new CompoundTag();
            for (Map.Entry<String, Integer> entry : weaponKillCounters.entrySet()) {
                killsTag.putInt(entry.getKey(), entry.getValue());
            }
            tag.put("weapon_kills", killsTag);
            
            // Faction kill counters
            CompoundTag factionKillsTag = new CompoundTag();
            for (Map.Entry<String, Integer> entry : factionKillCounters.entrySet()) {
                factionKillsTag.putInt(entry.getKey(), entry.getValue());
            }
            tag.put("faction_kills", factionKillsTag);
            
            // Permanent faction state
            tag.putBoolean("permanent_faction", permanentFaction);
            if (!permanentFactionType.isEmpty()) {
                tag.putString("permanent_faction_type", permanentFactionType);
            }
            
            // Login/logout tracking
            tag.putLong("last_logout", lastLogout);
            tag.putLong("last_login", lastLogin);
            
            return tag;
        }
        
        // Deserialize from NBT
        @Override
        public void readNBT(CompoundTag tag) {
            playerId = tag.getUUID("player_id");
            
            // Weapon kill counters
            if (tag.contains("weapon_kills", 10)) {
                CompoundTag killsTag = tag.getCompound("weapon_kills");
                for (String key : killsTag.getAllKeys()) {
                    weaponKillCounters.put(key, killsTag.getInt(key));
                }
            }
            
            // Faction kill counters
            if (tag.contains("faction_kills", 10)) {
                CompoundTag factionKillsTag = tag.getCompound("faction_kills");
                for (String key : factionKillsTag.getAllKeys()) {
                    factionKillCounters.put(key, factionKillsTag.getInt(key));
                }
            }
            
            // Permanent faction state
            permanentFaction = tag.getBoolean("permanent_faction");
            if (tag.contains("permanent_faction_type", 8)) {
                permanentFactionType = tag.getString("permanent_faction_type");
            }
            
            // Login/logout tracking
            lastLogout = tag.getLong("last_logout");
            lastLogin = tag.getLong("last_login");
        }
    }
    
    // Get player data, creating if necessary
    public PlayerData getPlayerData(Player player) {
        UUID uuid = player.getUUID();
        return playerDataMap.computeIfAbsent(uuid, PlayerData::new);
    }
    
    // Save all player data
    public void saveAllData() {
        for (PlayerData data : playerDataMap.values()) {
            // Write to world NBT or dedicated data storage
            // This is called on server shutdown or reload
        }
        AltarSMPFabric.LOGGER.info("Saved data for {} players", playerDataMap.size());
    }
    
    // Check if player has permanent faction tag
    public boolean hasPermanentFaction(Player player) {
        PlayerData data = getPlayerData(player);
        return data.isPermanentFaction();
    }
    
    // Get permanent faction type
    public String getPermanentFactionType(Player player) {
        PlayerData data = getPlayerData(player);
        return data.getPermanentFactionType();
    }
}