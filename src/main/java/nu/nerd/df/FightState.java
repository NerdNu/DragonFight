package nu.nerd.df;

import java.util.HashSet;
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
import org.bukkit.boss.DragonBattle;
import org.bukkit.boss.DragonBattle.RespawnPhase;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EnderDragonChangePhaseEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.projectiles.ProjectileSource;

import net.md_5.bungee.api.ChatColor;
import nu.nerd.beastmaster.BeastMaster;
import nu.nerd.beastmaster.DropResults;
import nu.nerd.beastmaster.DropSet;
import nu.nerd.beastmaster.Util;
import nu.nerd.beastmaster.mobs.MobType;

// ----------------------------------------------------------------------------
/**
 * Records state information about the current dragon fight.
 */
public class FightState implements Listener {
    // ------------------------------------------------------------------------
    /**
     * Actions performed on plugin enable.
     */
    public void onEnable() {
        findEndCrystals();

        boolean addedDropSet = false;
        for (int stageNumber = 1; stageNumber <= 10; ++stageNumber) {
            _stages[stageNumber - 1] = new Stage(stageNumber);

            // Add the stage N boss mob loot tables, if not defined.
            String dropSetId = Stage.getDropSetId(stageNumber);
            if (BeastMaster.LOOTS.getDropSet(dropSetId) == null) {
                BeastMaster.LOOTS.addDropSet(new DropSet(dropSetId));
                addedDropSet = true;
            }
        }
        if (addedDropSet) {
            BeastMaster.CONFIG.save();
        }
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
        sender.sendMessage(ChatColor.DARK_PURPLE + "Removed crystals: " + _crystals.size());
        _crystals.clear();

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
        sender.sendMessage(ChatColor.DARK_PURPLE + "Removed projectiles: " + projectileCount + ", mobs: " + mobCount);
        _stageNumber = 0;
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
     * Find the ender crystals that are part of the current dragon fight.
     * 
     * There is no ChunkLoadEvent for spawn chunks, so we need to scan loaded
     * chunks on startup.
     */
    protected void findEndCrystals() {
        World fightWorld = Bukkit.getWorld(FIGHT_WORLD);
        double radius = PILLAR_CIRCLE_RADIUS * 1.1;
        Location origin = new Location(fightWorld, 0, 64, 0);
        for (Entity entity : fightWorld.getNearbyEntities(origin, radius, 64, radius,
                                                          e -> e.getType() == EntityType.ENDER_CRYSTAL)) {
            debug("Loaded crystal: " + entity.getUniqueId() +
                  (entity.getScoreboardTags().contains(CRYSTAL_TAG) ? " (in the fight)" : ""));
            _crystals.add((EnderCrystal) entity);
        }
    }

    // ------------------------------------------------------------------------
    /**
     * When the dragon spawns, handle it with
     * {@link #onDragonSpawn(EnderDragon)}.
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

        DragonBattle battle = world.getEnderDragonBattle();
        if (battle == null || battle.getRespawnPhase() != RespawnPhase.SUMMONING_PILLARS) {
            return;
        }

        if (entity instanceof EnderCrystal) {
            onCrystalSpawn((EnderCrystal) entity);
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
     * When a stage is cleared of all its boss mobs, spawn the next stage.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    protected void onEntityDeath(EntityDeathEvent event) {
        if (!isFightWorld(event.getEntity().getWorld())) {
            return;
        }

        if (event.getEntityType() == EntityType.ENDER_DRAGON) {
            // TODO: df-dragon-drops custom handling.
            // TODO: clean up associated entities?
            _stageNumber = 0;
            return;
        }

        // TODO: actually needs to wait until all stage bosses are dead.
        if (hasTagOrGroup(event.getEntity(), BOSS_TAG)) {
            // TODO: update the stage boss bar.
            // TODO: consult the tracker rather than assuming there is only a
            // single boss.
            if (_stageNumber == 10) {
                // Just the dragon in stage 11.
                _stageNumber = 11;
            } else {
                nextStage();
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
        nextStage();
    }

    // ------------------------------------------------------------------------
    /**
     * Start the next stage.
     * 
     * This method is called to do the boss spawning sequence for stages 1 to
     * 10.
     */
    protected void nextStage() {
        // Remove a random crystal. Random order due to hashing UUID.
        EnderCrystal replacedCrystal = _crystals.iterator().next();
        Location bossSpawnLocation = getBossSpawnLocation();
        debug("Boss spawn location: " + Util.formatLocation(bossSpawnLocation));

        // Point the end crystal beam at the spawn location.
        // Needs to be delayed slightly after the dragon spawn.
        Bukkit.getScheduler().scheduleSyncDelayedTask(DragonFight.PLUGIN, () -> {
            Location beamTarget = bossSpawnLocation.clone().add(0, -2.5, 0);
            replacedCrystal.setBeamTarget(beamTarget);
        }, 5);

        // Schedule random flickering of the crystal.
        int totalFlickerTicks = 0;
        while (totalFlickerTicks < STAGE_START_DELAY * 60 / 100) {
            int flickerTicks = Util.random(1, 5);
            totalFlickerTicks += flickerTicks;
            Bukkit.getScheduler().scheduleSyncDelayedTask(DragonFight.PLUGIN, () -> {
                playSound(bossSpawnLocation, Sound.BLOCK_BELL_RESONATE);
                replacedCrystal.setGlowing(!replacedCrystal.isGlowing());
            }, totalFlickerTicks);
        }

        // End with the replaced crystal not glowing.
        Bukkit.getScheduler().scheduleSyncDelayedTask(DragonFight.PLUGIN, () -> {
            replacedCrystal.setGlowing(false);
        }, totalFlickerTicks + 5);

        // Give the boss a spawn sound.
        Bukkit.getScheduler().scheduleSyncDelayedTask(DragonFight.PLUGIN, () -> {
            playSound(bossSpawnLocation, Sound.BLOCK_BEACON_ACTIVATE);
        }, STAGE_START_DELAY * 80 / 100);

        // Remove the crystal and spawn the boss.
        Bukkit.getScheduler().scheduleSyncDelayedTask(DragonFight.PLUGIN, () -> {
            Stage stage = _stages[_stageNumber];
            ++_stageNumber;

            // Despawn the crystal that becomes the boss.
            _crystals.remove(replacedCrystal);

            // Remove entity, spawn boss and play effects.
            replacedCrystal.remove();

            // Spawn boss or bosses and set up for tracking.
            DropResults bosses = new DropResults();
            DropSet dropSet = stage.getDropSet();
            dropSet.generateRandomDrops(bosses, "DragonFight stage " + stage, null, bossSpawnLocation);

            // TODO: move tagging into the tracker.
            // TODO: Let the mob type define the group tags.

            // Show the title.
            List<Player> nearby = getNearbyPlayers();
            debug(nearby.size() + " players nearby.");
            stage.announce(nearby);
        }, STAGE_START_DELAY);
    }

    // ------------------------------------------------------------------------
    /**
     * Choose a random location to spawn the boss with a 3x3x3 volume of air.
     * 
     * If a suitable location cannot be found, put it on the portal pillar.
     * 
     * If players decide to arrange the arena to frustrate efforts to find a
     * location to spawn the boss, moderate them hard.
     */
    protected Location getBossSpawnLocation() {
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
    protected List<Player> getNearbyPlayers() {
        World world = Bukkit.getWorld(FIGHT_WORLD);
        return world.getPlayers().stream()
        .filter(p -> getMagnitude2D(p.getLocation()) < NEARBY_RADIUS)
        .collect(Collectors.toList());
    }

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
    private static final String CRYSTAL_TAG = "DF-crystal";

    /**
     * Tag applied to any entity spawned by the dragon fight: bosses, their
     * support mobs and launched projectiles, with the exception of the dragon
     * and crystals.
     */
    private static final String ENTITY_TAG = "DF-entity";

    /**
     * Tag applied only to summoned boss mobs on spawn.
     */
    private static final String BOSS_TAG = "DF-boss";

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

    // ------------------------------------------------------------------------
    /**
     * The remaining end crystals.
     */
    protected HashSet<EnderCrystal> _crystals = new HashSet<>();

    /**
     * Current stage number.
     * 
     * Stage 0 is before the fight, Stage 1 => first crystal removed and boss
     * spawned. Stage 10 => final boss spawned. Stage 11: dragon.
     */
    protected int _stageNumber;

    /**
     * Array of 10 {@link Stage}s.
     * 
     * Stage N is at index N-1.
     */
    protected Stage[] _stages = new Stage[10];

} // class FightState