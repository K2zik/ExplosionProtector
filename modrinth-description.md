Protect player builds from unwanted explosions — while natural terrain still breaks normally.

ExplosionProtector is a lightweight Paper/Folia plugin that works with **CoreProtect** to stop TNT, creepers, end crystals, beds, respawn anchors, and similar blasts from destroying blocks placed by players.

## Features

The plugin focuses on keeping builds safe without freezing the whole world from explosions.

- Protects **player-placed blocks** using a local tracker + CoreProtect lookups
- Lets **natural / unknown blocks** explode as usual (recommended default)
- **`always-explode-blocks` whitelist** — listed materials always break, even if a player placed them (e.g. `TNT`, `SAND`, `GRAVEL`)
- Optional TNT chain behavior so TNT can still break other TNT
- Protect hanging entities (item frames / paintings) from explosion damage
- Optional Enderman grief blocking
- Async CoreProtect queue with caching for better performance on large blasts
- Persistent local tracker (SQLite)
- Multi-language messages (`en`, `ru`, `es`, `zh`, `hi`, `ar`, `fr`, `de`, `ja`, `pt`)
- **One universal JAR** for Paper / Folia / Purpur / Spigot **1.19.x – 26.2**

Ideal for PvE, Creative, minigames, and adventure maps where builds must survive accidental or grief explosions.

## Installation

Setup takes only a few minutes.

1. Download `ExplosionProtector.jar`
2. Put it in your `plugins/` folder
3. Install **CoreProtect 24.0+** (recommended)
4. Start the server

You should see:

```
[ExplosionProtector] Plugin enabled: protecting player-placed blocks from explosions.
```

## Configuration

All options live in `plugins/ExplosionProtector/config.yml`.

Key options:

```yaml
protect-first-unknown: false
require-coreprotect: true
protect-player-placed-blocks: true

always-explode-blocks:
  - TNT
  # - SAND
  # - GRAVEL
  # - REDSTONE_WIRE

tnt-chain-breaks-only-tnt: true
protect-hanging-from-explosions: true
```

Reload with `/ep reload` after changes.

## Commands

Use `/ep` (alias `/explosionprot`) for admin controls. Default permissions are OP-only.

| Command | Description |
|---------|-------------|
| `/ep status` / `/ep info` | Plugin status and protection stats |
| `/ep language <code>` | Switch language |
| `/ep reload` | Reload config |
| `/ep toggle` | Enable/disable protection at runtime |
| `/ep save` | Force-save tracked blocks |
| `/ep cacheclear` | Clear placement cache |

Permissions: `explosionprotector.info`, `explosionprotector.reload`, `explosionprotector.toggle`

## Dependencies

Required and recommended software for this project:

- **Paper / Folia / Purpur / Spigot** — Minecraft **1.19+** (up to **26.2**)
- **CoreProtect** — **24.0+** recommended (`softdepend`; required if `require-coreprotect: true`)
