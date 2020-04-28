package nu.nerd.df;

import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.entity.Entity;

import nu.nerd.beastmaster.BeastMaster;
import nu.nerd.beastmaster.mobs.MobType;

// --------------------------------------------------------------------------
/**
 * DragonFight utility functions.
 * 
 * Note that the DragonFight plugin also references BeastMaster's DragonUtil
 * class, so to avoid a qualified name, we name this class differently.
 */
public class DragonUtil {
    // ------------------------------------------------------------------------
    /**
     * Return the world where the dragon fight occurs.
     * 
     * @return the world where the dragon fight occurs.
     */
    public static World getFightWorld() {
        return Bukkit.getWorld(FIGHT_WORLD);
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
     * The pillars about placed in a circle about 40 blocks from (0,0). The
     * largest pillars have a radius of 5 blocks, including the centre block, so
     * ensure that we block placements up to 6 blocks away (6/40 = 0.15).
     * 
     * Note that:
     * <ul>
     * <li>This method assumes that the world has already been confirmed to be
     * the fight world.</li>
     * <li>This method doesn't care what the angle of the location is from north
     * (or whatever reference).</li>
     * <li>It's quite tricky to be more precise than this about detecting the
     * spawning of crystals on pillars, because when the crystal on the pillar
     * spawns, the block underneath it comes back as AIR, not BEDROCK.</li>
     * </ul>
     * 
     * @param loc the location.
     * @return if the location is about the right range from the origin of the
     *         world to be the centre of a pillar.
     */
    public static boolean isOnPillarCircle(Location loc) {
        return Math.abs(getMagnitude2D(loc) - DragonUtil.PILLAR_CIRCLE_RADIUS) < 0.15 * DragonUtil.PILLAR_CIRCLE_RADIUS;
    }

    // ------------------------------------------------------------------------
    /**
     * The World where the fight occurs.
     * 
     * It could be configurable, but for now is constant.
     */
    private static final String FIGHT_WORLD = "world_the_end";

    /**
     * The approximate radius of the circle of pillars.
     */
    private static final double PILLAR_CIRCLE_RADIUS = 40.0;

} // class DragonUtil