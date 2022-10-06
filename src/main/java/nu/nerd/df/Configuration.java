package nu.nerd.df;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

// ----------------------------------------------------------------------------
/**
 * Configuration wrapper.
 */
public class Configuration {
    // ------------------------------------------------------------------------
    /**
     * The log message prefix.
     */
    public String LOG_PREFIX;

    /**
     * The debug message prefix.
     */
    public String DEBUG_PREFIX;

    /**
     * Current stage number: 0 to 11.
     *
     * Stage 0 is before the fight, Stage 1 => first crystal removed and boss
     * spawned. Stage 10 => final boss spawned. Stage 11: dragon. In Stage N, N
     * crystals have been removed.
     */
    public int STAGE_NUMBER;

    /**
     * The new value of STAGE_NUMBER, when there is a transition between stages.
     *
     * Spawn animations for the dragon and for stage bosses take time. To handle
     * server restarts during these spawn sequences, we need to record the fact
     * that it is underway and at least the new stage number. But in this
     * plugin, we record both as that makes backwards stage transitions feasible
     * (although not currently implemented).
     *
     * When NEW_STAGE_NUMBER != STAGE_NUMBER, the fight is part-way through
     * transitioning from STAGE_NUMBER to NEW_STAGE_NUMBER. The STAGE_NUMBER
     * will be set to NEW_STAGE_NUMBER when the transition is complete, in a few
     * seconds.
     */
    public int NEW_STAGE_NUMBER;

    /**
     * Total boss maximum health.
     *
     * This needs to be preserved across restarts, since a stage fight may have
     * a random number of bosses, some of which may have died. There is no easy
     * way to infer the total maximum boss health points in the stage after a
     * restart.
     */
    public double TOTAL_BOSS_MAX_HEALTH;

    /**
     * The UUID of the Player who placed the last end crystal to initiate the
     * current fight.
     *
     * That player receives the drops.
     */
    public UUID FIGHT_OWNER;

    /**
     * A map from Player UUID to the number of unclaimed dragon kill prizes for
     * that player.
     *
     * The key will be absent if there are no prizes to claim.
     */
    public HashMap<UUID, Integer> UNCLAIMED_PRIZES = new HashMap<>();

    // ------------------------------------------------------------------------
    /**
     * Default constructor.
     */
    public Configuration() {
        for (int stageNumber = 1; stageNumber <= 11; ++stageNumber) {
            _stages[stageNumber - 1] = new Stage(stageNumber);
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Return the stored number of unclaimed prizes for the player with the
     * specified UUID.
     *
     * @param playerUuid the player's UUID.
     * @return the number of unclaimed prizes accrued to the player.
     */
    public int getUnclaimedPrizes(UUID playerUuid) {
        return UNCLAIMED_PRIZES.getOrDefault(playerUuid, 0);
    }

    // ------------------------------------------------------------------------
    /**
     * Increment the stored number of unclaimed prizes for the player with the
     * specified UUID.
     *
     * @param playerUuid the player's UUID.
     * @param amount     the amount to increase the count of prizes (negative to
     *                   decrement).
     * @return the number of unclaimed prizes accrued to the player.
     */
    public Integer incUnclaimedPrizes(UUID playerUuid, int amount) {
        return UNCLAIMED_PRIZES.compute(playerUuid, (k, v) -> {
            int count = (v == null) ? amount : v + amount;
            return (count > 0) ? count : null;
        });
    }

    // ------------------------------------------------------------------------
    /**
     * Return a reference to the Stage with the specified stage number.
     *
     * @param stageNumber the stage number, from 1 to 11.
     * @return the Stage, or null if the stage number is out of range.
     */
    public Stage getStage(int stageNumber) {
        return (stageNumber < 1 || stageNumber > 11) ? null : _stages[stageNumber - 1];
    }

    // ------------------------------------------------------------------------
    /**
     * Reload the fight state.
     *
     * The fight state changes as the plugin runs, so only call this method when
     * loading the plugin.
     */
    public void reloadFightState() {
        DragonFight.PLUGIN.reloadConfig();
        FileConfiguration config = DragonFight.PLUGIN.getConfig();
        Logger logger = DragonFight.PLUGIN.getLogger();

        STAGE_NUMBER = config.getInt("state.stage-number", 0);
        NEW_STAGE_NUMBER = config.getInt("state.new-stage-number", 0);

        TOTAL_BOSS_MAX_HEALTH = config.getDouble("state.total-boss-max-health");
        try {
            String text = config.getString("state.fight-owner");
            FIGHT_OWNER = (text == null || text.isEmpty()) ? null : UUID.fromString(text);
        } catch (IllegalArgumentException ex) {
            FIGHT_OWNER = null;
        }

        UNCLAIMED_PRIZES.clear();
        ConfigurationSection unclaimedPrizes = config.getConfigurationSection("state.unclaimed-prizes");
        for (String key : unclaimedPrizes.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                // Docs don't say if getInt() throws for malformed.
                UNCLAIMED_PRIZES.put(uuid, unclaimedPrizes.getInt(key));
            } catch (IllegalArgumentException ex) {
                logger.warning("Unclaimed dragon prize registered to invalid UUID: " + key);
            }
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Reload the configuration.
     *
     * This method is the counterpart to {@link #reloadFightState}. This method
     * only loads the settings of the fight that are not modified as the fight
     * progresses.
     */
    public void reloadConfiguration() {
        DragonFight.PLUGIN.reloadConfig();
        FileConfiguration config = DragonFight.PLUGIN.getConfig();

        LOG_PREFIX = config.getString("settings.log-prefix");
        DEBUG_PREFIX = config.getString("settings.debug-prefix");

        for (int stageNumber = 1; stageNumber <= 11; ++stageNumber) {
            getStage(stageNumber).load(getStageSection(stageNumber));
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Save the configuration.
     */
    public void save() {
        FileConfiguration config = DragonFight.PLUGIN.getConfig();
        // Copy defaults.
        config.set("settings.log-prefix", LOG_PREFIX);
        config.set("settings.debug-prefix", DEBUG_PREFIX);

        config.set("state.stage-number", STAGE_NUMBER);
        config.set("state.new-stage-number", NEW_STAGE_NUMBER);
        config.set("state.total-boss-max-health", TOTAL_BOSS_MAX_HEALTH);
        config.set("state.fight-owner", (FIGHT_OWNER == null) ? null : FIGHT_OWNER.toString());

        ConfigurationSection unclaimedPrizes = config.createSection("state.unclaimed-prizes");
        for (Map.Entry<UUID, Integer> entry : UNCLAIMED_PRIZES.entrySet()) {
            unclaimedPrizes.set(entry.getKey().toString(), entry.getValue());
        }

        for (int stageNumber = 1; stageNumber <= 11; ++stageNumber) {
            getStage(stageNumber).save(getStageSection(stageNumber));
        }
        DragonFight.PLUGIN.saveConfig();
    }

    // ------------------------------------------------------------------------
    /**
     * Return the ConfigurationSection that encodes the specified stage.
     *
     * @param stageNumber the stage number in [1,11].
     * @return the section.
     */
    protected ConfigurationSection getStageSection(int stageNumber) {
        FileConfiguration config = DragonFight.PLUGIN.getConfig();
        return config.getConfigurationSection("stages." + stageNumber);
    }

    // ------------------------------------------------------------------------
    /**
     * {@link Stage}s 1 through 11, in indices 0 through 10, respectively.
     */
    protected Stage[] _stages = new Stage[11];

} // class Configuration