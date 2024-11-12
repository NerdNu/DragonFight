package nu.nerd.df;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.World;
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
import org.bukkit.entity.Creature;
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
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.EnderDragonChangePhaseEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;

import nu.nerd.beastmaster.BeastMaster;
import nu.nerd.beastmaster.Drop;
import nu.nerd.beastmaster.DropResults;
import nu.nerd.beastmaster.DropSet;
import nu.nerd.beastmaster.DropType;
import nu.nerd.beastmaster.Item;
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
 *
 * The vanilla/Spigot DragonBattle class has various idiosyncrasies that this
 * plugin must work around:
 *
 * <ul>
 * <li>The insurmountable problem, which I discovered last (unfortunately), is
 * that a dragon summoned using the Bukkit API has no AI, and logically, even if
 * it did, might not have AI tailored to the dragon fight.</li>
 * <li>The Bukkit DragonBattle class can lose track of a dragon - perhaps it was
 * in a chunk that was unloaded when a player logged out - and it will then
 * spawn a replacement EnderDragon entity. Consequently, you can end up with
 * multiple dragons.</li>
 * <li>In a similar vein, the DragonBattle class will simply respawn a dragon
 * entity that was remove()d through the Bukkit API. We need to use the
 * "/minecraft:kill" command to remove dragons in a way that tells DragonBattle
 * to back off.</li>
 * <li>If the CreatureSpawnEvent of the ender dragon is cancelled, the
 * DragonBattle goes into some weird state where there is no dragon, but there
 * is a visible dragon boss bar, and ender crystals no longer summon a new
 * dragon. The server cannot be fixed via the API (e.g.
 * DragonBattle.setRespawnState(NONE)) or vanilla commands. Therefore, we must
 * allow the vanilla DragonBattle class to finish spawning the dragon.</li>
 * <li>The World.spawn() call that creates the EnderDragon does not assign the
 * reference to it in DragonBattle until that function returns (duh!), but
 * World.spawn() calls the function to pass EntitySpawnEvent to this plugin. So
 * when DragonFight receives an EntitySpawnEvent for the dragon,
 * DragonBattle.getEnderDragon() will return null until the next game tick. So
 * when a dragon spawns, we need to wait until the next game tick to see whether
 * the DragonBattle was the culprit.</li>
 * <li>You cannot change the properties of the dragon spawned by DragonBattle in
 * the EntitySpawnEvent (to make it invulnerable), perhaps because those
 * properties are modified when World.spawn() returns. You have to wait a
 * tick.</li>
 * <li>You cannot set end crystals (on pillars) invulnerable until the tick
 * after the DragonBattle spawns the vanilla dragon.</li>
 * </ul>
 *
 * EnderDragon SpawnReason possibilities:
 * <ul>
 * <li>DEFAULT signifies that the dragon was summoned by ender crystals (vanilla
 * dragon fight).</li>
 * <li>CUSTOM signifies that the dragon was summoned by a plugin.</li>
 * </ul>
 *
 */
public class FightState implements Listener {
    // ------------------------------------------------------------------------
    /**
     * Actions performed on plugin enable.
     */
    public void onEnable() {
        defineBeastMasterObjects();

        // Force load chunks in the end, then wait a few seconds and hope that
        // the entities are loaded. There is no way to force-load entities.
        preLoadEndChunks();
        Bukkit.getScheduler().runTaskLater(DragonFight.PLUGIN, () -> {
            recoverFightState();
            reconfigureDragonBossBar();
        }, 100);
        Bukkit.getScheduler().scheduleSyncRepeatingTask(DragonFight.PLUGIN, _tracker, TrackerTask.PERIOD_TICKS, TrackerTask.PERIOD_TICKS);

        // Every 30 seconds remove extra dragons. YOLO.
        Bukkit.getScheduler().scheduleSyncRepeatingTask(DragonFight.PLUGIN, () -> {
            // Probably not a good idea to exclude stage 0, since the spawn
            // event is not fired for surplus dragons. :/
            removeSurplusDragons();
        }, 600, 600);
    }

    // ------------------------------------------------------------------------
    /**
     * Actions performed on plugin disable.
     */
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(DragonFight.PLUGIN);

        // If 4 crystals are placed and there is a restart, I'm not sure if
        // the dragon will respawn after the restart. Log the situation so an
        // admin refund is possible.
        List<Entity> spawningCrystals = getDragonSpawnCrystals();
        if (spawningCrystals.size() == 4) {
            log("The server is restarting during dragon spawn (4 crystals placed).");
            cmdStop(Bukkit.getConsoleSender());
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Return true if a dragon fight is currently underway.
     *
     * @return true if a fight is happening.
     */
    public boolean isFightHappening() {
        return DragonFight.CONFIG.STAGE_NUMBER > 0;
    }

    // ------------------------------------------------------------------------
    /**
     * Return true when the stage number is changing to
     * {@link Configuration#NEW_STAGE_NUMBER}.
     *
     * @return true when the stage number is changing.
     */
    public boolean isStageNumberChanging() {
        return getStageNumber() != getNewStageNumber();
    }

    // ------------------------------------------------------------------------
    /**
     * Finish the change of stage number to
     * {@link Configuration#NEW_STAGE_NUMBER}.
     */
    public void finishStageNumberChange() {
        setStageNumber(getNewStageNumber());
    }

    // ------------------------------------------------------------------------
    /**
     * Immediately change to the specified stage number.
     *
     * {@link Configuration#NEW_STAGE_NUMBER} is also immediately set to the new
     * stage number to signify the transition is complete.
     *
     * @param stageNumber the new stage number.
     */
    public void immediatelyChangeStageNumber(int stageNumber) {
        setStageNumber(stageNumber);
        setNewStageNumber(stageNumber);
    }

    // ------------------------------------------------------------------------
    /**
     * Get the current fight stage number.
     *
     * @return 0 for no fight, or 1 to 11 for the various fight stages.
     */
    public int getStageNumber() {
        return DragonFight.CONFIG.STAGE_NUMBER;
    }

    // ------------------------------------------------------------------------
    /**
     * Set the fight stage number.
     *
     * @param stageNumber the new stage number.
     */
    public void setStageNumber(int stageNumber) {
        if (stageNumber < 0 || stageNumber > 11) {
            throw new IllegalArgumentException("invalid stage number: " + stageNumber);
        }
        DragonFight.CONFIG.STAGE_NUMBER = stageNumber;
    }

    // ------------------------------------------------------------------------
    /**
     * Get the new fight stage number.
     *
     * The stage number changes to this new value after mob spawn animations.
     *
     * @return 0 for no fight, or 1 to 11 for the various fight stages.
     */
    public int getNewStageNumber() {
        return DragonFight.CONFIG.NEW_STAGE_NUMBER;
    }

    // ------------------------------------------------------------------------
    /**
     * Set the new fight stage number.
     *
     * The stage number changes to this new value after mob spawn animations.
     *
     * @param stageNumber the new stage number.
     */
    public void setNewStageNumber(int stageNumber) {
        if (stageNumber < 0 || stageNumber > 11) {
            throw new IllegalArgumentException("invalid stage number: " + stageNumber);
        }
        DragonFight.CONFIG.NEW_STAGE_NUMBER = stageNumber;
    }

    // ------------------------------------------------------------------------
    /**
     * Implement the <i>/df info</i> command.
     *
     * Show information about the current fight: stage, owner, boss health and
     * dragon health.
     *
     * @param sender the command sender, for message sending.
     */
    public void cmdInfo(CommandSender sender) {
        sender.sendMessage(ChatColor.DARK_PURPLE + "The current fight stage is " +
                           ChatColor.LIGHT_PURPLE + getStageNumber() + ChatColor.DARK_PURPLE + ".");
        if (isStageNumberChanging()) {
            sender.sendMessage(ChatColor.DARK_PURPLE + "The fight stage is changing to " +
                               ChatColor.LIGHT_PURPLE + DragonFight.CONFIG.NEW_STAGE_NUMBER + ChatColor.DARK_PURPLE + ".");
        }
        if (!isFightHappening()) {
            return;
        }

        // Stage number is 1 to 11 from here on.
        OfflinePlayer fightOwner = (DragonFight.CONFIG.FIGHT_OWNER == null) ? null : Bukkit.getOfflinePlayer(DragonFight.CONFIG.FIGHT_OWNER);
        if (fightOwner == null) {
            sender.sendMessage(ChatColor.DARK_PURPLE + "There is no fight owner.");
        } else {
            sender.sendMessage(ChatColor.DARK_PURPLE + "The final drops are owned by " +
                               ChatColor.LIGHT_PURPLE + fightOwner.getName() + ChatColor.DARK_PURPLE + ".");
        }

        if (getStageNumber() >= 1 && getStageNumber() <= 10) {
            sender.sendMessage(ChatColor.DARK_PURPLE + "The current total boss health is " +
                               ChatColor.LIGHT_PURPLE + String.format("%.1f", getTotalBossHealth()) +
                               ChatColor.DARK_PURPLE + " out of " +
                               ChatColor.LIGHT_PURPLE + DragonFight.CONFIG.TOTAL_BOSS_MAX_HEALTH + ".");
            sender.sendMessage(ChatColor.DARK_PURPLE + "Bosses:");
            _bosses.stream().sorted((b1, b2) -> {
                MobType b1MobType = BeastMaster.getMobType(b1);
                MobType b2MobType = BeastMaster.getMobType(b2);
                return b1MobType.getId().compareToIgnoreCase(b2MobType.getId());
            }).forEach((boss) -> {
                MobType bossMobType = BeastMaster.getMobType(boss);
                String bossName = (String) bossMobType.getDerivedProperty("name").getValue();
                if (bossName == null) {
                    bossName = ChatColor.GRAY + "no name";
                }
                sender.sendMessage(ChatColor.LIGHT_PURPLE + bossMobType.getId() + " " +
                                   ChatColor.translateAlternateColorCodes('&', bossName) +
                                   ChatColor.DARK_PURPLE + " - " +
                                   ChatColor.LIGHT_PURPLE + String.format("%.1f", boss.getHealth()) +
                                   ChatColor.DARK_PURPLE + " / " +
                                   ChatColor.LIGHT_PURPLE + String.format("%.1f", boss.getMaxHealth()));
            });
        }

        DragonBattle battle = DragonUtil.getFightWorld().getEnderDragonBattle();
        EnderDragon dragon = battle.getEnderDragon();
        if (dragon != null) {
            sender.sendMessage(ChatColor.DARK_PURPLE + "The current dragon health is " +
                               ChatColor.LIGHT_PURPLE + dragon.getHealth() +
                               ChatColor.DARK_PURPLE + " out of " +
                               ChatColor.LIGHT_PURPLE + dragon.getMaxHealth() + ".");
        } else {
            sender.sendMessage(ChatColor.DARK_PURPLE + "There is no dragon associated with the battle. :/");
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Implement the <i>/df stop</i> command.
     *
     * Stop the current fight and clean up mobs and projectiles.
     *
     * @param sender the command sender, for message sending.
     */
    public void cmdStop(CommandSender sender) {
        sender.sendMessage(ChatColor.DARK_PURPLE + "Stopping the fight.");
        World fightWorld = DragonUtil.getFightWorld();
        DragonBattle battle = fightWorld.getEnderDragonBattle();
        EnderDragon dragon = battle.getEnderDragon();
        if (dragon != null) {
            sender.sendMessage(ChatColor.DARK_PURPLE + "Removed the dragon.");
        }

        // Let's not even assume that the dragon is properly associated with
        // the DragonBattle.
        DragonUtil.removeAllDragons();

        // Don't assume that the state is consistent, i.e. that spawning
        // cystals are mutually exclusive with the dragon.
        List<Entity> spawningCrystals = getDragonSpawnCrystals();
        if (!spawningCrystals.isEmpty()) {
            spawningCrystals.forEach(e -> e.remove());
            sender.sendMessage(ChatColor.DARK_PURPLE + "Removed spawning crystals: " +
                               ChatColor.LIGHT_PURPLE + spawningCrystals.size());
        }

        for (EnderCrystal crystal : _crystals) {
            crystal.remove();
        }
        sender.sendMessage(ChatColor.DARK_PURPLE + "Removed pillar crystals: " + ChatColor.LIGHT_PURPLE + _crystals.size());
        _crystals.clear();

        cleanUpMobsAndProjectiles(sender);

        // Immediately hide the boss bar, rather than waiting for update.
        if (_bossBar != null) {
            _bossBar.setVisible(false);
        }

        // Clear stage numbers prior to config save.
        immediatelyChangeStageNumber(0);

        // Nobody owns the drops, even if a dragon randomly spawns for funsies.
        DragonFight.CONFIG.FIGHT_OWNER = null;
        DragonFight.CONFIG.save();
    }

    // ------------------------------------------------------------------------
    /**
     * Implement the <i>/df next</i> command.
     *
     * Remove the current stage boss and fight-associated support mobs and
     * projectiles, and skip to the next stage.
     *
     * @param sender the command sender, for message sending.
     */
    public void cmdNextStage(CommandSender sender) {
        World fightWorld = DragonUtil.getFightWorld();
        DragonBattle battle = fightWorld.getEnderDragonBattle();
        if (battle.getEnderDragon() == null) {
            sender.sendMessage(ChatColor.RED + "You need to spawn a dragon with end crystals first!");
            return;
        }

        int nextStage = (getStageNumber() + 1) % 12;
        if (nextStage == 0) {
            cmdStop(sender);
            return;
        }

        cleanUpMobsAndProjectiles(sender);
        despawnPillarCrystals(1);

        // Skip to the next stage.
        startStage(sender, getStageNumber() + 1, getBossSpawnLocation());
    }

    // ------------------------------------------------------------------------
    /**
     * Implement the <i>/df stage</i> command.
     *
     * Remove the current stage boss and fight-associated support mobs and
     * projectiles, and start the specified stage.
     *
     * The current implementation only supports skipping forwards - not going
     * backwards.
     *
     * @param sender      the command sender, for message sending.
     * @param stageNumber the new stage number from 0 (stopped) to 11 (dragon
     *                    only).
     */
    public void cmdSkipToStage(CommandSender sender, int stageNumber) {
        if (stageNumber < 0 || stageNumber > 11) {
            sender.sendMessage(ChatColor.RED + "The stage number must be from 0 to 11.");
            return;
        }

        if (stageNumber == 0) {
            cmdStop(sender);
            return;
        }

        if (stageNumber < getStageNumber()) {
            sender.sendMessage(ChatColor.RED + "Skipping backwards is not currently supported.");
            return;
        }

        if (stageNumber == getStageNumber()) {
            sender.sendMessage(ChatColor.DARK_PURPLE + "You're already in stage " +
                               ChatColor.LIGHT_PURPLE + stageNumber + ChatColor.DARK_PURPLE + ".");
            return;
        }

        // So from here on, stageNumber > getStageNumber().
        World fightWorld = DragonUtil.getFightWorld();
        DragonBattle battle = fightWorld.getEnderDragonBattle();
        if (battle.getEnderDragon() == null) {
            sender.sendMessage(ChatColor.RED + "You have to spawn the dragon first, using end crystals.");
            return;
        }

        cleanUpMobsAndProjectiles(sender);

        int skippedStages = stageNumber - getStageNumber();
        despawnPillarCrystals(skippedStages);
        startStage(sender, stageNumber, stageNumber == 11 ? null : getBossSpawnLocation());
    }

    // ------------------------------------------------------------------------
    /**
     * Log messages.
     *
     * @param message the message.
     */
    public static void log(String message) {
        DragonFight.PLUGIN.getLogger().info(ChatColor.translateAlternateColorCodes('&', DragonFight.CONFIG.LOG_PREFIX) +
                                            ' ' + message);
    }

    // ------------------------------------------------------------------------
    /**
     * Log debug messages.
     *
     * @param message the message.
     */
    public static void debug(String message) {
        DragonFight.PLUGIN.getLogger().info(ChatColor.translateAlternateColorCodes('&', DragonFight.CONFIG.DEBUG_PREFIX) +
                                            ' ' + message);
    }

    // ------------------------------------------------------------------------
    /**
     * Clean up mobs and projectiles and message the command sender with tallies
     * of entities removed.
     */
    protected void cleanUpMobsAndProjectiles(CommandSender sender) {
        int projectileCount = 0;
        int bossCount = 0;
        int supportCount = 0;
        for (Entity entity : DragonUtil.getFightWorld().getEntities()) {
            if (entity.isValid()) {
                if (entity instanceof Projectile && DragonUtil.hasTagOrGroup(entity, ENTITY_TAG)) {
                    entity.remove();
                    ++projectileCount;
                } else if (DragonUtil.hasTagOrGroup(entity, BOSS_TAG)) {
                    entity.remove();
                    ++bossCount;
                } else if (DragonUtil.hasTagOrGroup(entity, SUPPORT_TAG)) {
                    entity.remove();
                    ++supportCount;
                }
            }
        }

        _bosses.clear();
        sender.sendMessage(ChatColor.DARK_PURPLE + "Removed boss mobs: " + ChatColor.LIGHT_PURPLE + bossCount);
        sender.sendMessage(ChatColor.DARK_PURPLE + "Removed support mobs: " + ChatColor.LIGHT_PURPLE + supportCount);
        sender.sendMessage(ChatColor.DARK_PURPLE + "Removed projectiles: " + ChatColor.LIGHT_PURPLE + projectileCount);
    }

    // ------------------------------------------------------------------------
    /**
     * Load relevant end chunks when the plugin loads.
     *
     * As of Minecraft 1.17, loading a chunk does not immediately load the
     * entities therein; they are loaded asynchronously, some time later. There
     * is no method within the 1.18 Bukkit API to forcibly load those entities
     * synchronously.
     *
     * On startup, we need to add all the end crystals to the {@link #_crystals}
     * array. So this method starts the ball rolling on loading them and we
     * check for them a few seconds later in {@link recoverFightState()}.
     *
     * References:
     * <ul>
     * <li>https://github.com/PaperMC/Paper/issues/5872</li>
     * <li>https://hub.spigotmc.org/jira/browse/SPIGOT-6547</li>
     * <li>https://hub.spigotmc.org/jira/browse/SPIGOT-6934</li>
     * </ul>
     */
    protected void preLoadEndChunks() {
        World fightWorld = DragonUtil.getFightWorld();

        // Preload chunks to ensure we find the crystals.
        int chunkRange = (int) Math.ceil(TRACKED_RADIUS / 16);
        for (int x = -chunkRange; x <= chunkRange; ++x) {
            for (int z = -chunkRange; z <= chunkRange; ++z) {
                Chunk chunk = fightWorld.getChunkAt(x, z);
                chunk.addPluginChunkTicket(DragonFight.PLUGIN);
                chunk.setForceLoaded(true);
                chunk.load();
            }
        }
    }

    // ------------------------------------------------------------------------
    /**
     * When the plugin initialises, infer the state of the fight, including the
     * stage number, based on the presence of crystals, the dragon and bosses.
     * Then reconcile the inferred stage number with that saved in the config.
     *
     * Find the ender crystals that are part of the current dragon fight.
     *
     * There is no ChunkLoadEvent for spawn chunks, so we need to scan loaded
     * chunks on startup.
     */
    protected void recoverFightState() {
        World fightWorld = DragonUtil.getFightWorld();
        DragonBattle battle = fightWorld.getEnderDragonBattle();

        if (battle.getEnderDragon() != null) {
            log("Dragon " + battle.getEnderDragon().getUniqueId() + " exists.");
        } else {
            log("No dragon exists.");
        }

        // Preload chunks to ensure we find the crystals.
        int chunkRange = (int) Math.ceil(TRACKED_RADIUS / 16);
        for (int x = -chunkRange; x <= chunkRange; ++x) {
            for (int z = -chunkRange; z <= chunkRange; ++z) {
                Chunk chunk = fightWorld.getChunkAt(x, z);
                for (Entity entity : chunk.getEntities()) {
                    if (entity instanceof EnderCrystal && entity.getScoreboardTags().contains(PILLAR_CRYSTAL_TAG)) {
                        _crystals.add((EnderCrystal) entity);
                        // In case the restart happened immediately after
                        // the crystal spawned.
                        entity.setInvulnerable(true);

                    } else if (entity instanceof LivingEntity) {
                        // Find bosses within the discoverable range.
                        if (DragonUtil.hasTagOrGroup(entity, BOSS_TAG)) {
                            _bosses.add((LivingEntity) entity);
                        }
                    }
                }
            }
        }
        log("Discovered bosses: " + _bosses.size());
        log("Discovered crystals: " + _crystals.size());

        if (getStageNumber() < 0 || getStageNumber() > 11) {
            log("Stage number " + getStageNumber() + " is out of bounds. Setting to 0.");
            immediatelyChangeStageNumber(0);
        }

        log("Current stage: " + getStageNumber());
        if (isStageNumberChanging()) {
            log("Transitioning from stage " + getStageNumber() + " to stage " + getNewStageNumber());
            if (getNewStageNumber() != getStageNumber() + 1) {
                log("Too many skipped stages! Going direct to " + getNewStageNumber() + ".");
                startStage(null, getNewStageNumber(), getNewStageNumber() == 11 ? null : getBossSpawnLocation());
            } else {
                // Normal path... show boss spawn animations.
                animateNextStage();
            }
        }
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
            configurationChanged = true;
        } else if (!dragonFriendGroupsValue.contains("df-entity")) {
            dragonFriendGroupsValue.add("df-entity");
            configurationChanged = true;
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

        // Initialise stage loot tables. Stage 11 has no loot table.
        for (int stageNumber = 1; stageNumber <= 10; ++stageNumber) {
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
     * When a player logs in, notify them if they have unclaimed prizes.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    protected void onPlayerLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        int unclaimedCount = DragonFight.CONFIG.getUnclaimedPrizes(player.getUniqueId());
        if (unclaimedCount > 0) {
            // Delay the message so it comes after the usual noise.
            Bukkit.getScheduler().runTaskLater(DragonFight.PLUGIN, () -> {
                String plural = (unclaimedCount > 1) ? "s" : "";
                if (player.isOnline()) {
                    player.sendMessage(ChatColor.DARK_PURPLE + "You have " +
                                       ChatColor.LIGHT_PURPLE + unclaimedCount +
                                       ChatColor.DARK_PURPLE + " unclaimed dragon fight prize" + plural + ".");
                    player.sendMessage(ChatColor.DARK_PURPLE + "Ensure you have some empty inventory slots,");
                    player.sendMessage(ChatColor.DARK_PURPLE + "then run " +
                                       ChatColor.LIGHT_PURPLE + "/dragon prize" +
                                       ChatColor.DARK_PURPLE + " to claim your prize.");
                }
            }, 25);
        }
    }

    // ------------------------------------------------------------------------
    /**
     * When the dragon spawns, handle it with
     * {@link #onDragonSpawn(EnderDragon)}.
     *
     * Track any mobs that spawn with the boss group.
     *
     * @param event the event.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    protected void onCreatureSpawn(CreatureSpawnEvent event) {
        Location loc = event.getLocation();
        World world = loc.getWorld();
        if (!DragonUtil.isFightWorld(world)) {
            return;
        }

        Entity entity = event.getEntity();

        // Track the bosses. These might be dragons, but are not THE dragon.
        if (DragonUtil.hasTagOrGroup(entity, BOSS_TAG)) {
            LivingEntity boss = (LivingEntity) entity;
            MobType bossMobType = BeastMaster.getMobType(boss);
            log("Boss spawned: " + bossMobType.getId());
            _bosses.add(boss);
            DragonFight.CONFIG.TOTAL_BOSS_MAX_HEALTH += boss.getMaxHealth();
        } else {
            // Pass vanilla (DragonBattle) dragon spawns (not plugin spawns) to
            // onDragonSpawn().
            SpawnReason reason = event.getSpawnReason();
            if (reason == SpawnReason.DEFAULT && entity instanceof EnderDragon) {
                onDragonSpawn((EnderDragon) entity, reason);
            }
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Handle end crystals spawns during the pillar-summoning phase only with
     * {@link #onPillarCrystalSpawn(EnderCrystal)}.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    protected void onEntitySpawn(EntitySpawnEvent event) {
        Location loc = event.getLocation();
        World world = loc.getWorld();
        if (!DragonUtil.isFightWorld(world)) {
            return;
        }

        Entity entity = event.getEntity();
        if (entity instanceof EnderCrystal) {
            // Make the summoning crystals glowing and invulnerable to prevent
            // the player from initiating the fight and then destroying the
            // spawning crystals.
            if (isDragonSpawnCrystalLocation(loc.getBlock().getRelative(0, -1, 0).getLocation())) {
                entity.setInvulnerable(true);
                entity.setGlowing(true);
                entity.getScoreboardTags().add(SPAWNING_CRYSTAL_TAG);
            }

            // Register and protect crystals spawned on the pillars.
            DragonBattle battle = world.getEnderDragonBattle();
            if (battle.getRespawnPhase() == RespawnPhase.SUMMONING_PILLARS &&
                DragonUtil.isOnPillarCircle(entity.getLocation())) {
                onPillarCrystalSpawn((EnderCrystal) entity);
            }
        }
    }

    // ------------------------------------------------------------------------
    /**
     * When loading a chunk in the fight world, track any bosses.
     */
    @EventHandler()
    protected void onChunkLoad(ChunkLoadEvent event) {
        if (!DragonUtil.isFightWorld(event.getWorld())) {
            return;
        }

        for (Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof LivingEntity && DragonUtil.hasTagOrGroup(entity, BOSS_TAG)) {
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
        if (!DragonUtil.isFightWorld(event.getWorld())) {
            return;
        }

        for (Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof LivingEntity && DragonUtil.hasTagOrGroup(entity, BOSS_TAG)) {
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
        if (!DragonUtil.isFightWorld(event.getEntity().getWorld())) {
            return;
        }

        Projectile projectile = event.getEntity();
        ProjectileSource shooter = projectile.getShooter();
        if (shooter instanceof Entity) {
            Entity shooterEntity = (Entity) shooter;
            if (DragonUtil.hasTagOrGroup(shooterEntity, ENTITY_TAG)) {
                projectile.getScoreboardTags().add(ENTITY_TAG);
            }
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Protect the End Crystals on the pillars and the four crystals that spawn
     * the dragon, and protect the dragon in stages 1 to 10.
     *
     * You might think that you could just call setInvulnerable(true) on the
     * dragon to make it invulnerable. Perhaps in a periodic task like the
     * {@link FightState#Tracker}. You would be wrong; having setInvulnerable()
     * work as advertised is just wishful thinking.
     *
     * I had some problems with setInvulnerable(); it doesn't <i>just work</i>
     * on the 10 pillar crystals. If you think you can just set them
     * invulnerable when they spawn well no, that would be way too simple. What
     * I found was that vanilla Minecraft protects these crystals <i>until</i>
     * the dragon spawns. Probably behind the scenes vanilla code is then
     * setting them back to vulnerable after that. So, if you then set the
     * crystals invulnerable in the next tick <i>after the dragon is spawned</i>
     * the crystals will be protected. So this crystal protection code is here
     * partly for curiosity and partly to fend off any race condition in the
     * transition.
     *
     * This event needs to be processed before e.g. SafeCrystals drops a
     * crystal.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDamageEarly(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        // debug("onEntityDamageEarly() " + event.getEntityType() +
        // Util.formatLocation(entity.getLocation()));
        if (!DragonUtil.isFightWorld(entity.getWorld())) {
            return;
        }

        if (entity instanceof EnderDragon && getStageNumber() < 11) {
            DragonBattle battle = DragonUtil.getFightWorld().getEnderDragonBattle();
            if (entity == battle.getEnderDragon()) {
                event.setCancelled(true);
            }
        } else if (entity instanceof EnderCrystal) {
            if (_crystals.contains(entity) ||
                DragonUtil.hasTagOrGroup(entity, PILLAR_CRYSTAL_TAG) ||
                DragonUtil.hasTagOrGroup(entity, SPAWNING_CRYSTAL_TAG)) {
                event.setCancelled(true);
                // debug("Prevented end crystal damage at " +
                // Util.formatLocation(entity.getLocation()));
            }
            return;
        }
    }

    // ------------------------------------------------------------------------
    /**
     * What is this madness? Two handlers for the same event?! Well, one of them
     * has to be early and the other late.
     *
     * We need to check the final outcome of the EntityDamageEvent in order to
     * correctly update boss state, since some other plugin may have cancelled
     * the event.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onEntityDamageLate(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (!DragonUtil.isFightWorld(entity.getWorld())) {
            return;
        }

        // Update the "last seen time" of bosses and update boss bar.
        if (DragonUtil.hasTagOrGroup(entity, BOSS_TAG)) {
            Long now = System.currentTimeMillis();
            entity.setMetadata(BOSS_SEEN_TIME_KEY, new FixedMetadataValue(DragonFight.PLUGIN, now));

            LivingEntity boss = (LivingEntity) entity;
            if (_bossBar != null && _bossBar.isVisible()) {
                double finalHealth = Math.max(0.0, boss.getHealth() - event.getFinalDamage());
                double healthLoss = boss.getHealth() - finalHealth;
                double newProgress = _bossBar.getProgress() - healthLoss / DragonFight.CONFIG.TOTAL_BOSS_MAX_HEALTH;
                _bossBar.setProgress(Util.clamp(newProgress, 0.0, 1.0));
            }
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Prevent fight-relevant crystals from exploding.
     *
     * These will be the pillar crystals, or the dragon spawning ones. We can't
     * rely on checking for bedrock under them because of pistons.
     *
     * I haven't actually seen this code being called, but on the other hand it
     * doesn't hurt so it's going to stay for now.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityExplode(EntityExplodeEvent event) {
        Entity entity = event.getEntity();
        if (entity.getType() != EntityType.ENDER_CRYSTAL || !DragonUtil.isFightWorld(entity.getWorld())) {
            return;
        }

        if (DragonUtil.hasTagOrGroup(entity, PILLAR_CRYSTAL_TAG) ||
            DragonUtil.hasTagOrGroup(entity, SPAWNING_CRYSTAL_TAG)) {

            log("Prevented end crystal explosion at " + Util.formatLocation(entity.getLocation()));
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Prevent pillar crystals from being set on fire.
     *
     * This is easier said than done. You can cancel the event, or set the fire
     * ticks after the fact to be small so that the fire extinguishes itself
     * quickly, or both. Either way, the current 1.15.2 client just shows the
     * crystal on fire until you relog.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityCombust(EntityCombustEvent event) {
        Entity entity = event.getEntity();
        if (entity.getType() != EntityType.ENDER_CRYSTAL || !DragonUtil.isFightWorld(entity.getWorld())) {
            return;
        }

        if (DragonUtil.hasTagOrGroup(entity, PILLAR_CRYSTAL_TAG)) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTaskLater(DragonFight.PLUGIN, () -> entity.setFireTicks(1), 1);
            // debug("Prevent combustion of " + event.getEntityType() +
            // " at " + Util.formatLocation(event.getEntity().getLocation()));
        }
    }

    // ------------------------------------------------------------------------
    /**
     * When a stage is cleared of all its boss mobs, spawn the next stage.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    protected void onEntityDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        if (!DragonUtil.isFightWorld(entity.getWorld())) {
            return;
        }

        if (event.getEntity() instanceof EnderDragon) {
            onDragonDeath((EnderDragon) event.getEntity());
            return;
        }

        boolean bossDied = DragonUtil.hasTagOrGroup(entity, BOSS_TAG);
        if (bossDied) {
            _bosses.remove(entity);
            MobType bossMobType = BeastMaster.getMobType(entity);
            log("Boss died: " + bossMobType.getId());
        }

        // The fight is going and all the bosses are dead. Next stage!
        if (isFightHappening() && bossDied && _bosses.isEmpty()) {
            animateNextStage();
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Prevent bosses and other df-entity group mobs from using the end portal.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    protected void onEntityPortal(EntityPortalEvent event) {
        Entity entity = event.getEntity();
        if (!DragonUtil.isFightWorld(entity.getWorld())) {
            return;
        }

        MobType mobType = BeastMaster.getMobType(entity);
        if (mobType != null) {
            if (DragonUtil.hasTagOrGroup(entity, ENTITY_TAG)) {
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
     *
     * Also, prevent players from placing crystals in arbitrary locations on the
     * end portal frame bedrock. Crystals can only be placed in the four
     * designated "dragon spawning crystal" locations. The player gets a message
     * about that. Also, once the dragon is summoned, or summoning has started,
     * don't allow extra crystals in those four locations until the dragon is
     * dead.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    protected void onPlayerInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        // debug("Clicked " + block.getType() +
        // " at " + Util.formatLocation(block.getLocation()) +
        // " action " + event.getAction() + " hand " + event.getHand() +
        // " with " + event.getMaterial());
        if (event.getMaterial() != Material.END_CRYSTAL ||
            event.getAction() != Action.RIGHT_CLICK_BLOCK ||
            block.getType() != Material.BEDROCK) {
            return;
        }

        Location blockLoc = block.getLocation();
        if (!DragonUtil.isFightWorld(blockLoc.getWorld())) {
            return;
        }

        // If the player is placing a spawning crystal when 3 already exist
        // and there is no dragon, then he is the fight owner.
        World fightWorld = DragonUtil.getFightWorld();
        DragonBattle battle = fightWorld.getEnderDragonBattle();
        List<Entity> dragonSpawnCrystals = getDragonSpawnCrystals();
        if (battle.getEnderDragon() == null &&
            isDragonSpawnCrystalLocation(blockLoc) &&
            dragonSpawnCrystals.size() == 3) {
            log("The dragon was spawned by: " + event.getPlayer().getName());
            DragonFight.CONFIG.FIGHT_OWNER = event.getPlayer().getUniqueId();
            DragonFight.CONFIG.save();
        }

        // Restrict placement of crystals on the end portal frame.
        Player player = event.getPlayer();
        if (isDragonSpawnCrystalLocation(blockLoc)) {
            if (battle != null && (battle.getEnderDragon() != null ||
                                   battle.getRespawnPhase() == RespawnPhase.SUMMONING_PILLARS)) {
                player.sendMessage(ChatColor.DARK_PURPLE + "You can't place more end crystals until the dragon is dead!");
                event.setCancelled(true);
            }
        } else {
            player.sendMessage(ChatColor.DARK_PURPLE + "You can't place crystals there!");
            event.setCancelled(true);
        }

        // Prevent crystals being placed on pillars during the summoning
        // phase (when we tag them as relevant).
        if (battle != null && battle.getRespawnPhase() == RespawnPhase.SUMMONING_PILLARS &&
            DragonUtil.isOnPillarCircle(blockLoc)) {
            event.setCancelled(true);
            log("Cancelled " + event.getPlayer().getName() + " placing END_CRYSTAL at " + Util.formatLocation(blockLoc));
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Monitor the vanilla dragon fight stages.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    protected void onEnderDragonChangePhase(EnderDragonChangePhaseEvent event) {
        // debug("Changing to dragon phase: " + event.getNewPhase());
    }

    // ------------------------------------------------------------------------
    /**
     * When an end crystal spawns at the start of the fight, play accompanying
     * effects and register the entity.
     *
     * Tag crystals that were spawned by the dragon fight to prevent us from
     * mis-identifying player-placed crystals as relevant.
     */
    protected void onPillarCrystalSpawn(EnderCrystal crystal) {
        _crystals.add(crystal);

        Location loc = crystal.getLocation();
        log(crystal.getType() + " " + crystal.getUniqueId() +
            " spawned at " + Util.formatLocation(loc));

        // Cannot set crystals invulnerable immediately.
        crystal.setGlowing(true);
        crystal.getScoreboardTags().add(PILLAR_CRYSTAL_TAG);

        // Doesn't do anything. See the doc comment for onEntityDamageEarly().
        crystal.setInvulnerable(true);

        playSound(loc, Sound.BLOCK_BEACON_POWER_SELECT);
        loc.getWorld().strikeLightningEffect(loc);
    }

    // ------------------------------------------------------------------------
    /**
     * This method responds to an ender dragon spawning.
     *
     * At the time this method is called, the dragon entity is not yet
     * associated with the Bukkit DragonBattle instance, because we're executing
     * in the context of a CreatureSpawnEvent, which is effectively a
     * synchronous function call from World.spawn(). We would need to wait for 1
     * tick if we needed to access the dragon through the DragonBattle's methods
     * (note the called methods too).
     *
     * @param dragon the dragon.
     * @param reason the reason for spawning.
     */
    protected void onDragonSpawn(EnderDragon dragon, SpawnReason reason) {
        log("Dragon spawned (" + reason + "): " + dragon.getUniqueId());

        // Tag dragon as fight entity so its projectiles inherit that tag.
        dragon.getScoreboardTags().add(ENTITY_TAG);

        // Doesn't do anything. See onEntityDamageEarly().
        dragon.setInvulnerable(true);

        // Remove surplus dragons after this one is added to the world.
        Bukkit.getScheduler().runTaskLater(DragonFight.PLUGIN, () -> removeSurplusDragons(), 2);

        // Setting the crystals invulnerable before the dragon spawns does not
        // work. But Minecraft prevents them from being damaged.
        // Cannot set them invulnerable this tick either.
        for (EnderCrystal crystal : _crystals) {
            Bukkit.getScheduler().runTaskLater(DragonFight.PLUGIN,
                                               () -> crystal.setInvulnerable(true), 1);
        }
        reconfigureDragonBossBar();

        if (getStageNumber() == 0) {
            animateNextStage();
        }
    }

    // ------------------------------------------------------------------------
    /**
     * When the dragon dies:
     *
     * <ul>
     * <li>If the fight owner is online, put the dragon drops in that player's
     * inventory. If they are not online,
     * </ul>
     *
     * @param dragon the dragon that died.
     */
    protected void onDragonDeath(EnderDragon dragon) {
        log("The dragon died.");

        if (getStageNumber() != 11) {
            log("But we're not in stage 11, so it doesn't count.");
            return;
        }

        // In stage 11, the dragon died. Signify that the fight is over before
        // saving the config.
        immediatelyChangeStageNumber(0);
        // TODO: clean up associated entities?

        if (DragonFight.CONFIG.FIGHT_OWNER == null) {
            log("But nobody owns the fight, so it doesn't count.");
            return;
        }

        // Bukkit.getOfflinePlayer() NEVER returns null, even for non-existent.
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(DragonFight.CONFIG.FIGHT_OWNER);
        getNearbyPlayers().forEach(p -> p.sendMessage(ChatColor.LIGHT_PURPLE + offlinePlayer.getName() +
                                                      ChatColor.DARK_PURPLE + " was awarded the prize for defeating the dragon."));

        Player player = offlinePlayer.getPlayer();
        if (player == null) {
            // Fight owner is offline. Record an unclaimed prize.
            log("An unclaimed prize was added to offline fight owner " + offlinePlayer.getName() + ".");
            getNearbyPlayers().forEach(p -> p.sendMessage(ChatColor.DARK_PURPLE + "They can claim it when they log in."));
            DragonFight.CONFIG.incUnclaimedPrizes(offlinePlayer.getUniqueId(), 1);
        } else {
            // Select a single drop from the `df-dragon-drops` loot set.
            // TODO: actually, multiple drops should be supported, once
            // BeastMaster supports deferred item drops.
            log("Generating dragon prizes for " + player.getName());
            List<ItemStack> prizes = generatePrizes();
            if (!givePrizes(player, prizes)) {
                // The item(s) did not fit, so player must claim with command.
                DragonFight.CONFIG.incUnclaimedPrizes(offlinePlayer.getUniqueId(), 1);

                String slots = ChatColor.LIGHT_PURPLE + Integer.toString(prizes.size()) +
                               ChatColor.DARK_PURPLE + " inventory slot" + (prizes.size() > 1 ? "s" : "");
                player.sendMessage(ChatColor.DARK_PURPLE + "You need at least " + slots + " empty.");
                player.sendMessage(ChatColor.DARK_PURPLE + "Run " +
                                   ChatColor.LIGHT_PURPLE + "/dragon prize" +
                                   ChatColor.DARK_PURPLE + " to claim your prize.");
            }
        }

        // Clear the fight owner once prizes have been given or unclaimed prizes
        // recorded, to prevent extra prizes if spurious dragons spawn.
        DragonFight.CONFIG.FIGHT_OWNER = null;
        DragonFight.CONFIG.save();
    }

    // ------------------------------------------------------------------------
    /**
     * Give all of the specified dragon death prizes to the player, or none of
     * them if not all can fit in the player's inventory.
     *
     * @param player the player.
     * @param prizes the list of items to give.
     * @return true if all prizes could fit; false if they were deferred.
     */
    public static boolean givePrizes(Player player, List<ItemStack> prizes) {
        if (prizes.size() == 0) {
            log("No dragon drops have been configured!");
            return true;
        }

        // Check that the player has enough open inventory slots.
        int openSlots = (int) Stream.of(player.getInventory().getStorageContents())
            .filter(i -> (i == null || i.getType() == Material.AIR)).count();
        log(player.getName() + " has " + openSlots + " empty slots for " + prizes.size() + " items.");

        if (openSlots < prizes.size()) {
            return false;
        } else {
            ItemStack[] prizesArray = prizes.toArray(new ItemStack[prizes.size()]);
            HashMap<Integer, ItemStack> skippedItems = player.getInventory().addItem(prizesArray);
            player.sendMessage(ChatColor.DARK_PURPLE + "A prize for defeating the dragon has been placed in your inventory!");
            log("The dragon drops were put in " + player.getName() + "'s inventory.");

            // Check for failure. This should never happen. But the Bukkit
            // API has surprised me before.
            if (!skippedItems.isEmpty()) {
                log("Some items didn't fit in " + player.getName() + "'s inventory (and that should be impossible). They were:");
                for (ItemStack itemStack : skippedItems.values()) {
                    log("Skipped item: " + Util.getItemDescription(itemStack));
                }
                player.sendMessage(ChatColor.DARK_PURPLE + "Some prizes didn't fit in your inventory. That's a bug!");
                player.sendMessage(ChatColor.DARK_PURPLE + "Do a /modreq and we'll fix that for you.");
            }
            return true;
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Generate all ItemStack for a single dragon prize.
     *
     * @return a list of ItemStacks.
     */
    public static List<ItemStack> generatePrizes() {
        ArrayList<ItemStack> prizes = new ArrayList<>();
        DropSet dropSet = BeastMaster.LOOTS.getDropSet("df-dragon-drops");
        Drop drop = dropSet.chooseOneDrop(true);
        log("The dragon prize with ID " + drop.getId() + " was selected.");
        if (drop.getDropType() == DropType.ITEM) {
            Item item = BeastMaster.ITEMS.getItem(drop.getId());
            ItemStack itemStack = item.getItemStack();
            if (itemStack == null) {
                DragonFight.PLUGIN.getLogger().severe("Dragon drop item " + item.getId() + " is undefined!");
            } else {
                itemStack = itemStack.clone();
                itemStack.setAmount(Util.random(drop.getMinAmount(), drop.getMaxAmount()));
                prizes.add(itemStack);
            }
        } else {
            // TODO: in the final version this would be ok?
            DragonFight.PLUGIN.getLogger().severe("Invalid drop type selected from df-dragon-drops.");
        }
        return prizes;
    }

    // ------------------------------------------------------------------------
    /**
     * Spawn the bosses for the specified stage and show titles to nearby
     * players.
     *
     * This method holds code common to the "/df spawn <number>" command and
     * {@link #startStage(CommandSender, int, Location)}.
     *
     * @param stage             the stage (from 1 to 11), but note that the
     *                          dragon is already assumed to be spawned in the
     *                          case of stage 11 and is not spawned in.
     * @param bossSpawnLocation the location where the bosses are spawned.
     */
    public static void spawnStage(Stage stage, Location bossSpawnLocation) {
        DropResults results = new DropResults();
        if (stage.getStageNumber() >= 1 && stage.getStageNumber() <= 10) {
            DropSet dropSet = BeastMaster.LOOTS.getDropSet(stage.getDropSetId());
            if (dropSet != null) {
                dropSet.generateRandomDrops(results, "DragonFight stage " + stage, null, bossSpawnLocation, true);
            }
            log("Bosses are spawned.");
        }

        // Show the titles, messages and commands.
        ArrayList<Player> nearby = getNearbyPlayers();
        log(nearby.size() + " players nearby.");
        OfflinePlayer owner = (DragonFight.CONFIG.FIGHT_OWNER != null) ? Bukkit.getOfflinePlayer(DragonFight.CONFIG.FIGHT_OWNER) : null;
        stage.announce(nearby, owner, bossSpawnLocation);

        // Bosses target random players.
        for (LivingEntity mob : results.getMobs()) {
            if (mob instanceof Creature) {
                ((Creature) mob).setTarget(nearby.get(Util.randomInt(nearby.size())));
            }
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Return true if the specified location is that of a bedrock block where
     * you would place an end crystal to spawn the dragon.
     *
     * I'm not really sure how low the end portal frame can occur. In the
     * current world, the frame is at Y56. This method doesn't care about the Y
     * coordinate, as long as its clearly not bedrock down below Y6 (which only
     * occurs in custom generated terrain).
     *
     * The method checks for bedrock (which cannot be placed by an ordinary
     * player) under the crystal and checks for the right X and Z coordinates
     *
     * @param loc the location of the block under an end crystal.
     * @return true if the specified location is somewhere you would place an
     *         end crystal to spawn the dragon.
     */
    protected static boolean isDragonSpawnCrystalLocation(Location loc) {
        if (loc.getY() < 6 || loc.getBlock().getType() != Material.BEDROCK) {
            return false;
        }

        int x = Math.abs(loc.getBlockX());
        int z = Math.abs(loc.getBlockZ());
        return ((x == 3 && z == 0) || (x == 0 && z == 3));
    }

    // ------------------------------------------------------------------------
    /**
     * Return all (0-4) dragon-spawning crystals currently in existence.
     *
     * The range on the Y coordinate is largeish because I'm not sure of the
     * limits on the end portal spawn location.
     *
     * @return all dragon-spawning crystals currently in existence.
     */
    protected static List<Entity> getDragonSpawnCrystals() {
        World fightWorld = DragonUtil.getFightWorld();
        return fightWorld.getNearbyEntities(new Location(fightWorld, 0, 45, 0), 3, 40, 3)
            .stream().filter(e -> e.getType() == EntityType.ENDER_CRYSTAL &&
                                  isDragonSpawnCrystalLocation(e.getLocation().getBlock().getRelative(0, -1, 0).getLocation()))
            .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------------
    /**
     * Reconfigure the dragon's boss bar to not darken the sky or create fog.
     */
    protected void reconfigureDragonBossBar() {
        World fightWorld = DragonUtil.getFightWorld();
        DragonBattle battle = fightWorld.getEnderDragonBattle();
        if (battle.getBossBar() != null) {
            battle.getBossBar().removeFlag(BarFlag.DARKEN_SKY);
            battle.getBossBar().removeFlag(BarFlag.CREATE_FOG);
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Start the next boss stage by showing the boss spawn animations.
     *
     * This method is called to do the boss spawning sequence for stages 1 to
     * 10.
     */
    protected void animateNextStage() {
        setNewStageNumber(getStageNumber() + 1);

        // When going to stage 11 (dragon only) there are no bosses to spawn.
        // Just call startStage() and return.
        if (getNewStageNumber() == 11) {
            startStage(null, getNewStageNumber(), null);
            return;
        }

        // I have observed that sometimes the config has stage-number: 0 and
        // new-stage-number: 1, and there are no crystals. In that case, getting
        // the next crystal to remove by _crystals.iterator() fails and there
        // is an exception. Avoid that.
        // TODO: work out why we get into this inconsistent state and fix it.
        if (_crystals.isEmpty()) {
            return;
        }

        // From here forward, getNewStageNumber() is between 1 and 10.
        // Remove a random crystal. Random order due to hashing UUID.
        EnderCrystal replacedCrystal = _crystals.iterator().next();

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
        log("Boss spawn location: " + Util.formatLocation(bossSpawnLocation));

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
            _crystals.remove(replacedCrystal);
            replacedCrystal.remove();
            startStage(null, getNewStageNumber(), bossSpawnLocation);
        }, STAGE_START_DELAY);
    }

    // ------------------------------------------------------------------------
    /**
     * Despawn the specified number of pillar crystals.
     *
     * This method is used by the <i>/df next</i> and <i>/df stage</i> command
     * implementations.
     *
     * @param count the number of crystals to untrack and despawn.
     */
    protected void despawnPillarCrystals(int count) {
        while (!_crystals.isEmpty() && count-- > 0) {
            EnderCrystal replacedCrystal = _crystals.iterator().next();
            _crystals.remove(replacedCrystal);
            replacedCrystal.remove();
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Begin the specified boss stage (1 to 11 only).
     *
     * In the case of stages 1 through 10, the boss mob is spawned.
     *
     * @param sender            the command sender for messages, or null if
     *                          unused.
     * @param stageNumber       the stage number from 1 to 11.
     * @param bossSpawnLocation the location where the bosses are spawned.
     */
    protected void startStage(CommandSender sender, int stageNumber, Location bossSpawnLocation) {
        // The new stage number will (in many but not all cases) have already
        // been set to stageNumber arg. Ensure that all stage numbers are in
        // sync.
        immediatelyChangeStageNumber(stageNumber);

        if (sender != null) {
            sender.sendMessage(ChatColor.DARK_PURPLE + "Starting stage: " + ChatColor.LIGHT_PURPLE + stageNumber);
        }

        Stage stage = DragonFight.CONFIG.getStage(getStageNumber());
        log("Beginning stage: " + getStageNumber());

        DragonFight.CONFIG.TOTAL_BOSS_MAX_HEALTH = 0;

        // Spawn bosses in case of stage 1 through 10. And show titles.
        spawnStage(stage, bossSpawnLocation);

        if (stageNumber == 11) {
            // Stage 11. The dragon was set invulnerable in stage 1.
            DragonBattle battle = DragonUtil.getFightWorld().getEnderDragonBattle();
            EnderDragon dragon = battle.getEnderDragon();
            if (dragon != null) {
                dragon.setInvulnerable(false);
            }
            log("Dragon is now vulnerable.");
        }
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
        World fightWorld = DragonUtil.getFightWorld();
        float yaw = 360 * (float) Math.random();

        // Try to find a valid spawn location.
        // Retry up to a few times on failures.
        for (int attempt = 1; attempt <= BOSS_SPAWN_MAX_RETRIES; attempt++) {
            double range = Util.random(BOSS_SPAWN_RADIUS_MIN, BOSS_SPAWN_RADIUS_MAX);
            double angle = Util.random() * 2.0 * Math.PI;
            double x = range * Math.cos(angle);
            double z = range * Math.sin(angle);
            Location startLoc = new Location(fightWorld, x, ORIGIN_Y, z, yaw, 0f);

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

            // No valid space found at these coordinates; Log and retry.
            log(String.format("Failed to find space to spawn bosses at x=%.2f, z=%.2f", x, z));
        }

        // If all else fails. Plonk it on the portal pillar.
        // TODO Make the fallback spawn location configurable
        log("No valid locations found. Defaulting to the fallback spawn location.");
        return new Location(fightWorld, 0.5, ORIGIN_Y + 1, 0.5, yaw, 0f);
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
     * @param loc   the location.
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
    protected static ArrayList<Player> getNearbyPlayers() {
        World world = DragonUtil.getFightWorld();
        return world.getPlayers().stream()
            .filter(p -> DragonUtil.getMagnitude2D(p.getLocation()) < NEARBY_RADIUS)
            .collect(Collectors.toCollection(ArrayList::new));
    }

    // ------------------------------------------------------------------------
    /**
     * Update the BossBar for the current stage.
     *
     * If we're not in stage 1-10, don't show a custom boss bar, even if we have
     * some test bosses.
     */
    protected void updateBossBar() {
        if (getStageNumber() >= 1 && getStageNumber() <= 11) {
            // Vanilla/Bukkit code reuses the dragon boss bar across fights, so
            // set its colour across all stages.
            Stage stage = DragonFight.CONFIG.getStage(11);
            DragonBattle battle = DragonUtil.getFightWorld().getEnderDragonBattle();
            battle.getBossBar().setColor(stage.getBarColor());
        }

        if (_bosses.size() == 0 || getStageNumber() < 1 || getStageNumber() > 10) {
            if (_bossBar != null) {
                _bossBar.setVisible(false);
            }
            return;
        }

        // We have a non-zero number of bosses. Stage 1 to 10. Show the bar.
        Stage stage = DragonFight.CONFIG.getStage(getStageNumber());
        if (_bossBar == null) {
            _bossBar = Bukkit.createBossBar("", BarColor.WHITE, BarStyle.SOLID, new BarFlag[0]);
        }
        _bossBar.setColor(stage.getBarColor());
        _bossBar.setTitle(stage.format(stage.getTitle()));
        _bossBar.setVisible(true);

        // Update the players who see the bar.
        _bossBar.removeAll();
        getNearbyPlayers().forEach(p -> _bossBar.addPlayer(p));

        // Update the bar progress according to total remaining boss health.
        // The total health should never be 0 by the time it gets in here
        // but just in case I'm wrong, guard against division by 0.
        if (DragonFight.CONFIG.TOTAL_BOSS_MAX_HEALTH > 0.001) {
            double newProgress = getTotalBossHealth() / DragonFight.CONFIG.TOTAL_BOSS_MAX_HEALTH;
            _bossBar.setProgress(Util.clamp(newProgress, 0.0, 1.0));
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Return the total health of all extant bosses.
     *
     * @return the total health of all extant bosses.
     */
    public double getTotalBossHealth() {
        return _bosses.stream().reduce(0.0, (sum, b) -> sum + b.getHealth(), (h1, h2) -> h1 + h2);
    }

    // ------------------------------------------------------------------------
    /**
     * Vanilla code can randomly spawn extra dragons.
     *
     * Try to adapt. Not clear on when the DragonBattle has its EnderDragon
     * reference set, so let's keep at least one, even if not referenced by the
     * battle.
     */
    protected void removeSurplusDragons() {
        World fightWorld = DragonUtil.getFightWorld();
        Collection<EnderDragon> dragons = fightWorld.getEntitiesByClass(EnderDragon.class);
        if (dragons.size() > 1) {
            log("Removing surplus dragons.");
            log("There are currently: " + dragons.size() + " dragons.");
        }

        // So we can enforce invulnerability invariant on the dragon we keep.
        EnderDragon retainedDragon = null;
        DragonBattle battle = fightWorld.getEnderDragonBattle();
        if (battle.getEnderDragon() == null) {
            // Assume vanilla intends the newest dragon instance to replace
            // whatever others exist. Sort into ascending order by time existed.
            List<EnderDragon> dragonsByAge = dragons.stream()
                .sorted((d1, d2) -> d1.getTicksLived() - d2.getTicksLived())
                .collect(Collectors.toCollection(ArrayList::new));

            // Remove every dragon after the youngest.
            if (dragonsByAge.size() > 0) {
                retainedDragon = dragonsByAge.get(0);
            }
            for (int i = 1; i < dragonsByAge.size(); ++i) {
                EnderDragon dragon = dragonsByAge.get(i);
                DragonUtil.removeDragon(dragon);
            }
        } else {
            // Let the dragon battle keep the ender dragon it manages, remove
            // the others.
            retainedDragon = battle.getEnderDragon();
            for (EnderDragon dragon : dragons) {
                if (dragon != battle.getEnderDragon()) {
                    DragonUtil.removeDragon(dragon);
                }
            }
        }

        if (retainedDragon != null) {
            // Doesn't do anything. See onEntityDamageEarly().
            retainedDragon.setInvulnerable(getStageNumber() != 11);
        }
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
                        log("Returning " + mobType.getId() + " " +
                            boss.getUniqueId().toString().substring(0, 8) + " to the arena due to timeout.");
                        returnMobToBossSpawn(mobType, boss);
                        boss.setMetadata(BOSS_SEEN_TIME_KEY, new FixedMetadataValue(DragonFight.PLUGIN, now));
                        continue;
                    }
                }

                // Enforce "last seen" and position limits on bosses.
                Location loc = boss.getLocation();
                if (loc.getY() < MIN_BOSS_Y || DragonUtil.getMagnitude2D(loc) > BOSS_RADIUS) {
                    MobType mobType = BeastMaster.getMobType(boss);
                    log("Returning " + mobType.getId() + " " +
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
     * Tag applied to pillar end crystals on spawn so we know which ones are in
     * the fight on restart.
     */
    private static final String PILLAR_CRYSTAL_TAG = "df-crystal";

    /**
     * Tag applied to dragon-spawning end crystals on spawn so we know which
     * ones are in the fight on restart.
     */
    private static final String SPAWNING_CRYSTAL_TAG = "df-spawning";

    /**
     * Tag applied to any entity spawned by the dragon fight: bosses, their
     * support mobs and launched projectiles, with the exception of the dragon
     * and crystals.
     */
    private static final String ENTITY_TAG = "df-entity";

    /**
     * Group tag of summoned boss mobs on spawn.
     *
     * Note that the final boss enderdragon is not allowed to have this tag.
     * Note also that per-stage boss mobs can be enderdragons, but they are not
     * the final boss.
     */
    private static final String BOSS_TAG = "df-boss";

    /**
     * Group tag of support mobs summoned by bosses.
     */
    private static final String SUPPORT_TAG = "df-support";

    /**
     * Number of ticks to wait before starting the next stage.
     */
    private static int STAGE_START_DELAY = 150;

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
     * Maximum number of times to try to find a valid boss spawn location.
     */
    private static final int BOSS_SPAWN_MAX_RETRIES = 5;

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
    private static final double TRACKED_RADIUS = BOSS_RADIUS + 80.0;

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
     * Tracks mobs to enforce boundaries and update boss bars.
     */
    protected TrackerTask _tracker = new TrackerTask();

    /**
     * Current stage boss bar, which tracks all currently active bosses.
     */
    protected BossBar _bossBar;
} // class FightState