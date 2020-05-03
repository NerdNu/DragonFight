package nu.nerd.df.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import nu.nerd.beastmaster.commands.ExecutorBase;
import nu.nerd.df.DragonFight;

// ----------------------------------------------------------------------------
/**
 * Command executor for the <i>/dragon</i> command.
 */
public class DragonExecutor extends ExecutorBase {
    // ------------------------------------------------------------------------
    /**
     * Constructor.
     */
    public DragonExecutor() {
        super("dragon", "help", "info", "prize");
    }

    // ------------------------------------------------------------------------
    /**
     * @see org.bukkit.command.CommandExecutor#onCommand(org.bukkit.command.CommandSender,
     *      org.bukkit.command.Command, java.lang.String, java.lang.String[])
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("help"))) {
            return false;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("info")) {
            DragonFight.FIGHT.cmdPlayerInfo(sender);
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("prize")) {
            if (!isInGame(sender)) {
                return true;
            }
            DragonFight.FIGHT.cmdDragonPrize(sender);
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Invalid arguments. Try /" + command.getName() + " help.");
        return true;
    }
} // class DragonExecutor