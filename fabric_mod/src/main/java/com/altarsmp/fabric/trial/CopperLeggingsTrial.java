package com.altarsmp.fabric.trial;

import com.altarsmp.fabric.AltarSMPFabric;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraftforge.event.entity.player.*;
import net.minecraftforge.common.*;

import java.util.*;

public class CopperLeggingsTrial {
    private static final long DURATION_TICKS = 2700000; // 45 minutes (20 ticks = 1s, 2700000 / 20 = 135000 ticks... wait that's wrong)
    private static final long DURATION_MS = 2700000; // 45 minutes in milliseconds
    private static boolean isRunning = false;
    private static UUID currentHolder = null;
    private static final Map<UUID, Long> holdTimes = new HashMap<>();
    private static ItemStack coreItem = null;

    public CopperLeggingsTrial() {
        registerEvents();
    }

    private void registerEvents() {
        // Register player hit events for core transfer
        // Register player quit events
        // Register drop item events
    }

    // Start the hot potato trial
    public static void startLeggingsTrial() {
        if (isRunning) {
            return;
        }

        isRunning = true;
        currentHolder = null;
        holdTimes.clear();
        coreItem = createHotPotatoItem();

        // Give the core to a random player
        List<Player> players = getAllPlayers();
        if (!players.isEmpty()) {
            Player randomPlayer = players.get(new Random().nextInt(players.size()));
            givePotatoToPlayer(randomPlayer);
        }

        // Start the timer
        startTimer();

        broadcastMessage("<gold><bold>Copper Core Trial (Leggings) Started!<br/>"
            + "<gray>Duration: 45 minutes<br/>"
            + "<gray>Hit another player to transfer the core!"
        );
    }

    private ItemStack createHotPotatoItem() {
        return new ItemStack(Items.HEAVY_CORE) // Using heavy core as the base
            .copy()
            .setTag(new CompoundTag() {{
                putString("id", "hot_potato");
                putString("description", "Copper Infused Heavy Core");
            }});
    }

    private void givePotatoToPlayer(Player player) {
        currentHolder = player.getUUID();
        player.getInventory().add(createHotPotatoItem());
        player.addPotionEffect(new net.minecraft.potion.PotionEffect(
            net.minecraft.potion.PotionEffectType.GLOWING,
            Integer.MAX_VALUE,
            0,
            false,
            false
        ));

        broadcastMessage("<red>" + player.getName() + " <gold>now holds the Copper Core!");
    }

    private List<Player> getAllPlayers() {
        List<Player> result = new ArrayList<>();
        for (World world : net.minecraft.server.MinecraftServer.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Player) {
                    result.add((Player) entity);
                }
            }
        }
        return result;
    }

    private void startTimer() {
        // Schedule the 45-minute timer
        // At the end, determine the winner based on longest total hold time
    }

    // Transfer the core from one player to another
    public static void transferCore(Player fromPlayer, Player toPlayer) {
        if (!isRunning || currentHolder == null) {
            return;
        }

        // Remove from previous holder
        removePotatoFromPlayer(fromPlayer);

        // Give to new holder
        givePotatoToPlayer(toPlayer);

        // Record hold time
        long startTime = holdTimes.getOrDefault(fromPlayer.getUUID(), System.currentTimeMillis());
        long holdDuration = System.currentTimeMillis() - startTime;
        holdTimes.merge(fromPlayer.getUUID(), holdDuration, Long::sum);
    }

    private void removePotatoFromPlayer(Player player) {
        // Remove the core item from player's inventory
        for (ItemStack stack : player.getInventory().getContents()) {
            if (CopperLeggingsTrial.isHotPotato(stack)) {
                stack.setAmount(0);
                break;
            }
        }
        player.removePotionEffect(net.minecraft.potion.PotionEffectType.GLOWING);
    }

    public static boolean isHotPotato(ItemStack stack) {
        return stack != null && stack.hasTag() && "hot_potato".equals(stack.getTag().getString("id"));
    }

    public static boolean isRunning() {
        return isRunning;
    }

    public static long getTotalHoldTime(UUID playerId) {
        return holdTimes.getOrDefault(playerId, 0L);
    }

    public static void stopLeggingsTrial() {
        if (!isRunning) {
            return;
        }

        isRunning = false;

        // Determine winner (longest total hold time)
        UUID winner = null;
        long maxTime = 0;
        for (Map.Entry<UUID, Long> entry : holdTimes.entrySet()) {
            if (entry.getValue() > maxTime) {
                maxTime = entry.getValue();
                winner = entry.getKey();
            }
        }

        if (winner != null) {
            Player winnerPlayer = getPlayerByUUID(winner);
            if (winnerPlayer != null) {
                broadcastMessage("<green><bold>Winner: " + winnerPlayer.getName() + " (" + formatTime(maxTime / 1000) + ")");
            }
        }

        // Clear all players' glowing effect
        for (World world : net.minecraft.server.MinecraftServer.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Player) {
                    entity.removePotionEffect(net.minecraft.potion.PotionEffectType.GLOWING);
                }
            }
        }

        coreItem = null;
    }

    private String formatTime(long seconds) {
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;
        return minutes + ":" + String.format("%02d", remainingSeconds);
    }

    private Player getPlayerByUUID(UUID uuid) {
        for (World world : net.minecraft.server.MinecraftServer.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Player && ((Player) entity).getUUID().equals(uuid)) {
                    return (Player) entity;
                }
            }
        }
        return null;
    }

    private void broadcastMessage(String message) {
        for (World world : net.minecraft.server.MinecraftServer.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Player) {
                    ((Player) entity).sendMessage(net.kyori.adventure.text.Component.text(message), true);
                }
            }
        }
    }
}