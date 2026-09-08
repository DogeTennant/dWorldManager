package com.dogetennant.dworldmanager.database;

import com.dogetennant.dworldmanager.DWorldManager;
import com.dogetennant.dworldmanager.block.FrozenBlock;
import com.dogetennant.dworldmanager.block.PlacedBlock;
import com.dogetennant.dworldmanager.block.UnfreezeLogEntry;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public class SQLiteManager extends DatabaseManager {

    public SQLiteManager(DWorldManager plugin) {
        super(plugin);
    }

    //
    // Lifecycle
    //

    @Override
    public void initialize() throws Exception {
        File dbFile = new File(plugin.getDataFolder(), "data.db");
        plugin.getDataFolder().mkdirs();

        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        hikari.setDriverClassName("org.sqlite.JDBC");
        // SQLite does not support concurrent writes - pool size must be 1
        hikari.setMaximumPoolSize(1);
        hikari.setConnectionTimeout(30000);
        hikari.setPoolName("dWorldManager-SQLite");

        dataSource = new HikariDataSource(hikari);
        createTables();
        plugin.getLogger().info("Connected to SQLite database.");
    }

    @Override
    protected void createTables() throws Exception {
        try (Connection con = dataSource.getConnection();
             Statement stmt = con.createStatement()) {

            // Enable WAL mode - dramatically improves SQLite read performance
            // when background async tasks are reading while the main thread writes
            stmt.execute("PRAGMA journal_mode=WAL;");

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS "%s" (
                    id        INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    world     TEXT    NOT NULL,
                    x         INTEGER NOT NULL,
                    y         INTEGER NOT NULL,
                    z         INTEGER NOT NULL,
                    material  TEXT    NOT NULL,
                    frozen_at INTEGER NOT NULL,
                    UNIQUE (world, x, y, z)
                );
                """.formatted(frozenBlocksTable));

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS "%s" (
                    id              INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    world           TEXT    NOT NULL,
                    x               INTEGER NOT NULL,
                    y               INTEGER NOT NULL,
                    z               INTEGER NOT NULL,
                    material        TEXT    NOT NULL,
                    affected_count  INTEGER NOT NULL DEFAULT 1,
                    staff_uuid      TEXT    NOT NULL,
                    staff_name      TEXT    NOT NULL,
                    unfrozen_at     INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_unfreeze_log_time
                    ON "%s" (unfrozen_at);
                """.formatted(unfreezeLogTable, unfreezeLogTable));

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS "%s" (
                    id        INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    world     TEXT    NOT NULL,
                    x         INTEGER NOT NULL,
                    y         INTEGER NOT NULL,
                    z         INTEGER NOT NULL,
                    material  TEXT    NOT NULL,
                    placed_at INTEGER NOT NULL,
                    UNIQUE (world, x, y, z)
                );
                """.formatted(placedBlocksTable));
        }
    }

    //
    // Frozen blocks
    //

    @Override
    public void freezeBlock(FrozenBlock block) {
        String sql = """
            INSERT INTO "%s" (world, x, y, z, material, frozen_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(world, x, y, z) DO NOTHING;
            """.formatted(frozenBlocksTable);
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, block.world());
            ps.setInt(2, block.x());
            ps.setInt(3, block.y());
            ps.setInt(4, block.z());
            ps.setString(5, block.material());
            ps.setLong(6, block.frozenAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to freeze block at " + block.world() + " " + block.x() + " " + block.y() + " " + block.z(), e);
        }
    }

    @Override
    public void freezeBlocks(List<FrozenBlock> blocks) {
        if (blocks.isEmpty()) return;
        String sql = """
            INSERT INTO "%s" (world, x, y, z, material, frozen_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(world, x, y, z) DO NOTHING;
            """.formatted(frozenBlocksTable);
        try (Connection con = dataSource.getConnection()) {
            con.setAutoCommit(false);
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                for (FrozenBlock block : blocks) {
                    ps.setString(1, block.world());
                    ps.setInt(2, block.x());
                    ps.setInt(3, block.y());
                    ps.setInt(4, block.z());
                    ps.setString(5, block.material());
                    ps.setLong(6, block.frozenAt());
                    ps.addBatch();
                }
                ps.executeBatch();
                con.commit();
            } catch (SQLException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to batch-freeze " + blocks.size() + " block(s).", e);
        }
    }

    @Override
    public FrozenBlock getFrozenBlock(String world, int x, int y, int z) {
        String sql = "SELECT * FROM \"" + frozenBlocksTable
                + "\" WHERE world = ? AND x = ? AND y = ? AND z = ?;";
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, world);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapFrozenBlock(rs);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to query frozen block at " + world + " " + x + " " + y + " " + z, e);
        }
        return null;
    }

    @Override
    public boolean unfreezeBlock(String world, int x, int y, int z) {
        String sql = "DELETE FROM \"" + frozenBlocksTable
                + "\" WHERE world = ? AND x = ? AND y = ? AND z = ?;";
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, world);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to unfreeze block at " + world + " " + x + " " + y + " " + z, e);
            return false;
        }
    }

    @Override
    public int unfreezeRegion(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);

        String sql = "DELETE FROM \"" + frozenBlocksTable
                + "\" WHERE world = ? AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?;";
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, world);
            ps.setInt(2, minX); ps.setInt(3, maxX);
            ps.setInt(4, minY); ps.setInt(5, maxY);
            ps.setInt(6, minZ); ps.setInt(7, maxZ);
            return ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to unfreeze region in " + world, e);
            return 0;
        }
    }

    @Override
    public int unfreezeWorld(String world) {
        String sql = "DELETE FROM \"" + frozenBlocksTable + "\" WHERE world = ?;";
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, world);
            return ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to unfreeze world " + world, e);
            return 0;
        }
    }

    @Override
    public int unfreezeMaterial(String world, String material) {
        String sql = "DELETE FROM \"" + frozenBlocksTable + "\" WHERE world = ? AND material = ?;";
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, world);
            ps.setString(2, material);
            return ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to unfreeze material " + material + " in " + world, e);
            return 0;
        }
    }

    @Override
    public List<FrozenBlock> getAllFrozenBlocks() {
        String sql = "SELECT * FROM \"" + frozenBlocksTable + "\";";
        List<FrozenBlock> list = new ArrayList<>();
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(mapFrozenBlock(rs));
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load frozen blocks.", e);
        }
        return list;
    }

    @Override
    public List<FrozenBlock> getFrozenBlocksInWorld(String world) {
        String sql = "SELECT * FROM \"" + frozenBlocksTable + "\" WHERE world = ?;";
        List<FrozenBlock> list = new ArrayList<>();
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, world);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapFrozenBlock(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load frozen blocks for world " + world, e);
        }
        return list;
    }

    private FrozenBlock mapFrozenBlock(ResultSet rs) throws SQLException {
        return new FrozenBlock(
                rs.getString("world"),
                rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                rs.getString("material"),
                rs.getLong("frozen_at")
        );
    }

    //
    // Placed blocks
    //

    @Override
    public void recordPlacedBlock(PlacedBlock block) {
        String sql = """
            INSERT INTO "%s" (world, x, y, z, material, placed_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(world, x, y, z) DO UPDATE SET
                material = excluded.material,
                placed_at = excluded.placed_at;
            """.formatted(placedBlocksTable);
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, block.world());
            ps.setInt(2, block.x());
            ps.setInt(3, block.y());
            ps.setInt(4, block.z());
            ps.setString(5, block.material());
            ps.setLong(6, block.placedAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to record placed block at " + block.world() + " " + block.x() + " " + block.y() + " " + block.z(), e);
        }
    }

    @Override
    public boolean removePlacedBlock(String world, int x, int y, int z) {
        String sql = "DELETE FROM \"" + placedBlocksTable + "\" WHERE world = ? AND x = ? AND y = ? AND z = ?;";
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, world);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to remove placed block at " + world + " " + x + " " + y + " " + z, e);
            return false;
        }
    }

    @Override
    public List<PlacedBlock> getPlacedBlocksInWorld(String world) {
        String sql = "SELECT * FROM \"" + placedBlocksTable + "\" WHERE world = ?;";
        List<PlacedBlock> list = new ArrayList<>();
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, world);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapPlacedBlock(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load placed blocks for world " + world, e);
        }
        return list;
    }

    @Override
    public List<PlacedBlock> getAllPlacedBlocks() {
        String sql = "SELECT * FROM \"" + placedBlocksTable + "\";";
        List<PlacedBlock> list = new ArrayList<>();
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(mapPlacedBlock(rs));
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load placed blocks.", e);
        }
        return list;
    }

    private PlacedBlock mapPlacedBlock(ResultSet rs) throws SQLException {
        return new PlacedBlock(
                rs.getString("world"),
                rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                rs.getString("material"),
                rs.getLong("placed_at")
        );
    }

    //
    // Unfreeze audit log
    //

    @Override
    public void logUnfreeze(UnfreezeLogEntry entry) {
        String sql = """
            INSERT INTO "%s" (world, x, y, z, material, affected_count, staff_uuid, staff_name, unfrozen_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);
            """.formatted(unfreezeLogTable);
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, entry.world());
            ps.setInt(2, entry.x());
            ps.setInt(3, entry.y());
            ps.setInt(4, entry.z());
            ps.setString(5, entry.material());
            ps.setInt(6, entry.affectedCount());
            ps.setString(7, entry.staffUuid().toString());
            ps.setString(8, entry.staffName());
            ps.setLong(9, entry.unfrozenAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to log unfreeze action.", e);
        }
    }

    @Override
    public List<UnfreezeLogEntry> getUnfreezeLog(int limit) {
        String sql = "SELECT * FROM \"" + unfreezeLogTable + "\" ORDER BY unfrozen_at DESC LIMIT ?;";
        List<UnfreezeLogEntry> list = new ArrayList<>();
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapUnfreezeLogEntry(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load unfreeze log.", e);
        }
        return list;
    }

    private UnfreezeLogEntry mapUnfreezeLogEntry(ResultSet rs) throws SQLException {
        return new UnfreezeLogEntry(
                rs.getInt("id"),
                rs.getString("world"),
                rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                rs.getString("material"),
                rs.getInt("affected_count"),
                UUID.fromString(rs.getString("staff_uuid")),
                rs.getString("staff_name"),
                rs.getLong("unfrozen_at")
        );
    }
}
