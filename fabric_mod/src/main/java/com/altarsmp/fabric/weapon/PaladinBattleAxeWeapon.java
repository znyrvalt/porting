package com.altarsmp.fabric.weapon;

import com.altarsmp.fabric.AltarSMPFabric;
import com.altarsmp.fabric.faction.FactionManager;
import net.minecraft.ChatColor;
import net.minecraft.core.Direction;
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
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.util.RandomSource;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.client.ItemStackProperties;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.*;
import java.util.List;
import java.util.Map.Entry;

public class PaladinBattleAxeWeapon extends BaseWeapon {
    private static final int SHATTER_COOLDOWN_DEFAULT = 45;
    private static final int STALWART_COOLDOWN_DEFAULT = 45;
    private static final double EARTH_SHATTER_DAMAGE_DEFAULT = 7.0;
    private static final float SHATTER_KNOCKBACK_DEFAULT = 0.85F;
    private static final int STALWART_DURATION_DEFAULT = 100;
    private static final double STALWART_KNOCKBACK_DEFAULT = 1.2;
    private static final double STALWART_DAMAGE_CAP_DEFAULT = 60.0;
    private static final double STALWART_MIN_HIT_DAMAGE_DEFAULT = 4.0;
    private static final double STALWART_ABSORPTION_RATIO_DEFAULT = 0.5;

    private int shatterCooldown;
    private int stalwartCooldown;
    private double earthShatterDamage;
    private double shatterKnockback;
    private int stalwartDuration;
    private double stalwartKnockback;
    private double stalwartDamageCap;
    private double stalwartMinHitDamage;
    private double stalwartAbsorptionRatio;

    private final Map<String, long> cooldownBars = new HashMap<>();
    private final Map<UUID, Double> absorbedDamage = new HashMap<>();
    private final Set<UUID> absorbing = new HashSet<>();

    public PaladinBattleAxeWeapon(AltarSMPFabric plugin) {
        super(plugin);
        this.shatterCooldown = SHATTER_COOLDOWN_DEFAULT;
        this.stalwartCooldown = STALWART_COOLDOWN_DEFAULT;
        this.earthShatterDamage = EARTH_SHATTER_DAMAGE_DEFAULT;
        this.shatterKnockback = SHATTER_KNOCKBACK_DEFAULT;
        this.stalwartDuration = STALWART_DURATION_DEFAULT;
        this.stalwartKnockback = STALWART_KNOCKBACK_DEFAULT;
        this.stalwartDamageCap = STALWARD_DAMAGE_CAP_DEFAULT;
        this.stalwartMinHitDamage = STALWART_MIN_HIT_DAMAGE_DEFAULT;
        this.stalwartAbsorptionRatio = STALWART_ABSORPTION_RATIO_DEFAULT;
    }

    @Override
    public String getWeaponId() {
        return "paladinbattleaxe";
    }

    @Override
    public String getWeaponName() {
        return "<gradient:#FFD700:#FFA500:#FFD700>Paladin's Battle Axe</gradient>";
    }

    @Override
    public Material getBaseMaterial() {
        return Material.NETHERITE_AXE;
    }

    @Override
    public int getCustomModelData() {
        return 2;
    }

    @Override
    public List<ConfigField> getConfigFields() {
        return List.of(
            new ConfigField("Shatter Cooldown (s)", "abilities.paladinbattleaxe.shatter_cooldown", 0, 300),
            new ConfigField("Earth Shatter Damage", "abilities.paladinbattleaxe.earth_shatter_damage", 0.0F, 40.0F, 0.5F),
            new ConfigField("Shatter Radius", "abilities.paladinbattleaxe.shatter_radius", 0, 20),
            new ConfigField("Stalwart Cooldown (s)", "abilities.paladinbattleaxe.stalwart_cooldown", 0, 300),
            new ConfigField("Stalwart Duration (ticks)", "abilities.paladinbattleaxe.stalwart_duration", 0, 600),
            new ConfigField("Stalwart Damage Cap", "abilities.paladinbattleaxe.stalwart_damage_cap", 0.0F, 200.0F, 1.0F),
            new ConfigField("Stalwart Min Hit Damage", "abilities.paladinbattleaxe.stalwart_min_hit_damage", 0.0F, 50.0F, 0.5F),
            new ConfigField("Stalwart Absorption Ratio", "abilities.paladinbattleaxe.stalwart_absorption_ratio", 0.0D, 1.0D, 0.1D)
        );
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
        return 5; // Base enchantability
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        // Check if the item should show enchantment glow
        return stack.hasFoil();
    }

    @Override
    public int getCustomModelData(ItemStack stack) {
        return getCustomModelData();
    }

    @Override
    public String getTooltipStyleKey() {
        return "paladin_axe/paladin_axe";
    }

    @Override
    public ItemStack createWeapon() {
        int shatterCd = getShatterCooldown();
        int stalwartCd = getStalwartCooldown();

        List<String> tooltipLines = Arrays.asList(
            "<dark_gray>With an <gold>indomitable burning spirit<dark_gray>.",
            "<dark_gray><italic>\"Never quench thy flame, noble one.\"",
            "",
            "<gold><bold>Earth Shatter</bold>",
            "<dark_gray>⏱ " + shatterCd + "s <yellow>Offhand",
            "<gray>Summon a spectral axe where you look,",
            "<gray>creating a massive ground shockwave.",
            "<gray>You swing faster while holding the axe.",
            "",
            "<gold><bold>Stalwart Absorption</bold>",
            "<dark_gray>⏱ " + stalwartCd + "s <yellow>Shift-Offhand",
            "<gray>For 5 seconds, negate damage and",
            "<gray>store half of it for a returning shockwave.",
            "<gray>Gain <white>Resistance I <gray>while active."
        );

        return new ItemStack(getBaseMaterial())
            .copy()
            .setDisplayName(getWeaponNameDisplay())
            .setCustomModelData(getCustomModelData())
            .setTooltipLines(tooltipLines)
            .addEnchantment(Enchantment.SHARPNESS, plugin.getConfig().getInt("enchants.paladinbattleaxe.sharpness", 5))
            .addEnchantment(Enchantment.LOOTING, plugin.getConfig().getInt("enchants.paladinbattleaxe.looting", 5))
            .addEnchantment(Enchantment.SWEEPING_EDGE, plugin.getConfig().getInt("enchants.paladinbattleaxe.sweeping_edge", 3))
            .addEnchantment(Enchantment.UNBREAKING, plugin.getConfig().getInt("enchants.paladinbattleaxe.unbreaking", 10))
            .addEnchantment(Enchantment.MENDING, plugin.getConfig().getInt("enchants.paladinbattleaxe.mending", 1))
            .addEnchantment(Enchantment.EFFICIENCY, plugin.getConfig().getInt("enchants.paladinbattleaxe.efficiency", 5))
            .set(
                net.minecraft.world.item.component.ItemStackCompatibility.WEAPON_KEY, 
                getWeaponId()
            );
    }

    @Override
    public void onLeftClickUse(ItemStack stack, Level world, Player player, InteractionHand hand) {
        // Handle right-click/offhand shift usage for Stalwart
        if (player.isSneaking()) {
            useStalwartAbsorption(player);
        } else {
            useEarthShatter(player);
        }
    }

    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        if (attacker instanceof Player) {
            Location eyeLocation = attacker.getEyeLocation();
            Vector direction = eyeLocation.getDirection().normalize();
            Location location = attacker.getLocation().add(0.0, 1.0, 0.0).add(direction.clone().multiply(1.0));
            Vector knockback = direction.clone().crossProduct(new Vector(0, 1, 0)).normalize();

            // Spawn slash arc particles
            spawnSlashArc(world, location, knockback, true, Color.fromRGB(255, 200, 50), 1.2F);

            // Handle absorbed damage release
            if (absorbing.contains(attacker.getUUID())) {
                attacker.getWorld().playSound(attacker.getLocation(), net.minecraft.sound.SoundEvents.BLOCK_ANVIL_LAND, 0.6F, 1.8F);
            }
        }
        return super.hurtEnemy(stack, target, attacker);
    }

    @Override
    public void onRelease(ItemStack stack, Level world, Player player, int ticksUsed) {
        // Handle charge release
    }

    @Override
    public void inventoryTick(ItemStack stack, Level world, Entity entity, int slot, boolean selected) {
        // Update passive effects while holding
        if (entity instanceof Player) {
            Player player = (Player) entity;
            // Passive haste effect
            if (isHoldingThisWeapon(player)) {
                player.addPotionEffect(new net.minecraft.potion.PotionEffect(
                    net.minecraft.potion.PotionEffectType.HASTE, 
                    180, 
                    getPassiveHasteLevel(), 
                    false, 
                    false
                ));
            }
        }
    }

    private void spawnSlashArc(Level world, Location location, Vector direction, boolean isLeft, Color color, float radius) {
        int segments = 22;
        double arcLength = 2.2;
        double height = 1.5;

        Vector forward = location.getDirection();

        for (int i = 0; i < segments; i++) {
            double progress = (double)i / (segments - 1);
            double xOffset = (progress - 0.5) * arcLength * (isLeft ? 1 : -1);
            double yOffset = (0.5 - progress) * height;
            double zOffset = Math.sin(progress * Math.PI) * 0.3;

            Location particleLoc = location.clone().add(
                direction.clone().multiply(xOffset),
                forward.clone().crossProduct(new Vector(0, 1, 0)).normalize().multiply(zOffset)
            ).add(0.0, yOffset, 0.0);

            world.addParticle(ParticleOptions.DUST_OPACITY, particleLoc.x(), particleLoc.y(), particleLoc.z(), 
                0.03, 0.03, 0.03, new ParticleOptions(
                    ParticleType.DUST_COLOR, 
                    color.getRed() / 255.0F, 
                    color.getGreen() / 255.0F, 
                    color.getBlue() / 255.0F, 
                    radius
                )
            );
        }
    }

    private void useEarthShatter(Player player) {
        String cooldownKey = "paladin_earthshatter";
        if (!plugin.getCooldownManager().isOnCooldown(player, cooldownKey)) {
            // Ray trace to find target block
            RayTraceResult rayTrace = player.getWorld().rayTraceBlocks(
                player.getEyeLocation(), 
                player.getEyeLocation().getDirection(), 
                100.0
            );

            if (rayTrace != null && rayTrace.getHitBlock() != null) {
                Location impactLocation = rayTrace.getHitBlockPos().getAboveLocation();
                
                // Create shockwave pattern
                World world = player.getWorld();
                double damage = getEarthShatterDamage();
                int radius = getShatterRadius();
                double impactDamage = getShatterImpactDamage();

                // Track blocks affected
                Map<Integer, List<BlockPos>> blockGroups = new HashMap<>();

                for (int x = -radius; x <= radius; x++) {
                    for (int y = -radius; y <= radius; y++) {
                        for (int z = -radius; z <= radius; z++) {
                            if (x * x + y * y + z * z <= radius * radius) {
                                int group = (int) Math.round(Math.sqrt(x * x + z * z));
                                BlockPos pos = new BlockPos(
                                    impactLocation.getX() + x,
                                    impactLocation.getY() + y,
                                    impactLocation.getZ() + z
                                );
                                
                                Block block = world.getBlockState(pos).getBlock();
                                if (block != null && !block.isAir()) {
                                    blockGroups.computeIfAbsent(group, k -> new ArrayList<>()).add(pos);
                                }
                            }
                        }
                    }
                }

                // Schedule damage over time
                for (Entry<Integer, List<BlockPos>> entry : blockGroups.entrySet()) {
                    int groupSize = entry.getKey();
                    List<BlockPos> blocks = entry.getValue();

                    world.execute(() -> {
                        double avgY = blocks.stream()
                            .mapToDouble(pos -> world.getBlockState(pos).getY())
                            .average()
                            .orElse(impactLocation.getY());

                        for (BlockPos blockPos : blocks) {
                            Material material = world.getBlockState(blockPos).getMaterial();
                            if (material.isSolid() && !material.isLiquid()) {
                                // Spawn falling item display if block is an item
                                if (material.isBlock()) {
                                    BlockState blockState = world.getBlockState(blockPos);
                                    world.scheduleTick(blockPos.getX(), blockPos.getY(), blockPos.getZ(), blockState.getBlock(), 1);
                                }
                            }
                        }

                        // Apply entity damage
                        for (Entity entity : world.getNearbyEntities(
                            net.minecraft.core.Vec3.atBottomCenterOf(impactLocation), 
                            groupSize + 1, 
                            4.0, 
                            groupSize + 1
                        )) {
                            if (entity instanceof LivingEntity livingEntity && !livingEntity.equals(player)) {
                                double distance = livingEntity.distanceTo(impactLocation);
                                if (distance < groupSize + 0.5 && !(livingEntity instanceof Player && plugin.getTrustManager().isTrusted(player, (Player)livingEntity))) {
                                    // Apply true damage
                                    plugin.getAbilityTracker().applyTrueDamage(livingEntity, (float)getEarthShatterDamage(), player, true);
                                    
                                    // Knockback
                                    livingEntity.setVelocity(new Vector(0, getShatterKnockback(), 0));
                                    
                                    // Particle effects
                                    world.spawnParticle(ParticleOptions.DUST_OPACITY, 
                                        livingEntity.getX(), livingEntity.getY() + 1.0, livingEntity.getZ(), 
                                        15, 0.3, 0.5, 0.3, 0.0, 
                                        new ParticleOptions(
                                            ParticleType.DUST_COLOR, 
                                            200/255.0F, 
                                            165/255.0F, 
                                            100/255.0F, 
                                            1.0F
                                        )
                                    );
                                }
                            }
                        }

                        // Sound effect
                        world.playSound(null, impactLocation.x(), impactLocation.y(), impactLocation.z(), 
                            net.minecraft.sound.SoundEvents.BLOCK_GRAVEL_BREAK, 
                            net.minecraft.sound.SoundCategory.BLOCKS, 
                            0.5F, 0.8F
                        );
                    });
                }

                // Set cooldown
                plugin.getCooldownManager().setCooldown(player, cooldownKey, shatterCd * 1000L);
            }
        }
    }

    private void useStalwartAbsorption(Player player) {
        String cooldownKey = "paladin_stalwart";
        if (!plugin.getCooldownManager().isOnCooldown(player, cooldownKey)) {
            if (!absorbing.contains(player.getUUID())) {
                plugin.getCooldownManager().setCooldown(player, cooldownKey, stalwartCooldown * 1000L);
                
                // Start cooldown bar
                startCooldownBar(player, "Stalwart Absorption", stalwartCooldown, net.minecraft.world.color.Color.YELLOW);
                
                absorbing.add(player.getUUID());
                absorbedDamage.put(player.getUUID(), 0.0);
                player.playSound(player.getLocation(), net.minecraft.sound.SoundEvents.BLOCK_BEACON_ACTIVATE, 1.0F, 1.5F);
                
                // Set resistance effect
                int duration = getStalwartDuration();
                player.addPotionEffect(new net.minecraft.potion.PotionEffect(
                    net.minecraft.potion.PotionEffectType.RESISTANCE, 
                    duration, 
                    0
                ));
                
                // Start absorption timer
                new BukkitRunnable() {
                    int ticks = 0;
                    
                    @Override
                    public void run() {
                        if (ticks < duration && absorbing.contains(player.getUUID())) {
                            //Spawn particle effects
                            Location playerLoc = player.getLocation().add(0.0, 1.0, 0.0);
                            double progress = ticks * 0.2;
                            
                            for (int i = 0; i < 8; i++) {
                                double angle = progress + (Math.PI / 4) * i;
                                Location particleLoc = playerLoc.clone()
                                    .add(1.2 * Math.cos(angle), Math.sin(ticks * 0.1) * 0.5, 1.2 * Math.sin(angle));
                                
                                player.getWorld().spawnParticle(
                                    ParticleOptions.DUST_OPACITY, 
                                    particleLoc.x(), particleLoc.y(), particleLoc.z(), 
                                    1, 0.0, 0.0, 0.0, 
                                    new ParticleOptions(
                                        ParticleType.DUST_COLOR, 
                                        java.awt.Color.YELLOW.getRed() / 255.0F, 
                                        java.awt.Color.YELLOW.getGreen() / 255.0F, 
                                        java.awt.Color.YELLOW.getBlue() / 255.0F, 
                                        1.0F
                                    )
                                );
                            }
                            
                            double absorbed = absorbedDamage.getOrDefault(player.getUUID(), 0.0);
                            ticks++;
                        } else {
                            releaseAbsorbedDamage(player);
                            cancel();
                        }
                    }
                }.runTaskTimer(plugin, 0L, 1L);
            }
        }
    }

    void releaseAbsorbedDamage(Player player) {
        absorbing.remove(player.getUUID());
        absorbedDamage.remove(player.getUUID());
        
        double damage = Math.min(getStalwartDamageCap(), absorbedDamage.getOrDefault(player.getUUID(), 0.0));
        absorbedDamage.remove(player.getUUID());
        
        double knockback = getStalwartKnockback();
        player.playSound(player.getLocation(), net.minecraft.sound.SoundEvents.ENTITY_GENERIC_EXPLODE, 1.5F, 1.0F);
        player.playSound(player.getLocation(), net.minecraft.sound.SoundEvents.BLOCK_BEACON_DEACTIVATE, 1.0F, 0.8F);
        
        Location location = player.getLocation();
        
        // Particle effects
        player.getWorld().spawnParticle(ParticleOptions.DUST_OPACITY, location.clone().add(0.0, 1.0, 0.0), 80, 2.0, 1.0, 2.0, 0.1, 
            new ParticleOptions(
                ParticleType.DUST_COLOR, 
                java.awt.Color.YELLOW.getRed() / 255.0F, 
                java.awt.Color.YELLOW.getGreen() / 255.0F, 
                java.awt.Color.YELLOW.getBlue() / 255.0F, 
                2.0F
            )
        );
        player.getWorld().spawnParticle(ParticleOptions.EXPLOSION, location.clone().add(0.0, 1.0, 0.0), 3, 1.0, 0.5, 1.0, 0.0);
        
        // Shockwave damage
        int releaseRadius = getStalwartReleaseRadius();
        
        for (Entity entity : player.getWorld().getNearbyEntities(
            releaseRadius, 3.0, releaseRadius
        )) {
            if (entity instanceof LivingEntity livingEntity && !livingEntity.equals(player)) {
                if (!(livingEntity instanceof Player && plugin.getTrustManager().isTrusted(player, (Player)livingEntity))) {
                    livingEntity.damage(damage, player);
                    Vector knockbackVec = livingEntity.getLocation().toVector()
                        .subtract(location.toVector())
                        .normalize()
                        .multiply(knockback);
                    knockbackVec.setY(0.5);
                    livingEntity.setVelocity(knockbackVec);
                }
            }
        }
    }

    @Override
    public boolean onRightClickInteract(ItemStack stack, Level world, Player player, InteractionHand hand) {
        // Handle right click for earth shatter
        if (!skipDefaultActivation(player)) {
            if (isThisWeapon(player.getInventory().getItemInMainHand())) {
                useEarthShatter(player);
                return true;
            }
        }
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
        // Update passive effects
        if (entity instanceof Player) {
            Player player = (Player) entity;
            if (isHoldingThisWeapon(player)) {
                // Apply passive haste
                player.addPotionEffect(new net.minecraft.potion.PotionEffect(
                    net.minecraft.potion.PotionEffectType.HASTE, 
                    180, 
                    getPassiveHasteLevel(), 
                    false, 
                    false
                ));
            }
        }
    }

    private int getShatterCooldown() {
        return plugin.getConfig().getInt("abilities.paladinbattleaxe.shatter_cooldown", SHATTER_COOLDOWN_DEFAULT);
    }

    private int getStalwartCooldown() {
        return plugin.getConfig().getInt("abilities.paladinbattleaxe.stalwart_cooldown", STALWART_COOLDOWN_DEFAULT);
    }

    private int getPassiveHasteLevel() {
        return plugin.getConfig().getInt("abilities.paladinbattleaxe.passive_haste_level", 0);
    }

    private int getShatterRadius() {
        return plugin.getConfig().getInt("abilities.paladinbattleaxe.shatter_radius", 6);
    }

    private double getEarthShatterDamage() {
        return plugin.getConfig().getDouble("abilities.paladinbattleaxe.earth_shatter_damage", EARTH_SHATTER_DAMAGE_DEFAULT);
    }

    private double getShatterImpactDamage() {
        return plugin.getConfig().getDouble("abilities.paladinbattleaxe.shatter_impact_damage", 6.0);
    }

    private int getStalwartDuration() {
        return plugin.getConfig().getInt("abilities.paladinbattleaxe.stalwart_duration", STALWART_DURATION_DEFAULT);
    }

    private double getStalwartKnockback() {
        return plugin.getConfig().getDouble("abilities.paladinbattleaxe.stalwart_knockback", STALWART_KNOCKBACK_DEFAULT);
    }

    private double getStalwartDamageCap() {
        return plugin.getConfig().getDouble("abilities.paladinbattleaxe.stalwart_damage_cap", STALWARD_DAMAGE_CAP_DEFAULT);
    }

    private double getStalwartMinHitDamage() {
        return plugin.getConfig().getDouble("abilities.paladinbattleaxe.stalwart_min_hit_damage", STALWART_MIN_HIT_DAMAGE_DEFAULT);
    }

    private double getStalwartAbsorptionRatio() {
        return plugin.getConfig().getDouble("abilities.paladinbattleaxe.stalwart_absorption_ratio", STALWART_ABSORPTION_RATIO_DEFAULT);
    }

    private int getStalwartReleaseRadius() {
        return plugin.getConfig().getInt("abilities.paladinbattleaxe.stalwart_release_radius", 5);
    }

    // Cooldown bar management
    private void startCooldownBar(Player player, String barName, int duration, net.minecraft.world.color.Color color) {
        String barKey = player.getUUID() + ":" + barName;
        
        if (cooldownBars.containsKey(barKey)) {
            cooldownBars.get(barKey).removeAll();
        }
        
        net.kyori.adventure.bar.Bar bar = net.kyori.adventure.bar.Bar.create(
            net.kyori.adventure.bar.Bar.Color.valueOf(color),
            net.kyori.adventure.bar.Bar.Style.SOLID
        );
        
        bar.addPlayer(player);
        cooldownBars.put(barKey, bar);
        
        new BukkitRunnable() {
            double progress = duration;
            
            @Override
            public void run() {
                if (progress <= 0.0) {
                    bar.removeAll();
                    cooldownBars.remove(barKey);
                    player.playSound(player.getLocation(), net.minecraft.sound.SoundEvents.BLOCK_BELL_USE, 0.5F, 1.5F);
                    cancel();
                } else {
                    bar.setProgress(progress / duration);
                    progress -= 0.1;
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    @Override
    protected boolean isThisWeapon(ItemStack var1) {
        return var1 != null && var1.getType() != Material.AIR ? m.a(var1, this.WEAPON_KEY, this.getWeaponId()) : false;
    }
    
    // Helper method for applying damage
    private void applyDamage(LivingEntity entity, float amount) {
        entity.hurt(this.getIndirectEntityDamageSource(), amount);
    }
}