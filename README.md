# ExplosionProtector for CoreProtect

**Protect player builds from explosions — one JAR for Paper/Folia/Purpur/Spigot 1.19–26.2**

Lightweight plugin that keeps player-placed blocks safe from TNT, creepers, crystals, beds/anchors, etc., while natural terrain still breaks normally.

---

## Description

ExplosionProtector checks each block in an explosion:

- **Player-placed blocks** are protected (local tracker + CoreProtect).
- **Natural / unknown blocks** are destroyed as usual (`protect-first-unknown: false` by default).
- **Whitelist (`always-explode-blocks`)** — listed materials **always** explode, even if a player placed them (useful for TNT, sand, redstone, traps, etc.).
- **TNT chains** can still break other TNT when `tnt-chain-breaks-only-tnt` is enabled.

Ideal for PvE, creative, minigames, and maps where builds must survive accidental blasts.

---

## Installation

1. Download `ExplosionProtector.jar` (universal build).
2. Put it in `plugins/`.
3. Install **CoreProtect 24.0+** (softdepend; required if `require-coreprotect: true`).
4. Start the server. Console should show:
   ```
   [ExplosionProtector] Plugin enabled: protecting player-placed blocks from explosions.
   ```

**Supported servers:** Paper / Folia / Purpur / Spigot — Minecraft **1.19.x, 1.20.x, 1.21.x, 26.1.x, 26.2**  
**Java:** 17+ (use the JVM required by your server; 26.x usually needs Java 21/25)

---

## Configuration

File: `plugins/ExplosionProtector/config.yml`

### Important options

```yaml
language: en   # en, ru, es, zh, hi, ar, fr, de, ja, pt
plugin-active: true

# false = natural/unknown blocks break (recommended)
# true  = protect unknowns until CoreProtect answers (safer builds, weaker TNT vs terrain)
protect-first-unknown: false
require-coreprotect: true

protect-player-placed-blocks: true
enable-local-tracker: true
persist-player-placed-blocks: true

tnt-chain-breaks-only-tnt: true
protect-hanging-from-explosions: true
protect-item-frames: true
protect-paintings: true
block-enderman-grief: true

# Always destroy these materials in explosions, even if player-placed.
always-explode-blocks:
  - TNT
  # - SAND
  # - GRAVEL
  # - REDSTONE_WIRE

enabled-worlds: []
disabled-worlds: []
debug: false
```

After editing config, run `/ep reload`.

### Message files

On first run the plugin extracts `messages.yml` and `messages_<lang>.yml` for:  
`en`, `ru`, `es`, `zh`, `hi`, `ar`, `fr`, `de`, `ja`, `pt`.

---

## Commands

| Command | Permission | Description |
|---------|------------|-------------|
| `/ep status` / `/ep info` | `explosionprotector.info` | Status, tracker size, CoreProtect queue health |
| `/ep language <code>` | `explosionprotector.info` | Switch language |
| `/ep reload` | `explosionprotector.reload` | Reload config |
| `/ep toggle` | `explosionprotector.toggle` | Enable/disable protection at runtime |
| `/ep save` | `explosionprotector.reload` | Force-save tracked blocks |
| `/ep cacheclear` | `explosionprotector.reload` | Clear placement cache |

Aliases: `/explosionprot`

---

## How protection works

```text
Explosion
  → always-explode-blocks?  → destroy
  → local tracker hit?      → protect
  → placement cache hit?    → protect/deny
  → else                    → protect-first-unknown + async CoreProtect learn
```

Player placements are recorded on `BlockPlaceEvent`. CoreProtect is used as async fallback for older builds.

---

## Change Log

### [3.1]
- Confirm support for Minecraft **26.2** on Paper / Folia / Purpur / Spigot
- Keep one universal JAR (`api-version: 1.19`) for 1.19–26.2

### [3.0]
- Universal single JAR for 1.19–26.1 (Folia-supported)
- CoreProtect 24.0 API
- `protect-first-unknown` default `false` (terrain explodes correctly)
- **`always-explode-blocks` whitelist** — materials that always break in explosions
- Async CoreProtect queue, SQLite tracker, Folia-safe schedulers

### [1.1] – 2025-04-27
- CoreProtect lookup cache, multi-language, TNT chain handling, unified explode handlers

### [1.0] – Initial Release
- Basic protection via CoreProtect API

---

## Dependencies

- Paper / Folia / Purpur / Spigot **1.19+** (up to **26.2**)
- **CoreProtect** 24.0 recommended (older versions often work via softdepend)

---

## License

MIT License. See `LICENSE` in the repository.
