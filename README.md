# DragonFight
A Bukkit plugin providing a custom dragon boss fight.


## Overview

The DragonFight plugin implements an 11 stage boss fight to defeat the ender
dragon.

In the first 10 stages, a randomly selected end crystal is converted into one
or more boss mobs in the circle between the obsidian pillars. All the other end
crystals are invulnerable. When the player defeats the current stage boss mobs,
the next crystal is turned into boss mobs.

Since the other crystals are invulnerable and heal the dragon, it's very
unlikely that the dragon can be killed until all bosses of all 10 stages are
defeated.

In the 11th and final stage of the fight, the player must defeat the dragon.

The DragonFight plugin relies on new mob properties added in BeastMaster
v2.13.0. At the time of writing, those properties are described in the
[release notes for that version of BeastMaster](https://github.com/NerdNu/BeastMaster/releases/tag/v2.13.0), 
but are not yet documented on the BeastMaster wiki.


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

 * `attack-damage` 50.0
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

 * go more than 100 blocks from the centre of the world,
 * have not taken any damage in the last 60 seconds, in which case they've
   probably teleported underground,
 * go below Y0, in which case they are about to fall out of the world, or
 * if the mob is about to use a portal.


## Commands

There is currently one administrative command:

 * `/df stop` - stop the current dragon fight, removing all mobs and projectiles.

There is also a technical administrator command:

 * `/dragonfight reload` - reload the plugin configuration.


## Permissions

 * `dragonfight.admin` grants permisson to use the `/df` staff command.
 * `dragonfight.console` grants permisson to use the `/dragonfight` command to
   reload the configuration, and is only relevant to staff with console access.

