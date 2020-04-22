package nu.nerd.df;

import java.util.Collection;

import org.bukkit.ChatColor;
import org.bukkit.boss.BarColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import nu.nerd.beastmaster.BeastMaster;
import nu.nerd.beastmaster.DropSet;

// ----------------------------------------------------------------------------
/**
 * Holds the configuration for one fight stage.
 */
public class Stage {
    // ------------------------------------------------------------------------
    /**
     * Return the ID of the loot table that defines the boss mob(s) for the
     * specified stage.
     * 
     * @param stageNumber the stage number from 1 to 10.
     * @returns the DropSet ID.
     * @throws IllegalArgumentException for an invalid stage number.
     */
    public static String getDropSetId(int stageNumber) {
        if (stageNumber < 1 || stageNumber > 10) {
            throw new IllegalArgumentException("invalid stage number: " + stageNumber);
        }
        return "df-stage" + stageNumber;
    }

    // ------------------------------------------------------------------------
    /**
     * Swap the contents of two stages.
     * 
     * @param a a stage.
     * @param b another stage.
     */
    public static void swap(Stage a, Stage b) {
        // Get the old drops (dropset name from stage number).
        DropSet aOldDrops = BeastMaster.LOOTS.getDropSet(a.getDropSetId());
        if (aOldDrops == null) {
            aOldDrops = new DropSet(a.getDropSetId());
        }
        DropSet bOldDrops = BeastMaster.LOOTS.getDropSet(b.getDropSetId());
        if (bOldDrops == null) {
            bOldDrops = new DropSet(b.getDropSetId());
        }

        // Update stage numbers which also changes getDropSetId() result.
        int tmpStageNumber = a._stageNumber;
        a._stageNumber = b._stageNumber;
        b._stageNumber = tmpStageNumber;

        // New drop sets to be registered using new IDs but other's drops.
        DropSet newADrops = new DropSet(a.getDropSetId(), bOldDrops);
        DropSet newBDrops = new DropSet(b.getDropSetId(), aOldDrops);
        BeastMaster.LOOTS.addDropSet(newADrops);
        BeastMaster.LOOTS.addDropSet(newBDrops);
        // Don't save drops yet, since we may be shuffling all 10 stages.

        BarColor tmpBarColor = a._barColor;
        a._barColor = b._barColor;
        b._barColor = tmpBarColor;

        String tmpTitle = a._title;
        a._title = b._title;
        b._title = tmpTitle;

        String tmpSubtitle = a._title;
        a._subtitle = b._subtitle;
        b._subtitle = tmpSubtitle;

        String tmpMessage = a._message;
        a._message = b._message;
        b._message = tmpMessage;
    }

    // ------------------------------------------------------------------------
    /**
     * Constructor.
     * 
     * @param stageNumber the stage number from 1 to 10.
     */
    public Stage(int stageNumber) {
        _stageNumber = stageNumber;
        _title = "Stage " + _stageNumber;
    }

    // ------------------------------------------------------------------------
    /**
     * Return the number of this stage.
     * 
     * @return the number of this stage.
     */
    public int getStageNumber() {
        return _stageNumber;
    }

    // ------------------------------------------------------------------------
    /**
     * Return the DropSet ID that defines the boss mob(s) for this stage.
     * 
     * @return the DropSet ID that defines the boss mob(s) for this stage.
     */
    public String getDropSetId() {
        return getDropSetId(_stageNumber);
    }

    // ------------------------------------------------------------------------
    /**
     * Show the stage title to the specified players.
     * 
     * @param players the players that should see the title.
     */
    public void announce(Collection<Player> players) {
        for (Player player : players) {
            // Subtitles are not visible if there is no title.
            if (!_title.isEmpty()) {
                player.sendTitle(format(_title), format(_subtitle), 10, 70, 20);
            }
            if (!_message.isEmpty()) {
                player.sendMessage(format(_message));
            }
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Load this Stage from the specified configuration section.
     * 
     * @param section the section.
     */
    public void load(ConfigurationSection section) {
        _stageNumber = Integer.parseInt(section.getName());
        try {
            _barColor = BarColor.valueOf(section.getString("barcolor").toUpperCase());
        } catch (IllegalArgumentException ex) {
            _barColor = BarColor.WHITE;
        }
        _title = section.getString("title", "Stage {}");
        _subtitle = section.getString("subtitle", "Stage {} subtitle");
        _message = section.getString("message", "");
    }

    // ------------------------------------------------------------------------
    /**
     * Save this Stage to the specified configuration section.
     * 
     * @param section the section.
     */
    public void save(ConfigurationSection section) {
        section.set("barcolor", _barColor.toString());
        section.set("title", _title);
        section.set("subtitle", _subtitle);
        section.set("message", _message);
    }

    // ------------------------------------------------------------------------
    /**
     * Set the colour of the boss bar.
     * 
     * @param barColor the BarColor.
     */
    public void setBarColor(BarColor barColor) {
        _barColor = barColor;
    }

    // ------------------------------------------------------------------------
    /**
     * Return the colour of the boss bar.
     * 
     * @return the colour of the boss bar.
     */
    public BarColor getBarColor() {
        return _barColor;
    }

    // ------------------------------------------------------------------------
    /**
     * Set the raw title of this stage.
     * 
     * @param title the title.
     */
    public void setTitle(String title) {
        _title = title;
    }

    // ------------------------------------------------------------------------
    /**
     * Return the raw title of this stage.
     * 
     * @return the raw title of this stage.
     */
    public String getTitle() {
        return _title;
    }

    // ------------------------------------------------------------------------
    /**
     * Set the raw subtitle of this stage.
     * 
     * @param subtitle the subtitle.
     */
    public void setSubtitle(String subtitle) {
        _subtitle = subtitle;
    }

    // ------------------------------------------------------------------------
    /**
     * Return the raw subtitle of this stage.
     * 
     * @return the raw subtitle of this stage.
     */
    public String getSubtitle() {
        return _subtitle;
    }

    // ------------------------------------------------------------------------
    /**
     * Set the raw message of this stage.
     * 
     * @param message the message.
     */
    public void setMessage(String message) {
        _message = message;
    }

    // ------------------------------------------------------------------------
    /**
     * Return the raw message of this stage.
     * 
     * @return the raw message of this stage.
     */
    public String getMessage() {
        return _message;
    }

    // ------------------------------------------------------------------------
    /**
     * Format a title, subtitle or message string for this stage by translating
     * colour codes and replacing "{}" with the current stage number.
     */
    public String format(String text) {
        return ChatColor.translateAlternateColorCodes('&', text.replaceAll("\\{\\}", Integer.toString(_stageNumber)));
    }

    // ------------------------------------------------------------------------
    /**
     * The stage number from 1 to 10.
     */
    protected int _stageNumber;

    /**
     * Colour of the BossBar.
     */
    protected BarColor _barColor;

    /**
     * The stage title text.
     */
    protected String _title = "Stage {}";

    /**
     * The stage subtitle text.
     */
    protected String _subtitle = "Stage {} subtitle";

    /**
     * Message sent to players.
     */
    protected String _message = "";
} // class Stage