package nu.nerd.df;

import java.util.UUID;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

// ----------------------------------------------------------------------------
/**
 * Configuration wrapper.
 */
public class Configuration {
    // ------------------------------------------------------------------------
    /**
     * Total boss maximum health.
     * 
     * This needs to be preserved across restarts, since a stage fight may have
     * a random number of bosses, some of which may have died. THere is no easy
     * way to work infer the total maximum boss health points in the stage after
     * a restart.
     */
    public double TOTAL_BOSS_MAX_HEALTH;

    /**
     * The UUID of the Player who placed the last end crystal to initiate the
     * current fight.
     * 
     * That player receives the drops.
     */
    public UUID FIGHT_OWNER;

    // ------------------------------------------------------------------------
    /**
     * Default constructor.
     */
    public Configuration() {
        for (int stageNumber = 1; stageNumber <= 10; ++stageNumber) {
            _stages[stageNumber - 1] = new Stage(stageNumber);
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Return a reference to the Stage with the specified stage number.
     * 
     * @param stageNumber the stage number, from 1 to 10.
     * @return the Stage, or null if the stage number is out of range.
     */
    public Stage getStage(int stageNumber) {
        return (stageNumber < 1 || stageNumber > 10) ? null : _stages[stageNumber - 1];
    }

    // ------------------------------------------------------------------------
    /**
     * Reload the configuration.
     */
    public void reload() {
        DragonFight.PLUGIN.reloadConfig();
        FileConfiguration config = DragonFight.PLUGIN.getConfig();
        TOTAL_BOSS_MAX_HEALTH = config.getDouble("state.total-boss-max-health");
        try {
            String text = config.getString("state.fight-owner");
            FIGHT_OWNER = (text == null || text.isEmpty()) ? null : UUID.fromString(text);
        } catch (IllegalArgumentException ex) {
            FIGHT_OWNER = null;
        }

        for (int stageNumber = 1; stageNumber <= 10; ++stageNumber) {
            getStage(stageNumber).load(getStageSection(stageNumber));
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Save the configuration.
     */
    public void save() {
        FileConfiguration config = DragonFight.PLUGIN.getConfig();
        config.set("state.total-boss-max-health", TOTAL_BOSS_MAX_HEALTH);
        config.set("state.fight-owner", (FIGHT_OWNER == null) ? null : FIGHT_OWNER.toString());

        for (int stageNumber = 1; stageNumber <= 10; ++stageNumber) {
            getStage(stageNumber).save(getStageSection(stageNumber));
        }
        DragonFight.PLUGIN.saveConfig();
    }

    // ------------------------------------------------------------------------
    /**
     * Return the ConfigurationSection that encodes the specified stage.
     * 
     * @param stageNumber the stage number in [1,10].
     * @return the section.
     */
    protected ConfigurationSection getStageSection(int stageNumber) {
        FileConfiguration config = DragonFight.PLUGIN.getConfig();
        return config.getConfigurationSection("stages." + stageNumber);
    }

    // ------------------------------------------------------------------------
    /**
     * {@link Stage}s 1 through 10, in indices 0 through 9, respectively.
     * 
     * Stage 11, the dragon on its own, does not use a Stage instance.
     */
    protected Stage[] _stages = new Stage[10];

} // class Configuration