package com.dogetennant.dworldmanager.database;

import com.dogetennant.dworldmanager.DWorldManager;
import com.dogetennant.dworldmanager.block.FrozenBlock;
import com.dogetennant.dworldmanager.block.PlacedBlock;
import com.dogetennant.dworldmanager.block.UnfreezeLogEntry;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public class MySQLManager extends DatabaseManager {

    public MySQLManager(DWorldManager plugin) {
        super(plugin);
    }

    //
    // Lifecycle
    //

    @Override
    public void initialize() throws Exception {
        var cfg = plugin.getConfigManager();

        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl("jdbc:mysql://"
                + cfg.getMysqlHost() + ":"
                + cfg.getMysqlPort() + "/"
                + cfg.getMysqlDatabase()
                + "?useSSL=false&characterEncoding=UTF-8&useUnicode=true");
        hikari.setUsername(cfg.getMysqlUsername());
        hikari.setPassword(cfg.getMysqlPassword());
        hikari.setMaximumPoolSize(cfg.getMysqlPoolSize());
        hikari.setConnectionTimeout(cfg.getMysqlConnectionTimeout());
        hikari.setMaxLifetime(cfg.getMysqlMaxLifetime());
        hikari.setPoolName("dWorldManager-MySQL");

        // Validate connections before handing them out
        hikari.setConnectionTestQuery("SELECT 1");

        dataSource = new HikariDataSource(hikari);
        createTables();
        plugin.getLogger().info("Connected to MySQL database.");
    }

    @Override
    protected void createTables() throws Exception {
        try (Connection con = dataSource.getConnection();
             Statement stmt = con.createStatement()) {

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS `%s` (
                    id        INT         NOT NULL AUTO_INCREMENT,
                    world     VARCHAR(64) NOT NULL,
                    x         INT         NOT NULL,
                    y         INT         NOT NULL,
                    z         INT         NOT NULL,
                    material  VARCHAR(64) NOT NULL,
                    frozen_at BIGINT      NOT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY location (world, x, y, z)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """.formatted(frozenBlocksTable));

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS `%s` (
                    id             INT          NOT NULL AUTO_INCREMENT,
                    world          VARCHAR(64)  NOT NULL,
                    x              INT          NOT NULL,
                    y              INT          NOT NULL,
                    z              INT          NOT NULL,
                    material       VARCHAR(64)  NOT NULL,
                    affected_count INT          NOT NULL DEFAULT 1,
                    staff_uuid     VARCHAR(36)  NOT NULL,
                    staff_name     VARCHAR(64)  NOT NULL,
                    unfrozen_at    BIGINT       NOT NULL,
                    PRIMARY KEY (id),
                    INDEX idx_unfreeze_log_time (unfrozen_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """.formatted(unfreezeLogTable));

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS `%s` (
                    id        INT         NOT NULL AUTO_INCREMENT,
                    world     VARCHAR(64) NOT NULL,
                    x         INT         NOT NULL,
                    y         INT         NOT NULL,
                    z         INT         NOT NULL,
                    material  VARCHAR(64) NOT NULL,
                    placed_at BIGINT      NOT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY location (world, x, y, z)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """.formatted(placedBlocksTable));
        }
    }

    //
    // Frozen blocks
    //

    @Override
    public void freezeBlock(FrozenBlock block) {
        String sql = """
            INSERT INTO `%s` (world, x, y, z, material, frozen_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE world = world;
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
            INSERT INTO `%s` (world, x, y, z, material, frozen_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE world = world;
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
        String sql = "SELECT * FROM `" + frozenBlocksTable
                + "` WHERE world = ? AND x = ? AND y = ? AND z = ?;";
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
        String sql = "DELETE FROM `" + frozenBlocksTable
                + "` WHERE world = ? AND x = ? AND y = ? AND z = ?;";
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

        String sql = "DELETE FROM `" + frozenBlocksTable
                + "` WHERE world = ? AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?;";
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
        String sql = "DELETE FROM `" + frozenBlocksTable + "` WHERE world = ?;";
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
        String sql = "DELETE FROM `" + frozenBlocksTable + "` WHERE world = ? AND material = ?;";
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
        String sql = "SELECT * FROM `" + frozenBlocksTable + "`;";
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
        String sql = "SELECT * FROM `" + frozenBlocksTable + "` WHERE world = ?;";
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
        String sql = "INSERT INTO `" + placedBlocksTable
                + "` (world, x, y, z, material, placed_at) VALUES (?, ?, ?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE material = VALUES(material), placed_at = VALUES(placed_at);";
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
        String sql = "DELETE FROM `" + placedBlocksTable + "` WHERE world = ? AND x = ? AND y = ? AND z = ?;";
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
        String sql = "SELECT * FROM `" + placedBlocksTable + "` WHERE world = ?;";
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
        String sql = "SELECT * FROM `" + placedBlocksTable + "`;";
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
        String sql = "INSERT INTO `" + unfreezeLogTable
                + "` (world, x, y, z, material, affected_count, staff_uuid, staff_name, unfrozen_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);";
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
        String sql = "SELECT * FROM `" + unfreezeLogTable + "` ORDER BY unfrozen_at DESC LIMIT ?;";
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
