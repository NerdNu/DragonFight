package nu.nerd.df;

import java.util.List;

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
     * Constructor.
     * 
     * @param stageNumber the stage number from 1 to 10.
     */
    public Stage(int stageNumber) {
        _stageNumber = stageNumber;
        _dropSetId = getDropSetId(stageNumber);
    }

    // ------------------------------------------------------------------------
    /**
     * Get the DropSet that defines the boss mob(s) for this stage.
     */
    public DropSet getDropSet() {
        DropSet dropSet = BeastMaster.LOOTS.getDropSet(_dropSetId);
        // Lazily re-create if removed by an admin after onEnable().
        if (dropSet == null) {
            dropSet = new DropSet(_dropSetId);
            BeastMaster.CONFIG.save();
        }
        return dropSet;
    }

    // ------------------------------------------------------------------------
    /**
     * Show the stage title to the specified players.
     * 
     * @param players the players that should see the title.
     */
    public void announce(List<Player> players) {
        for (Player player : players) {
            // Subtitles are not visible if there is no title.
            if (!_title.isEmpty()) {
                player.sendTitle(_title, _subtitle, 10, 70, 20);
            }
            if (!_message.isEmpty()) {
                player.sendMessage(_message);
            }
        }
    }

    // ------------------------------------------------------------------------
    /**
     * The stage number from 1 to 10.
     */
    protected int _stageNumber;

    /**
     * The DropSet ID.
     */
    protected String _dropSetId;

    /**
     * The stage title text.
     */
    protected String _title = "";

    /**
     * The stage subtitle text.
     */
    protected String _subtitle = "";

    /**
     * Message sent to players.
     */
    protected String _message = "";
} // class Stage