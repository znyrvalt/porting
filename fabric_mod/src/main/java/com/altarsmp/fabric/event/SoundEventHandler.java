package com.altarsmp.fabric.event;

import com.altarsmp.fabric.AltarSMPFabric;
import net.minecraft.sound.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.*;
import net.minecraftforge.event.*;
import net.minecraftforge.event.entity.*;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.RegistryObject;
import net.minecraft.core.Registry;
import net.minecraft.sound.SoundEvents;
import net.minecraft.sound.SoundSource;
import net.minecraftforge.registries.*;

import java.util.*;

public class SoundEventHandler {
    private final AltarSMPFabric mod;
    
    public SoundEventHandler() {
        this.mod = AltarSMPFabric.getInstance();
    }
    
    @SubscribeEvent
    public void onRegisterSounds(RegistryEvent.Register<SoundEvent> event) {
        RegistryObject<SoundEvent> builder;
        
        // Altar sounds
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "blood_altar_activate")));
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "bone_altar_activate")));
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "contagion_signal_activate")));
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "copper_helmet_altar_activate")));
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "copper_chestplate_altar_activate")));
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "copper_pickaxe_altar_activate")));
        
        // Weapon sounds
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "paladinbattleaxe_shatter")));
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "paladinbattleaxe_stalwart_activate")));
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "vulcanscrossbow_shoot")));
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "palecrossbow_shoot")));
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "eclipsesword_moonlit_stars")));
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "nightpiercer_bat_transform")));
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "pureblade_shade_soul")));
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "shadowblade_dagger_throw")));
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "bloodlust_bleed")));
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "earthgauntlet_meteor_slam")));
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "cutlass_parry")));
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "windweaver_cyclone")));
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "witherbone_slam")));
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "wandofillusion_spin")));
        
        // Blood Moon sounds
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "bloodmoon_start")));
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "bloodmoon_end")));
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "bloodmoon_tick")));
        
        // Copper trial sounds
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "coppertrial_start")));
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "coppertrial_hold")));
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "coppertrial_transfer")));
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "coppertrial_end")));
        
        // VFX-related sounds (subtle)
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "vfx_electric_spark")));
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "vfx_impact")));
        builder = event.getRegistry().register(new SoundEvent(new net.minecraft.util.ResourceLocation("altarSMP", "vfx_charge")));
        
        AltarSMPFabric.LOGGER.info("Registered {} sound events", event.getRegistry().size());
    }
    
    @SubscribeEvent
    public void onPlaySound(net.minecraftforge.event.entity.player.PlaySoundEvent event) {
        // Redirect vanilla sounds to custom sounds where appropriate
        if (event.getName().getNamespace().equals("minecraft")) {
            String soundName = event.getName().getPath();
            
            // Example: Replace certain weapon sounds with custom ones
            if (soundName.equals("entity.anvil.land") && isPlayerHoldingLegendary(event.getEntity())) {
                event.setSound(SoundEvents.valueOf("altarSMP:paladinbattleaxe_shatter"));
                event.setVolume(0.6F);
                event.setPitch(1.0F);
            }
            
            if (soundName.equals("entity.generic.explode") && isPlayerHoldingLegendary(event.getEntity())) {
                event.setSound(SoundEvents.valueOf("altarSMP:withersymbiote_rampage_start"));
                event.setVolume(1.0F);
                event.setPitch(1.2F);
            }
        }
    }
    
    @SubscribeEvent
    public void onPlaySoundAtEntity(net.minecraftforge.event.entity.PlaySoundAtEntityEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            
            // Weapon-specific sounds
            if (event.getName().getNamespace().equals("minecraft")) {
                String soundName = event.getName().getPath();
                
                // Check what weapon player is holding
                for (BaseWeapon weapon : AltarSMPFabric.getWeapons()) {
                    if (weapon.isHoldingThisWeapon(player)) {
                        // Override sound based on weapon
                        if (weapon.getWeaponId().equals("paladinbattleaxe")) {
                            if (soundName.equals("entity.player.attack.narrow")) {
                                event.setSound(SoundEvents.valueOf("altarSMP:paladinbattleaxe_shatter"));
                                event.setVolume(0.8F);
                            }
                        }
                        if (weapon.getWeaponId().equals("vulcanscrossbow")) {
                            if (soundName.equals("item.bow.use")) {
                                event.setSound(SoundEvents.valueOf("altarSMP:vulcanscrossbow_shoot"));
                                event.setVolume(1.0F);
                            }
                        }
                    }
                }
            }
        }
    }
    
    @SubscribeEvent
    public void onPlaySubmixSound(net.minecraftforge.event.SubmixMixEvent event) {
        // submix sound handling
    }
    
    @SubscribeEvent
    public void onSoundDone(net.minecraftforge.event.entity.SoundDoneEvent event) {
        // Handle sound completion events
    }
    
    @SubscribeEvent
    public void onAmbientSound(net.minecraftforge.event.entity.living.LivingUpdateEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        
        // Apply weapon ambient sounds
        for (BaseWeapon weapon : AltarSMPFabric.getWeapons()) {
            if (weapon.isHoldingThisWeapon(player)) {
                // Weapon ambient/V loop sounds
                // e.g., bloodlust bleed sound, frostscythe hum, etc.
            }
        }
        
        // Vampire/pale ambient sounds
        if (FactionManager.isVampire(player)) {
            // Vampire hum/night sound
        }
        
        if (FactionManager.isPale(player)) {
            // Pale ambient sound
        }
    }
    
    @SubscribeEvent
    public void onNetSound(net.minecraftforge.network.NetworkingEvent.SendPacket event) {
        // Network sound packets
    }
    
    private boolean isPlayerHoldingLegendary(Player player) {
        if (player == null || player.getInventory() == null) return false;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getTag() != null && stack.getTag().contains("weapon_id")) {
                String weaponId = stack.getTag().getString("weapon_id");
                if (weaponId.startsWith("paladin") || weaponId.startsWith("vulcan") || weaponId.startsWith("eclipse")) {
                    return true;
                }
            }
        }
        return false;
    }
}