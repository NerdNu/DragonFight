package nu.nerd.df;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarFlag;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.boss.DragonBattle;
import org.bukkit.boss.DragonBattle.RespawnPhase;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EnderDragonChangePhaseEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.BoundingBox;

import net.md_5.bungee.api.ChatColor;
import nu.nerd.beastmaster.BeastMaster;
import nu.nerd.beastmaster.Drop;
import nu.nerd.beastmaster.DropResults;
import nu.nerd.beastmaster.DropSet;
import nu.nerd.beastmaster.DropType;
import nu.nerd.beastmaster.PotionSet;
import nu.nerd.beastmaster.ProbablePotion;
import nu.nerd.beastmaster.Util;
import nu.nerd.beastmaster.mobs.DataType;
import nu.nerd.beastmaster.mobs.MobProperty;
import nu.nerd.beastmaster.mobs.MobType;

// ----------------------------------------------------------------------------
/**
 * Records state information about the current dragon fight.
 * 
 * Define the term "arena" to mean the space within the circle of obsidian
 * pillars in the end.
 */
public class FightState implements Listener {
    // ------------------------------------------------------------------------
    /**
     * Actions performed on plugin enable.
     */
    public void onEnable() {
        discoverFightState();
        debug("Detected stage: " + _stageNumber);
        defineBeastMasterObjects();
        Bukkit.getScheduler().scheduleSyncRepeatingTask(DragonFight.PLUGIN, _tracker, TrackerTask.PERIOD_TICKS, TrackerTask.PERIOD_TICKS);
    }

    // ------------------------------------------------------------------------
    /**
     * Actions performed on plugin disable.
     */
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(DragonFight.PLUGIN);
    }

    // ------------------------------------------------------------------------
    /**
     * Stop the current fight and clean up mobs and projectiles.
     * 
     * @param sender the command sender, for messages.
     */
    public void stop(CommandSender sender) {
        World fightWorld = Bukkit.getWorld(FIGHT_WORLD);
        DragonBattle battle = fightWorld.getEnderDragonBattle();
        EnderDragon dragon = battle.getEnderDragon();
        if (dragon != null) {
            // Hacky, but works.
            Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), "execute in minecraft:the_end run kill @e[type=minecraft:ender_dragon]");
            sender.sendMessage(ChatColor.DARK_PURPLE + "Removed the dragon.");
        }

        for (EnderCrystal crystal : _crystals) {
            crystal.remove();
        }
        sender.sendMessage(ChatColor.DARK_PURPLE + "Removed crystals: " + ChatColor.LIGHT_PURPLE + _crystals.size());
        _crystals.clear();

        for (LivingEntity boss : _bosses) {
            boss.remove();
        }
        sender.sendMessage(ChatColor.DARK_PURPLE + "Removed bosses: " + ChatColor.LIGHT_PURPLE + _bosses.size());
        _bosses.clear();

        int projectileCount = 0;
        int mobCount = 0;
        for (Entity entity : fightWorld.getEntities()) {
            if (entity.isValid() && hasTagOrGroup(entity, ENTITY_TAG)) {
                entity.remove();
                if (entity instanceof Projectile) {
                    ++projectileCount;
                } else {
                    // Note: Crystals are tagged CRYSTAL_TAG, not ENTITY_TAG.
                    ++mobCount;
                }
            }
        }
        sender.sendMessage(ChatColor.DARK_PURPLE + "Removed mobs: " + ChatColor.LIGHT_PURPLE + mobCount);
        sender.sendMessage(ChatColor.DARK_PURPLE + "Removed projectiles: " + ChatColor.LIGHT_PURPLE + projectileCount);
        _stageNumber = 0;

        // Immediately hide the boss bar, rather than waiting for update.
        if (_bossBar != null) {
            _bossBar.setVisible(false);
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Remove the current stage boss and fight-associated support mobs and
     * projectiles, and skip to the next stage.
     */
    public void nextStage(CommandSender sender) {
        World fightWorld = Bukkit.getWorld(FIGHT_WORLD);
        DragonBattle battle = fightWorld.getEnderDragonBattle();
        if (battle.getEnderDragon() == null) {
            sender.sendMessage(ChatColor.RED + "You need to spawn a dragon with end crystals first!");
            return;
        }
        if (_stageNumber == 11) {
            stop(sender);
            return;
        }

        int projectileCount = 0;
        int bossCount = 0;
        int supportCount = 0;
        for (Entity entity : fightWorld.getEntities()) {
            if (entity.isValid()) {
                if (entity instanceof Projectile && hasTagOrGroup(entity, ENTITY_TAG)) {
                    entity.remove();
                    ++projectileCount;
                } else if (hasTagOrGroup(entity, BOSS_TAG)) {
                    entity.remove();
                    ++bossCount;
                } else if (hasTagOrGroup(entity, SUPPORT_TAG)) {
                    entity.remove();
                    ++supportCount;
                }
            }
        }
        sender.sendMessage(ChatColor.DARK_PURPLE + "Removed boss mobs: " + ChatColor.LIGHT_PURPLE + bossCount);
        sender.sendMessage(ChatColor.DARK_PURPLE + "Removed support mobs: " + ChatColor.LIGHT_PURPLE + supportCount);
        sender.sendMessage(ChatColor.DARK_PURPLE + "Removed projectiles: " + ChatColor.LIGHT_PURPLE + projectileCount);
        sender.sendMessage(ChatColor.DARK_PURPLE + "Starting stage: " + ChatColor.LIGHT_PURPLE + (_stageNumber + 1));

        // In stage 10, there are no more crystals to remove.
        if (_stageNumber < 10) {
            nextStage();
        } else {
            _stageNumber = 11;

            // Show a fixed stage 11 title for the dragon.
            getNearbyPlayers().forEach(p -> p.sendTitle(ChatColor.DARK_PURPLE + "Stage 11",
                                                        ChatColor.LIGHT_PURPLE + "Defeat the dragon.", 10, 70, 20));
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Log debug message.
     * 
     * @param message the message.
     */
    public static void debug(String message) {
        DragonFight.PLUGIN.getLogger().info(message);
    }

    // ------------------------------------------------------------------------
    /**
     * Return true if an entity has the specified scoreboard tag or BeastMaster
     * group.
     * 
     * @param entity the entity.
     * @param tag the tag or group to look for.
     * @return true if the entity has the tag or group.
     */
    public static boolean hasTagOrGroup(Entity entity, String tag) {
        if (entity.getScoreboardTags().contains(tag)) {
            return true;
        }

        MobType mobType = BeastMaster.getMobType(entity);
        if (mobType != null) {
            @SuppressWarnings("unchecked")
            Set<String> groups = (Set<String>) mobType.getDerivedProperty("groups").getValue();
            return groups != null && groups.contains(tag);
        }

        return false;
    }

    // ------------------------------------------------------------------------
    /**
     * Return true if the specified world is the one where the dragon fight can
     * occur.
     * 
     * @param world the world.
     * @return true if the specified world is the one where the dragon fight can
     *         occur.
     */
    public static boolean isFightWorld(World world) {
        return world.getEnvironment() == Environment.THE_END
               && world.getName().equals(FIGHT_WORLD);
    }

    // ------------------------------------------------------------------------
    /**
     * Return the magnitude of the X and Z components of location, i.e. the
     * distance between loc and the world origin in the XZ plane (ignoring Y
     * coordinate).
     * 
     * @param loc the location.
     * @return sqrt(x^2 + z^2).
     */
    public static double getMagnitude2D(Location loc) {
        return Math.sqrt(loc.getX() * loc.getX() + loc.getZ() * loc.getZ());
    }

    // ------------------------------------------------------------------------
    /**
     * Return true if the specified location is on the circle where the centre
     * of the obsidian pillars are placed.
     * 
     * The pillars about placed in a circle about 40 blocks from (0,0).
     * 
     * Note that:
     * <ul>
     * <li>This method assumes that the world has already been confirmed to be
     * the fight world.</li>
     * <li>This method doesn't care what the angle of the location is from north
     * (or whatever reference).</li>
     * </ul>
     * 
     * @param loc the location.
     * @return if the location is about the right range from the origin of the
     *         world to be the centre of a pillar.
     */
    public static boolean isOnPillarCircle(Location loc) {
        return Math.abs(getMagnitude2D(loc) - PILLAR_CIRCLE_RADIUS) < 0.1 * PILLAR_CIRCLE_RADIUS;
    }

    // ------------------------------------------------------------------------
    /**
     * When the plugin initialises, infer the state of the fight, including the
     * stage number, based on the presence of crystals, the dragon and bosses.
     * 
     * Find the ender crystals that are part of the current dragon fight.
     * 
     * There is no ChunkLoadEvent for spawn chunks, so we need to scan loaded
     * chunks on startup.
     */
    protected void discoverFightState() {
        World fightWorld = Bukkit.getWorld(FIGHT_WORLD);
        double radius = PILLAR_CIRCLE_RADIUS * 1.1;
        Location origin = new Location(fightWorld, 0, 64, 0);
        for (Entity entity : fightWorld.getNearbyEntities(origin, radius, 64, radius,
                                                          e -> e.getType() == EntityType.ENDER_CRYSTAL)) {
            debug("Loaded crystal: " + entity.getUniqueId() +
                  (entity.getScoreboardTags().contains(CRYSTAL_TAG) ? " (in the fight)" : ""));
            _crystals.add((EnderCrystal) entity);
        }

        // Work out what stage we're in.
        DragonBattle battle = fightWorld.getEnderDragonBattle();
        _stageNumber = (battle.getEnderDragon() == null) ? 0
                                                         : 10 - _crystals.size();
        if (_stageNumber == 10) {
            // The difference between stage 10 and 11 is that in 11 there are no
            // boss mobs.
            for (Entity entity : fightWorld.getEntities()) {
                if (entity.isValid() && hasTagOrGroup(entity, BOSS_TAG)) {
                    return;
                }
            }
            _stageNumber = 11;
        }

        // Find bosses within the discoverable range.
        BoundingBox box = new BoundingBox(-TRACKED_RADIUS, 0, -TRACKED_RADIUS,
                                          TRACKED_RADIUS, 256, TRACKED_RADIUS);
        Collection<Entity> entities = fightWorld.getNearbyEntities(box);
        for (Entity entity : entities) {
            if (entity instanceof LivingEntity) {
                if (hasTagOrGroup(entity, BOSS_TAG)) {
                    _bosses.add((LivingEntity) entity);
                }
            }
        }
        debug("Discovered bosses: " + _bosses.size());
    }

    // ------------------------------------------------------------------------
    /**
     * Create default definitions for BeastMaster objects.
     */
    protected void defineBeastMasterObjects() {
        boolean configurationChanged = false;

        // Default items.
        if (BeastMaster.ITEMS.getItem("df-elytra") == null) {
            BeastMaster.ITEMS.addItem("df-elytra", new ItemStack(Material.ELYTRA));
            configurationChanged = true;
        }
        if (BeastMaster.ITEMS.getItem("df-dragon-head") == null) {
            BeastMaster.ITEMS.addItem("df-dragon-head", new ItemStack(Material.DRAGON_HEAD));
            configurationChanged = true;
        }
        if (BeastMaster.ITEMS.getItem("df-placeholder-head") == null) {
            String skullData = "item:\n" +
                               "  ==: org.bukkit.inventory.ItemStack\n" +
                               "  v: 2230\n" +
                               "  type: PLAYER_HEAD\n" +
                               "  meta:\n" +
                               "    ==: ItemMeta\n" +
                               "    meta-type: SKULL\n" +
                               "    display-name: 'TODO: add texture'\n" +
                               "    internal: H4sIAAAAAAAAAE2KzW6CQBhFvzZpQkkfo1uSAQRk0YUpRIfIUJHf2Y0wRKaDNQhWfK4+YOmui5vcc89VAVR42X+OUn70X00ruQrq3M68H1p+eQZl4Ldh7PlFBYAHBZ4yJkcOP3wKEC2OqC4CWU3YnjnZIxlhcXbwKZsO79jG3ew3K3s7uf++1sByS5ZmcKSn3XjoMrQ1Y8k3sV516ZUktYgSH4WJr0dedS/vuxtNQoMI2c6R1MPfYecbVKwmIlKTisqg63Si69IIRSzLPF0QLxPE+9tSneSZCNvAbQr0BqDAI67hlTmmzh2r0pyGWdqC1ba2rF1XQ1W9ZDZzddsxAH4BxCeVXBwBAAA=\n";
            ItemStack dfPlaceholderHead = null;
            try {
                YamlConfiguration config = new YamlConfiguration();
                config.loadFromString(skullData);
                dfPlaceholderHead = config.getItemStack("item");
            } catch (InvalidConfigurationException ex) {
                DragonFight.PLUGIN.getLogger().warning("Unable to load df-placeholder-head.");
            }
            if (dfPlaceholderHead != null) {
                BeastMaster.ITEMS.addItem("df-placeholder-head", dfPlaceholderHead);
            }
            configurationChanged = true;
        }

        // Default potion sets.
        if (BeastMaster.POTIONS.getPotionSet("df-boss-potions") == null) {
            PotionSet dfBossPotions = new PotionSet("df-boss-potions");
            PotionEffect potionEffect = new PotionEffect(PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 0, false, false);
            dfBossPotions.addPotion(new ProbablePotion(potionEffect, 1.0));
            BeastMaster.POTIONS.addPotionSet(dfBossPotions);
            configurationChanged = true;
        }

        // EnderDragon friend groups.
        MobType dragonMobType = BeastMaster.MOBS.getMobType(EntityType.ENDER_DRAGON);
        MobProperty dragonFriendGroups = dragonMobType.getProperty("friend-groups");
        @SuppressWarnings("unchecked")
        Set<String> dragonFriendGroupsValue = (Set<String>) dragonFriendGroups.getValue();
        if (dragonFriendGroupsValue == null) {
            dragonMobType.getProperty("friend-groups").setValue(DataType.TAG_SET.deserialise("df-entity"));
        } else if (!dragonFriendGroupsValue.contains("df-entity")) {
            dragonFriendGroupsValue.add("df-entity");
        }

        // Default mob types.
        if (BeastMaster.MOBS.getMobType("df-support") == null) {
            MobType dfSupport = new MobType("df-support", "skeleton");
            dfSupport.getProperty("show-name-plate").setValue(true);
            dfSupport.getProperty("pick-up-percent").setValue(0.0);
            dfSupport.getProperty("helmet-drop-percent").setValue(0.0);
            dfSupport.getProperty("chest-plate-drop-percent").setValue(0.0);
            dfSupport.getProperty("leggings-drop-percent").setValue(0.0);
            dfSupport.getProperty("boots-drop-percent").setValue(0.0);
            dfSupport.getProperty("main-hand-drop-percent").setValue(0.0);
            dfSupport.getProperty("drops").setValue("df-no-drops");
            dfSupport.getProperty("can-despawn").setValue(false);
            dfSupport.getProperty("groups").setValue(DataType.TAG_SET.deserialise("df-entity,df-support"));
            dfSupport.getProperty("friend-groups").setValue(DataType.TAG_SET.deserialise("df-entity"));
            BeastMaster.MOBS.addMobType(dfSupport);
            configurationChanged = true;
        }
        if (BeastMaster.MOBS.getMobType("df-boss") == null) {
            MobType dfBoss = new MobType("df-boss", "df-support");
            dfBoss.getProperty("potion-buffs").setValue("df-boss-potions");
            dfBoss.getProperty("health").setValue(300.0);
            dfBoss.getProperty("groups").setValue(DataType.TAG_SET.deserialise("df-boss,df-entity"));
            BeastMaster.MOBS.addMobType(dfBoss);
            configurationChanged = true;
        }
        if (BeastMaster.MOBS.getMobType("df-placeholder-boss") == null) {
            MobType dfPlaceholderBoss = new MobType("df-placeholder-boss", "df-boss");
            dfPlaceholderBoss.getProperty("entity-type").setValue(EntityType.WITHER_SKELETON);
            dfPlaceholderBoss.getProperty("name").setValue("Test Boss Pls Ignore");
            dfPlaceholderBoss.getProperty("helmet").setValue("df-placeholder-head");
            dfPlaceholderBoss.getProperty("main-hand").setValue("stone_sword");
            BeastMaster.MOBS.addMobType(dfPlaceholderBoss);
            configurationChanged = true;
        }

        // Default loot tables.
        if (BeastMaster.LOOTS.getDropSet("df-dragon-drops") == null) {
            DropSet dfDragonDrops = new DropSet("df-dragon-drops");
            dfDragonDrops.addDrop(new Drop(DropType.ITEM, "df-elytra", 1.0, 1, 1));
            dfDragonDrops.addDrop(new Drop(DropType.ITEM, "df-dragon-head", 1.0, 1, 1));
            BeastMaster.LOOTS.addDropSet(dfDragonDrops);
            configurationChanged = true;
        }

        // Force df-no-drops to only drop NOTHING. Avoid saving always.
        DropSet dfNoDrops = BeastMaster.LOOTS.getDropSet("df-no-drops");
        if (dfNoDrops == null) {
            dfNoDrops = new DropSet("df-no-drops");
            dfNoDrops.addDrop(new Drop(DropType.NOTHING, "NOTHING", 1.0, 1, 1));
            BeastMaster.LOOTS.addDropSet(dfNoDrops);
            configurationChanged = true;
        } else {
            Drop nothing = dfNoDrops.getDrop("NOTHING");
            if (nothing == null ||
                nothing.getDropType() != DropType.NOTHING ||
                dfNoDrops.getAllDrops().size() != 1) {

                dfNoDrops = new DropSet("df-no-drops");
                dfNoDrops.addDrop(new Drop(DropType.NOTHING, "NOTHING", 1.0, 1, 1));
                BeastMaster.LOOTS.removeDropSet("df-no-drops");
                BeastMaster.LOOTS.addDropSet(dfNoDrops);
                configurationChanged = true;
            }
        }

        // Initialise stage loot tables.
        for (int stageNumber = 1; stageNumber <= 10; ++stageNumber) {
            _stages[stageNumber - 1] = new Stage(stageNumber);

            // Add the stage N boss mob loot tables, if not defined.
            String dropSetId = Stage.getDropSetId(stageNumber);
            if (BeastMaster.LOOTS.getDropSet(dropSetId) == null) {
                BeastMaster.LOOTS.addDropSet(new DropSet(dropSetId));
                configurationChanged = true;
            }
            DropSet stageDropSet = BeastMaster.LOOTS.getDropSet(dropSetId);
            if (stageDropSet.getAllDrops().isEmpty()) {
                stageDropSet.addDrop(new Drop(DropType.MOB, "df-placeholder-boss", 1.0, 1, 1));
                configurationChanged = true;
            }
        }

        // Save if anything changed.
        if (configurationChanged) {
            BeastMaster.CONFIG.save();
        }
    }

    // ------------------------------------------------------------------------
    /**
     * When the dragon spawns, handle it with
     * {@link #onDragonSpawn(EnderDragon)}.
     * 
     * Track any mobs that spawn with the boss group.
     * 
     * Handle end crystals spawns during the pillar-summoning phase only with
     * {@link #onCrystalSpawn(EnderCrystal)}.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    protected void onEntitySpawn(EntitySpawnEvent event) {
        Location loc = event.getLocation();
        World world = loc.getWorld();
        if (!isFightWorld(world)) {
            return;
        }

        Entity entity = event.getEntity();
        if (entity instanceof EnderDragon) {
            // Tag dragon as fight entity so its projectiles inherit that tag.
            entity.getScoreboardTags().add(ENTITY_TAG);
            onDragonSpawn((EnderDragon) entity);
        }

        // Track the bosses.
        if (entity instanceof LivingEntity && hasTagOrGroup(entity, BOSS_TAG)) {
            _bosses.add((LivingEntity) entity);
        }

        DragonBattle battle = world.getEnderDragonBattle();
        if (battle.getRespawnPhase() != RespawnPhase.SUMMONING_PILLARS) {
            return;
        }

        if (entity instanceof EnderCrystal) {
            onCrystalSpawn((EnderCrystal) entity);
        }
    }

    // ------------------------------------------------------------------------
    /**
     * When loading a chunk in the fight world, track any bosses.
     */
    @EventHandler()
    protected void onChunkLoad(ChunkLoadEvent event) {
        if (!isFightWorld(event.getWorld())) {
            return;
        }

        for (Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof LivingEntity && hasTagOrGroup(entity, BOSS_TAG)) {
                _bosses.add((LivingEntity) entity);
            }
        }
    }

    // ------------------------------------------------------------------------
    /**
     * When unloading a chunk in the fight world, un-track any bosses.
     */
    @EventHandler()
    protected void onChunkUnload(ChunkUnloadEvent event) {
        if (!isFightWorld(event.getWorld())) {
            return;
        }

        for (Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof LivingEntity && hasTagOrGroup(entity, BOSS_TAG)) {
                _bosses.remove(entity);
            }
        }
    }

    // ------------------------------------------------------------------------
    /**
     * When a projectile is launched by a mob with the ENTITY_TAG tag or group,
     * tag it with ENTITY_TAG so we can easily clean it up later.
     * 
     * The dragon is tagged thus so that its projectiles can also be removed.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    protected void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!isFightWorld(event.getEntity().getWorld())) {
            return;
        }

        Projectile projectile = event.getEntity();
        ProjectileSource shooter = projectile.getShooter();
        if (shooter instanceof Entity) {
            Entity shooterEntity = (Entity) shooter;
            if (hasTagOrGroup(shooterEntity, ENTITY_TAG)) {
                projectile.getScoreboardTags().add(ENTITY_TAG);
            }
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Protect the End Crystals on the pillars, since setInvulnerable(true) is
     * currently broken, apparently.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onEntityDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (!isFightWorld(entity.getWorld())) {
            return;
        }

        if (entity instanceof EnderCrystal) {
            if (_crystals.contains(entity)) {
                event.setCancelled(true);
            }
            return;
        }

        // Update the "last seen time" of bosses.
        if (hasTagOrGroup(entity, BOSS_TAG)) {
            Long now = System.currentTimeMillis();
            entity.setMetadata(BOSS_SEEN_TIME_KEY, new FixedMetadataValue(DragonFight.PLUGIN, now));
        }
    }

    // ------------------------------------------------------------------------
    /**
     * When a stage is cleared of all its boss mobs, spawn the next stage.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    protected void onEntityDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        if (!isFightWorld(entity.getWorld())) {
            return;
        }

        if (event.getEntityType() == EntityType.ENDER_DRAGON) {
            // TODO: df-dragon-drops custom handling.
            // TODO: clean up associated entities?
            _stageNumber = 0;
            return;
        }

        boolean bossDied = hasTagOrGroup(entity, BOSS_TAG);
        if (bossDied) {
            _bosses.remove(entity);
        }

        if (_stageNumber != 0 && bossDied && _bosses.isEmpty()) {
            // TODO: update the stage boss bar.
            // TODO: consult the tracker rather than assuming there is only a
            // single boss.
            if (_stageNumber == 10) {
                // Just the dragon in stage 11.
                debug("Beginning stage 11.");
                _stageNumber = 11;
            } else {
                nextStage();
            }
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Prevent bosses and other df-entity group mobs from using the end portal.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    protected void onEntityPortal(EntityPortalEvent event) {
        Entity entity = event.getEntity();
        if (!isFightWorld(entity.getWorld())) {
            return;
        }

        MobType mobType = BeastMaster.getMobType(entity);
        if (mobType != null) {
            if (hasTagOrGroup(entity, ENTITY_TAG)) {
                event.setCancelled(true);
                returnMobToBossSpawn(mobType, entity);
            }
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Prevent players from placing end crystals on bedrock in the end during
     * the SUMMONING_PILLARS phase of the dragon fight.
     * 
     * This is a simple precaution to ensure that any crystals spawning during
     * that phase are because of the dragon fight, and not due to players.
     * EntitySpawnEvent doesn't give us a spawn reason that would disambiguate
     * the two.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    protected void onPlayerInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getMaterial() != Material.END_CRYSTAL
            || event.getAction() != Action.RIGHT_CLICK_BLOCK
            || block.getType() != Material.OBSIDIAN) {
            return;
        }

        Location loc = block.getLocation();
        if (!isFightWorld(loc.getWorld()) || !isOnPillarCircle(loc)) {
            return;
        }

        DragonBattle battle = loc.getWorld().getEnderDragonBattle();
        if (battle != null && battle.getRespawnPhase() == RespawnPhase.SUMMONING_PILLARS) {
            event.setCancelled(true);
            debug("Cancelled " + event.getPlayer().getName() + " placing END_CRYSTAL at " + Util.formatLocation(loc));
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Monitor the vanilla dragon fight stages.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    protected void onEnderDragonChangePhase(EnderDragonChangePhaseEvent event) {
        debug("Changing to dragon phase: " + event.getNewPhase());
    }

    // ------------------------------------------------------------------------
    /**
     * When an end crystal spawns at the start of the fight, play accompanying
     * effects and register the entity.
     * 
     * Tag crystals that were spawned by the dragon fight to prevent us from
     * mis-identifying player-placed crystals as relevant.
     */
    protected void onCrystalSpawn(EnderCrystal crystal) {
        _crystals.add(crystal);

        Location loc = crystal.getLocation();
        debug(crystal.getType() + " " + crystal.getUniqueId() +
              " spawned at " + Util.formatLocation(loc));

        crystal.getScoreboardTags().add(CRYSTAL_TAG);
        crystal.setGlowing(true);

        // Currently doesn't do anything. Bug report needed:
        crystal.setInvulnerable(true);

        playSound(loc, Sound.BLOCK_BEACON_POWER_SELECT);
        loc.getWorld().strikeLightningEffect(loc);
    }

    // ------------------------------------------------------------------------
    /**
     * When the dragon spawns, schedule the start of phase 1.
     * 
     * We need to be careful about badly-timed restarts (as always). Since we
     * detect phases on reload by the absence of crystals, remove the crystal
     * before adding the boss.
     */
    protected void onDragonSpawn(EnderDragon dragon) {
        if (_crystals.size() == 0) {
            debug("Dragon spawned but there were no ender crystals?!");
            return;
        }

        // Let's have it so we can still see.
        World fightWorld = Bukkit.getWorld(FIGHT_WORLD);
        DragonBattle battle = fightWorld.getEnderDragonBattle();
        battle.getBossBar().removeFlag(BarFlag.DARKEN_SKY);
        battle.getBossBar().removeFlag(BarFlag.CREATE_FOG);

        nextStage();
    }

    // ------------------------------------------------------------------------
    /**
     * Start the next stage.
     * 
     * This method is called to do the boss spawning sequence for stages 1 to
     * 10.
     */
    @SuppressWarnings("deprecation")
    protected void nextStage() {
        // Remove a random crystal. Random order due to hashing UUID.
        EnderCrystal replacedCrystal = _crystals.iterator().next();
        // debug("Boss spawn location: " +
        // Util.formatLocation(bossSpawnLocation));

        // Schedule random flickering of the crystal and searching of the beam.
        // Needs to be delayed slightly after the dragon spawn for beam to work.
        int totalFlickerTicks = 5;
        while (totalFlickerTicks < STAGE_START_DELAY * 60 / 100) {
            int flickerTicks = Util.random(1, 5);
            totalFlickerTicks += flickerTicks;
            Bukkit.getScheduler().scheduleSyncDelayedTask(DragonFight.PLUGIN, () -> {
                Location beamTarget = getBossSpawnLocation().add(0, -2.5, 0);
                replacedCrystal.setBeamTarget(beamTarget);
                replacedCrystal.setGlowing(!replacedCrystal.isGlowing());
                playSound(beamTarget, Sound.BLOCK_BELL_RESONATE);
            }, totalFlickerTicks);
        }

        // Choose final beam target and spawn location.
        Location bossSpawnLocation = getBossSpawnLocation();

        // End with the replaced crystal not glowing.
        Bukkit.getScheduler().scheduleSyncDelayedTask(DragonFight.PLUGIN, () -> {
            Location beamTarget = bossSpawnLocation.clone().add(0, -2.5, 0);
            replacedCrystal.setGlowing(false);
            replacedCrystal.setBeamTarget(beamTarget);
            playSound(beamTarget, Sound.BLOCK_BELL_RESONATE);
        }, totalFlickerTicks + 5);

        // Give the boss a spawn sound and set final target position.
        Bukkit.getScheduler().scheduleSyncDelayedTask(DragonFight.PLUGIN, () -> {
            playSound(bossSpawnLocation, Sound.BLOCK_BEACON_ACTIVATE);
        }, STAGE_START_DELAY * 80 / 100);

        // Remove the crystal and spawn the boss.
        Bukkit.getScheduler().scheduleSyncDelayedTask(DragonFight.PLUGIN, () -> {
            ++_stageNumber;
            Stage stage = getCurrentStage();
            debug("Beginning stage: " + _stageNumber);

            // Despawn the crystal that becomes the boss.
            _crystals.remove(replacedCrystal);

            // Remove entity, spawn boss and play effects.
            replacedCrystal.remove();

            // Spawn boss or bosses and set up for tracking.
            DropResults results = new DropResults();
            DropSet dropSet = stage.getDropSet();
            dropSet.generateRandomDrops(results, "DragonFight stage " + stage, null, bossSpawnLocation);

            // Compute the total health for the stage's boss bar.
            _totalBossMaxHealth = results.getMobs().stream()
            .filter(mob -> hasTagOrGroup(mob, BOSS_TAG))
            .reduce(0.0, (sum, b) -> sum + b.getMaxHealth(), (h1, h2) -> h1 + h2);

            // TODO: stage max health needs to be save in case of restart.

            // Show the title.
            Set<Player> nearby = getNearbyPlayers();
            debug(nearby.size() + " players nearby.");
            stage.announce(nearby);
        }, STAGE_START_DELAY);
    }

    // ------------------------------------------------------------------------
    /**
     * Choose a random location to spawn the boss with a 3x3x3 volume of air
     * within the arena.
     * 
     * If a suitable location cannot be found, put it on the portal pillar.
     * 
     * If players decide to arrange the arena to frustrate efforts to find a
     * location to spawn the boss, moderate them hard.
     */
    protected static Location getBossSpawnLocation() {
        World world = Bukkit.getWorld(FIGHT_WORLD);
        double range = Util.random(BOSS_SPAWN_RADIUS_MIN, BOSS_SPAWN_RADIUS_MAX);
        double angle = Util.random() * 2.0 * Math.PI;
        double x = range * Math.cos(angle);
        double z = range * Math.sin(angle);
        float yaw = 360 * (float) Math.random();
        Location startLoc = new Location(world, x, ORIGIN_Y, z, yaw, 0f);

        // Find local highest block to stand on.
        Location loc = null;
        for (int i = 5; i >= -5; --i) {
            loc = startLoc.clone().add(0, i, 0);
            if (!loc.getBlock().isPassable()) {
                break;
            }
        }

        // Now go up to find space.
        for (int i = 1; i < 10; ++i) {
            loc.add(0, 1, 0);
            if (Util.isPassable3x3x3(loc)) {
                return loc;
            }
        }

        // If all else fails. Plonk it on the portal pillar.
        return new Location(world, 0.5, ORIGIN_Y + 1, 0.5, yaw, 0f);
    }

    // ------------------------------------------------------------------------
    /**
     * If the specified entity is a mob, teleport it to a boss spawn location,
     * playing particle and sound effects.
     * 
     * @param entity the mob.
     */
    protected static void returnMobToBossSpawn(MobType mobType, Entity entity) {
        Util.doTeleportEffects(mobType, entity.getLocation());
        Location newLoc = FightState.getBossSpawnLocation();
        entity.teleport(newLoc);
    }

    // ------------------------------------------------------------------------
    /**
     * Play a sound effect at the location with standard options.
     * 
     * @param loc the location.
     * @param sound the sound.
     */
    protected void playSound(Location loc, Sound sound) {
        loc.getWorld().playSound(loc, sound, 12.0f, 0.5f + 1.5f * (float) Math.random());
    }

    // ------------------------------------------------------------------------
    /**
     * Return the collection of players near the end portal that should see
     * titles.
     * 
     * @return nearby players.
     */
    protected Set<Player> getNearbyPlayers() {
        World world = Bukkit.getWorld(FIGHT_WORLD);
        return world.getPlayers().stream()
        .filter(p -> getMagnitude2D(p.getLocation()) < NEARBY_RADIUS)
        .collect(Collectors.toSet());
    }

    // ------------------------------------------------------------------------
    /**
     * Return the {@link Stage} instance for the current stage, or null if there
     * is no battle ongoing.
     * 
     * Note that _stageNumber is one higher than the array index.
     * 
     * @return the {@link Stage} instance for the current stage, or null if
     *         there is no battle ongoing.
     */
    protected Stage getCurrentStage() {
        return _stageNumber == 0 ? null : _stages[_stageNumber - 1];
    }

    // ------------------------------------------------------------------------
    /**
     * Update the BossBar for the current stage.
     * 
     * If we're not in stage 1-10, don't show a boss bar, even if we have some
     * test bosses.
     */
    protected void updateBossBar() {
        if (_bosses.size() == 0 || _stageNumber < 1 || _stageNumber > 10) {
            if (_bossBar != null) {
                _bossBar.setVisible(false);
            }
            return;
        }

        // TODO: remove after total boss max health is persistent.
        if (_totalBossMaxHealth < 1.0) {
            _totalBossMaxHealth = _bosses.stream()
            .filter(mob -> hasTagOrGroup(mob, BOSS_TAG))
            .reduce(0.0, (sum, b) -> sum + b.getMaxHealth(), (h1, h2) -> h1 + h2);
        }
        // debug("Stage: " + _stageNumber + ", Bosses: " + _bosses.size() + ",
        // Max: " + _totalBossMaxHealth);

        // Non-zero number of bosses. Stage 1 to 10.
        // Ensure we have a bar for the stage.
        Stage stage = getCurrentStage();
        if (_bossBar == null) {
            _bossBar = Bukkit.createBossBar("", BarColor.RED, BarStyle.SOLID, new BarFlag[0]);
        }
        _bossBar.setTitle(stage.getTitle());
        _bossBar.setVisible(true);

        // Update the players who see the bar.
        _bossBar.removeAll();
        getNearbyPlayers().forEach(p -> _bossBar.addPlayer(p));

        // Update the bar progress according to total remaining boss health.
        double totalBossHealth = _bosses.stream()
        .reduce(0.0, (sum, b) -> sum + b.getHealth(), (h1, h2) -> h1 + h2);
        _bossBar.setProgress(Util.clamp(totalBossHealth / _totalBossMaxHealth, 0.0, 1.0));
    }

    // ------------------------------------------------------------------------
    /**
     * A repeating task that tracks boss fight participants to:
     * 
     * <ul>
     * <li>Return bosses to the fight area when they go outside the designated
     * radius or below a certain Y coordinate.</li>
     * <li>Return bosses to the fight area when they haven't taken damage in a
     * minute, indicating the player can't find them.</li>
     * </ul>
     */
    private final class TrackerTask implements Runnable {
        // --------------------------------------------------------------------
        /**
         * Period in ticks between runs of this task.
         */
        static final int PERIOD_TICKS = 20;

        // --------------------------------------------------------------------
        /**
         * @see java.lang.Runnable#run()
         */
        @Override
        public void run() {
            Iterator<LivingEntity> it = _bosses.iterator();
            while (it.hasNext()) {
                LivingEntity boss = it.next();

                // Clean up bosses that have been remove()d, e.g. by /butcher.
                if (!boss.isValid()) {
                    it.remove();
                }

                // Track last seen time of bosses.
                long now = System.currentTimeMillis();
                List<MetadataValue> metas = boss.getMetadata(BOSS_SEEN_TIME_KEY);
                if (metas.isEmpty()) {
                    boss.setMetadata(BOSS_SEEN_TIME_KEY, new FixedMetadataValue(DragonFight.PLUGIN, now));
                } else {
                    long lastSeen = metas.get(0).asLong();
                    if (now - lastSeen > MAX_BOSS_NO_SEEN_TIME_MS) {
                        MobType mobType = BeastMaster.getMobType(boss);
                        debug("Returning " + mobType.getId() + " " +
                              boss.getUniqueId().toString().substring(0, 8) + " to the arena due to timeout.");
                        returnMobToBossSpawn(mobType, boss);
                        boss.setMetadata(BOSS_SEEN_TIME_KEY, new FixedMetadataValue(DragonFight.PLUGIN, now));
                        continue;
                    }
                }

                // Enforce "last seen" and position limits on bosses.
                Location loc = boss.getLocation();
                if (loc.getY() < MIN_BOSS_Y || getMagnitude2D(loc) > BOSS_RADIUS) {
                    MobType mobType = BeastMaster.getMobType(boss);
                    debug("Returning " + mobType.getId() + " " +
                          boss.getUniqueId().toString().substring(0, 8) + " to the arena due to location.");
                    returnMobToBossSpawn(mobType, boss);
                    boss.setMetadata(BOSS_SEEN_TIME_KEY, new FixedMetadataValue(DragonFight.PLUGIN, now));
                }
            } // while
            updateBossBar();
        } // run
    } // class TrackerTask

    // ------------------------------------------------------------------------
    /**
     * The World where the fight occurs.
     * 
     * It could be configurable, but for now is constant.
     */
    private static final String FIGHT_WORLD = "world_the_end";

    /**
     * Tag applied to end crystals on spawn so we know which ones are in the
     * fight on restart.
     */
    private static final String CRYSTAL_TAG = "df-crystal";

    /**
     * Tag applied to any entity spawned by the dragon fight: bosses, their
     * support mobs and launched projectiles, with the exception of the dragon
     * and crystals.
     */
    private static final String ENTITY_TAG = "df-entity";

    /**
     * Group of summoned boss mobs on spawn.
     */
    private static final String BOSS_TAG = "df-boss";

    /**
     * Group of support mobs summoned by bosses.
     */
    private static final String SUPPORT_TAG = "df-support";

    /**
     * Number of ticks to wait before starting the next stage.
     */
    private static int STAGE_START_DELAY = 150;

    /**
     * The approximate radius of the circle of pillars.
     */
    private static final double PILLAR_CIRCLE_RADIUS = 40.0;

    /**
     * Radius in blocks on the XZ plane within which players are considered to
     * be "nearby", i.e. close enough to see titles etc.
     * 
     * Note that only 2-D distance from the world origin is considered.
     */
    private static final double NEARBY_RADIUS = 100.0;

    /**
     * Minimum radius around the origin where bosses spawn.
     */
    private static final double BOSS_SPAWN_RADIUS_MIN = 10.0;

    /**
     * Maximum radius around the origin where bosses spawn.
     */
    private static final double BOSS_SPAWN_RADIUS_MAX = 30.0;

    /**
     * Starting Y coordinate to search for a spawnable location for the boss.
     */
    private static final double ORIGIN_Y = 60;

    /**
     * Minimum allowed Y coordinate of a boss before being moved back to a boss
     * spawn location.
     */
    private static final double MIN_BOSS_Y = 40.0;

    /**
     * Maximum XZ distance from spawn of any boss, before being respawned
     * between the pillars.
     */
    private static final double BOSS_RADIUS = 80.0;

    /**
     * Radius in which entities are checked.
     * 
     * This needs to be larger than BOSS_RADIUS to account for the maximum
     * distance the mob could travel in PERIOD_TICKS.
     */
    private static final double TRACKED_RADIUS = BOSS_RADIUS + 100.0;

    /**
     * System.currentMillis() timestamp of the last time a boss took damage or
     * was teleported back to the arena.
     */
    private static String BOSS_SEEN_TIME_KEY = "seen-time";

    /**
     * Maximum time in milliseconds that a boss is allowed to stand around not
     * taking damage before being teleported back to the arena.
     */
    private static final long MAX_BOSS_NO_SEEN_TIME_MS = 90 * 1000;

    // ------------------------------------------------------------------------
    /**
     * The remaining end crystals.
     */
    protected HashSet<EnderCrystal> _crystals = new HashSet<>();

    /**
     * The current set of boss mobs.
     * 
     * Used to efficiently enforce movement limits and update the current
     * stage's boss bar.
     */
    protected HashSet<LivingEntity> _bosses = new HashSet<>();

    /**
     * Current stage number.
     * 
     * in Stage N, N crystals have been removed.
     * 
     * Stage 0 is before the fight, Stage 1 => first crystal removed and boss
     * spawned. Stage 10 => final boss spawned. Stage 11: dragon.
     */
    int _stageNumber;

    /**
     * Sum of the maximum health of all current stage bosses so that the boss
     * bar can show the correct progress.
     * 
     * For ad-hoc testing, this updates whenever the total maximum health of all
     * bosses exceeds the current _totalBossMaxHealth value.
     * 
     * TODO: this needs to be saved with the configuration for restarts.
     */
    protected double _totalBossMaxHealth;

    /**
     * Array of 10 {@link Stage}s.
     * 
     * Stage N is at index N-1.
     * 
     * Stage 11, the dragon on its own, does not use a Stage instance.
     */
    protected Stage[] _stages = new Stage[10];

    /**
     * Tracks mobs to enforce boundaries and update boss bars.
     */
    protected TrackerTask _tracker = new TrackerTask();

    /**
     * Current stage boss bar, which tracks all currently active bosses.
     */
    protected BossBar _bossBar;
} // class FightState