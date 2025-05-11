package net.w_graj.connected_components;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import nl.cwts.networkanalysis.Clustering;
import nl.cwts.networkanalysis.ClusteringAlgorithm;
import nl.cwts.networkanalysis.ComponentsAlgorithm;
import nl.cwts.networkanalysis.Network;
import nl.cwts.util.LargeIntArray;

public class ConnectedComponents {
    private static final String DB_URL = "jdbc:postgresql:cse3000";
    private static final String DB_USER = "wojtek";
    private static final String DB_PASS = "";

    private static final int FETCH_SIZE = 4096;
    private static final int BATCH_SIZE = 4096;

    private static final String SELECT_ROOT_GROUP_IDS_SQL = "SELECT DISTINCT"
            + "\n    root_group_id"
            + "\nFROM"
            + "\n    artifacts";

    private static final String SELECT_NODES_SQL = "SELECT"
            + "\n    id,"
            + "\n    group_id,"
            + "\n    artifact_id"
            + "\nFROM"
            + "\n    artifacts"
            + "\nWHERE"
            + "\n    root_group_id = ?";

    private static final String SELECT_EDGES_SQL = "SELECT"
            + "\n    parents.from_artifact_id,"
            + "\n    parents.to_artifact_id"
            + "\nFROM"
            + "\n    parents"
            + "\n    JOIN artifacts AS a_artifacts ON a_artifacts.id = parents.from_artifact_id"
            + "\n    JOIN artifacts AS b_artifacts ON b_artifacts.id = parents.to_artifact_id"
            + "\n        AND b_artifacts.root_group_id = a_artifacts.root_group_id"
            + "\nWHERE"
            + "\n    a_artifacts.root_group_id = ?";

    private static final String INSERT_COMMUNITY_SQL = "INSERT INTO communities_parents(artifact_id, community) VALUES (?, ?)";

    public static void main(final String[] args) throws Exception {
        try (
                final Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                final PreparedStatement selectRootGroupIds = conn.prepareStatement(SELECT_ROOT_GROUP_IDS_SQL,
                        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                final PreparedStatement selectEdges = conn.prepareStatement(SELECT_EDGES_SQL,
                        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                final PreparedStatement selectNodes = conn.prepareStatement(SELECT_NODES_SQL,
                        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                final PreparedStatement insertCommunity = conn.prepareStatement(INSERT_COMMUNITY_SQL);) {
            conn.setAutoCommit(false);
            selectEdges.setFetchSize(FETCH_SIZE);
            selectNodes.setFetchSize(FETCH_SIZE);
            selectRootGroupIds.setFetchSize(FETCH_SIZE);

            final ResultSet rootGroupIdRs = selectRootGroupIds.executeQuery();
            int communityOffset = 0;
            int batch = 0;
            while (rootGroupIdRs.next()) {
                final String rootGroupId = rootGroupIdRs.getString(1);
                selectEdges.setString(1, rootGroupId);
                selectNodes.setString(1, rootGroupId);

                final Map<Integer, Integer> artifactIdToNodeId = new HashMap<>();
                final Map<Integer, Integer> nodeIdToArtifactId = new HashMap<>();
                final ResultSet nodeRs = selectNodes.executeQuery();
                int i = 0;
                while (nodeRs.next()) {
                    int id = nodeRs.getInt(1);
                    artifactIdToNodeId.put(id, i);
                    nodeIdToArtifactId.put(i, id);
                    i++;
                }
                final LargeIntArray[] edges = new LargeIntArray[2];
                edges[0] = new LargeIntArray(0);
                edges[1] = new LargeIntArray(0);
                final ResultSet edgeRs = selectEdges.executeQuery();
                while (edgeRs.next()) {
                    edges[0].append(artifactIdToNodeId.get(edgeRs.getInt(1)));
                    edges[1].append(artifactIdToNodeId.get(edgeRs.getInt(2)));
                }

                if (edges[0].size() == 0)
                    continue;

                final Network network = new Network(artifactIdToNodeId.size(), false, edges, false, false);
                final ClusteringAlgorithm algo = new ComponentsAlgorithm();
                final Clustering clustering = algo.findClustering(network);

                final int[] clusters = clustering.getClusters();
                final int[] nNodesPer = clustering.getNNodesPerCluster();
                for (i = 0; i < clusters.length; i++) {
                    if (nNodesPer[clusters[i]] == 1)
                        continue;
                    insertCommunity.setInt(1, nodeIdToArtifactId.get(i));
                    insertCommunity.setInt(2, clusters[i] + communityOffset);
                    insertCommunity.addBatch();
                    if (batch++ >= BATCH_SIZE) {
                        batch = 0;
                        flushBatch(insertCommunity);
                    }
                }

                communityOffset += clustering.getNClusters();
            }

            flushBatch(insertCommunity);

            conn.commit();
        }
    }

    private static void flushBatch(final PreparedStatement statement)
            throws SQLException {
        statement.executeBatch();
        statement.clearBatch();
    }
}
