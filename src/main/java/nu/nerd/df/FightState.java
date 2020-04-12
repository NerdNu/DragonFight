package nu.nerd.df;

import java.util.HashSet;
import java.util.List;
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
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EnderDragonChangePhaseEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import nu.nerd.beastmaster.Util;

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
     * Log debug message.
     * 
     * @param message the message.
     */
    public void debug(String message) {
        DragonFight.PLUGIN.getLogger().info(message);
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
     * Finding these in ChunkLoadEvent because there is no event for spawn
     * chunks.
     */
    protected void findEndCrystals() {
        World fightWorld = Bukkit.getWorld(FIGHT_WORLD);
        double radius = PILLAR_CIRCLE_RADIUS * 1.1;
        Location origin = new Location(fightWorld, 0, 64, 0);
        for (Entity entity : fightWorld.getNearbyEntities(origin, radius, 64, radius,
                                                          e -> e.getType() == EntityType.ENDER_CRYSTAL)) {
            debug("Loaded crystal: "
                  + entity.getUniqueId()
                  + (entity.getScoreboardTags().contains(CRYSTAL_TAG) ? " (in the fight)" : ""));
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
        debug(crystal.getType()
              + " " + crystal.getUniqueId()
              + " spawned at " + Util.formatLocation(loc));

        crystal.getScoreboardTags().add(CRYSTAL_TAG);
        crystal.setGlowing(true);
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
     */
    protected void nextStage() {
        // Remove a random crystal. Random order due to hashing UUID.
        EnderCrystal replacedCrystal = _crystals.iterator().next();
        Location bossSpawnLocation = getBossSpawnLocation();
        debug("Boss spawn location: " + Util.formatLocation(bossSpawnLocation));

        // Point the end crystal beam at the spawn location.
        // Needs to be delayed slightly after the dragon spawn.
        Bukkit.getScheduler().scheduleSyncDelayedTask(DragonFight.PLUGIN, () -> {
            replacedCrystal.setBeamTarget(bossSpawnLocation);
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
            ++_stageNumber;
            _crystals.remove(replacedCrystal);

            // Remove entity, spawn boss and play effects.
            replacedCrystal.remove();

            // TODO: spawn mob per df-stage1-boss.
            playSound(bossSpawnLocation, Sound.BLOCK_END_PORTAL_SPAWN);

            // TODO: use Stage class.
            List<Player> nearby = getNearbyPlayers();
            debug(nearby.size() + " players nearby.");
            nearby.stream().forEach(p -> p.sendTitle("Stage 1", "It begins...", 10, 70, 20));
        }, STAGE_START_DELAY);
    }

    // ------------------------------------------------------------------------
    /**
     * Return true if the 3x3x3 blocks centred horizontally on the specified
     * location, with the location in the bottom row of the three, are air.
     * 
     * @param loc the location of the middle, bottom row of the 3x3x3 box to
     *        check.
     * @return true if it's air.
     */
    protected boolean isAir3x3x3(Location loc) {
        // Check for 3x3x3 air.
        for (int dx = -1; dx <= 1; ++dx) {
            for (int dz = -1; dz <= 1; ++dz) {
                // Offset up for undulating terrain.
                for (int dy = 0; dy <= 2; ++dy) {
                    Location check = loc.clone().add(dx, dy, dz);
                    if (check.getBlock().getType() != Material.AIR) {
                        return false;
                    }
                }
            }
        }
        return true;
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
            if (loc.getBlock().getType() != Material.AIR) {
                break;
            }
        }

        // Now go up to find air.
        for (int i = 1; i < 10; ++i) {
            loc.add(0, 1, 0);
            if (isAir3x3x3(loc)) {
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
     * spawned. Stage 10 => final boss spawned.
     */
    protected int _stageNumber;
} // class FightState