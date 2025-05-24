package net.w_graj.cluster;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import nl.cwts.networkanalysis.Clustering;
import nl.cwts.networkanalysis.ClusteringAlgorithm;
import nl.cwts.networkanalysis.LouvainAlgorithm;
import nl.cwts.networkanalysis.Network;
import nl.cwts.util.LargeDoubleArray;
import nl.cwts.util.LargeIntArray;

public class Cluster {
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

    private static final String SELECT_EDGES_SQL = "WITH group_artifacts AS ("
            + "\n    SELECT"
            + "\n        id"
            + "\n    FROM"
            + "\n        artifacts"
            + "\n    WHERE"
            + "\n        root_group_id = ?"
            + "\n),"
            + "\nparent_pairs AS ("
            + "\n    SELECT"
            + "\n        LEAST(p.from_artifact_id, p.to_artifact_id) AS a,"
            + "\n    GREATEST(p.from_artifact_id, p.to_artifact_id) AS b,"
            + "\n    TRUE AS is_parent"
            + "\nFROM"
            + "\n    parents p"
            + "\n    JOIN group_artifacts ga1 ON ga1.id = p.from_artifact_id"
            + "\n    JOIN group_artifacts ga2 ON ga2.id = p.to_artifact_id"
            + "\n),"
            + "\nall_pairs AS ("
            + "\n    SELECT"
            + "\n        a,"
            + "\n        b,"
            + "\n        is_parent,"
            + "\n        NULL::NUMERIC AS coeff"
            + "\n    FROM"
            + "\n        parent_pairs"
            + "\nUNION"
            + "\nSELECT"
            + "\n    s.a_artifact_id,"
            + "\n    s.b_artifact_id,"
            + "\n    FALSE,"
            + "\n    s.coeff"
            + "\nFROM"
            + "\n    self_artifact_overlap_coefficients s"
            + "\n    JOIN group_artifacts ga1 ON ga1.id = s.a_artifact_id"
            + "\n    JOIN group_artifacts ga2 ON ga2.id = s.b_artifact_id"
            + "\n)"
            + "\nSELECT"
            + "\n    ap.a AS a_artifact_id,"
            + "\n    ap.b AS b_artifact_id,"
            + "\n    ap.is_parent,"
            + "\n    ap.coeff"
            + "\nFROM"
            + "\n    all_pairs ap";

    private static final String INSERT_COMMUNITY_SQL = "INSERT INTO communities(artifact_id, community)"
            + "\n    VALUES (?, ?)";

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
            int groupIdCount = 0;
            while (rootGroupIdRs.next()) {
                if (++groupIdCount % 512 == 0)
                    System.out.println(groupIdCount);

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
                final LargeDoubleArray edgeWeights = new LargeDoubleArray(0);
                final ResultSet edgeRs = selectEdges.executeQuery();
                while (edgeRs.next()) {
                    edges[0].append(artifactIdToNodeId.get(edgeRs.getInt(1)));
                    edges[1].append(artifactIdToNodeId.get(edgeRs.getInt(2)));
                    edgeWeights.append((edgeRs.getBoolean(3) ? 1.0 : 0.0) * 0.96 + 0.04 * edgeRs.getDouble(4));
                }

                if (edges[0].size() == 0)
                    continue;

                final Network network = new Network(artifactIdToNodeId.size(), false, edges, edgeWeights, false, false);
                final ClusteringAlgorithm algo = new LouvainAlgorithm(0.003, 70, new Random(0));
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
