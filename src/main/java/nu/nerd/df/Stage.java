package nu.nerd.df;

import java.util.Collection;
import java.util.Map;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
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
     * The Stage instances are intended to stay at the same indicies, so the
     * stage number doesn't change. Only the properties that determine the
     * appearance of the stage, and the contents of the DropSets swap.
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

        // New drop sets to be registered using new IDs but other's drops.
        DropSet aNewDrops = new DropSet(a.getDropSetId(), bOldDrops);
        DropSet bNewDrops = new DropSet(b.getDropSetId(), aOldDrops);
        BeastMaster.LOOTS.addDropSet(aNewDrops);
        BeastMaster.LOOTS.addDropSet(bNewDrops);
        // Don't save drops yet, since we may be shuffling all 10 stages.

        BarColor tmpBarColor = a._barColor;
        a._barColor = b._barColor;
        b._barColor = tmpBarColor;

        String tmpTitle = a._title;
        a._title = b._title;
        b._title = tmpTitle;

        String tmpSubtitle = a._subtitle;
        a._subtitle = b._subtitle;
        b._subtitle = tmpSubtitle;

        String tmpMessage = a._message;
        a._message = b._message;
        b._message = tmpMessage;

        String tmpCommand = a._stageCommand;
        a._stageCommand = b._stageCommand;
        b._stageCommand = tmpCommand;

        String tmpPlayerCommand = a._playerCommand;
        a._playerCommand = b._playerCommand;
        b._playerCommand = tmpPlayerCommand;
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
     * Show the stage title and messages to the specified players, and run
     * commands.
     *
     * @param players the players that should see the title.
     * @param owner   the fight owner, who may be offline and may be null.
     * @param bLoc    the spawn location of the stage bosses.
     */
    public void announce(Collection<Player> players, OfflinePlayer owner, Location bLoc) {
        for (Player player : players) {
            // Subtitles are not visible if there is no title.
            if (!_title.isEmpty()) {
                player.sendTitle(format(_title), format(_subtitle), 10, 70, 20);
            }
            if (!_message.isEmpty()) {
                player.sendMessage(format(_message));
            }
            if (!_playerCommand.isEmpty()) {
                Map<String, Supplier<String>> vars = Strings.getPerPlayerVariables(player, owner, bLoc, this);
                runConsoleCommand(Strings.translate(Strings.substitute(_playerCommand, vars)));
            }
        }

        if (!_stageCommand.isEmpty()) {
            Map<String, Supplier<String>> vars = Strings.getAllPlayerVariables(players, owner, bLoc, this);
            runConsoleCommand(Strings.translate(Strings.substitute(_stageCommand, vars)));
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
        _title = section.getString("title", "Stage %sn%");
        _subtitle = section.getString("subtitle", "Stage %sn% subtitle");
        _message = section.getString("message", "");
        _playerCommand = section.getString("player-command", "");
        _stageCommand = section.getString("stage-command", "");
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
        section.set("player-command", _playerCommand);
        section.set("stage-command", _stageCommand);
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
     * Set the unformatted title of this stage.
     *
     * @param title the title.
     */
    public void setTitle(String title) {
        _title = title;
    }

    // ------------------------------------------------------------------------
    /**
     * Return the unformatted title of this stage.
     *
     * @return the unformatted title of this stage.
     */
    public String getTitle() {
        return _title;
    }

    // ------------------------------------------------------------------------
    /**
     * Set the unformatted subtitle of this stage.
     *
     * @param subtitle the subtitle.
     */
    public void setSubtitle(String subtitle) {
        _subtitle = subtitle;
    }

    // ------------------------------------------------------------------------
    /**
     * Return the unformatted subtitle of this stage.
     *
     * @return the unformatted subtitle of this stage.
     */
    public String getSubtitle() {
        return _subtitle;
    }

    // ------------------------------------------------------------------------
    /**
     * Set the unformatted message of this stage.
     *
     * @param message the message.
     */
    public void setMessage(String message) {
        _message = message;
    }

    // ------------------------------------------------------------------------
    /**
     * Return the unformatted message of this stage.
     *
     * @return the unformatted message of this stage.
     */
    public String getMessage() {
        return _message;
    }

    // ------------------------------------------------------------------------
    /**
     * Set the per-player command of this stage, run for each online player in
     * the fight.
     *
     * The leading '/' character will be removed.
     *
     * @param command the command.
     */
    public void setPlayerCommand(String playerCommand) {
        _playerCommand = playerCommand.startsWith("/") ? playerCommand.substring(1) : playerCommand;
    }

    // ------------------------------------------------------------------------
    /**
     * Return the unformatted per-player command of this stage.
     *
     * @return the unformatted per-player command of this stage.
     */
    public String getPlayerCommand() {
        return _playerCommand;
    }

    // ------------------------------------------------------------------------
    /**
     * Set the command of this stage, run once for the fight stage.
     *
     * The leading '/' character will be removed.
     *
     * @param stageCommand the command.
     */
    public void setStageCommand(String stageCommand) {
        _stageCommand = stageCommand.startsWith("/") ? stageCommand.substring(1) : stageCommand;
    }

    // ------------------------------------------------------------------------
    /**
     * Return the unformatted command of this stage.
     *
     * @return the unformatted command of this stage.
     */
    public String getStageCommand() {
        return _stageCommand;
    }

    // ------------------------------------------------------------------------
    /**
     * Format a title, subtitle or message string for this stage by translating
     * colour codes and replacing "%sn%" with the current stage number.
     */
    public String format(String text) {
        return ChatColor.translateAlternateColorCodes('&', text.replaceAll("%sn%", Integer.toString(_stageNumber)));
    }

    // ------------------------------------------------------------------------
    /**
     * Run the specified command as the console sender.
     *
     * @param formattedCommand the command, after colour and variable
     *                         substitutions.
     */
    public static void runConsoleCommand(String formattedCommand) {
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), formattedCommand);
        } catch (Exception ex) {
            String message = ex.getClass().getName() + ": \"" + ex.getMessage() + "\" running \"" + formattedCommand + "\"";
            DragonFight.PLUGIN.getLogger().severe(message);
        }
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
    protected String _title = "Stage %sn%";

    /**
     * The stage subtitle text.
     */
    protected String _subtitle = "Stage %sn% subtitle";

    /**
     * Message sent to players.
     */
    protected String _message = "";

    /**
     * Command sent to each player individually.
     */
    protected String _playerCommand = "";

    /**
     * Command sent to all players collectively.
     */
    protected String _stageCommand = "";

} // class Stage