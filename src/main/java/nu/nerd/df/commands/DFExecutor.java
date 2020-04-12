package nu.nerd.df.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import net.md_5.bungee.api.ChatColor;
import nu.nerd.beastmaster.commands.ExecutorBase;
import nu.nerd.df.DragonFight;

// ----------------------------------------------------------------------------
/**
 * Command executor for the `/df` command.
 */
public class DFExecutor extends ExecutorBase {
    // ------------------------------------------------------------------------
    /**
     * @param name
     * @param subcommands
     */
    public DFExecutor() {
        super("df", "help", "stop");
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

        if (args.length == 1 && args[0].equalsIgnoreCase("stop")) {
            DragonFight.FIGHT.stop(sender);
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Invalid arguments. Try /" + command.getName() + " help.");
        return true;
    }
} // class DFExecutor