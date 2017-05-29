/*
 * Microsoft JDBC Driver for SQL Server
 * 
 * Copyright(c) Microsoft Corporation All rights reserved.
 * 
 * This program is made available under the terms of the MIT License. See the LICENSE file in the project root for more information.
 */
package com.microsoft.sqlserver.jdbc.unit.statement;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.DriverManager;
import java.sql.ParameterMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.junit.platform.runner.JUnitPlatform;
import org.junit.runner.RunWith;

import com.microsoft.sqlserver.jdbc.SQLServerConnection;
import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import com.microsoft.sqlserver.jdbc.SQLServerPreparedStatement;
import com.microsoft.sqlserver.testframework.AbstractTest;

@RunWith(JUnitPlatform.class)
public class PreparedStatementTest extends AbstractTest {
    private void executeSQL(SQLServerConnection conn, String sql) throws SQLException {
        Statement stmt = conn.createStatement();
        stmt.execute(sql);
    }

    private int executeSQLReturnFirstInt(SQLServerConnection conn, String sql) throws SQLException {
        Statement stmt = conn.createStatement();
        ResultSet result = stmt.executeQuery(sql);
        
        int returnValue = -1;

        if(result.next())
            returnValue = result.getInt(1);

        return returnValue;
    }

    /**
     * Test handling of unpreparing prepared statements.
     * 
     * @throws SQLException
     */
    @Test
    @SuppressWarnings("resource")
    public void testBatchedUnprepare() throws SQLException {
        SQLServerConnection conOuter = null;

        // Make sure correct settings are used.
        SQLServerConnection.setDefaultEnablePrepareOnFirstPreparedStatementCall(SQLServerConnection.getInitialDefaultEnablePrepareOnFirstPreparedStatementCall());
        SQLServerConnection.setDefaultServerPreparedStatementDiscardThreshold(SQLServerConnection.getInitialDefaultServerPreparedStatementDiscardThreshold());

        try (SQLServerConnection con = (SQLServerConnection)DriverManager.getConnection(connectionString)) {
            conOuter = con;

            con.setStatementPoolingCacheSize(0);

            // Clean-up proc cache
            this.executeSQL(con, "DBCC FREEPROCCACHE;");

            String lookupUniqueifier = UUID.randomUUID().toString();

            String queryCacheLookup = String.format("%%/*unpreparetest_%s%%*/SELECT * FROM sys.tables;", lookupUniqueifier);
            String query = String.format("/*unpreparetest_%s only sp_executesql*/SELECT * FROM sys.tables;", lookupUniqueifier);

            // Verify nothing in cache.
            String verifyTotalCacheUsesQuery = String.format("SELECT CAST(ISNULL(SUM(usecounts), 0) AS INT) FROM sys.dm_exec_cached_plans AS p CROSS APPLY sys.dm_exec_sql_text(p.plan_handle) AS s WHERE s.text LIKE '%s'", queryCacheLookup);

            assertSame(0, executeSQLReturnFirstInt(con, verifyTotalCacheUsesQuery));

            int iterations = 25;

            // Verify no prepares for 1 time only uses.
            for (int i = 0; i < iterations; ++i) {
                try (SQLServerPreparedStatement pstmt = (SQLServerPreparedStatement)con.prepareStatement(query)) {
                    pstmt.executeQuery();
                }
                assertSame(0, con.getDiscardableServerPreparedStatementCount());
            }

            // Verify total cache use.
            assertSame(iterations, executeSQLReturnFirstInt(con, verifyTotalCacheUsesQuery));

            int prevDiscardActionCount = 0;

            // Now verify unprepares are needed.
            int handle = 0;
            for (int i = 0; i < iterations; ++i) {

                // Verify current queue depth is expected.
                assertSame(prevDiscardActionCount, con.getDiscardableServerPreparedStatementCount());

                query = String.format("/*unpreparetest_%s, sp_executesql->sp_prepexec->sp_execute- batched sp_unprepare*/SELECT * FROM sys.tables;", UUID.randomUUID().toString());
                try (SQLServerPreparedStatement pstmt = (SQLServerPreparedStatement)con.prepareStatement(query)) {
                    pstmt.executeQuery(); // sp_executesql

                    pstmt.executeQuery(); // sp_prepexec
                    ++prevDiscardActionCount;

                    pstmt.executeQuery(); // sp_execute

                    if (i > 0)
                        assertNotSame(handle, pstmt.getPreparedStatementHandle());

                    handle = pstmt.getPreparedStatementHandle();
                }

                // Verify clean-up is happening as expected.
                if (prevDiscardActionCount > con.getServerPreparedStatementDiscardThreshold()) {
                    prevDiscardActionCount = 0;
                }

                assertSame(prevDiscardActionCount, con.getDiscardableServerPreparedStatementCount());
            }

            // Skipped for now due to unexpected failures. Not functional so not critical.

            // Verify total cache use.
            int expectedCacheHits = iterations;
            int actualCacheHits = executeSQLReturnFirstInt(con, verifyTotalCacheUsesQuery);
            assertTrue(expectedCacheHits == actualCacheHits,
                       String.format("expectedCacheHits not as expected, %d != %d", expectedCacheHits, actualCacheHits));
        }

        // Verify clean-up happened on connection close.
        assertSame(0, conOuter.getDiscardableServerPreparedStatementCount());
    }

    /**
     * Test handling of statement pooling for prepared statements.
     *
     * @throws SQLException
     */
    @Test
    public void testMetadataCaching() throws SQLException {
        // Make sure correct settings are used.
        SQLServerConnection.setDefaultEnablePrepareOnFirstPreparedStatementCall(SQLServerConnection.getInitialDefaultEnablePrepareOnFirstPreparedStatementCall());
        SQLServerConnection.setDefaultServerPreparedStatementDiscardThreshold(SQLServerConnection.getInitialDefaultServerPreparedStatementDiscardThreshold());

        String lookupUniqueifier = UUID.randomUUID().toString();
        String query = String.format("/*statementpoolingtest_%s*/SELECT * FROM sys.tables;", lookupUniqueifier);

        ParameterMetaData metadata;
        try (SQLServerConnection con = (SQLServerConnection)DriverManager.getConnection(connectionString)) {
            // Execute query first, creates metadata cache entry
            try (SQLServerPreparedStatement pstmt = (SQLServerPreparedStatement)con.prepareStatement(query)) {
                pstmt.executeQuery(); // sp_executesql

                metadata = pstmt.getParameterMetaData();  // causes population of parameter metadata cache
                Assert.assertNotNull(metadata);
            }

            // Execute query again, should get the same ParameterMetaData instance as above
            try (SQLServerPreparedStatement pstmt = (SQLServerPreparedStatement)con.prepareStatement(query)) {
                pstmt.executeQuery(); // sp_prepexec

                assertSame(metadata, pstmt.getParameterMetaData());
            }

            // Execute query again, should get the same ParameterMetaData instance as above
            try (SQLServerPreparedStatement pstmt = (SQLServerPreparedStatement)con.prepareStatement(query)) {
                pstmt.executeQuery(); // sp_exec

                assertSame(metadata, pstmt.getParameterMetaData());
            }

            // Execute new statement with different SQL text and verify it does NOT get same metadata instance
            try (SQLServerPreparedStatement pstmt = (SQLServerPreparedStatement)con.prepareStatement(query + ";")) {
                pstmt.executeQuery(); // sp_executesql

                assertNotSame(metadata, pstmt.getParameterMetaData());
            }
        }
    }

    @Test
    public void testPrepareRace() throws Exception {
        // Make sure correct settings are used.
        SQLServerConnection.setDefaultEnablePrepareOnFirstPreparedStatementCall(true);
        SQLServerConnection.setDefaultServerPreparedStatementDiscardThreshold(2);

        final String[] queries = new String[3];
        queries[0] = String.format("SELECT * FROM sys.tables -- %s", UUID.randomUUID());
        queries[1] = String.format("SELECT * FROM sys.tables -- %s", UUID.randomUUID());
        queries[2] = String.format("SELECT * FROM sys.tables -- %s", UUID.randomUUID());

        ExecutorService threadPool = Executors.newFixedThreadPool(4);
        final AtomicReference<Exception> exception = new AtomicReference<>();
        try (SQLServerConnection con = (SQLServerConnection)DriverManager.getConnection(connectionString)) {

            for (int i = 0; i < 4; i++) {
                threadPool.execute(new Runnable() {
                    public void run() {
                        for (int j = 0; j < 500; j++) {
                            try (SQLServerPreparedStatement pstmt = (SQLServerPreparedStatement) con.prepareStatement(queries[j % 3])) {
                                pstmt.executeQuery(); // sp_executesql
                            }
                            catch (SQLException e) {
                                exception.set(e);
                                break;
                            }
                        }
                    }
                });
            }

            threadPool.shutdown();
            threadPool.awaitTermination(120, SECONDS);

            assertNull(exception.get());

            // Force un-prepares.
            con.closeUnreferencedPreparedStatementHandles();

            // Verify that queue is now empty.
            assertSame(0, con.getDiscardableServerPreparedStatementCount());

        }
        finally {
            SQLServerConnection.setDefaultEnablePrepareOnFirstPreparedStatementCall(SQLServerConnection.getInitialDefaultEnablePrepareOnFirstPreparedStatementCall());
            SQLServerConnection.setDefaultServerPreparedStatementDiscardThreshold(SQLServerConnection.getInitialDefaultServerPreparedStatementDiscardThreshold());
        }
    }

    /**
     * Test handling of the two configuration knobs related to prepared statement handling.
     * 
     * @throws SQLException
     */
    @Test
    public void testStatementPoolingPreparedStatementExecAndUnprepareConfig() throws SQLException {

        // Verify initial defaults are correct:
        assertTrue(SQLServerConnection.getInitialDefaultServerPreparedStatementDiscardThreshold() > 1);
        assertTrue(false == SQLServerConnection.getInitialDefaultEnablePrepareOnFirstPreparedStatementCall());
        assertSame(SQLServerConnection.getInitialDefaultServerPreparedStatementDiscardThreshold(), SQLServerConnection.getDefaultServerPreparedStatementDiscardThreshold());
        assertSame(SQLServerConnection.getInitialDefaultEnablePrepareOnFirstPreparedStatementCall(), SQLServerConnection.getDefaultEnablePrepareOnFirstPreparedStatementCall());

        // Test Data Source properties
        SQLServerDataSource dataSource = new SQLServerDataSource();
        dataSource.setURL(connectionString);
        // Verify defaults.
        assertTrue(0 < dataSource.getStatementPoolingCacheSize());
        assertSame(SQLServerConnection.getDefaultEnablePrepareOnFirstPreparedStatementCall(), dataSource.getEnablePrepareOnFirstPreparedStatementCall());
        assertSame(SQLServerConnection.getDefaultServerPreparedStatementDiscardThreshold(), dataSource.getServerPreparedStatementDiscardThreshold());
        // Verify change
        dataSource.setStatementPoolingCacheSize(0);
        assertSame(0, dataSource.getStatementPoolingCacheSize());
        dataSource.setEnablePrepareOnFirstPreparedStatementCall(!dataSource.getEnablePrepareOnFirstPreparedStatementCall());
        assertNotSame(SQLServerConnection.getDefaultEnablePrepareOnFirstPreparedStatementCall(), dataSource.getEnablePrepareOnFirstPreparedStatementCall());
        dataSource.setServerPreparedStatementDiscardThreshold(dataSource.getServerPreparedStatementDiscardThreshold() + 1);
        assertNotSame(SQLServerConnection.getDefaultServerPreparedStatementDiscardThreshold(), dataSource.getServerPreparedStatementDiscardThreshold());
        // Verify connection from data source has same parameters.
        SQLServerConnection connDataSource = (SQLServerConnection)dataSource.getConnection();
        assertSame(dataSource.getStatementPoolingCacheSize(), connDataSource.getStatementPoolingCacheSize());
        assertSame(dataSource.getEnablePrepareOnFirstPreparedStatementCall(), connDataSource.getEnablePrepareOnFirstPreparedStatementCall());
        assertSame(dataSource.getServerPreparedStatementDiscardThreshold(), connDataSource.getServerPreparedStatementDiscardThreshold());

        // Test connection string properties.
        // Make sure default is not same as test.
        assertNotSame(true, SQLServerConnection.getDefaultEnablePrepareOnFirstPreparedStatementCall());
        assertNotSame(3, SQLServerConnection.getDefaultServerPreparedStatementDiscardThreshold());

        // Test disableStatementPooling
        String connectionStringDisableStatementPooling = connectionString + ";disableStatementPooling=true;";
        SQLServerConnection connectionDisableStatementPooling = (SQLServerConnection)DriverManager.getConnection(connectionStringDisableStatementPooling);
        assertSame(0, connectionDisableStatementPooling.getStatementPoolingCacheSize());
        assertTrue(!connectionDisableStatementPooling.isStatementPoolingEnabled());
        String connectionStringEnableStatementPooling = connectionString + ";disableStatementPooling=false;";
        SQLServerConnection connectionEnableStatementPooling = (SQLServerConnection)DriverManager.getConnection(connectionStringEnableStatementPooling);
        assertTrue(0 < connectionEnableStatementPooling.getStatementPoolingCacheSize());

        // Test EnablePrepareOnFirstPreparedStatementCall
        String connectionStringNoExecuteSQL = connectionString + ";enablePrepareOnFirstPreparedStatementCall=true;";
        SQLServerConnection connectionNoExecuteSQL = (SQLServerConnection)DriverManager.getConnection(connectionStringNoExecuteSQL);
        assertSame(true, connectionNoExecuteSQL.getEnablePrepareOnFirstPreparedStatementCall());

        // Test ServerPreparedStatementDiscardThreshold
        String connectionStringThreshold3 = connectionString + ";ServerPreparedStatementDiscardThreshold=3;";
        SQLServerConnection connectionThreshold3 = (SQLServerConnection)DriverManager.getConnection(connectionStringThreshold3);
        assertSame(3, connectionThreshold3.getServerPreparedStatementDiscardThreshold());

        // Test combination of EnablePrepareOnFirstPreparedStatementCall and ServerPreparedStatementDiscardThreshold
        String connectionStringThresholdAndNoExecuteSQL = connectionString + ";ServerPreparedStatementDiscardThreshold=3;enablePrepareOnFirstPreparedStatementCall=true;";
        SQLServerConnection connectionThresholdAndNoExecuteSQL = (SQLServerConnection)DriverManager.getConnection(connectionStringThresholdAndNoExecuteSQL);
        assertSame(true, connectionThresholdAndNoExecuteSQL.getEnablePrepareOnFirstPreparedStatementCall());
        assertSame(3, connectionThresholdAndNoExecuteSQL.getServerPreparedStatementDiscardThreshold());

        // Test that an error is thrown for invalid connection string property values (non int/bool).
        try {
            String connectionStringThresholdError = connectionString + ";ServerPreparedStatementDiscardThreshold=hej;";
            DriverManager.getConnection(connectionStringThresholdError);
            fail("Error for invalid ServerPreparedStatementDiscardThresholdexpected.");
        }
        catch(SQLException e) {
            // Good!
        }
        try {
            String connectionStringNoExecuteSQLError = connectionString + ";enablePrepareOnFirstPreparedStatementCall=dobidoo;";
            DriverManager.getConnection(connectionStringNoExecuteSQLError);
            fail("Error for invalid enablePrepareOnFirstPreparedStatementCall expected.");
        }
        catch(SQLException e) {
            // Good!
        }

        // Change the defaults and verify change stuck.
        SQLServerConnection.setDefaultEnablePrepareOnFirstPreparedStatementCall(!SQLServerConnection.getInitialDefaultEnablePrepareOnFirstPreparedStatementCall());
        SQLServerConnection.setDefaultServerPreparedStatementDiscardThreshold(SQLServerConnection.getInitialDefaultServerPreparedStatementDiscardThreshold() - 1);
        assertNotSame(SQLServerConnection.getInitialDefaultServerPreparedStatementDiscardThreshold(), SQLServerConnection.getDefaultServerPreparedStatementDiscardThreshold());
        assertNotSame(SQLServerConnection.getInitialDefaultEnablePrepareOnFirstPreparedStatementCall(), SQLServerConnection.getDefaultEnablePrepareOnFirstPreparedStatementCall());

        // Verify invalid (negative) changes are handled correctly.
        SQLServerConnection.setDefaultServerPreparedStatementDiscardThreshold(-1);
        assertSame(0, SQLServerConnection.getDefaultServerPreparedStatementDiscardThreshold());

        // Verify instance settings.
        SQLServerConnection conn1 = (SQLServerConnection)DriverManager.getConnection(connectionString);
        assertSame(SQLServerConnection.getDefaultServerPreparedStatementDiscardThreshold(), conn1.getServerPreparedStatementDiscardThreshold());
        assertSame(SQLServerConnection.getDefaultEnablePrepareOnFirstPreparedStatementCall(), conn1.getEnablePrepareOnFirstPreparedStatementCall());
        conn1.setServerPreparedStatementDiscardThreshold(SQLServerConnection.getDefaultServerPreparedStatementDiscardThreshold() + 1);
        conn1.setEnablePrepareOnFirstPreparedStatementCall(!SQLServerConnection.getDefaultEnablePrepareOnFirstPreparedStatementCall());
        assertNotSame(SQLServerConnection.getDefaultServerPreparedStatementDiscardThreshold(), conn1.getServerPreparedStatementDiscardThreshold());
        assertNotSame(SQLServerConnection.getDefaultEnablePrepareOnFirstPreparedStatementCall(), conn1.getEnablePrepareOnFirstPreparedStatementCall());

        // Verify new instance not same as changed instance.
        SQLServerConnection conn2 = (SQLServerConnection)DriverManager.getConnection(connectionString);
        assertNotSame(conn1.getServerPreparedStatementDiscardThreshold(), conn2.getServerPreparedStatementDiscardThreshold());
        assertNotSame(conn1.getEnablePrepareOnFirstPreparedStatementCall(), conn2.getEnablePrepareOnFirstPreparedStatementCall());

        // Verify instance setting is followed.
        SQLServerConnection.setDefaultServerPreparedStatementDiscardThreshold(SQLServerConnection.getInitialDefaultServerPreparedStatementDiscardThreshold());
        try (SQLServerConnection con = (SQLServerConnection)DriverManager.getConnection(connectionString)) {

            // Turn off use of prepared statement cache.
            con.setStatementPoolingCacheSize(0);

            String query = "/*unprepSettingsTest*/SELECT * FROM sys.objects;";

            // Verify initial default is not serial:
            assertTrue(1 < SQLServerConnection.getDefaultServerPreparedStatementDiscardThreshold());

            // Verify first use is batched.
            try (SQLServerPreparedStatement pstmt = (SQLServerPreparedStatement)con.prepareStatement(query)) {
                pstmt.execute();
            }
            // Verify that the un-prepare action was not handled immediately.
            assertSame(1, con.getDiscardableServerPreparedStatementCount());

            // Force un-prepares.
            con.closeUnreferencedPreparedStatementHandles();

            // Verify that queue is now empty.
            assertSame(0, con.getDiscardableServerPreparedStatementCount());

            // Set instance setting to serial execution of un-prepare actions.
            con.setServerPreparedStatementDiscardThreshold(1);

            try (SQLServerPreparedStatement pstmt = (SQLServerPreparedStatement)con.prepareStatement(query)) {
                pstmt.execute();
            }
            // Verify that the un-prepare action was handled immediately.
            assertSame(0, con.getDiscardableServerPreparedStatementCount());
        }
    }
}
