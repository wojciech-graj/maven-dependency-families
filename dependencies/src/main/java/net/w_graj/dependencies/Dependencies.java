package net.w_graj.dependencies;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;

public class Dependencies {
    private static final String DB_URL = "jdbc:postgresql:cse3000";
    private static final String DB_USER = "wojtek";
    private static final String DB_PASS = "";

    private static final int BATCH_SIZE = 4096;
    private static final int FETCH_SIZE = 4096;

    private static final String SELECT_SQL = "SELECT"
            + "\n    version_id,"
            + "\n    value"
            + "\nFROM"
            + "\n    poms";

    private static final String INSERT_SQL = "INSERT INTO dependencies(from_version_id, to_artifact_id, version, scope, managed)"
            + "\nSELECT"
            + "\n    ?,"
            + "\n    id,"
            + "\n    ?,"
            + "\n    ?,"
            + "\n    ?"
            + "\nFROM"
            + "\n    artifacts"
            + "\nWHERE"
            + "\n    group_id = ?"
            + "\n    AND artifact_id = ?";

    public static void main(final String[] args) throws Exception {
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
            final MavenXpp3Reader reader = new MavenXpp3Reader();

            while (rs.next()) {
                row_count++;
                if (row_count % 100000 == 0)
                    System.out.println(row_count);

                final int versionId = rs.getInt(1);
                final String pomS = rs.getString(2);
                final Model pom;
                try {
                    pom = reader.read(new StringReader(pomS));
                } catch (Exception e) {
                    System.err.println(e);
                    continue;
                }

                for (final Dependency dependency : pom.getDependencies()) {
                    insertDependency(insert, versionId, dependency.getVersion(), dependency.getScope(), false,
                            dependency.getGroupId(), dependency.getArtifactId());
                    batch_count++;

                }

                final DependencyManagement dependencyManagement = pom.getDependencyManagement();
                if (dependencyManagement != null) {
                    for (final Dependency dependency : dependencyManagement.getDependencies()) {
                        insertDependency(insert, versionId, dependency.getVersion(), dependency.getScope(), true,
                                dependency.getGroupId(), dependency.getArtifactId());
                        batch_count++;

                    }
                }

                if (batch_count >= BATCH_SIZE) {
                    flushBatch(insert);
                    batch_count = 0;
                }
            }

            flushBatch(insert);

            conn.commit();
        }
    }

    private static void insertDependency(final PreparedStatement insert, final int versionId, final String version,
            final String scope, final boolean managed, final String groupId, final String artifactId)
            throws SQLException {
        insert.setInt(1, versionId);
        insert.setString(2, version);
        insert.setString(3, scope);
        insert.setBoolean(4, managed);
        insert.setString(5, groupId);
        insert.setString(6, artifactId);
        insert.addBatch();
    }

    private static void flushBatch(final PreparedStatement statement)
            throws SQLException {
        statement.executeBatch();
        statement.clearBatch();
    }
}
