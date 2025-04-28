package net.w_graj.bundleimport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.apache.felix.utils.manifest.Clause;
import org.apache.felix.utils.manifest.Parser;

public class BundleImport {
    private static final String DB_URL = "jdbc:postgresql:cse3000";
    private static final String DB_USER = "wojtek";
    private static final String DB_PASS = "";

    private static final int BATCH_SIZE = 1024;
    private static final int FETCH_SIZE = 1024;

    private static final String SELECT_BUNDLE_VERSIONS_1_SQL = "SELECT"
            + "\n    id,"
            + "\n    require_bundle,"
            + "\n    export_package"
            + "\nFROM"
            + "\n    bundle_versions";

    private static final String SELECT_BUNDLE_VERSIONS_2_SQL = "SELECT"
            + "\n    id,"
            + "\n    import_package"
            + "\nFROM"
            + "\n    bundle_versions";

    private static final String INSERT_EXPORTED_PACKAGE_SQL = "WITH package AS ("
            + "\nINSERT INTO packages(name)"
            + "\n        VALUES (?)"
            + "\n    ON CONFLICT (name)"
            + "\n        DO NOTHING"
            + "\n    RETURNING"
            + "\n        id)"
            + "\n    INSERT INTO exported_packages(bundle_version_id, package_id, version)"
            + "\n        VALUES (?,("
            + "\n                SELECT"
            + "\n                    id"
            + "\n                FROM"
            + "\n                    package"
            + "\n                UNION ALL"
            + "\n                SELECT"
            + "\n                    id"
            + "\n                FROM"
            + "\n                    packages"
            + "\n                WHERE"
            + "\n                    name = ?), ?)"
            + "\n    ON CONFLICT (bundle_version_id,"
            + "\n        package_id,"
            + "\n        version)"
            + "\n        DO NOTHING";

    private static final String INSERT_REQUIRED_BUNDLE_SQL = "INSERT INTO required_bundles(from_bundle_version_id, to_bundle_id, version)"
            + "\nSELECT"
            + "\n    ?,"
            + "\n    id,"
            + "\n    ?"
            + "\nFROM"
            + "\n    bundles"
            + "\nWHERE"
            + "\n    bundle_symbolic_name = ?"
            + "\nON CONFLICT (from_bundle_version_id,"
            + "\n    to_bundle_id)"
            + "\n    DO UPDATE SET"
            + "\n        version = coalesce(required_bundles.version, EXCLUDED.version)";

    private static final String INSERT_IMPORTED_PACKAGE_SQL = "INSERT INTO imported_packages(from_bundle_version_id, to_package_id, version)"
            + "\nSELECT"
            + "\n    ?,"
            + "\n    id,"
            + "\n    ?"
            + "\nFROM"
            + "\n    packages"
            + "\nWHERE"
            + "\n    name = ?"
            + "\nON CONFLICT (from_bundle_version_id,"
            + "\n    to_package_id)"
            + "\n    DO UPDATE SET"
            + "\n        version = coalesce(imported_packages.version, EXCLUDED.version)";

    public static void main(String[] args) throws Exception {
        try (
                final Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                final PreparedStatement getBundleVersions1 = conn.prepareStatement(
                        SELECT_BUNDLE_VERSIONS_1_SQL, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                final PreparedStatement getBundleVersions2 = conn.prepareStatement(
                        SELECT_BUNDLE_VERSIONS_2_SQL, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                final PreparedStatement insertExportedPackage = conn.prepareStatement(INSERT_EXPORTED_PACKAGE_SQL);
                final PreparedStatement insertRequiredBundle = conn.prepareStatement(INSERT_REQUIRED_BUNDLE_SQL);
                final PreparedStatement insertImportedPackage = conn.prepareStatement(INSERT_IMPORTED_PACKAGE_SQL);

        ) {
            conn.setAutoCommit(false);
            getBundleVersions1.setFetchSize(FETCH_SIZE);
            getBundleVersions2.setFetchSize(FETCH_SIZE);
            final ResultSet rs1 = getBundleVersions1.executeQuery();
            final ResultSet rs2 = getBundleVersions2.executeQuery();
            final List<PreparedStatement> statements1 = List.of(insertExportedPackage, insertRequiredBundle);
            final List<PreparedStatement> statements2 = List.of(insertImportedPackage);
            int count = 0;

            while (rs1.next()) {
                final int id = rs1.getInt(1);
                final String requireBundle = rs1.getString(2);
                final String exportPackage = rs1.getString(3);
                count++;

                try {
                    for (final Clause clause : Parser.parseHeader(exportPackage))
                        processExportedPackage(insertExportedPackage, id, clause);
                } catch (Exception e) {
                    System.err.println(e);
                }

                try {
                    for (final Clause clause : Parser.parseHeader(requireBundle))
                        processRequiredBundle(insertRequiredBundle, id, clause);
                } catch (Exception e) {
                    System.err.println(e);
                }

                if (count % BATCH_SIZE == 0)
                    flushBatches(statements1);

                if (count % 10000 == 0)
                    System.out.printf("Batch 1: %d\n", count);
            }

            flushBatches(statements1);
            count = 0;

            while (rs2.next()) {
                final int id = rs2.getInt(1);
                final String importPackage = rs2.getString(2);
                count++;

                try {
                    for (final Clause clause : Parser.parseHeader(importPackage))
                        processImportedPacakge(insertImportedPackage, id, clause);
                } catch (Exception e) {
                    System.err.println(e);
                }

                if (count % BATCH_SIZE == 0)
                    flushBatches(statements2);

                if (count % 10000 == 0)
                    System.out.printf("Batch 2: %d\n", count);
            }

            flushBatches(statements2);

            conn.commit();
        }
    }

    private static void processExportedPackage(PreparedStatement insertExportedPackage, int id, Clause clause)
            throws SQLException {
        final String version = clause.getAttribute("version");
        insertExportedPackage.setString(1, clause.getName());
        insertExportedPackage.setInt(2, id);
        insertExportedPackage.setString(3, clause.getName());
        insertExportedPackage.setString(4, (version != null) ? version : "0.0.0");
        insertExportedPackage.addBatch();
    }

    private static void processRequiredBundle(PreparedStatement insertRequiredBundle, int id, Clause clause)
            throws SQLException {
        insertRequiredBundle.setInt(1, id);
        insertRequiredBundle.setString(2, clause.getAttribute("bundle-version"));
        insertRequiredBundle.setString(3, clause.getName());
        insertRequiredBundle.addBatch();
    }

    private static void processImportedPacakge(PreparedStatement insertImportedPackage, int id, Clause clause)
            throws SQLException {
        insertImportedPackage.setInt(1, id);
        insertImportedPackage.setString(2, clause.getAttribute("version"));
        insertImportedPackage.setString(3, clause.getName());
        insertImportedPackage.addBatch();
    }

    private static void flushBatches(List<PreparedStatement> statements)
            throws SQLException {
        for (final PreparedStatement statement : statements) {
            statement.executeBatch();
            statement.clearBatch();
        }
    }
}
