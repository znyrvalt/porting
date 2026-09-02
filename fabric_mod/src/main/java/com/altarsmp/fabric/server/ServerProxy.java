package com.altarsmp.fabric.server;

import com.altarsmp.fabric.AltarSMPFabric;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.*;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.*;
import net.minecraftforge.event.level.*;
import net.minecraftforge.event.entity.player.*;
import net.minecraftforge.event.tick.*;
import net.minecraftforge.event.level.*;
import net.minecraftforge.event.entity.living.*;
import net.minecraftforge.event.entity.player.*;
import net.minecraftforge.common.*;

import org.apache.logging.log4j.*;

public class ServerProxy implements ModInitializer {
    @Override
    public void onInitialize() {
        AltarSMPFabric.LOGGER.info("Server initializing...");
        
        // Register all event handlers
        registerEventHandlers();
        
        // Configure forge events
        configureForgeEvents();
    }
    
    private void registerEventHandlers() {
        // Register all mod event listeners
        AltarSMPFabric.getEventBus().register(new PlayerEventHandler());
        AltarSMPFabric.getEventBus().register(new EntityEventHandler());
        AltarSMPFabric.getEventBus().register(new BlockEventHandler());
        AltarSMPFabric.getEventBus().register(new ItemEventHandler());
        AltarSMPFabric.getEventBus().register(new AltarEventHandler());
        AltarSMPFabric.getEventBus().register(new WeaponEventHandler());
        AltarSMPFabric.getEventBus().register(new FactionEventHandler());
        AltarSMPFabric.getEventBus().register(new TrialEventHandler());
        AltarSMPFabric.getEventBus().register(new BloodMoonEventHandler());
        AltarSMPFabric.getEventBus().register(new VFXEventHandler());
        AltarSMPFabric.getEventBus().register(new SoundEventHandler());
    }
    
    private void configureForgeEvents() {
        // Register Forge-specific events
        MinecraftForge.EVENT_BUS.register(new PlayerTracker());
        MinecraftForge.EVENT_BUS.register(new CooldownTracker());
        MinecraftForge.EVENT_BUS.register(new AbilityTracker());
    }
    
    @SubscribeEvent
    public void onServerStarting(FMLServerStartingEvent event) {
        // Register commands during server start
        AltarSMPFabric.LOGGER.info("Registering server commands...");
        
        // Register /altarsmp and subcommands
        // Register /bloodmoon
        // Register /coppertrial
        // Register other admin commands
    }
}