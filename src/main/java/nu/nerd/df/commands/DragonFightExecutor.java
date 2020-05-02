package nu.nerd.df.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import nu.nerd.beastmaster.commands.ExecutorBase;
import nu.nerd.df.DragonFight;

//-----------------------------------------------------------------------------
/**
 * Command executor for the <i>/dragonfight</i> command.
 */
public class DragonFightExecutor extends ExecutorBase {
    // ------------------------------------------------------------------------
    /**
     * Constructor.
     */
    public DragonFightExecutor() {
        super("dragonfight", "help", "reload");
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

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            DragonFight.CONFIG.reload();
            sender.sendMessage(ChatColor.DARK_PURPLE + "DragonFight configuration reloaded.");
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Invalid arguments. Try /" + command.getName() + " help.");
        return true;
    }
} // class DragonFightExecutor