package nu.nerd.df;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

//----------------------------------------------------------------------------
/**
 * Facilities for formatting strings and doing string substitution in stage
 * commands.
 */
class Strings {
    /**
     * Translate formatting codes beginning with '&' and replace doubled-up
     * ampersands with a single ampersand.
     *
     * @param message the message string to be translated.
     * @return the translated message.
     */
    public static String translate(String message) {
        return ChatColor.translateAlternateColorCodes('&', message.replace("&&", "\f")).replace("\f", "&");
    }

    /**
     * Return a map containing the substitution variables applicable to
     * per-player stage commands.
     *
     * The map maps a variable name to the Supplier<> that returns a String
     * representation of the variable's value.
     *
     * @param player the specific player targeted by the stage command.
     * @param owner  the fight owner, who may be offline.
     * @param bLoc   the location where the stage bosses will be spawned.
     * @param stage  the fight stage.
     * @return a map of String suppliers that format variables.
     */
    public static Map<String, Supplier<String>> getPerPlayerVariables(Player player, OfflinePlayer owner, Location bLoc, Stage stage) {
        Map<String, Supplier<String>> variables = new HashMap<>();
        final Location pLoc = player.getLocation();

        // Just to be extra safe in case Bukkit.getOfflinePlayer() fails.
        variables.put("o", () -> (owner != null) ? owner.getName() : "");
        variables.put("w", () -> bLoc.getWorld().getName());

        variables.put("p", () -> player.getName());
        variables.put("pc", () -> String.format("%d %d %d", pLoc.getBlockX(), pLoc.getBlockY(), pLoc.getBlockZ()));
        variables.put("pc.", () -> String.format("%.3f %.3f %.3f", pLoc.getX(), pLoc.getY(), pLoc.getZ()));
        variables.put("px", () -> Integer.toString(pLoc.getBlockX()));
        variables.put("py", () -> Integer.toString(pLoc.getBlockY()));
        variables.put("pz", () -> Integer.toString(pLoc.getBlockZ()));
        variables.put("px.", () -> String.format("%.3f", pLoc.getX()));
        variables.put("py.", () -> String.format("%.3f", pLoc.getY()));
        variables.put("pz.", () -> String.format("%.3f", pLoc.getZ()));

        variables.put("bc", () -> String.format("%d %d %d", bLoc.getBlockX(), bLoc.getBlockY(), bLoc.getBlockZ()));
        variables.put("bc.", () -> String.format("%.3f %.3f %.3f", bLoc.getX(), bLoc.getY(), bLoc.getZ()));
        variables.put("bx", () -> Integer.toString(bLoc.getBlockX()));
        variables.put("by", () -> Integer.toString(bLoc.getBlockY()));
        variables.put("bz", () -> Integer.toString(bLoc.getBlockZ()));
        variables.put("bx.", () -> String.format("%.3f", bLoc.getX()));
        variables.put("by.", () -> String.format("%.3f", bLoc.getY()));
        variables.put("bz.", () -> String.format("%.3f", bLoc.getZ()));

        variables.put("sn", () -> Integer.toString(stage.getStageNumber()));
        variables.put("st", () -> stage.format(stage.getTitle()));
        variables.put("ss", () -> stage.format(stage.getSubtitle()));
        variables.put("sm", () -> stage.format(stage.getMessage()));

        return variables;
    }

    /**
     * Return a map containing the substitution variables applicable to
     * all-player stage commands.
     *
     * The map maps a variable name to the Supplier<> that returns a String
     * representation of the variable's value.
     *
     * @param players a list of all nearby players targeted by the command.
     * @param owner   the fight owner, who may be offline.
     * @param bLoc    the location where the stage bosses will be spawned.
     * @param stage   the fight stage.
     * @return a map of String suppliers that format variables.
     */
    public static Map<String, Supplier<String>> getAllPlayerVariables(Collection<Player> players, OfflinePlayer owner, Location bLoc, Stage stage) {
        Map<String, Supplier<String>> variables = new HashMap<>();
        final String playerNames = players.stream().map(Player::getName).collect(Collectors.joining(","));

        // Just to be extra safe in case Bukkit.getOfflinePlayer() fails.
        variables.put("o", () -> (owner != null) ? owner.getName() : "");
        variables.put("w", () -> bLoc.getWorld().getName());

        variables.put("ps", () -> playerNames);

        variables.put("bc", () -> String.format("%d %d %d", bLoc.getBlockX(), bLoc.getBlockY(), bLoc.getBlockZ()));
        variables.put("bc.", () -> String.format("%.3f %.3f %.3f", bLoc.getX(), bLoc.getY(), bLoc.getZ()));
        variables.put("bx", () -> Integer.toString(bLoc.getBlockX()));
        variables.put("by", () -> Integer.toString(bLoc.getBlockY()));
        variables.put("bz", () -> Integer.toString(bLoc.getBlockZ()));
        variables.put("bx.", () -> String.format("%.3f", bLoc.getX()));
        variables.put("by.", () -> String.format("%.3f", bLoc.getY()));
        variables.put("bz.", () -> String.format("%.3f", bLoc.getZ()));

        variables.put("sn", () -> Integer.toString(stage.getStageNumber()));
        variables.put("st", () -> stage.format(stage.getTitle()));
        variables.put("ss", () -> stage.format(stage.getSubtitle()));
        variables.put("sm", () -> stage.format(stage.getMessage()));

        return variables;
    }

    /**
     * Perform variable substitution on a format containing variable references
     * of the form %name%.
     *
     * The sequence %% is replaced with a single %.
     *
     * As a special case for backwards compatibility, %s is treated as
     * equivalent to %p%.
     *
     * @param format    the format specifier.
     * @param variables a map from variable name (without %) to an object that
     *                  supplies its String representation.
     * @return the format with all defined variable references replaced;
     *         undefined variables are not replaced and %% is converted to %.
     */
    public static String substitute(String format, Map<String, Supplier<String>> variables) {
        StringBuilder result = new StringBuilder();
        StringBuilder segment = new StringBuilder();

        // True if inside a %variable% reference:
        boolean inVar = false;
        for (int i = 0; i < format.length(); ++i) {
            char c = format.charAt(i);
            if (c == '%') {
                if (inVar) {
                    // End this variable reference.
                    inVar = false;
                    String variableName = segment.toString();
                    segment.setLength(0);

                    if (variables.containsKey(variableName)) {
                        result.append(variables.get(variableName).get());
                    } else {
                        result.append('%').append(variableName).append('%');
                    }
                } else {
                    // Non-variable, literal text.
                    char next = (i + 1 < format.length()) ? format.charAt(i + 1) : '\0';
                    if (next == '%') {
                        // "%%" => literal '%'
                        segment.append('%');
                        ++i;
                    } else {
                        // End this literal segment; start a variable.
                        inVar = true;
                        if (segment.length() > 0) {
                            result.append(segment.toString());
                            segment.setLength(0);
                        }
                    }
                }
            } else {
                segment.append(c);
            }
        } // for

        // Last segment.
        if (inVar) {
            // Mis-matched % at start of variable reference becomes literal.
            segment.insert(0, '%');
        }

        if (segment.length() > 0) {
            result.append(segment.toString());
        }

        return result.toString();
    }
}