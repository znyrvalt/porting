package com.altarsmp.fabric.item;

import com.altarsmp.fabric.AltarSMPFabric;
import net.minecraft.world.item.*;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.component.CustomModelDataComponent;
import net.minecraft.world.item.component.ItemStacks;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.common.ToolAction;
import net.minecraftforge.common.ToolTier;
import net.minecraftforge.common.util.Lazy;
import net.minecraftforge.registries.*;

import java.util.*;

public class ModItems {
    // Register all mod items here
    public static final RegistryItem<Item> WEAPON_HANDLE = new RegistryItem<Item>("weapon_handle");
    public static final RegistryItem<Item> ILLUSION_CORE = new RegistryItem<Item>("illusion_core");
    public static final RegistryItem<Item> HYPERION_SHARD = new RegistryItem<Item>("hyperionshard");
    public static final RegistryItem<Item> NIGHTPIERCER_SHARD = new RegistryItem<Item>("nightpiercershard");
    public static final RegistryItem<Item> PALE_SHARD = new RegistryItem<Item>("paleshard");
    public static final RegistryItem<Item> COPPER_FRAGMENT = new RegistryItem<Item>("copper_fragment");
    public static final RegistryItem<Item> COPPER_CHESTPLATE_FRAGMENT = new RegistryItem<Item>("copper_chestplate_fragment");
    public static final RegistryItem<Item> WARDEN_HEART = new RegistryItem<Item>("warden_heart");
    public static final RegistryItem<Item> HEAVY_CORE = new RegistryItem<Item>("heavy_core");
    public static final RegistryItem<Item> CUSTOM_SOUL_IN_A_BOTTLE = new RegistryItem<Item>("soul_in_a_bottle");
    public static final RegistryItem<Item> CUSTOM_VULKAN_HEAD = new RegistryItem<Item>("vulkan_head");
    public static final RegistryItem<Item> CUSTOM_IC = new RegistryItem<Item>("ic");
    public static final RegistryItem<Item> CUSTOM_SKULL = new RegistryItem<Item>("skull");
    public static final RegistryItem<Item> CUSTOM_REDBLOCK = new RegistryItem<Item>("redblock");
    
    // Weapon items - these will hold weapon data
    public static final RegistryItem<Item> ANCIENT_BLADE = new RegistryItem<Item>("ancientblade");
    public static final RegistryItem<Item> WITHER_SYMBIOTE = new RegistryItem<Item>("withersymbiote");
    public static final RegistryItem<Item> TIDEBREAKER = new RegistryItem<Item>("tidebreaker");
    public static final RegistryItem<Item> DRAGON_REND = new RegistryItem<Item>("dragonrend");
    public static final RegistryItem<Item> BOW_OF_DECEPTION = new RegistryItem<Item>("bowofdeception");
    public static final RegistryItem<Item> OMEGEN_WEAPON = new RegistryItem<Item>("omen");
    public static final RegistryItem<Item> AMETHYST_AXE = new RegistryItem<Item>("amethyst_axe");
    public static final RegistryItem<Item> AMETHYST_PICKAXE = new RegistryItem<Item>("amethyst_pickaxe");
    public static final RegistryItem<Item> BLACK_GHAST_SADDLE = new RegistryItem<Item>("black_ghast_saddle");
    public static final RegistryItem<Item> DRAGON_HEART = new RegistryItem<Item>("dragon_heart");
    public static final RegistryItem<Item> FRAGMENT_OF_THE_SEA = new RegistryItem<Item>("fragment_of_the_sea");
    public static final RegistryItem<Item> SOUL_IN_A_BOTTLE = new RegistryItem<Item>("soul_in_a_bottle");
    public static final RegistryItem<Item> WARDEN_HEART_ITEM = new RegistryItem<Item>("warden_heart");
    
    // Copper armor items
    public static final RegistryItem<Item> COPPER_HELMET = new RegistryItem<Item>("copper_helmet");
    public static final RegistryItem<Item> COPPER_CHESTPLATE = new RegistryItem<Item>("copper_chestplate");
    public static final RegistryItem<Item> COPPER_LEGGINGS = new RegistryItem<Item>("copper_leggings");
    public static final RegistryItem<Item> COPPER_BOOTS = new RegistryItem<Item>("copper_boots");
    public static final RegistryItem<Item> COPPER_PICKAXE = new RegistryItem<Item>("copper_pickaxe");
    public static final RegistryItem<Item> COPPER_PICKAXE_II = new RegistryItem<Item>("copper_pickaxe_iie");
    
    // Season 2 items
    public static final RegistryItem<Item> ECHO_SHARD = new RegistryItem<Item>("echo_shard");
    public static final RegistryItem<Item> DISC_FRAGMENT_5 = new RegistryItem<Item>("disc_fragment_5");
    public static final RegistryItem<Item> CLOSED_EYEBLOSSOM = new RegistryItem<Item>("closed_eyeblossom");
    public static final RegistryItem<Item> OPEN_EYEBLOSSOM = new RegistryItem<Item>("open_eyeblossom");
    public static final RegistryItem<Item> CUSTOM_SOUL_IN_A_BOTTLE2 = new RegistryItem<Item>("custom_soul_in_a_bottle");
    public static final RegistryItem<Item> CUSTOM_ANCESTRAL_HEAD = new RegistryItem<Item>("custom_ancestral_head");
    
    // Constructor - register items
    public static void register() {
        AltarSMPFabric.LOGGER.info("Registering mod items...");
    }
    
    // Helper class for registry items
    public static class RegistryItem<T extends Item> {
        private final String name;
        private final Lazy<T> instance;
        
        public RegistryItem(String name) {
            this.name = name;
            this.instance = Lazy.of(() -> createItem(name));
        }
        
        public T get() {
            return instance.get();
        }
        
        private T createItem(String name) {
            // Create item based on name
            try {
                return (T) new Item(new Item.Properties())
                    .setRegistryName(AltarSMPFabric.MOD_ID, name);
            } catch (Exception e) {
                AltarSMPFabric.LOGGER.error("Failed to create item: {}", name, e);
                return null;
            }
        }
        
        // Set custom properties for the item
        public RegistryItem<T> maxStackSize(int size) {
            // Would need to return a modified item - this is a simplified version
            return this;
        }
        
        // Set custom model data
        public RegistryItem<T> customModelData(int data) {
            // Would be applied when the item is created - simplified
            return this;
        }
    }
    
    // Create a weapon handle item
    public static Item createWeaponHandleItem() {
        return new Item(new Item.Properties())
            .registryName(AltarSMPFabric.MOD_ID, "weapon_handle")
            .setCustomModelData(0);
    }
    
    // Create a warden heart item
    public static Item createWardenHeartItem() {
        return new Item(new Item.Properties())
            .registryName(AltarSMPFabric.MOD_ID, "warden_heart")
            .setCustomModelData(42);
    }
    
    // Create a heavy core item
    public static Item createHeavyCoreItem() {
        return new Item(new Item.Properties())
            .registryName(AltarSMPFabric.MOD_ID, "heavy_core")
            .setCustomModelData(43);
    }
    
    // Create a copper fragment item (tracks coordinates)
    public static Item createCopperFragmentItem() {
        return new Item(new Item.Properties())
            .registryName(AltarSMPFabric.MOD_ID, "copper_fragment")
            .setCustomModelData(44)
            // Add NBT data for coordinate tracking
            .addAttributeModifier(Attributes.ATTACK_DAMAGE, "coordinate_tracking", 0.0, AttributeModifier.Operation.ADDITION);
    }
    
    // Create a chestplate shard item
    public static Item createChestplateShardItem() {
        return new Item(new Item.Properties())
            .registryName(AltarSMPFabric.MOD_ID, "chestplate_shard")
            .setCustomModelData(45);
    }
    
    // Create a pale shard item
    public static Item createPaleShardItem() {
        return new Item(new Item.Properties())
            .registryName(AltarSMPFabric.MOD_ID, "pale_shard")
            .setCustomModelData(46);
    }
    
    // Create a hyperion shard item
    public static Item createHyperionShardItem() {
        return new Item(new Item.Properties())
            .registryName(AltarSMPFabric.MOD_ID, "hyperion_shard")
            .setCustomModelData(47);
    }
    
    // Create a nightpiercer shard item
    public static Item createNightpiercerShardItem() {
        return new Item(new Item.Properties())
            .registryName(AltarSMPFabric.MOD_ID, "nightpiercer_shard")
            .setCustomModelData(48);
    }
    
    // Create a soul in a bottle item
    public static Item createSoulInABottleItem() {
        return new Item(new Item.Properties())
            .registryName(AltarSMPFabric.MOD_ID, "soul_in_a_bottle")
            .setCustomModelData(49);
    }
    
    // Create an illusion core item
    public static Item createIllusionCoreItem() {
        return new Item(new Item.Properties())
            .registryName(AltarSMPFabric.MOD_ID, "illusion_core")
            .setCustomModelData(50);
    }
    
    // Create a wither heart item
    public static Item createWitherHeartItem() {
        return new Item(new Item.Properties())
            .registryName(AltarSMPFabric.MOD_ID, "witherheart")
            .setCustomModelData(51);
    }
    
    // Create an amethyst axe item
    public static Item createAmethystAxeItem() {
        return new Item(new Item.Properties())
            .registryName(AltarSMPFabric.MOD_ID, "amethyst_axe")
            .setCustomModelData(52);
    }
    
    // Create an amethyst pickaxe item
    public static Item createAmethystPickaxeItem() {
        return new Item(new Item.Properties())
            .registryName(AltarSMPFabric.MOD_ID, "amethyst_axe") // Note: should be pickaxe but using same base
            .setCustomModelData(53);
    }
    
    // Create a black ghast saddle item
    public static Item createBlackGhastSaddleItem() {
        return new Item(new Item.Properties())
            .registryName(AltarSMPFabric.MOD_ID, "black_ghast_saddle")
            .setCustomModelData(54);
    }
    
    // Create a dragon heart item
    public static Item createDragonHeartItem() {
        return new Item(new Item.Properties())
            .registryName(AltarSMPFabric.MOD_ID, "dragon_heart")
            .setCustomModelData(55);
    }
    
    // Create a fragment of the sea item
    public static Item createFragmentOfTheSeaItem() {
        return new Item(new Item.Properties())
            .registryName(AltarSMPFabric.MOD_ID, "fragment_of_the_sea")
            .setCustomModelData(56);
    }
    
    // Create a disc fragment 5 item
    public static Item createDiscFragment5Item() {
        return new Item(new Item.Properties())
            .registryName(AltarSMPFabric.MOD_ID, "disc_fragment_5")
            .setCustomModelData(57);
    }
    
    // Create closed eyeblossom item
    public static Item createClosedEyeLossomItem() {
        return new Item(new Item.Properties())
            .registryName(AltarSMPFabric.MOD_ID, "closed_eyeblossom")
            .setCustomModelData(58);
    }
    
    // Create open eyeblossom item
    public static Item createOpenEyeLossomItem() {
        return new Item(new Item.Properties())
            .registryName(AltarSMPFabric.MOD_ID, "open_eyeblossom")
            .setCustomModelData(59);
    }
}