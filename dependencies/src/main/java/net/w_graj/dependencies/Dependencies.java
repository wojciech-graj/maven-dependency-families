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
import org.apache.maven.model.Parent;
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

    private static final String INSERT_DEPENDENCY_SQL = "INSERT INTO dependencies(from_version_id, to_artifact_id, version, scope, managed)"
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

    private static final String INSERT_PARENT_SQL = "INSERT INTO parents(from_artifact_id, to_artifact_id)"
            + "\nSELECT"
            + "\n    versions.artifact_id,"
            + "\n    artifacts.id"
            + "\nFROM"
            + "\n    artifacts"
            + "\n    FULL OUTER JOIN versions ON TRUE"
            + "\nWHERE"
            + "\n    versions.id = ?"
            + "\n    AND artifacts.group_id = ?"
            + "\n    AND artifacts.artifact_id = ?"
        + "\nON CONFLICT(from_artifact_id, to_artifact_id)"
        + "\n    DO NOTHING";

    public static void main(final String[] args) throws Exception {
        try (
                final Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                final PreparedStatement select = conn.prepareStatement(
                        SELECT_SQL, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                final PreparedStatement insertDependency = conn.prepareStatement(INSERT_DEPENDENCY_SQL);
            final PreparedStatement insertParent = conn.prepareStatement(INSERT_PARENT_SQL);) {
            conn.setAutoCommit(false);
            select.setFetchSize(FETCH_SIZE);
            final ResultSet rs = select.executeQuery();

            int rowCount = 0;
            int dependencyBatchCount = 0;
            int parentBatchCount = 0;
            final MavenXpp3Reader reader = new MavenXpp3Reader();

            while (rs.next()) {
                rowCount++;
                if (rowCount % 100000 == 0)
                    System.out.println(rowCount);

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
                    insertDependency(insertDependency, versionId, dependency.getVersion(), dependency.getScope(), false,
                            dependency.getGroupId(), dependency.getArtifactId());
                    dependencyBatchCount++;

                }

                final DependencyManagement dependencyManagement = pom.getDependencyManagement();
                if (dependencyManagement != null) {
                    for (final Dependency dependency : dependencyManagement.getDependencies()) {
                        insertDependency(insertDependency, versionId, dependency.getVersion(), dependency.getScope(), true,
                                dependency.getGroupId(), dependency.getArtifactId());
                        dependencyBatchCount++;

                    }
                }

                final Parent parent = pom.getParent();
                if (parent != null) {
                    insertParent(insertParent, versionId, parent.getGroupId(), parent.getArtifactId());
                    parentBatchCount++;
                }

                if (dependencyBatchCount >= BATCH_SIZE) {
                    flushBatch(insertDependency);
                    dependencyBatchCount = 0;
                }

                if (parentBatchCount >= BATCH_SIZE) {
                    flushBatch(insertParent);
                    parentBatchCount = 0;
                }
            }

            flushBatch(insertDependency);
            flushBatch(insertParent);

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

    private static void insertParent(final PreparedStatement insert, final int versionId, final String groupId, final String artifactId) throws SQLException {
        insert.setInt(1, versionId);
        insert.setString(2, groupId);
        insert.setString(3, artifactId);
        insert.addBatch();
    }

    private static void flushBatch(final PreparedStatement statement)
            throws SQLException {
        statement.executeBatch();
        statement.clearBatch();
    }
}
