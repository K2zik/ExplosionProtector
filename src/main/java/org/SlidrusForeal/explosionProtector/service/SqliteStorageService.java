package org.SlidrusForeal.explosionProtector.service;

import org.SlidrusForeal.explosionProtector.model.PackedBlockUtil;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SqliteStorageService {
    private final Object dbLock = new Object();
    private Connection connection;

    public void open(File dataFolder) throws SQLException {
        synchronized (dbLock) {
            close();
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }

            File db = new File(dataFolder, "player_placed.db");
            connection = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("PRAGMA journal_mode=WAL");
                st.executeUpdate("PRAGMA synchronous=NORMAL");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS tracked_blocks (" +
                        "world TEXT NOT NULL, " +
                        "packed INTEGER NOT NULL, " +
                        "chunk_key INTEGER NOT NULL, " +
                        "updated_at INTEGER NOT NULL, " +
                        "PRIMARY KEY (world, packed))");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tracked_blocks_world_chunk " +
                        "ON tracked_blocks(world, chunk_key)");
            }
        }
    }

    public Map<String, List<Long>> loadAll() throws SQLException {
        synchronized (dbLock) {
            ensureOpen();
            Map<String, List<Long>> data = new HashMap<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT world, packed FROM tracked_blocks");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String world = rs.getString(1);
                    long packed = rs.getLong(2);
                    data.computeIfAbsent(world, ignored -> new ArrayList<>()).add(packed);
                }
            }
            return data;
        }
    }

    public void saveSnapshot(Map<String, List<Long>> snapshot) throws SQLException {
        synchronized (dbLock) {
            ensureOpen();
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM tracked_blocks");
                 PreparedStatement insert = connection.prepareStatement(
                         "INSERT INTO tracked_blocks(world, packed, chunk_key, updated_at) VALUES (?, ?, ?, ?)")) {
                delete.executeUpdate();
                long now = System.currentTimeMillis();
                for (Map.Entry<String, List<Long>> entry : snapshot.entrySet()) {
                    String world = entry.getKey();
                    for (Long packed : entry.getValue()) {
                        insert.setString(1, world);
                        insert.setLong(2, packed);
                        insert.setLong(3, PackedBlockUtil.packChunkFromPacked(packed));
                        insert.setLong(4, now);
                        insert.addBatch();
                    }
                }
                insert.executeBatch();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    public void applyChanges(Map<String, Set<Long>> upserts, Map<String, Set<Long>> deletes) throws SQLException {
        synchronized (dbLock) {
            ensureOpen();
            if ((upserts == null || upserts.isEmpty()) && (deletes == null || deletes.isEmpty())) {
                return;
            }

            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM tracked_blocks WHERE world = ? AND packed = ?");
                 PreparedStatement upsert = connection.prepareStatement(
                         "INSERT INTO tracked_blocks(world, packed, chunk_key, updated_at) VALUES (?, ?, ?, ?) "
                                 + "ON CONFLICT(world, packed) DO UPDATE SET "
                                 + "chunk_key = excluded.chunk_key, updated_at = excluded.updated_at")) {
                if (deletes != null) {
                    for (Map.Entry<String, Set<Long>> entry : deletes.entrySet()) {
                        String world = entry.getKey();
                        for (Long packed : entry.getValue()) {
                            delete.setString(1, world);
                            delete.setLong(2, packed);
                            delete.addBatch();
                        }
                    }
                    delete.executeBatch();
                }

                if (upserts != null) {
                    long now = System.currentTimeMillis();
                    for (Map.Entry<String, Set<Long>> entry : upserts.entrySet()) {
                        String world = entry.getKey();
                        for (Long packed : entry.getValue()) {
                            upsert.setString(1, world);
                            upsert.setLong(2, packed);
                            upsert.setLong(3, PackedBlockUtil.packChunkFromPacked(packed));
                            upsert.setLong(4, now);
                            upsert.addBatch();
                        }
                    }
                    upsert.executeBatch();
                }

                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    public void close() throws SQLException {
        synchronized (dbLock) {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
            connection = null;
        }
    }

    private void ensureOpen() throws SQLException {
        if (connection == null || connection.isClosed()) {
            throw new SQLException("SQLite connection is not open");
        }
    }
}
