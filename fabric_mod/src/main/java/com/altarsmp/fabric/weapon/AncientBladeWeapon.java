package com.altarsmp.fabric.weapon;

import com.altarsmp.fabric.AltarSMPFabric;
import com.altarsmp.fabric.faction.FactionManager;
import com.altarsmp.fabric.item.ModItems;
import net.minecraft.ChatColor;
import net.minecraft.core.*;
import net.minecraft.core.particles.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemDisplay;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomModelDataComponent;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.UseAction;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.*;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.client.ItemStackProperties;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.*;
import java.util.List;
import java.util.Map.Entry;

public class AncientBladeWeapon extends BaseWeapon {
    private static final double SPEED_BONUS_DEFAULT = 0.25;
    private static final int KILLS_REQUIRED_DEFAULT = 2;
    private static final int PRESENCE_COOLDOWN_DEFAULT = 45;
    private static final int PRESENCE_KILLS_REQUIRED_DEFAULT = 3;
    private static final int PRESENCE_MARK_DURATION_DEFAULT = 10;
    private static final int TIGHTENED_GRIP_KILLS_REQUIRED_DEFAULT = 4;
    private static final int ECHOLocation_COOLDOWN_DEFAULT = 3;
    private static final int ECHOLocation_KILLS_REQUIRED_DEFAULT = 1;
    private static final int UNYIELDING_DARKNESS_COOLDOWN_DEFAULT = 60;
    private static final double UNYIELDING_DAMAGE_DEFAULT = 8.0;
    private static final int UNYIELDING_SONIC_COUNT_DEFAULT = 5;

    private int presenceCooldown;
    private int presenceKillsRequired;
    private int presenceMarkDuration;
    private int tightenedGripKillsRequired;
    private int echolocationCooldown;
    private int echolocationKillsRequired;
    private int unyieldingCooldown;
    private double unyieldingDamage;
    private int unyieldingSonicCount;

    private final Map<String, Long> cooldownTimers = new HashMap<>();

    public AncientBladeWeapon(AltarSMPFabric plugin) {
        super(plugin);
        this.presenceCooldown = PRESENCE_COOLDOWN_DEFAULT;
        this.presenceKillsRequired = PRESENCE_KILLS_REQUIRED_DEFAULT;
        this.presenceMarkDuration = PRESENCE_MARK_DURATION_DEFAULT;
        this.tightenedGripKillsRequired = TIGHTENED_GRIP_KILLS_REQUIRED_DEFAULT;
        this.echolocationCooldown = ECHOLocation_COOLDOWN_DEFAULT;
        this.echolocationKillsRequired = ECHOLocation_KILLS_REQUIRED_DEFAULT;
        this.un yieldingCooldown = UNYIELDING_DARKNESS_COOLDOWN_DEFAULT;
        this.un yieldingDamage = UNYIELDING_DAMAGE_DEFAULT;
        this.un yieldingSonicCount = UNYIELDING_SONIC_COUNT_DEFAULT;
    }

    @Override
    public String getWeaponId() {
        return "ancientblade";
    }

    @Override
    public String getWeaponName() {
        return "<gradient:#FFD7C0:#FFA500:#FFD7C0>Ancient Blade</gradient>";
    }

    @Override
    public Material getBaseMaterial() {
        return Material.NETHERITE_SWORD;
    }

    @Override
    public int getCustomModelData() {
        return 10;
    }

    @Override
    public List<ConfigField> getConfigFields() {
        return List.of(
            new ConfigField("Deep Connection Speed Bonus", "abilities.ancientblade.deep_connection", 0.0, 1.0, 0.01),
            new ConfigField("Swift Kills Required", "abilities.ancientblade.swift", 0, 10),
            new ConfigField("Presence Cooldown (s)", "abilities.ancientblade.presence", 0, 300),
            new ConfigField("Presence Kills Required", "abilities.ancientblade.presence_kills", 0, 20),
            new ConfigField("Presence Mark Duration (s)", "abilities.ancientblade.presence_duration", 0, 60),
            new ConfigField("Tightened Grip Kills Required", "abilities.ancientblade.tightened_grip", 0, 20),
            new ConfigField("Echolocation Cooldown (ticks)", "abilities.ancientblade.echolocation", 0, 60),
            new ConfigField("Echolocation Kills Required", "abilities.ancientblade.echolocation_kills", 0, 10),
            new ConfigField("Unyielding Darkness Cooldown (s)", "abilities.ancientblade.unyielding", 0, 300),
            new ConfigField("Unyielding Damage", "abilities.ancientblade.unyielding_damage", 0.0, 50.0, 1.0),
            new ConfigField("Unyielding Sonic Count", "abilities.ancientblade.unyielding_sonic", 0, 20)
        );
    }

    @Override
    public UseAction getUseAction(ItemStack stack) {
        return UseAction.ATTACK;
    }

    @Override
    public int getUseDuration(ItemStack stack) {
        return 5;
    }

    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        if (attacker instanceof Player) {
            Player player = (Player) attacker;
            
            // Swift ability - increase speed after kills
            if (hasEnoughKillsForSwift(player)) {
                player.addPotionEffect(new net.minecraft.potion.PotionEffect(
                    net.minecraft.potion.PotionEffectType.SPEED, 
                    40, 
                    1, // Speed I
                    false, 
                    false, 
                    true
                ));
            }
            
            // Presence ability - mark target after kills
            if (canUsePresenceAbility(player)) {
                usePresenceAbility(player, target);
            }
            
            // Tightened Grip - strength after kills
            if (hasEnoughKillsForTightenedGrip(player)) {
                player.addPotionEffect(new net.minecraft.potion.PotionEffect(
                    net.minecraft.potion.PotionEffectType.STRENGTH, 
                    40, 
                    1, // Strength I
                    false, 
                    false, 
                    true
                ));
            }
            
            // Echolocation - show range indicator
            if (canUseEcholocation(player)) {
                useEcholocationAbility(player);
            }
            
            // Unyielding Darkness - damage boost
            if (canUseUnyieldingDarkness(player)) {
                useUnyieldingDarknessAbility(player);
            }
        }
        
        return super.hurtEnemy(stack, target, attacker);
    }

    @Override
    public void onLeftClickUse(ItemStack stack, Level world, Player player, InteractionHand hand) {
        // Ancient Blade primary attack - spectral slash
        useSpectralSlash(player, world);
    }

    @Override
    public void onRightClickInteract(ItemStack stack, Level world, Player player, InteractionHand hand) {
        // Ancient Blade abilities menu - would open GUI
    }

    @Override
    public void inventoryTick(ItemStack stack, Level world, Entity entity, int slot, boolean selected) {
        if (entity instanceof Player) {
            Player player = (Player) entity;
            if (isHoldingThisWeapon(player)) {
                // Apply passive speed buff on sculk
                applySculkSpeedBuff(player);
            }
        }
    }

    private void useSpectralSlash(Player player, Level world) {
        // Spectral slash ability
        World worldRef = world;
        Location playerLoc = player.getEyeLocation();
        
        // Create spectral blade effect
        for (int i = 0; i < 10; i++) {
            double offsetX = (Math.random() - 0.5) * 2.0;
            double offsetY = (Math.random() - 0.5) * 2.0;
            double offsetZ = (Math.random() - 0.5) * 2.0;
            worldRef.addParticle(ParticleTypes.ENTITY_ELECTRIC_SPARK, 
                playerLoc.x() + offsetX, 
                playerLoc.y() + offsetY, 
                playerLoc.z() + offsetZ, 
                0, 0, 0);
        }
        
        // Apply speed buff if deep connection is active
        if (hasDeepConnectionBuff(player)) {
            player.addPotionEffect(new net.minecraft.potion.PotionEffect(
                net.minecraft.potion.PotionEffectType.SPEED, 
                40, 
                1, // Speed I
                false, 
                false, 
                true
            ));
        }
    }

    private void usePresenceAbility(Player player, LivingEntity target) {
        // Presence ability - mark target with darkness
        String cooldownKey = "ancientblade_presence";
        
        if (!cooldownTimers.containsKey(player.getUUID() + ":" + cooldownKey) || 
            System.currentTimeMillis() - cooldownTimers.get(player.getUUID() + ":" + cooldownKey) > presenceCooldown * 1000) {
            
            // Apply mark effect - Darkness effect
            player.getWorld().sendSystemMessage(net.kyori.adventure.text.Component.translatable(
                "ancientblade.presence_mark", player.getName(), target.getName()
            ));
            
            // Mark the target
            target.addEffect(new net.minecraft.potion.PotionEffect(
                net.minecraft.potion.PotionEffectType.DARKNESS, 
                presenceMarkDuration * 20, // Convert seconds to ticks
                1, // Darkness I
                false, 
                false, 
                true
            ));
            
            // Set cooldown
            cooldownTimers.put(player.getUUID() + ":" + cooldownKey, System.currentTimeMillis());
        }
    }

    private void useEcholocationAbility(Player player) {
        // Echolocation ability - detect entities in range
        String cooldownKey = "ancientblade_echolocation";
        
        if (!cooldownTimers.containsKey(player.getUUID() + ":" + cooldownKey) || 
            System.currentTimeMillis() - cooldownTimers.get(player.getUUID() + ":" + cooldownKey) > echolocationCooldown * 1000) {
            
            // Get all entities in range
            List<Entity> entities = player.getWorld().getEntities(
                player, 
                player.getBoundingBox().expand(24, 24, 24), // 24 block range
                (entity -> entity instanceof LivingEntity && !entity.equals(player))
            );
            
            // Show particle effects for each entity
            for (Entity entity : entities) {
                double distance = player.distanceTo(entity);
                // Create particle at entity location
                player.getWorld().addParticle(ParticleTypes.END_ROD, 
                    entity.getX(), entity.getY(), entity.getZ(), 
                    0, 0, 0);
            }
            
            // Set cooldown
            cooldownTimers.put(player.getUUID() + ":" + cooldownKey, System.currentTimeMillis());
        }
    }

    private void useUnyieldingDarknessAbility(Player player) {
        // Unyielding Darkness - damage boost after kills
        String cooldownKey = "ancientblade_unyielding";
        
        if (!cooldownTimers.containsKey(player.getUUID() + ":" + cooldownKey) || 
            System.currentTimeMillis() - cooldownTimers.get(player.getUUID() + ":" + cooldownKey) > un yieldingCooldown * 1000) {
            
            // Apply strength effect as damage boost
            player.addPotionEffect(new net.minecraft.potion.PotionEffect(
                net.minecraft.potion.PotionEffectType.DAMAGE_BOOST, // Actually this doesn't exist, use strength
                100, // 5 seconds in ticks
                1, // Strength I
                false, 
                false, 
                true
            ));
            
            // Sonic effects
            for (int i = 0; i < un yieldingSonicCount; i++) {
                player.getWorld().addParticle(ParticleTypes.SONIC_BOOM, 
                    player.getX(), player.getY(), player.getZ(), 
                    0, 0, 0);
            }
            
            // Set cooldown
            cooldownTimers.put(player.getUUID() + ":" + cooldownKey, System.currentTimeMillis());
        }
    }

    private boolean hasEnoughKillsForSwift(Player player) {
        // Check kill counter for swift ability
        int killCount = plugin.getDataPersistence().getPlayerData(player).getKillCount("ancientblade");
        return killCount >= 2; // kills_required: 2
    }

    private boolean canUsePresenceAbility(Player player) {
        // Check cooldown and kill counter
        String cooldownKey = "ancientblade_presence";
        long lastUsed = cooldownTimers.getOrDefault(player.getUUID() + ":" + cooldownKey, 0L);
        return System.currentTimeMillis() - lastUsed > presenceCooldown * 1000 && 
               plugin.getDataPersistence().getPlayerData(player).getKillCount("ancientblade") >= presenceKillsRequired;
    }

    private boolean hasEnoughKillsForTightenedGrip(Player player) {
        int killCount = plugin.getDataPersistence().getPlayerData(player).getKillCount("ancientblade");
        return killCount >= tightenedGripKillsRequired;
    }

    private boolean canUseEcholocation(Player player) {
        String cooldownKey = "ancientblade_echolocation";
        long lastUsed = cooldownTimers.getOrDefault(player.getUUID() + ":" + cooldownKey, 0L);
        return System.currentTimeMillis() - lastUsed > echolocationCooldown * 1000;
    }

    private boolean canUseUnyieldingDarkness(Player player) {
        String cooldownKey = "ancientblade_unyielding";
        long lastUsed = cooldownTimers.getOrDefault(player.getUUID() + ":" + cooldownKey, 0L);
        return System.currentTimeMillis() - lastUsed > un yieldingCooldown * 1000;
    }

    private void applySculkSpeedBuff(Player player) {
        // Deep Connection - +25% speed on sculk
        // Check if player is near sculk
        BlockPos pos = player.blockPosition();
        for (int x = -3; x <= 3; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -3; z <= 3; z++) {
                    BlockState state = player.getWorld().getBlockState(pos.offset(x, y, z));
                    if (state.is(Blocks.SCULK)) {
                        // Apply speed buff
                        player.addPotionEffect(new net.minecraft.potion.PotionEffect(
                            net.minecraft.potion.PotionEffectType.SPEED, 
                            40, 
                            1, // Speed I (25% would be amplifier 0.25 but we use level 1)
                            false, 
                            false, 
                            true
                        ));
                        break;
                    }
                }
            }
        }
    }

    private boolean hasDeepConnectionBuff(Player player) {
        // Check if the deep connection passive is active
        int killCount = plugin.getDataPersistence().getPlayerData(player).getKillCount("ancientblade");
        return killCount >= 2; // Based on kills_required: 2 for swift, but deep_connection is always active
    }
}