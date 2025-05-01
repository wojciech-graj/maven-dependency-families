package net.w_graj.versioncheck;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.osgi.framework.Version;
import org.apache.felix.utils.version.VersionRange;

public class VersionCheck {
    private static final String DB_URL = "jdbc:postgresql:cse3000";
    private static final String DB_USER = "wojtek";
    private static final String DB_PASS = "";

    private static final int BATCH_SIZE = 4096;
    private static final int FETCH_SIZE = 4096;

    private static final String SELECT_SQL = "SELECT DISTINCT"
            + "\n    imported_packages.version AS req_version,"
            + "\n    exported_packages.version"
            + "\nFROM ( SELECT DISTINCT"
            + "\n        version,"
            + "\n        to_package_id"
            + "\n    FROM"
            + "\n        imported_packages) AS imported_packages"
            + "\n    JOIN packages ON packages.id = imported_packages.to_package_id"
            + "\n    JOIN exported_packages ON exported_packages.package_id = packages.id"
            + "\nWHERE"
            + "\n    imported_packages.version IS NOT NULL";

    private static final String INSERT_SQL = "INSERT INTO versions_in_range(version_range, version)"
            + "\n    VALUES (?, ?)";

    public static void main(String[] args) throws Exception {
        try (
                final Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                final PreparedStatement select = conn.prepareStatement(
                        SELECT_SQL, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                final PreparedStatement insert = conn.prepareStatement(INSERT_SQL);) {
            conn.setAutoCommit(false);
            select.setFetchSize(FETCH_SIZE);
            final ResultSet rs = select.executeQuery();

            int row_count = 0;
            int batch_count = 0;

            while (rs.next()) {
                row_count++;
                if (row_count % 100000 == 0)
                    System.out.println(row_count);

                final String reqVersionS = rs.getString(1);
                final String versionS = rs.getString(2);
                final Version version;
                final VersionRange reqVersion;
                try {
                    version = Version.parseVersion(versionS);
                    reqVersion = VersionRange.parseVersionRange(reqVersionS);
                } catch (Exception e) {
                    continue;
                }
                if (!reqVersion.contains(version))
                    continue;

                insert.setString(1, reqVersionS);
                insert.setString(2, versionS);
                insert.addBatch();
                batch_count++;

                if (batch_count % BATCH_SIZE == 0)
                    flushBatch(insert);
            }

            flushBatch(insert);

            conn.commit();
        }
    }

    private static void flushBatch(PreparedStatement statement)
            throws SQLException {
        statement.executeBatch();
        statement.clearBatch();
    }
}
