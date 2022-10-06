package nu.nerd.df;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import nu.nerd.beastmaster.commands.ExecutorBase;
import nu.nerd.df.commands.DFExecutor;
import nu.nerd.df.commands.DragonExecutor;
import nu.nerd.df.commands.DragonFightExecutor;

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
     * Configuration as singleton.
     */
    public static Configuration CONFIG = new Configuration();

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
        CONFIG.reloadConfiguration();
        CONFIG.reloadFightState();

        addCommandExecutor(new DFExecutor());
        addCommandExecutor(new DragonExecutor());
        addCommandExecutor(new DragonFightExecutor());

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
        CONFIG.save();
    }

    // ------------------------------------------------------------------------
    /**
     * Add the specified CommandExecutor and set it as its own TabCompleter.
     *
     * @param executor the CommandExecutor.
     */
    protected void addCommandExecutor(ExecutorBase executor) {
        PluginCommand command = getCommand(executor.getName());
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }
} // class DragonFight