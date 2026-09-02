package com.altarsmp.fabric.item;

import com.altarsmp.fabric.AltarSMPFabric;
import com.altarsmp.fabric.weapon.BaseWeapon;
import net.minecraft.world.item.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.attribute.*;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.*;
import net.minecraft.world.item.component.CustomModelDataComponent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.core.*;
import net.minecraft.util.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.network.syncher.*;
import org.joml.Vector3fc;

import java.util.*;

public class WeaponItem extends BaseWeapon {
    private ItemStack stack;

    public WeaponItem(AltarSMPFabric plugin) {
        super(plugin);
        this.stack = ItemStack.EMPTY;
    }

    @Override
    public String getWeaponId() {
        return null; // Implemented by subclasses
    }

    @Override
    public String getWeaponName() {
        return null; // Implemented by subclasses
    }

    @Override
    public Material getBaseMaterial() {
        return Material.NETHERITE_AXE; // Default, overridden by subclasses
    }

    @Override
    public int getCustomModelData() {
        return 0; // Overridden by subclasses
    }

    @Override
    public List<ConfigField> getConfigFields() {
        return List.of(); // Overridden by subclasses
    }

    @Override
    public ItemStack createWeapon() {
        return new ItemStack(this);
    }

    @Override
    public UseAction getUseAction(ItemStack stack) {
        return UseAction.ATTACK;
    }

    @Override
    public int getUseDuration(ItemStack stack) {
        return 4;
    }

    @Override
    public float getDestroySpeed(ItemStack stack, BlockState blockState) {
        return 6.0F;
    }

    @Override
    public int getEnchantmentValue() {
        return 5;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return stack.hasFoil();
    }

    @Override
    public int getCustomModelData(ItemStack stack) {
        return getCustomModelData();
    }

    @Override
    public String getTooltipStyleKey() {
        return null;
    }

    @Override
    public void onLeftClickUse(ItemStack stack, Level world, Player player, InteractionHand hand) {
        // Default - can be overridden
    }

    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        return false;
    }

    @Override
    public void onRelease(ItemStack stack, Level world, Player player, int ticksUsed) {
        // Default
    }

    @Override
    public void inventoryTick(ItemStack stack, Level world, Entity entity, int slot, boolean selected) {
        // Default - can be overridden for passive effects
    }

    @Override
    public boolean onRightClickInteract(ItemStack stack, Level world, Player player, InteractionHand hand) {
        return false;
    }

    @Override
    public void onUnequip(ItemStack stack, Player player) {
        // Clean up when unequipped
        absorbing.remove(player.getUUID());
        absorbedDamage.remove(player.getUUID());
    }

    @Override
    public void onUpdate(ItemStack stack, Level world, Entity entity, int slot, boolean selected) {
        super.onUpdate(stack, world, entity, slot, selected);
        if (entity instanceof Player) {
            Player p = (Player) entity;
            if (isHoldingThisWeapon(p)) {
                // Apply passive effects
                addPassiveEffects(p);
            }
        }
    }

    // Apply weapon-specific passive effects
    protected void addPassiveEffects(Player player) {
        // To be overridden by specific weapon classes
    }

    // Check if this weapon is being held
    public boolean isHoldingThisWeapon(Player player) {
        return isThisWeapon(player.getInventory().getItemInMainHand());
    }

    // Weapon identity check
    protected boolean isThisWeapon(ItemStack var1) {
        if (var1 == null || var1.getItem() == null) {
            return false;
        }
        // Check via custom model data or persistent data container
        return var1.getItem() instanceof WeaponItem && 
               ((WeaponItem)var1).getWeaponId().equals(var1.getTag().getString("weapon_id", ""));
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
                            var1.hurtSound();
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
                    var1.hurtSound();
                    var1.getWorld().playSound(var1.getBlockPos(), net.minecraft.sound.SoundEvents.ENTITY_PLAYER_HURT, 1.0F, 1.0F);
                    var1.setNoDamageTicks(10);
                }
            }
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

    // Helper method for damage source
    private void applyDamage(LivingEntity entity, float amount) {
        entity.hurt(this.getIndirectEntityDamageSource(), amount);
    }

    // Get indirect damage source
    private net.minecraft.world.damagesource.DamageSource getIndirectEntityDamageSource() {
        return net.minecraft.world.damagesource.DamageSource.GENERIC;
    }
}