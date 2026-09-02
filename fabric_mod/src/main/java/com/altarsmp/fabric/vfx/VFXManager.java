package com.altarsmp.fabric.vfx;

import com.altarsmp.fabric.AltarSMPFabric;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.Constants;
import net.minecraftforge.client.particle.*;
import net.minecraftforge.common.util.Lazy;
import net.minecraftforge.fml.ModList;

import java.util.*;
import java.util.function.*;

public class VFXManager {
    public static final ResourceLocation ALTARSMP_PREFIX = new ResourceLocation("altarsmp");
    public static final ResourceLocation MINEMINECRAFT_PREFIX = new ResourceLocation("minecraft");

    public static void init() {
        // Initialize VFX system
        AltarSMPFabric.LOGGER.info("VFX system initialized");
    }

    // Particle effects
    public static void spawnParticle(String effect, World world, double x, double y, double z, double... velocities) {
        // Spawn a particle effect by name
        ParticleType<?> type = getParticleType(effect);
        if (type != null) {
            world.addParticle(type, x, y, z, velocities.length > 0 ? velocities[0] : 0, velocities.length > 1 ? velocities[1] : 0, velocities.length > 2 ? velocities[2] : 0);
        }
    }

    public static void spawnParticle(ParticleType<?> type, World world, double x, double y, double z, double vx, double vy, double vz) {
        world.addParticle(type, x, y, z, vx, vy, vz);
    }

    // Common particle effects
    public static void spawnRedstoneParticle(World world, double x, double y, double z) {
        spawnParticle("redstone", world, x, y, z);
    }

    public static void spawnExplosionParticle(World world, double x, double y, double z, double radius) {
        for (int i = 0; i < 20; i++) {
            double rx = x + (Math.random() - 0.5) * radius;
            double ry = y + (Math.random() - 0.5) * radius;
            double rz = z + (Math.random() - 0.5) * radius;
            world.addParticle(ParticleType.EXPLOSION, rx, ry, rz, 0, 0, 0);
        }
    }

    public static void spawnImpactEffect(World world, double x, double y, double z, Material material) {
        // Spawn impact particles based on material type
        switch (material) {
            case Blocks.STONE:
                spawnParticle(ParticleType.BLOCK_CRUMBLE, world, x, y, z, 0.1, 0.1, 0.1);
                break;
            case Blocks.DIRT:
                spawnParticle(ParticleType.BLOCK_CRUMBLE, world, x, y, z, 0.2, 0.2, 0.2);
                break;
            case Blocks.WOOD:
                spawnParticle(ParticleType.ENTITY_smoke, world, x, y, z, 0.1, 0.1, 0.1);
                break;
            default:
                spawnParticle(ParticleType.BLOCK_CRUMBLE, world, x, y, z, 0.1, 0.1, 0.1);
        }
    }

    // Weapon trail effects
    public static void spawnWeaponTrail(World world, Entity entity, double offsetX, double offsetY, double offsetZ) {
        // Create a trailing particle effect following the entity
        double lastX = entity.getX() - offsetX;
        double lastY = entity.getY() - offsetY;
        double lastZ = entity.getZ() - offsetZ;
        
        world.addParticle(ParticleType.ENTITY_ELECTRIC_SPARK, entity.getX(), entity.getY(), entity.getZ(), 0, 0, 0);
    }

    // Spell effect circles
    public static void spawnCircleEffect(World world, double x, double y, double z, double radius, int segments) {
        for (int i = 0; i < segments; i++) {
            double angle = (double)i / (double)segments * Math.PI * 2;
            double px = x + Math.cos(angle) * radius;
            double pz = z + Math.sin(angle) * radius;
            world.addParticle(ParticleType.MOB_SPELL, px, y, pz, 0, 0, 0);
        }
    }

    // Beam effect
    public static void spawnBeamEffect(World world, double startX, double startY, double startZ, double endX, double endY, double endZ, int tickCount) {
        for (int tick = 0; tick < tickCount; tick++) {
            double t = (double)tick / (double)tickCount;
            double x = startX + (endX - startX) * t;
            double y = startY + (endY - startY) * t;
            double z = startZ + (endZ - startZ) * t;
            world.addParticle(ParticleType.ENTITY_ELECTRIC_SPARK, x, y, z, 0, 0, 0);
        }
    }

    // Ring effect
    public static void spawnRingEffect(World world, double x, double y, double z, double innerRadius, double outerRadius, int heightSegments, int ringSegments) {
        for (int h = 0; h < heightSegments; h++) {
            double yOffset = y + (double)h / (double)heightSegments * 1.0;
            for (int s = 0; s < ringSegments; s++) {
                double angle = (double)s / (double)ringSegments * Math.PI * 2;
                double px = x + Math.cos(angle) * outerRadius;
                double pz = z + Math.sin(angle) * outerRadius;
                world.addParticle(ParticleType.EXPLODE, px, yOffset, pz, 0, 0, 0);
            }
        }
    }

    // Trail particles for movement
    public static void spawnTrailParticles(World world, Entity entity, int count) {
        for (int i = 0; i < count; i++) {
            double offsetX = (Math.random() - 0.5) * 0.5;
            double offsetY = (Math.random() - 0.5) * 0.5;
            double offsetZ = (Math.random() - 0.5) * 0.5;
            world.addParticle(ParticleType.PORTAL, entity.getX() + offsetX, entity.getY() + offsetY, entity.getZ() + offsetZ, 0, 0, 0);
        }
    }

    // Healing effect particles
    public static void spawnHealingParticles(World world, double x, double y, double z) {
        spawnParticle("healing", world, x, y, z);
    }

    // Damage effect particles
    public static void spawnDamageParticles(World world, double x, double y, double z) {
        spawnParticle("critical", world, x, y, z);
    }

    // Portal effect
    public static void spawnPortalParticles(World world, double x, double y, double z) {
        spawnParticle("portal", world, x, y, z);
    }

    // Smoke effect
    public static void spawnSmokeEffect(World world, double x, double y, double z, double duration) {
        for (int i = 0; i < (int)(duration * 10); i++) {
            double dx = (Math.random() - 0.5) * 0.3;
            double dy = (Math.random() - 0.5) * 0.3;
            double dz = (Math.random() - 0.5) * 0.3;
            world.addParticle(ParticleType.SMOKE, x + dx, y + dy, z + dz, 0, 0, 0);
        }
    }

    // Fire effect
    public static void spawnFireEffect(World world, double x, double y, double z) {
        spawnParticle(ParticleType.FLAME, world, x, y, z);
    }

    // Splash potion effect
    public static void spawnSplashParticles(World world, double x, double y, double z, int radius) {
        for (int i = 0; i < 20; i++) {
            double dx = (Math.random() - 0.5) * radius;
            double dy = (Math.random() - 0.5) * radius;
            double dz = (Math.random() - 0.5) * radius;
            world.addParticle(ParticleType.SPLASH, x + dx, y + dy, z + dz, 0, 0, 0);
        }
    }

    // Critical hit particles
    public static void spawnCriticalHitParticles(World world, Entity entity) {
        double offsetX = (Math.random() - 0.5) * 0.5;
        double offsetY = (Math.random() - 0.5) * 0.5;
        double offsetZ = (Math.random() - 0.5) * 0.5;
        world.addParticle(ParticleType.CRIT, entity.getX() + offsetX, entity.getY() + offsetY, entity.getZ() + offsetZ, 0, 0, 0);
    }

    // Heart particles (for healing)
    public static void spawnHeartParticles(World world, double x, double y, double z) {
        spawnParticle(ParticleType.ENTITY_HEART, world, x, y, z);
    }

    // Angle particles (for aiming)
    public static void spawnAngleParticles(World world, double x, double y, double z, float pitch, float yaw) {
        // Spawn particles in a cone shape
        for (int i = 0; i < 10; i++) {
            float dx = (Math.random() - 0.5f) * 0.5f;
            float dy = (Math.random() - 0.5f) * 0.5f;
            float dz = (Math.random() - 0.5f) * 0.5f;
            world.addParticle(ParticleType.END_ROD, x + dx, y + dy, z + dz, dx, dy, dz);
        }
    }

    // Get particle type by name
    private static ParticleType<?> getParticleType(String name) {
        switch (name.toLowerCase()) {
            case "redstone":
                return net.minecraft.world.level.particle.RedstoneParticleType.REDSONE;
            case "critical":
                return net.minecraft.world.level.particle.CriticalHitParticleType.CRIT;
            case "healing":
                return net.minecraft.world.level.particle.HealingParticleType.HEALING;
            case "portal":
                return net.minecraft.world.level.particle.PortalParticleType.PORTAL;
            case "flame":
                return net.minecraft.world.level.particle.FlameParticleType.FLAME;
            case "smoke":
                return net.minecraft.world.level.particle.SmokeParticleType.SMOKE;
            case "explode":
                return net.minecraft.world.level.particle.ExplosionParticleType.EXPLODE;
            default:
                return null;
        }
    }
}