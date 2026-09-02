package com.altarsmp.fabric.weapon;

import com.altarsmp.fabric.AltarSMPFabric;
import net.minecraft.ChatColor;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteCodeAdapter;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.ai.pathing.PathNodeType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomModelDataComponent;
import net.minecraft.world.item.component.ItemStacks;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.UseAction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.nbt.CompoundTag;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.*;
import java.util.function.*;

public abstract class BaseWeapon implements net.minecraft.world.item.UseAnim {
    protected final AltarSMPFabric plugin;
    protected final NamespacedKey WEAPON_KEY;

    public BaseWeapon(AltarSMPFabric plugin) {
        this.plugin = plugin;
        this.WEAPON_KEY = new NamespacedKey(plugin, "altar_weapon");
    }

    public abstract String getWeaponId();

    public abstract String getWeaponName();

    public abstract Material getBaseMaterial();

    public abstract int getCustomModelData();

    public abstract List<ConfigField> getConfigFields();

    public abstract ItemStack createWeapon();

    public String getWeaponId() {
        return getWeaponId();
    }

    public String getWeaponNameDisplay() {
        return getWeaponName();
    }

    public Material getBaseMaterialItem() {
        return getBaseMaterial();
    }

    public int getCustomModelDataInt() {
        return getCustomModelData();
    }

    // Check if an item stack is this weapon
    public static boolean isThisWeapon(ItemStack var1) {
        if (var1 == null || var1.getItem() == null) {
            return false;
        }
        // Check via custom model data or persistent data container
        return var1.getItem() instanceof BaseWeaponWeapon || 
               var1.getTag() != null && var1.getTag().contains("weapon_id");
    }

    // Check if player is holding this weapon
    public boolean isHoldingThisWeapon(Player var1) {
        return isThisWeapon(var1.getInventory().getItemInMainHand());
    }

    // Skip default activation (for command-mode abilities)
    protected boolean skipDefaultActivation(Player var1) {
        return plugin.getCommandControlsManager().shouldSkipDefaultActivation(var1);
    }

    // Check if it's a crazy slots weapon
    protected boolean isCrazySlotsWeapon(ItemStack var1) {
        if (var1 != null && var1.hasItemMeta()) {
            return var1.getItemMeta().getPersistentDataContainer().has(
                new NamespacedKey(plugin, "crazy_slots_transform_id"), 
                net.minecraft.world.item.Items.PAPER // Placeholder - needs proper PersistentDataType
            );
        }
        return false;
    }

    // Check if weapon is in inventory
    protected boolean hasWeaponInInventory(Player var1) {
        for (ItemStack var5 : var1.getInventory().getContents()) {
            if (isThisWeapon(var5)) {
                return true;
            }
        }
        return false;
    }

    // Add tooltip to list
    protected List<String> withTooltip(List<String> var1) {
        return var1;
    }

    // Get weapon name with tooltip
    protected String nameWithTooltip() {
        return ChatColor.translateAlternateColorCodes('&', getWeaponName());
    }

    // Get tooltip style key
    protected NamespacedKey resolveTooltipStyle() {
        if (!plugin.getConfig().getBoolean("cosmetics.tooltip_styles_enabled", true)) {
            return null;
        } else {
            String tooltipKey = getTooltipStyleKey();
            return tooltipKey == null ? null : new NamespacedKey("altarsmp", tooltipKey);
        }
    }

    // Apply totem effects
    protected void applyTotemEffects(Player var1) {
        var1.setHealth(1.0);
        var1.playSound(var1.getSoundSource(), net.minecraft.sound.SoundEvents.ENTITY_PLAYER_LEVELUP, 1.0F, 1.0F);
        int regenDuration = plugin.getConfig().getInt("totem.regen_duration", 900);
        int regenLevel = plugin.getConfig().getInt("totem.regen_level", 1);
        int absorptionDuration = plugin.getConfig().getInt("totem.absorption_duration", 100);
        int absorptionLevel = plugin.getConfig().getInt("totem.absorption_level", 1);
        int fireResistDuration = plugin.getConfig().getInt("totem.fire_resist_duration", 800);
        int noDamageTicks = plugin.getConfig().getInt("totem.no_damage_ticks", 20);

        var1.addPotionEffect(new net.minecraft.potion.PotionEffect(
            net.minecraft.potion.PotionEffectType.REGENERATION, 
            regenDuration, 
            regenLevel
        ));
        var1.addPotionEffect(new net.minecraft.potion.PotionEffect(
            net.minecraft.potion.PotionEffectType.ABSORPTION, 
            absorptionDuration, 
            absorptionLevel
        ));
        var1.addPotionEffect(new net.minecraft.potion.PotionEffect(
            net.minecraft.potion.PotionEffectType.FIRE_RESISTANCE, 
            fireResistDuration, 
            0
        ));
        var1.setNoDamageTicks(noDamageTicks);
    }

    // Apply true damage
    protected void applyTrueDamage(LivingEntity var1, double var2) {
        applyTrueDamage(var1, var2, null, false);
    }

    public void applyTrueDamage(LivingEntity var1, double var2, Player var4) {
        applyTrueDamage(var1, var2, var4, false);
    }

    protected void applyTrueDamage(LivingEntity var1, double var2, Player var4, boolean var5) {
        if (var1.getNoDamageTicks() <= var1.getMaximumNoDamageTicks() / 2) {
            if (!(var1 instanceof Player var6 && var6.getGameMode() == net.minecraft.world.GameMode.CREATIVE)) {
                if (!(!var5 && var1 instanceof Player var12) || plugin.getAbilityImmunityManager().e(var12)) {
                    if (var1 instanceof Player var13) {
                        ItemStack var7 = var13.getInventory().getItemInMainHand();
                        ItemStack var8 = var13.getInventory().getItemInOffHand();
                        if (var13.getAbsorptionAmount() + var13.getHealth() <= var2) {
                            if (var8.getType() == Material.TOTEM_OF_UNDYING) {
                                var8.setAmount(var8.getAmount() - 1);
                                applyTotemEffects(var13);
                                return;
                            }
                            if (var7.getType() == Material.TOTEM_OF_UNDYING) {
                                var7.setAmount(var7.getAmount() - 1);
                                applyTotemEffects(var13);
                                return;
                            }
                        }
                    }

                    double var14 = var1.getAbsorptionAmount();
                    if (var14 > 0.0) {
                        if (var14 >= var2) {
                            var1.setAbsorptionAmount(var14 - var2);
                            var1.playHurtSound();
                            var1.getWorld().playSound(var1.getBlockPos(), net.minecraft.sound.SoundEvents.ENTITY_PLAYER_HURT, 1.0F, 1.0F);
                            var1.setNoDamageTicks(10);
                            return;
                        }
                        var1.setAbsorptionAmount(0.0);
                        var2 -= var14;
                    }

                    double var15 = Math.max(0.5, var1.getHealth() - var2);
                    net.minecraft.world.entity.EntityDamageEvent var10 = new net.minecraft.world.entity.EntityDamageEvent(var1, net.minecraft.world.entity.DamageCause.GENERIC, var2);
                    var1.setLastDamageCause(var10);
                    if (var4 != null && var1 instanceof Player var11) {
                        var11.setKiller(var4);
                    }

                    var1.setHealth(var15);
                    var1.playHurtSound();
                    var1.getWorld().playSound(var1.getBlockPos(), net.minecraft.sound.SoundEvents.ENTITY_PLAYER_HURT, 1.0F, 1.0F);
                    var1.setNoDamageTicks(10);
                }
            }
        }
    }

    // Get tooltip style key
    protected abstract String getTooltipStyleKey();

    // Resolve an item from the weapon
    protected ItemStack resolveWeaponItem() {
        return createWeapon();
    }
}