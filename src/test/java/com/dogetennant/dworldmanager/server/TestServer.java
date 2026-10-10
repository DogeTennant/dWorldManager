package com.dogetennant.dworldmanager.server;

import com.dogetennant.dworldmanager.DWorldManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Event;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The whole plugin on a MockBukkit server: its own config.yml and SQLite database in a temporary
 * folder, every listener and the /dwm command registered, and a "build" world with a 32-block
 * border (the 3 x 3 chunks around 0,0). Stop it with {@code MockBukkit.unmock()}.
 *
 * Database work runs on the plugin's own database thread, as on a server. {@link #settle()} lets
 * all of it finish without sleeping: it queues a marker behind the work, waits for the marker, and
 * runs the server ticks that hand the results back, until nothing is left to do.
 */
final class TestServer {

    final ServerMock server;
    final TestWorld world;
    final DWorldManager plugin;

    private TestServer(ServerMock server, TestWorld world, DWorldManager plugin) {
        this.server = server;
        this.world = world;
        this.plugin = plugin;
    }

    static TestServer start() {
        removeMockitoServer();
        ServerMock server = MockBukkit.mock();
        TestWorld world = new TestWorld("build", 32);
        server.addWorld(world);
        return new TestServer(server, world, MockBukkit.load(DWorldManager.class));
    }

    /**
     * The database tests leave a Mockito server in {@code Bukkit} for the rest of the test run
     * ({@code TestPlugin#installImmediateScheduler}, which installs it again when it is gone).
     * MockBukkit refuses to start next to it.
     */
    private static void removeMockitoServer() {
        if (Bukkit.getServer() == null || Bukkit.getServer() instanceof ServerMock) return;
        try {
            Field field = Bukkit.class.getDeclaredField("server");
            field.setAccessible(true);
            field.set(null, null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot remove the database tests' server", e);
        }
    }

    /** A player in the build world, at 8, 4, 8. */
    PlayerMock player(String name) {
        return join(new PlayerMock(server, name));
    }

    /** An operator: has every dWorldManager permission. */
    PlayerMock admin() {
        PlayerMock admin = player("Admin");
        admin.setOp(true);
        return admin;
    }

    /** A player whose crosshair is on {@link LookingPlayer#lookingAt} (MockBukkit does not trace it). */
    LookingPlayer lookingPlayer(String name) {
        return join(new LookingPlayer(server, name));
    }

    private <P extends PlayerMock> P join(P player) {
        server.addPlayer(player);
        player.teleport(new Location(world, 8.5, 4, 8.5));
        return player;
    }

    ConsoleCommandSenderMock console() {
        return server.getConsoleSender();
    }

    Block block(int x, int y, int z, Material type) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(type);
        return block;
    }

    /** Runs a command and lets everything it starts finish. */
    void run(CommandSender sender, String command) {
        server.dispatchCommand(sender, command);
        settle();
    }

    <E extends Event> E fire(E event) {
        server.getPluginManager().callEvent(event);
        return event;
    }

    /** Stops the plugin and starts it again on the same data folder, like a server restart. */
    void restart() {
        server.getPluginManager().disablePlugin(plugin);
        server.getPluginManager().enablePlugin(plugin);
        settle();
    }

    /** Lets the database thread and the scheduled tasks finish everything queued so far. */
    void settle() {
        for (int tick = 0; tick < 10_000; tick++) {
            awaitDatabase();
            if (server.getScheduler().getPendingTasks().isEmpty()) return;
            server.getScheduler().performOneTick();
        }
        throw new IllegalStateException("the plugin never finished its work");
    }

    private void awaitDatabase() {
        if (!plugin.isEnabled()) return;
        CountDownLatch marker = new CountDownLatch(1);
        plugin.getDatabaseQueue().submit(marker::countDown);
        try {
            if (!marker.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the database thread is stuck");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Every message the player got since the last call, as plain text. */
    static List<String> messages(PlayerMock player) {
        List<String> messages = new ArrayList<>();
        for (Component message; (message = player.nextComponentMessage()) != null; ) {
            messages.add(PlainTextComponentSerializer.plainText().serialize(message));
        }
        return messages;
    }

    static List<String> messages(ConsoleCommandSenderMock console) {
        List<String> messages = new ArrayList<>();
        for (Component message; (message = console.nextComponentMessage()) != null; ) {
            messages.add(PlainTextComponentSerializer.plainText().serialize(message));
        }
        return messages;
    }

    static final class LookingPlayer extends PlayerMock {

        Block lookingAt;

        LookingPlayer(ServerMock server, String name) {
            super(server, name);
        }

        @Override
        public Block getTargetBlockExact(int maxDistance) {
            return lookingAt;
        }
    }
}
