package org.asamk.signal.manager.storage.incomingmessages;

import java.sql.Connection;
import java.sql.SQLException;

import org.asamk.signal.manager.storage.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IncomingMessagesStore implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(IncomingMessagesStore.class);

    private static final String TABLE_INCOMING_MESSAGES = "incoming_messages";

    private final Database database;

    public IncomingMessagesStore(final Database database) {
        this.database = database;
    }

    public static void createSql(Connection connection) throws SQLException {
        try (final var statement = connection.createStatement()) {
            statement.executeUpdate("""
                                    CREATE TABLE incoming_messages (
                                      _id INTEGER PRIMARY KEY,
                                      message TEXT UNIQUE,
                                      date TEXT,
                                      timestamp INTEGER
                                    ) STRICT;
                                    """);
        }
    }

    public void insertOrUpdate(final String message,
            final String date,
            final long sentTimestamp) {
        
        insert(message, date, sentTimestamp);
    }
    
    private void insert(final String message,
                        final String date,
                        final long sentTimestamp) {

        /*final var sql = """
                        INSERT OR IGNORE INTO %s (message, date, timestamp) VALUES (?, ?, ?)
                        UPDATE my_table SET date = ?, timestamp = ? WHERE message=?
                        """.formatted(TABLE_INCOMING_MESSAGES);

        try (final var connection = database.getConnection()) {
            connection.setAutoCommit(false);

            try (final var statement = connection.prepareStatement(sql)) {
                statement.setBytes(1, message.getBytes());
                statement.setBytes(2, date.getBytes());
                statement.setLong(3, sentTimestamp);
                statement.setBytes(4, date.getBytes());
                statement.setLong(5, sentTimestamp);
                statement.setBytes(6, message.getBytes());
            }

            connection.commit();
        } catch (SQLException e) {
            logger.warn("Failed to insert into incoming messages table", e);
        }*/
        
        final var sql = (
                """
                INSERT INTO %s (message, date, timestamp)
                VALUES (?, ?, ?)
                ON CONFLICT (message) DO UPDATE SET date = ?, timestamp = ?
                """
        ).formatted(TABLE_INCOMING_MESSAGES);
        
        try (final var connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try (final var statement = connection.prepareStatement(sql)) {
                statement.setString(1, message);
                statement.setString(2, date);
                statement.setLong(3, sentTimestamp);
                statement.setString(4, date);
                statement.setLong(5, sentTimestamp);
                statement.executeUpdate();
            }

            connection.commit();
        } catch (SQLException e) {
            logger.warn("Failed to insert into incoming messages table", e);
        }
    }

    @Override
	public void close() throws Exception {
	}
}
