package nu.nerd.df.commands;

import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import nu.nerd.beastmaster.commands.ExecutorBase;
import nu.nerd.df.DragonFight;
import nu.nerd.df.FightState;

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
            cmdInfo(sender);
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("prize")) {
            if (!isInGame(sender)) {
                return true;
            }
            cmdDragonPrize(sender);
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Invalid arguments. Try /" + command.getName() + " help.");
        return true;
    }

    // ------------------------------------------------------------------------
    /**
     * Implement the <i>/dragon info</i> command.
     *
     * This command is for ordinary players to check the fight status.
     */
    public void cmdInfo(CommandSender sender) {
        if (!DragonFight.FIGHT.isFightHappening() && !DragonFight.FIGHT.isStageNumberChanging()) {
            sender.sendMessage(ChatColor.DARK_PURPLE + "Nobody is fighting the dragon right now.");
        } else {
            sender.sendMessage(ChatColor.DARK_PURPLE + "The current fight stage is " +
                               ChatColor.LIGHT_PURPLE + DragonFight.FIGHT.getStageNumber() +
                               ChatColor.DARK_PURPLE + ".");
            if (DragonFight.CONFIG.FIGHT_OWNER == null) {
                sender.sendMessage(ChatColor.DARK_PURPLE + "But mysteriously, nobody owns the final drops. :/");
            } else {
                OfflinePlayer fightOwner = Bukkit.getOfflinePlayer(DragonFight.CONFIG.FIGHT_OWNER);
                sender.sendMessage(ChatColor.DARK_PURPLE + "The final drops are owned by " +
                                   ChatColor.LIGHT_PURPLE + fightOwner.getName() + ChatColor.DARK_PURPLE + ".");
            }
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Implement the <i>/dragon prize</i> command.
     *
     * @param sender the command sender, for message sending.
     */
    public void cmdDragonPrize(CommandSender sender) {
        Player player = (Player) sender;
        UUID playerUuid = player.getUniqueId();

        int unclaimed = DragonFight.CONFIG.getUnclaimedPrizes(playerUuid);
        if (unclaimed <= 0) {
            sender.sendMessage(ChatColor.DARK_PURPLE + "You don't have any unclaimed dragon prizes.");
        } else {
            List<ItemStack> prizes = FightState.generatePrizes();
            if (FightState.givePrizes(player, prizes)) {
                DragonFight.CONFIG.incUnclaimedPrizes(playerUuid, -1);
                DragonFight.CONFIG.save();

                if (--unclaimed > 0) {
                    String prizeCount = ChatColor.LIGHT_PURPLE + Integer.toString(unclaimed) +
                                        ChatColor.DARK_PURPLE + " unclaimed prize" + (unclaimed > 1 ? "s" : "");
                    sender.sendMessage(ChatColor.DARK_PURPLE + "You still have " + prizeCount + ".");
                }
            } else {
                // The item(s) did not fit.
                String slots = ChatColor.LIGHT_PURPLE + Integer.toString(prizes.size()) +
                               ChatColor.DARK_PURPLE + " inventory slot" + (prizes.size() > 1 ? "s" : "");
                player.sendMessage(ChatColor.DARK_PURPLE + "You need at least " + slots + " empty.");
                player.sendMessage(ChatColor.DARK_PURPLE + "Make some room in your inventory and try again.");
            }
        }
    }

} // class DragonExecutor