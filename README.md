# DragonFight
A Bukkit plugin providing a custom dragon boss fight.


## Overview

The DragonFight plugin implements an 11 stage boss fight to defeat the ender
dragon.

In the first 10 stages, a randomly selected end crystal is converted into one
or more boss mobs in the circle between the obsidian pillars. All the other end
crystals are invulnerable. When the player defeats the current stage boss mobs,
the next crystal is turned into boss mobs.

The dragon is also invulnerable until all 10 stages have been cleared. In the
11th and final stage of the fight, the player must defeat the dragon.

The DragonFight plugin relies on new mob properties added in BeastMaster
v2.13.0. Consult the BeastMaster wiki for a detailed description of all
[mob properties](https://github.com/NerdNu/BeastMaster/wiki/Mobs#mob-property-reference).

In the current version, score-keeping is not implemented. In the completed
plugin, team and individual completion times will be recorded for all 11 stages
as well as the total time for all stages combined.

The drops or "prize" for defeating the dragon are given to the player who
placed the fourth (final) end crystal to summon the dragon. There are
admin commands to switch the owner of the fight in the event that someone
spawns the dragon and then leaves with no intention of killing it.

If the fight owner has a full inventory or is offline when the dragon dies,
then the unclaimed prize is recorded. They will see a message about unclaimed
prizes when they next log in and they can claim a prize with the `/dragon prize`
command.


## BeastMaster Groups

The DragonFight plugin makes use of BeastMaster `groups` and `friend-groups`
properties to ensure that all mobs participating in the fight cooperate rather
than attack each other.

 * All mobs in the fight have the `df-entity` group.
 * Projectiles are assigned the `df-entity` scoreboard tag.
 * Boss mobs have the `df-boss` group, which the plugin uses to track them
   in order to update the boss bar and detect the end of a fight stage.
 * Support mobs spawned when a boss is low on health have the `df-support`
   group. The DragonFight plugin (or perhaps BeastMaster) can in principle
   put limits on the number of support mobs, although that is not currently
   implemented.


## Customisation

The bulk of the plugin's fight mechanics are controlled by [BeastMaster](https://github.com/NerdNu/BeastMaster),
and are therefore readily configurable with BeastMaster commands.

DragonFight ensures that certain loot tables, mob types, items and potion
sets are defined. Anything missing is re-created with a default definition
when the plugin starts up. Any changes you make to these customisation hooks
will persist across server restarts, however, provided that you don't delete
the object entirely.

> *All of the BeastMaster objects that configure DragonFight are given names with
the prefix `df-` and it is recommended that any other BeastMaster objects you
create for the dragon fight also have this same prefix.*


### Customisation - Built-In Loot Tables

The first 10 bosses are defined by loot tables named `df-stage1` through
`df-stage10`. By default, these loot tables all contain a single mob drop of
type `df-placeholder-boss`. Any stage loot table can be configured to drop
several mobs that are collectively counted as the stage boss, so that all of
them must be killed to move on to the next stage.

The final item drops of the ender dragon are defined by the `df-dragon-drops`
loot table. By default, it contains a reference to an item `df-elytra`, a
set of elytra, and `df-dragon-head` a dragon head item.

The loot table `df-no-drops` is pre-defined as a loot table with a 100%
chance of dropping `NOTHING`, and is set as the default drops for the
`df-boss` and `df-support` predefined mob types.


### Customisation - Built-In Mobs

There are three mob types predefined by the DragonFight plugin: `df-boss` and
`df-support` which provide basic properties to be inherited by all boss and
support mob types, and `df-placeholder-boss`, the default boss for all
unconfigured stages.

Although it's not mandatory to use `df-boss` or `df-support` as the parent type
of boss and support mobs in the fight stages, it's highly recommended to do so
because it saves a lot of effort and avoids some potential mistakes.

In addition to these predefined mob types, the DragonFight plugin adds
`df-entity` to the friend groups of the vanilla `EnderDragon` mob type, so
that it will not attack any mob belonging to that group, which includes any
mob whose ancestor type is either of `df-support` or `df-boss`.

The `df-support` mob type inherits its properties from `skeleton` and has the
following default properties set by the DragonFight plugin when first defined:

 * `show-name-plate` true
 * `pick-up-percent` 0.0
 * `helmet-drop-percent` 0.0
 * `chest-plate-drop-percent` 0.0
 * `leggings-drop-percent` 0.0
 * `boots-drop-percent` 0.0
 * `main-hand-drop-percent` 0.0
 * `drops` df-nothing
 * `can-despawn` false
 * `groups` df-entity,df-support
 * `friend-groups` df-entity

The `df-boss` mob inherits all of the above properties from `df-support` and
sets the following default properties when first defined by the DragonFight
plugin:

 * `potion-buffs` df-boss-potions
 * `health` 300.0
 * `groups` df-boss,df-entity

The recommended way to define a new support mob type is to inherit from
`df-support` and then set the `entity-type` property of the new mob type:

    /beast-mob add df-my-creeper df-support
    /beast-mob set df-my-creeper entity-type creeper

The recommended way to define a new boss mob type is to inherit from
`df-boss` and then set the `entity-type` property of the new mob type:

    /beast-mob add df-evoker-boss df-boss
    /beast-mob set df-evoker-boss entity-type evoker


### Customisation - Built-In Potion Sets

The `df-boss-potions` set is configured with a potion of infinite fire
resistance that is applied to all bosses that inherited from the `df-boss`
type.


### Customisation - Built-In Items

DragonFight will add definitions for several items if it finds they are not
defined when the plugin starts up. The items can be permanently redefined
with the `/beast-item redefine` command.

 * `df-elytra` is the set of elytra dropped by the `df-dragon-drops` loot table.
 * `df-dragon-head` is the dragon head dropped by the `df-dragon-drops` loot table.
 * `df-placeholder-head` is the custom head of the `df-placeholder-boss` mob,
   spawned by default in all unconfigured stages.


## Mob Tracking

To prevent mobs from wandering too far off, the DragonFight mob will teleport
bosses to a random location within the circle between the obsidian pillars if
they:

 * go more than 80 blocks from the centre of the world,
 * have not taken any damage in the last 90 seconds, in which case they've
   probably teleported underground,
 * go below Y40, in which case they are out of reach or may be about to fall
   out of the world, or
 * if the mob is about to use a portal.


## Commands
### Player Commands

 * `/dragon help` - Show this help. Equivalent to `/help /<command>`.
 * `/dragon info` - Show information about the current fight: stage number, owner.
 * `/dragon prize` - Claim one prize for defeating the dragon.


### Administrator Commands

There is currently one administrative command:

 * `/df help` - Show help for the `/df` command. Equivalent to `/help /df`.

 * `/df info` - Show information about the current fight: stage number, owner, boss health and dragon health.

 * `/df stop` - stop the current dragon fight, removing all mobs and projectiles.

 * `/df next` - Kill the current boss, skipping to the next stage.

 * `/df stage <number>` - Skip forward to the specified stage, from 0 to 11 (dragon only). Stage 0 stops the fight.

 * `/df spawn <number>` - Spawn the bosses from stage 1 through 10 on the block where you are looking.
   This command is intended to be used during development of a DragonFight configuration, rather than during
   an ongoing fight.

 * `/df owner <player>` - Set the owner of the current fight.

 * `/df unclaimed` - List players with unclaimed prizes.

 * `/df list` - List all 11 stage titles and subtitles.

 * `/df swap <from> <to>` - Swap two stages by stage number (1 to 10).

 * `/df move <from> <to>` - Move stage `<from>` to stage `<to>` and shift in-between stages into the gap.

 * `/df config <stage>` - Show the configuration of `<stage>` 1 to 11.
 * `/df config <stage> barcolor <color>` - Configure stage bar color.
   * Note that Minecraft only allows [7 boss bar colors](https://hub.spigotmc.org/javadocs/spigot/org/bukkit/boss/BarColor.html),
     `BLUE`, `GREEN`, `PINK`, `PURPLE`, `RED`, `WHITE` and `YELLOW`.
 * `/df config <stage> title <text>` - Configure stage title. `%sn%` can be substituted (see below).
 * `/df config <stage> subtitle <text>` - Configure stage subtitle. `%sn%` can be substituted (see below).
 * `/df config <stage> message <text>` - Configure stage message. `%sn%` can be substituted (see below).
 * `/df config <stage> player-command <text>` - Configure the per-player command sent to each player in the fight when it begins.
   The command is run with full permissions. Many variables can be substituted into the command (see below).
 * `/df config <stage> stage-command <text>` - Configure the command run once at the start of the fight, after per-player commands.
   The command is run with full permissions. Many variables can be substituted into the command (see below).

 > *Note that if you are configuring commands that are CommandHelper aliases, they must be run
 > using `/runalias <your-command-here>`. For vanilla Minecraft commands and plugin commands,
 > drop the `/runalias`. Regardless of the type of command, the leading `/` can be omitted.*


### Stage Text Substitution

Titles, subtitles and messages and commands accept `&c`-style [formatting codes](https://minecraft.gamepedia.com/Formatting_codes#Color_codes).
They are also subject to variable substitution as tabulated below.

| Variable | Applied To        | Substitution Text |
| :---     | :---              | :--- |
| %%       | all text          | The single character '%'. |
| %sn%     | all text          | The stage number, 1 to 11. |
| %st%     | all commands      | The stage title, after substitution of `%sn%`. |
| %ss%     | all commands      | The stage subtitle, after substitution of `%sn%`. |
| %sm%     | all commands      | The stage message, after substitution of `%sn%`. |
| %o%      | all commands      | The name of the player who "owns" the fight (placed the final end crystal). |
| %w%      | all commands      | The name of the world where the fight is taking place (probably always `world_the_end`). |
| %bc%     | all commands      | The integer coordinates - x y z - of the location where the bosses spawn. |
| %bc.%    | all commands      | The floating point coordinates - x y z - of the location where the bosses spawn, to three decimal places, separated by spaces. |
| %bx%     | all commands      | The integer X coordinate of the spawn location of the bosses. |
| %by%     | all commmands     | The integer Y coordinate of the spawn location of the bosses. |
| %bz%     | all commmands     | The integer Z coordinate of the spawn location of the bosses. |
| %bx.%    | all commmands     | The floating point X coordinate (to three decimal places) of the spawn location of the bosses. |
| %by.%    | all commmands     | The floating point Y coordinate (to three decimal places) of the spawn location of the bosses. |
| %bz.%    | all commmands     | The floating point Z coordinate (to three decimal places) of the spawn location of the bosses. |
| %ps%     | `stage-command`   | A comma-separated list of the names all players considered to be in the fight (by proximity). |
| %p%      | `player-commmand` | The name of the one player affected by the player-command. |
| %pc%     | `player-commmand` | The player's coordinates - x y z - as integers, separated by spaces. |
| %pc.%    | `player-commmand` | The player's coordinates - x y z - as floating point numbers to three decimal places, separated by spaces. |
| %px%     | `player-commmand` | The player's X coordinate as an integer. |
| %py%     | `player-commmand` | The player's Y coordinate as an integer. |
| %pz%     | `player-commmand` | The player's Z coordinate as an integer. |
| %px.%    | `player-commmand` | The player's X coordinate as a floating point number to three decimal places. |
| %py.%    | `player-commmand` | The player's Y coordinate as a floating point number to three decimal places. |
| %pz.%    | `player-commmand` | The player's Z coordinate as a floating point number to three decimal places. |

Examples:

 * Imagine you have a CommandHelper command `/link $selector $url $colour $` that formats a clickable link
   to all players matching the selector, with the URL as hover text, a specified colour and the remainder
   of the command line devoted to the text displayed in chat. You configure fight stage 1 to send that
   link to every player in the fight with:
   ```
   /df config 1 player-command runalias link %p% https://www.youtube.com/watch?v=mX7Dq8q6zoQ green Wait What?
   ```

 * Imagine you have a CommandHelper command `/strike-wxyz $world $x $y $z` that strikes harmless lightning
   at the specified x, y, and z coordinates in `$world`. You can use that to show a lightning strike where
   the stage bosses spawn with:
   ```
   /df config 1 stage-command runalias strike-wxyz %w% %bc.%
   ```


### Technical Administrator Commands

For staff with access to the host and the ability to edit the configuration file directly, there is a technical administrator command:

 * `/dragonfight reload` - reload the plugin configuration.


## Permissions

 * `dragonfight.admin` grants permisson to use the `/df` staff command.
 * `dragonfight.console` grants permisson to use the `/dragonfight` command to
   reload the configuration, and is only relevant to staff with console access.
