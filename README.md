# dWorldManager

A Paper plugin that stops items and value leaking out of a persistent world when the another world gets wiped.

It does two independent things:

**Block freezing (grandfathering)** - scans a world (or a region, or one block) for valuable materials and marks them frozen. Frozen blocks can't be broken by anyone until staff explicitly unfreeze them. Every unfreeze is written to an audit log.

**Container clearing** - a container, item frame, armor stand or storage entity gets flagged ("tainted") the moment a player actually puts something into it. `/dwm clearcontainers` empties every tainted one in the world, plus allays and dropped items. Containers nobody ever deposited into - dungeon and village loot chests, for example - are left alone.

Blocks and entities are never removed, only emptied. A chest someone used as storage stays part of their build, it's just empty afterwards.

## Requirements

- Paper (api-version 26.1)
- SQLite works with no setup. MySQL is optional; the driver is downloaded at startup.

## Commands

Base command is `/dworldmanager`, alias `/dwm`.

| Command | What it does |
| --- | --- |
| `/dwm reload` | Reload config.yml |
| `/dwm freeze <world> [--placed-only] [material ...]` | Scan a whole world and freeze matching blocks |
| `/dwm freeze region <world> <x1> <y1> <z1> <x2> <y2> <z2> [--placed-only] [material ...]` | Same, but inside an explicit coordinate box |
| `/dwm freeze block` | Freeze the block you're looking at |
| `/dwm unfreeze <world> [material]` | Unfreeze a whole world, or just one material in it |
| `/dwm unfreeze region <world> <x1> <y1> <z1> <x2> <y2> <z2>` | Unfreeze everything in a box |
| `/dwm unfreeze block` | Unfreeze the block you're looking at |
| `/dwm clearcontainers <world>` | Empty every tainted container/entity + dropped items |
| `/dwm frozen <world> [material]` | Frozen block counts per material, or coordinates for one material |
| `/dwm auditlog [limit]` | Recent staff unfreeze actions (default 10) |
| `/dwm migrate` | Copy SQLite data into MySQL (see Storage) |

Notes:

- If you don't pass any materials, the freeze commands use `blocks.restricted-materials` from the config.
- `--placed-only` freezes only blocks a player placed, leaving naturally generated ones alone - useful for ore. Placement is only tracked for materials that were in `restricted-materials` at the time they were placed, so add ore variants there first if you want this.
- Full-world scans and container clears are bounded by the world border and refuse to run if it's bigger than `max-scan-border-size`. Set a real border on the world first.
- Scans are spread across ticks with a time budget, so they don't lag the server, and report progress every few seconds.

## Permissions

All default to op.

| Permission | Grants |
| --- | --- |
| `dworldmanager.reload` | `/dwm reload` |
| `dworldmanager.freeze` | all `/dwm freeze` variants |
| `dworldmanager.unfreeze` | all `/dwm unfreeze` variants |
| `dworldmanager.clearcontainers` | `/dwm clearcontainers` |
| `dworldmanager.audit` | `/dwm frozen` and `/dwm auditlog` |
| `dworldmanager.migrate` | `/dwm migrate` |

## Config

The important keys:

- `debug` - verbose logging of taint/clear decisions. Noisy, only for diagnosing why something did or didn't get cleared.
- `storage.type` - `sqlite` or `mysql`.
- `storage.table-prefix` - prefix for table names, if you share a MySQL database with other plugins.
- `blocks.restricted-materials` - the material list used by freeze scans and by `--placed-only` placement tracking.
- `blocks.max-scan-border-size` - safety cap on world border size for full-world scans.
- `blocks.scan-tick-budget-ms` - how many ms per tick a scan may use. Higher = faster, laggier. Keep it well under 50.
- `blocks.max-region-volume` - safety cap on `/dwm freeze region` box size.
- `messages` - every player-facing string, with `&` colour codes and `%placeholder%` tokens.

## Storage

SQLite by default, stored in `data.db` in the plugin folder.

To move to MySQL: fill in the `storage.mysql` section, set `storage.type: mysql`, restart, then run `/dwm migrate`. Migration reads the old `data.db` and copies frozen blocks, placement records and the audit log into MySQL. It runs async and never deletes the SQLite file, so it's safe to run and safe to re-run.

## License

Free to use. No support promised - open an issue if something's broken and I'll look when I can.
