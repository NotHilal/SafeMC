# SafePlots

A small server-side NeoForge mod for Minecraft **26.3**. Admins define plots of land, link a sign to each one,
and players claim a plot by right-clicking its sign. Only the owner and the players they trust can change
anything inside it.

Workflow: **admin selects land → admin creates the plot → admin links a sign → player right-clicks the sign →
the plot is theirs → outsiders can't build, steal, explode, burn or flood it.**

## Requirements

| What | Version |
|---|---|
| Minecraft (server) | 26.3 |
| NeoForge | 26.3.0.35-beta or newer 26.3 build |
| Java | 25 |

There are no other dependencies, and no database.

**Players don't need to install anything.** The mod only runs on the server. If you also add content mods
(new blocks, items or mobs), players need *those* mods as usual, but not SafePlots.

## Building

```sh
./gradlew build          # Windows: gradlew.bat build
```

The jar is written to `build/libs/safeplots-26.3-1.0.0.jar`. The first build downloads Minecraft and NeoForge,
which takes a few minutes.

## Installing

1. Install a NeoForge 26.3 server ([neoforged.net](https://neoforged.net)).
2. Copy `safeplots-26.3-1.0.0.jar` into the server's `mods/` folder.
3. Start the server. Plot data is stored in `<world>/safeplots.json`.

## Commands

Admin means operator permission level 2 or higher (the default `/op` level is 4).

### Player commands

| Command | What it does |
|---|---|
| `/plot trust <player> [plot]` | Lets a player build in your plot |
| `/plot untrust <player> [plot]` | Removes that access |
| `/plot abandon [plot]` | Gives up your plot. The buildings stay exactly as they are |
| `/plot movesign [plot]` | Moves your plot's claim sign: place a sign inside your plot, or right-click one that's already there. The old sign is blanked |
| `/plot cancel` | Stops moving (or, for admins, linking) a sign |
| `/ppt help` (or just `/ppt`) | Lists every command you can use; admins also see the admin and moderation commands. Click a line to type it |
| `/mvtp <world>` | Teleports you to another world (see [Extra worlds](#extra-worlds-mv-mvtp)) |
| `/plot showlimits [plot]` | Outlines the plot's borders with glowing particles (only you see them). Run it again to hide them. Admins can show any plot |

Without `[plot]`, these use the plot you are standing in. `trust`, `untrust`, `movesign` and `showlimits` also fall back
to your only plot if you have exactly one; if you have several, name the one you mean.

### Admin commands

| Command | What it does |
|---|---|
| `/plot wand` | Gives you the Plot Wand (a red candle): left-click a block for position 1, right-click for position 2 |
| `/plot pos1`, `/plot pos2` | Sets a corner at the block you're looking at, or where you stand |
| `/plot create <name>` | Creates a plot exactly between the two corners you selected (X, Y and Z) |
| `/plot create <name> full` | Same X/Z, but from bedrock to build limit |
| `/plot create <name> height <minY> [maxY]` | Same X/Z, with your own height range (no maxY = up to the build limit) |
| `/plot setheight <name> <minY> [maxY]` | Changes only the height of an existing plot |
| `/plot redefine <name> [full \| height <minY> [maxY]]` | Moves an existing plot to your current selection. Owner, trusted players and sign stay |
| `/plot sign <name>` | The next sign you place or right-click is linked to the plot. Regular and hanging signs both work |
| `/plot delete <name>` | Deletes the plot. Blocks are not changed |
| `/plot setowner <name> <player>` | Gives the plot to a player, ignoring their claim limit |
| `/plot removeowner <name>` | Makes the plot available again |
| `/plot setlimit <player> <number>` | Sets how many plots a player may own |
| `/plot addlimit <player> <amount>` | Adds to (or with a negative number, removes from) that limit |
| `/plot list` | Lists all plots |
| `/plot list <player>` | Lists a player's plots and their limit |
| `/plot info [name]` | Shows the name, status, owner, area, dimension, trusted players and sign. Without a name, shows the plot you're in |
| `/plot bypass` | Toggles protection bypass for yourself |

Plot names may use letters, numbers, `_` and `-`.

## Creating a plot (admins)

1. Run `/plot wand`, then left-click one corner block and right-click the opposite corner block.
   (Or look at each corner and use `/plot pos1` and `/plot pos2`.)
2. Check the chat for "Position 1 set to ..." and "Position 2 set to ...".
3. Run `/plot create house_01`. If it overlaps an existing plot, you'll see
   `Cannot create plot: this region overlaps with house_02.`
4. Run `/plot sign house_01`, then place a sign (regular or hanging, inside or outside the plot) or right-click
   one that's already there. It now reads **AVAILABLE / Right Click / to Claim**.
   If you change your mind, `/plot cancel` exits linking mode.

### Plot height

By default a plot is exactly the box between your two corners, including their heights. Select one
corner low (for example a few blocks underground) and the other high (the top of what should be
protected). Everything outside that box, like a mine underneath, stays open to everyone.

```
/plot create house_01                  # exactly the box between your two corners
/plot create house_01 full             # same X/Z, from bedrock to build limit
/plot create house_01 height 50        # same X/Z, from Y 50 up to the build limit
/plot create house_01 height 50 150    # same X/Z, from Y 50 to Y 150
```

Note: anyone can build above the top of a plot, so make it tall enough (or use `full` / `height`).

Existing plots can be changed at any time; this never changes any blocks:

```
/plot setheight house_01 50            # new floor, keeps X/Z
/plot redefine house_01                # new box from your current selection
```

Changes that would overlap another plot are refused. Use `/plot info <name>` to see a plot's area.

To replace a sign, run `/plot sign <name>` again and click the new sign. The old one is blanked.

## Claiming a plot (players)

Right-click the plot's sign. The mod checks, in order:

1. whether the plot is already claimed (`This plot is already claimed by Steve.`),
2. how many plots you already own compared to your limit
   (`You already own 1/1 plots. Ask an admin for another claim slot.`).

If both checks pass, you get `✓ Plot claimed: house_01` and the sign changes to **YourName's / property**.

The owner can change the sign's text color by right-clicking it with any dye (one dye is used up). The color is
saved with the plot and stays when the sign is moved or replaced.

## Claim limits

Each player has a single number, `maxClaims`, which starts at **1**. There's no upper limit.

- `/plot setlimit Steve 3` lets Steve own up to 3 plots.
- `/plot addlimit Steve 1` raises Steve's current limit by one.

Lowering a limit doesn't take away plots a player already owns; they just can't claim more.
`/plot setowner` ignores limits.

## What is protected

Inside a plot, only the **owner**, **trusted players** and **admins with `/plot bypass` on** can change things.
**Unclaimed plots are protected too**, so nobody can loot or grief a plot before it's claimed.

Blocked for everyone else:

- breaking and placing blocks, and using any block (chests, barrels, shulker boxes, furnaces, doors, buttons, ...)
- buckets (water, lava, powder snow), flint and steel, fire charges, bonemeal, trampling farmland
- attacking or using item frames, armor stands, paintings, animals, villagers and minecarts, including with arrows
- burning arrows lighting TNT or other blocks
- explosions (TNT, TNT minecarts, creepers, beds, respawn anchors, end crystals, withers, ...): the explosion
  still happens, but no block inside any plot is destroyed and no protected entity inside is hurt
- pistons pushing or pulling blocks across the plot border
- hoppers and hopper minecarts pulling items out of a plot from outside it
- water and lava flowing into a plot from outside, including lava turning water into stone
- dispensers outside a plot firing into it
- fire destroying blocks in a plot
- hostile mobs griefing near plots (endermen taking blocks, ravagers, withers, zombies breaking doors)
- breaking a claim sign (admins with bypass only)

### Pets (everywhere, not only in plots)

Tamed pets (wolves, cats, parrots, horses, donkeys, mules, llamas, camels, ...) can only be hurt, ridden,
leashed, fed or opened by their owner, or by an admin with `/plot bypass`. This covers melee, arrows and
other projectiles, TNT someone lit, and another player's pet attacking them. Mobs, fall damage, lava and
other environmental damage still hurt pets normally. A pet's owner can always interact with their own pet,
even inside someone else's plot.

### Graves

When a player dies, everything they drop (inventory, armor, offhand) and their dropped XP go into a
**grave**: their own player head, placed where they died (or the first free spot just above). Nothing is
left on the ground for others to take.

- **Only the player who died can open it.** Right-click or break the head to get everything back; armor
  goes straight back on if the slot is free, and anything that doesn't fit is dropped at your feet where
  only you can pick it up.
- Other players get "This is Steve's grave." Explosions, pistons, water and lava can't destroy it.
- The items are stored in `<world>/safeplots-graves.dat`, not in the head, so they survive restarts and
  even the head being removed. Graves don't expire.
- With the `keepInventory` gamerule on, no grave is created (nothing drops).

| Command | Who | What it does |
|---|---|---|
| `/grave list` | everyone | Lists your graves and where they are |
| `/grave list <player>` | admins | Lists another player's graves |
| `/grave restore <id>` | admins | Gives a grave's items straight to its (online) owner, e.g. after a death in the void or somewhere unreachable |

### Homes and teleport requests

| Command | What it does |
|---|---|
| `/sethome` | Saves your current spot as your home. Everyone has **one** home; running it again moves it. Works anywhere |
| `/home` | Teleports to your home |
| `/delhome` | Deletes your home |
| `/tpa <player>` | Asks to teleport to a player; they get clickable [Accept] [Deny] buttons |
| `/tpahere <player>` | Asks a player to teleport to you |
| `/tpaccept [player]`, `/tpdeny [player]` | Answers the newest request (or the one from that player). Requests expire after 60 s |

Every teleport has a **3 second warm-up** that is cancelled if you move or take damage (so it can't be
used to escape a fight or a creeper), then a **30 second cooldown**. Admins teleport instantly. Frozen
players can't teleport. Homes are stored in `<world>/safeplots-homes.json`.

### Moderation tools

All require op level 2 or higher. Durations look like `30m`, `2h`, `7d`, `1w` or `1d12h`.

| Command | What it does |
|---|---|
| `/vanish` | Toggles invisibility for yourself: non-admins can't see you and you disappear from their tab list (they see "left the game"). Other admins still see you. Ends when you log out |
| `/freeze <player>` | The player can't move, build, interact, fight or use commands (except `/msg`, to talk to staff) and can't be hurt. Stays frozen after relogging or a restart |
| `/unfreeze <player>` | Unfreezes them |
| `/mute <player> [duration\|perm] [reason]` | Blocks chat and private messages. No duration = permanent. Survives restarts |
| `/unmute <player>` | Lifts a mute |
| `/tempban <player> <duration> [reason]` | A normal vanilla ban that ends automatically; kicks them if online. Lift it early with `/pardon <player>` |
| `/setjail` | Sets the jail spawn to where you stand (position and facing) |
| `/jail <player> <duration> [reason]` | Teleports the player to the jail. When the time is up they go back to where they were. Jailing them again changes the time |
| `/unjail <player>` | Releases a player early and sends them back |

While jailed, a player can't break or place blocks or use `/home` or `/tpa`, and is pulled back to the jail
spawn if they get more than 16 blocks away (ender pearls, dying, changing dimension, ...). Jail time keeps
running while they are offline; a player jailed while offline is sent to jail when they join.

Mutes, freezes and jail are stored in `<world>/safeplots-moderation.json`; temp bans in the vanilla
`banned-players.json`. All of these work for offline players who have joined before.

Liquids, pistons, hoppers and dispensers inside a plot work normally, and they also work between two plots
that have the same owner.

Protection doesn't depend on the sign. If the sign is destroyed, the plot stays claimed and protected. If a
sign is placed at the same spot again, it immediately becomes the claim sign again.

### No-mob-spawn zones

Admins can mark areas (spawn, roads, a town, ...) where hostile mobs never spawn by themselves. Select the
area with the plot wand (or `/plot pos1` / `/plot pos2`), then:

| Command | What it does |
|---|---|
| `/nomobspawn create <name>` | Hostile mobs no longer spawn in the selected X/Z area, at any height |
| `/nomobspawn delete <name>` | Removes the zone |
| `/nomobspawn list` | Lists all zones (also just `/nomobspawn`) |
| `/nomobspawn here` | Tells you which zone you are standing in |

Natural spawns, spawners, trial spawners, patrols, phantoms and structure spawns are blocked. Spawn eggs,
`/summon`, villagers turning into witches etc. still work, animals spawn normally, and hostile mobs can still
walk in from outside. Mobs that were already there stay until killed. Zones are stored in
`<world>/safeplots-nomobspawn.json`.

### Extra worlds (/mv, /mvtp)

Admins can add worlds while the server runs, like Multiverse. Each one is a normal server dimension
(`safeplots:<name>`) built from vanilla dimension types, so players join with a plain vanilla client.

| Command | What it does |
|---|---|
| `/mv create <name> <type> [seed]` | Creates and loads a world. Types: `normal`, `amplified`, `large_biomes`, `flat`, `void`, `nether`, `end`. No seed = random; a word is hashed like on the vanilla create-world screen |
| `/mv list` | Lists the worlds (also just `/mv`) |
| `/mv setspawn` | Sets the spawn of the world you're in |
| `/mv delete <name> confirm` | Unloads the world and deletes its folder. Players inside are sent to the main spawn |
| `/mvtp <world>` | **Everyone**: goes to a world's spawn, with the usual 3 s warm-up and cooldown. `world`, `nether` and `end` are the vanilla dimensions |
| `/mvtp <world> <player>` | Admin: sends a player there instantly |

The first visit picks a safe spot near 0,0 as the spawn (a void world gets a small stone platform). Worlds
are listed in `<world>/safeplots-worlds.json` and stored in `<world>/dimensions/safeplots/<name>/`; they load at
startup before anyone can join. Plots, homes, graves and no-spawn zones work in them like anywhere else.

- Time and weather follow the main world.
- Nether/End portals inside an extra world lead to the main Nether/End.
- A player who was offline inside a world when it was deleted logs back in at the same coordinates in
  the main world.

### Deliberate simplifications (V1)

- **Fire and explosions never destroy blocks in any plot**, including your own TNT or a fire in your own fireplace.
- **Outsiders can't interact with any non-hostile entity inside a plot**, so that includes villager trading
  and riding horses.
- `/plot bypass` is off by default for every admin and resets after a restart, so admins can't grief by
  accident.
- A claim sign in an unloaded chunk is redrawn the next time someone clicks it.

### Known gaps

- Trees growing from saplings planted just outside a plot can grow leaves into it.
- Machines from other mods are blocked inside plots if they act as a "fake player", but a mod that edits
  blocks directly (without events) can bypass protection. Test any tech mods you add.

## Edge cases

| Case | Behaviour |
|---|---|
| Username change | Plots, trust and limits are stored by UUID. Names are only cached for display and update on login |
| Offline players | Commands accept any player who has joined the server before |
| Server restart | Everything is saved to `safeplots.json` right after each change (temp file, then an atomic move) |
| Corrupt `safeplots.json` | The server refuses to start instead of running with no protection. Fix or restore the file |
| Two players clicking one sign at the same time | Clicks are handled one after another on the server thread; the second player sees "already claimed" |
| Plot deleted | Blocks stay; the sign is blanked |
| Owner removed or plot abandoned | Trusted players are cleared, the sign shows AVAILABLE again, buildings stay |
| Standing on a boundary | The edge blocks (both corners) are part of the plot |
| Explosion partly inside a plot | Only the blocks outside the plot are destroyed |

## Data format

`<world>/safeplots.json`:

```json
{
  "plots": {
    "house_01": {
      "dimension": "minecraft:overworld",
      "min": [100, -64, 200],
      "max": [130, 319, 230],
      "owner": "8667ba71-b85a-4004-af54-457a9734eed7",
      "trusted": [],
      "sign": [95, -60, 215]
    }
  },
  "players": {
    "8667ba71-b85a-4004-af54-457a9734eed7": { "name": "Steve", "maxClaims": 1 }
  }
}
```

Edit it only while the server is stopped.

## Development

The `src/selftest` source set is a dev-only mod that runs an end-to-end check of claiming, commands and all
protections with simulated players. It isn't included in the release jar. Enable RCON in
`run/server.properties`, then:

```
./gradlew runServer
/selftest setup      # immediate checks, then places the tick-based scenarios
/selftest check      # ~15 s later: liquids, pistons, hoppers, fire, dispenser, abandon/setowner/delete
/selftest persist    # after a restart: data survived
/selftest worlds     # creates test worlds; restart, run again: /mv and /mvtp checks
```
