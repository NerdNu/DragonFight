package nu.nerd.df;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

// ----------------------------------------------------------------------------
/**
 * Main plugin class.
 */
public class DragonFight extends JavaPlugin {
    // ------------------------------------------------------------------------
    /**
     * The plugin as a singleton.
     */
    public static DragonFight PLUGIN;

    /**
     * Current fight as singleton.
     */
    public static FightState FIGHT = new FightState();

    // ------------------------------------------------------------------------
    /**
     * @see org.bukkit.plugin.java.JavaPlugin#onEnable()
     */
    @Override
    public void onEnable() {
        PLUGIN = this;

        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(FIGHT, this);
        FIGHT.onEnable();
    }

    // ------------------------------------------------------------------------
    /**
     * @see org.bukkit.plugin.java.JavaPlugin#onDisable()
     */
    @Override
    public void onDisable() {
        FIGHT.onDisable();
    }

} // class DragonFight