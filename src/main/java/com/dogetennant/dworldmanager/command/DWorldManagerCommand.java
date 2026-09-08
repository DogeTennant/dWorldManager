package com.dogetennant.dworldmanager.command;

import com.dogetennant.dworldmanager.DWorldManager;
import com.dogetennant.dworldmanager.block.BlockFreezeService;
import com.dogetennant.dworldmanager.block.FrozenBlock;
import com.dogetennant.dworldmanager.block.UnfreezeLogEntry;
import com.dogetennant.dworldmanager.container.ClearStats;
import com.dogetennant.dworldmanager.container.ContainerClearService;
import com.dogetennant.dworldmanager.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

public class DWorldManagerCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("reload", "migrate", "freeze", "unfreeze", "clearcontainers", "frozen", "auditlog");

    private static final String PLACED_ONLY_FLAG = "--placed-only";

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final DWorldManager plugin;

    public DWorldManagerCommand(DWorldManager plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                if (!requirePermission(sender, "dworldmanager.reload")) return true;
                plugin.getConfigManager().load();
                sender.sendMessage(Msg.of(plugin, "reload-success", "&adWorldManager config reloaded."));
            }
            case "migrate" -> {
                if (!requirePermission(sender, "dworldmanager.migrate")) return true;
                plugin.getMigrationManager().migrate(sender);
            }
            case "freeze" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("region")) {
                    handleFreezeRegion(sender, args);
                } else if (args.length >= 2 && args[1].equalsIgnoreCase("block")) {
                    handleFreezeBlock(sender);
                } else {
                    handleFreeze(sender, args);
                }
            }
            case "unfreeze" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("region")) {
                    handleUnfreezeRegion(sender, args);
                } else if (args.length >= 2 && args[1].equalsIgnoreCase("block")) {
                    handleUnfreezeBlock(sender);
                } else {
                    handleUnfreeze(sender, args);
                }
            }
            case "clearcontainers" -> handleClearContainers(sender, args);
            case "frozen" -> handleFrozenView(sender, args);
            case "auditlog" -> handleAuditLog(sender, args);
            default -> sender.sendMessage(Msg.of(plugin, "unknown-subcommand",
                    "&cUnknown subcommand '%input%'. Run /dwm for a list.", "input", args[0]));
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("dWorldManager " + plugin.getPluginMeta().getVersion(),
                NamedTextColor.GOLD, TextDecoration.BOLD));
        sendHelpLine(sender, "/dwm reload", "Reload the config");
        sendHelpLine(sender, "/dwm migrate", "Run SQLite -> MySQL data migration");
        sendHelpLine(sender, "/dwm freeze <world> [--placed-only] [material ...]", "Grandfather restricted blocks in a world");
        sendHelpLine(sender, "/dwm freeze region <world> <x1> <y1> <z1> <x2> <y2> <z2> [--placed-only] [material ...]", "Grandfather restricted blocks in a region");
        sendHelpLine(sender, "/dwm freeze block", "Grandfather the block you're looking at");
        sendHelpLine(sender, "/dwm unfreeze <world> [material]", "Unfreeze blocks in a world, optionally by material");
        sendHelpLine(sender, "/dwm unfreeze region <world> <x1> <y1> <z1> <x2> <y2> <z2>", "Unfreeze blocks in a region");
        sendHelpLine(sender, "/dwm unfreeze block", "Unfreeze the block you're looking at");
        sendHelpLine(sender, "/dwm clearcontainers <world>", "Clear tainted containers/entities and dropped items");
        sendHelpLine(sender, "/dwm frozen <world> [material]", "Show frozen block counts, or coordinates for one material");
        sendHelpLine(sender, "/dwm auditlog [limit]", "Show recent staff unfreeze actions");
    }

    private void sendHelpLine(CommandSender sender, String usage, String description) {
        sender.sendMessage(Component.text(usage, NamedTextColor.AQUA)
                .append(Component.text(" - " + description, NamedTextColor.GRAY)));
    }

    private boolean requirePermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        sender.sendMessage(Msg.of(plugin, "no-permission", "&cYou don't have permission to do that."));
        return false;
    }

    private Set<Material> parseMaterialsOrDefault(CommandSender sender, String[] args, int startIndex) {
        if (args.length > startIndex) {
            Set<Material> materials = new HashSet<>();
            for (int i = startIndex; i < args.length; i++) {
                Material material = Material.matchMaterial(args[i]);
                if (material == null) {
                    sender.sendMessage(Msg.of(plugin, "unknown-material", "&cUnknown material: %material%", "material", args[i]));
                    return null;
                }
                materials.add(material);
            }
            return materials;
        }
        return plugin.getConfigManager().getRestrictedMaterials();
    }

    private int[] parseCoords(CommandSender sender, String[] args, int startIndex) {
        try {
            int[] result = new int[6];
            for (int i = 0; i < 6; i++) {
                result[i] = Integer.parseInt(args[startIndex + i]);
            }
            return result;
        } catch (NumberFormatException e) {
            sender.sendMessage(Msg.raw("&cCoordinates must be whole numbers."));
            return null;
        }
    }

    private int percent(int part, int total) {
        return total == 0 ? 100 : (int) ((part * 100L) / total);
    }

    private boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (arg.equalsIgnoreCase(flag)) return true;
        }
        return false;
    }

    private String[] withoutFlag(String[] args, String flag) {
        return Arrays.stream(args).filter(a -> !a.equalsIgnoreCase(flag)).toArray(String[]::new);
    }

    //
    // Freeze
    //

    // /dwm freeze <world> [--placed-only] [material ...]
    private void handleFreeze(CommandSender sender, String[] args) {
        if (!requirePermission(sender, "dworldmanager.freeze")) return;
        if (args.length < 2) {
            sender.sendMessage(Msg.raw("&cUsage: /dwm freeze <world> [--placed-only] [material ...]"));
            return;
        }

        World world = plugin.getServer().getWorld(args[1]);
        if (world == null) {
            sender.sendMessage(Msg.of(plugin, "unknown-world", "&cUnknown world: %world%", "world", args[1]));
            return;
        }

        boolean placedOnly = hasFlag(args, PLACED_ONLY_FLAG);
        String[] materialArgs = withoutFlag(args, PLACED_ONLY_FLAG);

        Set<Material> materials = parseMaterialsOrDefault(sender, materialArgs, 2);
        if (materials == null) return;

        String scope = placedOnly ? " (player-placed only)" : "";
        sender.sendMessage(Msg.of(plugin, "freeze-scanning",
                "&eScanning '%world%' for %count% material(s) to freeze%scope% - this may take a while, progress will be reported periodically...",
                "world", world.getName(), "count", String.valueOf(materials.size()), "scope", scope));

        plugin.getBlockFreezeService().freezeAllInWorld(world, materials, placedOnly, new BlockFreezeService.ScanCallback() {
            @Override
            public void onComplete(int frozen) {
                sender.sendMessage(Msg.of(plugin, "freeze-complete",
                        "&aFreeze scan complete: %count% block(s) grandfathered in '%world%'%scope%.",
                        "count", String.valueOf(frozen), "world", world.getName(), "scope", scope));
            }

            @Override
            public void onError(String message) {
                sender.sendMessage(Msg.raw(message));
            }

            @Override
            public void onProgress(int scanned, int total) {
                sender.sendMessage(Msg.of(plugin, "freeze-progress",
                        "&eFreeze scan: %scanned% / %total% chunks (%percent%%)",
                        "scanned", String.valueOf(scanned), "total", String.valueOf(total),
                        "percent", String.valueOf(percent(scanned, total))));
            }
        });
    }

    // /dwm freeze region <world> <x1> <y1> <z1> <x2> <y2> <z2> [--placed-only] [material ...]
    private void handleFreezeRegion(CommandSender sender, String[] args) {
        if (!requirePermission(sender, "dworldmanager.freeze")) return;
        if (args.length < 9) {
            sender.sendMessage(Msg.raw("&cUsage: /dwm freeze region <world> <x1> <y1> <z1> <x2> <y2> <z2> [--placed-only] [material ...]"));
            return;
        }

        World world = plugin.getServer().getWorld(args[2]);
        if (world == null) {
            sender.sendMessage(Msg.of(plugin, "unknown-world", "&cUnknown world: %world%", "world", args[2]));
            return;
        }

        int[] c = parseCoords(sender, args, 3);
        if (c == null) return;

        boolean placedOnly = hasFlag(args, PLACED_ONLY_FLAG);
        String[] materialArgs = withoutFlag(args, PLACED_ONLY_FLAG);

        Set<Material> materials = parseMaterialsOrDefault(sender, materialArgs, 9);
        if (materials == null) return;

        String scope = placedOnly ? " (player-placed only)" : "";

        plugin.getBlockFreezeService().freezeRegion(world, c[0], c[1], c[2], c[3], c[4], c[5], materials, placedOnly,
                new BlockFreezeService.ScanCallback() {
                    @Override
                    public void onComplete(int frozen) {
                        sender.sendMessage(Msg.of(plugin, "freeze-region-complete",
                                "&aFreeze region complete: %count% block(s) grandfathered in '%world%'%scope%.",
                                "count", String.valueOf(frozen), "world", world.getName(), "scope", scope));
                    }

                    @Override
                    public void onError(String message) {
                        sender.sendMessage(Msg.raw(message));
                    }
                });
    }

    // /dwm freeze block
    private void handleFreezeBlock(CommandSender sender) {
        if (!requirePermission(sender, "dworldmanager.freeze")) return;
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Msg.of(plugin, "player-only", "&cThis command can only be run by a player."));
            return;
        }

        Block target = player.getTargetBlockExact(10);
        if (target == null || target.getType().isAir()) {
            sender.sendMessage(Msg.of(plugin, "no-target-block", "&cYou're not looking at a block."));
            return;
        }

        if (plugin.getBlockFreezeService().isFrozen(target)) {
            sender.sendMessage(Msg.of(plugin, "freeze-block-already",
                    "&eThat block (%material% at %x%,%y%,%z%) is already frozen.",
                    blockTokens(target)));
            return;
        }

        plugin.getBlockFreezeService().freezeSingleBlock(target);
        sender.sendMessage(Msg.of(plugin, "freeze-block-success",
                "&a%material% at %x%,%y%,%z% is now frozen.", blockTokens(target)));
    }

    //
    // Unfreeze
    //

    // /dwm unfreeze <world> [material]
    private void handleUnfreeze(CommandSender sender, String[] args) {
        if (!requirePermission(sender, "dworldmanager.unfreeze")) return;
        if (args.length < 2) {
            sender.sendMessage(Msg.raw("&cUsage: /dwm unfreeze <world> [material]"));
            return;
        }

        World world = plugin.getServer().getWorld(args[1]);
        if (world == null) {
            sender.sendMessage(Msg.of(plugin, "unknown-world", "&cUnknown world: %world%", "world", args[1]));
            return;
        }

        UUID staffUuid = sender instanceof Player player ? player.getUniqueId() : BlockFreezeService.CONSOLE_UUID;
        String staffName = sender.getName();

        if (args.length > 2) {
            Material material = Material.matchMaterial(args[2]);
            if (material == null) {
                sender.sendMessage(Msg.of(plugin, "unknown-material", "&cUnknown material: %material%", "material", args[2]));
                return;
            }
            int count = plugin.getBlockFreezeService().unfreezeMaterialInWorld(world, material, staffUuid, staffName);
            sender.sendMessage(Msg.of(plugin, "unfreeze-material-success",
                    "&a%count% block(s) of %material% unfrozen in '%world%'.",
                    "count", String.valueOf(count), "material", material.name(), "world", world.getName()));
        } else {
            int count = plugin.getBlockFreezeService().unfreezeAllInWorld(world, staffUuid, staffName);
            sender.sendMessage(Msg.of(plugin, "unfreeze-world-success",
                    "&a%count% block(s) unfrozen in '%world%'.",
                    "count", String.valueOf(count), "world", world.getName()));
        }
    }

    // /dwm unfreeze region <world> <x1> <y1> <z1> <x2> <y2> <z2>
    private void handleUnfreezeRegion(CommandSender sender, String[] args) {
        if (!requirePermission(sender, "dworldmanager.unfreeze")) return;
        if (args.length < 9) {
            sender.sendMessage(Msg.raw("&cUsage: /dwm unfreeze region <world> <x1> <y1> <z1> <x2> <y2> <z2>"));
            return;
        }

        World world = plugin.getServer().getWorld(args[2]);
        if (world == null) {
            sender.sendMessage(Msg.of(plugin, "unknown-world", "&cUnknown world: %world%", "world", args[2]));
            return;
        }

        int[] c = parseCoords(sender, args, 3);
        if (c == null) return;

        UUID staffUuid = sender instanceof Player player ? player.getUniqueId() : BlockFreezeService.CONSOLE_UUID;
        String staffName = sender.getName();

        int count = plugin.getBlockFreezeService().unfreezeRegion(world, c[0], c[1], c[2], c[3], c[4], c[5], staffUuid, staffName);
        sender.sendMessage(Msg.of(plugin, "unfreeze-region-success",
                "&a%count% block(s) unfrozen in the selected region in '%world%'.",
                "count", String.valueOf(count), "world", world.getName()));
    }

    // /dwm unfreeze block
    private void handleUnfreezeBlock(CommandSender sender) {
        if (!requirePermission(sender, "dworldmanager.unfreeze")) return;
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Msg.of(plugin, "player-only", "&cThis command can only be run by a player."));
            return;
        }

        Block target = player.getTargetBlockExact(10);
        if (target == null || target.getType().isAir()) {
            sender.sendMessage(Msg.of(plugin, "no-target-block", "&cYou're not looking at a block."));
            return;
        }

        boolean removed = plugin.getBlockFreezeService().unfreezeSingleBlock(target, player.getUniqueId(), player.getName());
        if (removed) {
            sender.sendMessage(Msg.of(plugin, "unfreeze-block-success",
                    "&a%material% at %x%,%y%,%z% unfrozen.", blockTokens(target)));
        } else {
            sender.sendMessage(Msg.of(plugin, "unfreeze-block-not-frozen", "&eThat block isn't frozen."));
        }
    }

    private String[] blockTokens(Block block) {
        return new String[]{
                "material", block.getType().name(),
                "x", String.valueOf(block.getX()),
                "y", String.valueOf(block.getY()),
                "z", String.valueOf(block.getZ())
        };
    }

    //
    // Container clearing
    //

    // /dwm clearcontainers <world>
    private void handleClearContainers(CommandSender sender, String[] args) {
        if (!requirePermission(sender, "dworldmanager.clearcontainers")) return;
        if (args.length < 2) {
            sender.sendMessage(Msg.raw("&cUsage: /dwm clearcontainers <world>"));
            return;
        }

        World world = plugin.getServer().getWorld(args[1]);
        if (world == null) {
            sender.sendMessage(Msg.of(plugin, "unknown-world", "&cUnknown world: %world%", "world", args[1]));
            return;
        }

        sender.sendMessage(Msg.of(plugin, "clearcontainers-scanning",
                "&eScanning '%world%' for tainted containers - this may take a while, progress will be reported periodically...",
                "world", world.getName()));

        plugin.getContainerClearService().clearTaintedInWorld(world, new ContainerClearService.ClearCallback() {
            @Override
            public void onComplete(ClearStats stats) {
                sender.sendMessage(Msg.of(plugin, "clearcontainers-complete",
                        "&aContainer clear complete in '%world%': %containers% container(s), %frames% item frame(s), "
                                + "%stands% armor stand(s), %allays% allay(s), %items% dropped item(s) - %total% total.",
                        "world", world.getName(),
                        "containers", String.valueOf(stats.containersCleared),
                        "frames", String.valueOf(stats.itemFramesCleared),
                        "stands", String.valueOf(stats.armorStandsCleared),
                        "allays", String.valueOf(stats.allaysCleared),
                        "items", String.valueOf(stats.droppedItemsRemoved),
                        "total", String.valueOf(stats.total())));
            }

            @Override
            public void onError(String message) {
                sender.sendMessage(Msg.raw(message));
            }

            @Override
            public void onProgress(int scanned, int total) {
                sender.sendMessage(Msg.of(plugin, "clearcontainers-progress",
                        "&eContainer clear: %scanned% / %total% chunks (%percent%%)",
                        "scanned", String.valueOf(scanned), "total", String.valueOf(total),
                        "percent", String.valueOf(percent(scanned, total))));
            }
        });
    }

    //
    // Viewers
    //

    // /dwm frozen <world> [material]
    private void handleFrozenView(CommandSender sender, String[] args) {
        if (!requirePermission(sender, "dworldmanager.audit")) return;
        if (args.length < 2) {
            sender.sendMessage(Msg.raw("&cUsage: /dwm frozen <world> [material]"));
            return;
        }

        World world = plugin.getServer().getWorld(args[1]);
        if (world == null) {
            sender.sendMessage(Msg.of(plugin, "unknown-world", "&cUnknown world: %world%", "world", args[1]));
            return;
        }

        List<FrozenBlock> blocks = plugin.getDatabaseManager().getFrozenBlocksInWorld(world.getName());

        if (args.length > 2) {
            Material material = Material.matchMaterial(args[2]);
            if (material == null) {
                sender.sendMessage(Msg.of(plugin, "unknown-material", "&cUnknown material: %material%", "material", args[2]));
                return;
            }
            List<FrozenBlock> matches = blocks.stream().filter(b -> b.material().equals(material.name())).toList();
            if (matches.isEmpty()) {
                sender.sendMessage(Component.text("No frozen " + material.name() + " in '" + world.getName() + "'.", NamedTextColor.GRAY));
                return;
            }
            sender.sendMessage(Component.text(matches.size() + " frozen " + material.name()
                    + " in '" + world.getName() + "':", NamedTextColor.GOLD));
            int shown = 0;
            for (FrozenBlock block : matches) {
                if (shown >= 20) {
                    sender.sendMessage(Component.text("  ...and " + (matches.size() - shown) + " more.", NamedTextColor.GRAY));
                    break;
                }
                sender.sendMessage(Component.text("  " + block.x() + "," + block.y() + "," + block.z(), NamedTextColor.GRAY));
                shown++;
            }
        } else {
            if (blocks.isEmpty()) {
                sender.sendMessage(Component.text("No frozen blocks in '" + world.getName() + "'.", NamedTextColor.GRAY));
                return;
            }
            Map<String, Integer> counts = new TreeMap<>();
            for (FrozenBlock block : blocks) {
                counts.merge(block.material(), 1, Integer::sum);
            }
            sender.sendMessage(Component.text(blocks.size() + " frozen block(s) in '" + world.getName() + "':", NamedTextColor.GOLD));
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                sender.sendMessage(Component.text("  " + entry.getKey() + ": " + entry.getValue(), NamedTextColor.GRAY));
            }
        }
    }

    // /dwm auditlog [limit]
    private void handleAuditLog(CommandSender sender, String[] args) {
        if (!requirePermission(sender, "dworldmanager.audit")) return;

        int limit = 10;
        if (args.length > 1) {
            try {
                limit = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException e) {
                sender.sendMessage(Msg.raw("&cLimit must be a whole number."));
                return;
            }
        }

        List<UnfreezeLogEntry> entries = plugin.getDatabaseManager().getUnfreezeLog(limit);
        if (entries.isEmpty()) {
            sender.sendMessage(Component.text("No unfreeze actions recorded yet.", NamedTextColor.GRAY));
            return;
        }

        sender.sendMessage(Component.text("Last " + entries.size() + " unfreeze action(s):", NamedTextColor.GOLD));
        for (UnfreezeLogEntry entry : entries) {
            boolean hasCoords = !(entry.x() == 0 && entry.y() == 0 && entry.z() == 0);
            String materialLabel = entry.material().equals("*") ? "all materials" : entry.material();
            String where = hasCoords ? " at " + entry.x() + "," + entry.y() + "," + entry.z() : "";
            String when = TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(entry.unfrozenAt()));

            sender.sendMessage(Component.text("  [" + when + "] " + entry.staffName() + " unfroze "
                    + entry.affectedCount() + "x " + materialLabel + " in '" + entry.world() + "'" + where,
                    NamedTextColor.GRAY));
        }
    }

    //
    // Tab completion
    //

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> results = new ArrayList<>();
            StringUtil.copyPartialMatches(args[0], SUBCOMMANDS, results);
            return results;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("freeze") || sub.equals("unfreeze")) {
            return completeFreezeUnfreeze(sub, args);
        }
        if (sub.equals("clearcontainers") || sub.equals("frozen")) {
            List<String> results = new ArrayList<>();
            if (args.length == 2) {
                StringUtil.copyPartialMatches(args[1], worldNames(), results);
            } else if (sub.equals("frozen") && args.length == 3) {
                StringUtil.copyPartialMatches(args[2], materialNames(), results);
            }
            return results;
        }

        return List.of();
    }

    private List<String> completeFreezeUnfreeze(String sub, String[] args) {
        List<String> results = new ArrayList<>();
        boolean isFreeze = sub.equals("freeze");

        if (args.length == 2) {
            List<String> options = new ArrayList<>(worldNames());
            options.add("region");
            options.add("block");
            StringUtil.copyPartialMatches(args[1], options, results);
            return results;
        }

        boolean isRegion = args[1].equalsIgnoreCase("region");
        boolean isBlock = args[1].equalsIgnoreCase("block");

        if (isBlock) {
            return results; // no further arguments
        }

        if (isRegion) {
            if (args.length == 3) {
                StringUtil.copyPartialMatches(args[2], worldNames(), results);
            } else if (isFreeze && args.length >= 10) {
                StringUtil.copyPartialMatches(args[args.length - 1], materialAndFlagOptions(), results);
            }
            // args 4-9 are raw coordinates - nothing sensible to suggest
            return results;
        }

        // plain world-scoped freeze/unfreeze: args[1] is the world name
        boolean materialPosition = (isFreeze && args.length >= 3) || (!isFreeze && args.length == 3);
        if (materialPosition) {
            List<String> options = isFreeze ? materialAndFlagOptions() : materialNames();
            StringUtil.copyPartialMatches(args[args.length - 1], options, results);
        }
        return results;
    }

    private List<String> materialAndFlagOptions() {
        List<String> options = new ArrayList<>(materialNames());
        options.add(PLACED_ONLY_FLAG);
        return options;
    }

    private List<String> worldNames() {
        return Bukkit.getWorlds().stream().map(World::getName).toList();
    }

    private List<String> materialNames() {
        return Arrays.stream(Material.values()).map(Enum::name).toList();
    }
}
