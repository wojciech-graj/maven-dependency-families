package net.w_graj.detect;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import nl.cwts.networkanalysis.Clustering;
import nl.cwts.networkanalysis.ClusteringAlgorithm;
import nl.cwts.networkanalysis.ComponentsAlgorithm;
import nl.cwts.networkanalysis.LeidenAlgorithm;
import nl.cwts.networkanalysis.LouvainAlgorithm;
import nl.cwts.networkanalysis.Network;
import nl.cwts.util.LargeBooleanArray;
import nl.cwts.util.LargeDoubleArray;
import nl.cwts.util.LargeIntArray;

public class Detect {
    private static final String DB_URL = "jdbc:postgresql:cse3000";
    private static final String DB_USER = "wojtek";
    private static final String DB_PASS = "";

    private static final int FETCH_SIZE = 4096;

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

    private static final List<String> testGroupIds = List.of("org.junit", "org.slf4j", "com.google", "org.apache",
            "io.github", "org.eclipse", "org.wso2", "app.cash", "io.circe", "org.scalatest",
            "com.squareup", "org.jetbrains");
    private static final List<List<String>> testFamilies = List.of(List.of("org.junit"), List.of("org.slf4j"),
            List.of("com.google.guava", "com.google.errorprone"),
            List.of("org.apache.kafka", "org.apache.xbean", "org.apache.lucene"),
            List.of("io.github.jsoagger", "io.github.llamalad7", "io.github.amyassist", "io.github.panpf.sketch4",
                    "io.github.panpf.zoomimage", "io.github.panpf.jsonx"),
            List.of("org.eclipse.ditto", "org.eclipse.xtext", "org.eclipse.store"),
            List.of("org.wso2.charon", "org.wso2.msf4j"),
            List.of("app.cash.sqldelight", "app.cash.treehouse", "app.cash.wisp"), List.of("io.circe"),
            List.of("org.scalatest"),
            List.of("com.squareup.retrofit2", "com.squareup.okio"), List.of("org.jetbrains.exposed"));

    private interface ExternalEvaluation {
        public abstract double eval(int tp, int fp, int fn);
    }

    private static class Jaccard implements ExternalEvaluation {
        public double eval(final int tp, final int fp, final int fn) {
            return ((float) tp) / (tp + fp + fn);
        }
    }

    private static class Dice implements ExternalEvaluation {
        public double eval(final int tp, final int fp, final int fn) {
            return ((float) tp * 2) / (tp * 2 + fp + fn);
        }
    }

    private static class FowlkesMallows implements ExternalEvaluation {
        public double eval(final int tp, final int fp, final int fn) {
            return Math.sqrt(((float) tp) / (tp + fp) + ((float) tp) / tp + fn);
        }
    }

    private static class FalseDiscoveryRate implements ExternalEvaluation {
        public double eval(final int tp, final int fp, final int fn) {
            return ((float) fp) / (tp + fp);
        }
    }

    private static class FalseNegativeRate implements ExternalEvaluation {
        public double eval(final int tp, final int fp, final int fn) {
            return ((float) fn) / (tp + fn);
        }
    }

    private static class TestGroup {
        public final int nNodes;
        public final List<List<Integer>> families;
        public final LargeIntArray[] edges;
        public final LargeBooleanArray edgeParents;
        public final LargeDoubleArray edgeCoeffs;

        public TestGroup(final PreparedStatement selectEdges, final PreparedStatement selectNodes,
                final String rootGroupId,
                final List<String> familyGroupIds) throws SQLException {
            selectEdges.setString(1, rootGroupId);
            // selectEdges.setString(2, rootGroupId);
            selectNodes.setString(1, rootGroupId);
            final Map<Integer, Integer> nodes = new HashMap<>();
            this.families = IntStream.range(0, familyGroupIds.size())
                    .mapToObj(i -> new ArrayList<Integer>())
                    .collect(Collectors.toList());

            final ResultSet nodeRs = selectNodes.executeQuery();
            int i = 0;
            while (nodeRs.next()) {
                final int id = nodeRs.getInt(1);
                final String groupId = nodeRs.getString(2);
                nodes.put(id, i);
                for (int j = 0; j < familyGroupIds.size(); j++)
                    if (groupId.startsWith(familyGroupIds.get(j)))
                        this.families.get(j).add(i);
                i++;
            }
            nNodes = nodes.size();
            edges = new LargeIntArray[2];
            edges[0] = new LargeIntArray(0);
            edges[1] = new LargeIntArray(0);
            edgeParents = new LargeBooleanArray(0);
            edgeCoeffs = new LargeDoubleArray(0);
            final ResultSet edgeRs = selectEdges.executeQuery();
            while (edgeRs.next()) {
                edges[0].append(nodes.get(edgeRs.getInt(1)));
                edges[1].append(nodes.get(edgeRs.getInt(2)));

                edgeParents.append(edgeRs.getBoolean(3));
                edgeCoeffs.append(edgeRs.getDouble(4));
            }
        }

        public double[] test(ClusteringAlgorithm algo, double alpha, List<ExternalEvaluation> eval) {
            final LargeDoubleArray edgeWeights = new LargeDoubleArray(0);
            final LargeIntArray[] edges = new LargeIntArray[2];
            edges[0] = new LargeIntArray(0);
            edges[1] = new LargeIntArray(0);
            for (int i = 0; i < this.edgeParents.size(); i++) {
                final double coeff = this.edgeCoeffs.get(i);
                final double parent = this.edgeParents.get(i) ? 1.0 : 0.0;
                final double weight = parent * alpha + coeff * (1.0 - alpha);
                if (weight <= 1e-6)
                    continue;
                edges[0].append(this.edges[0].get(i));
                edges[1].append(this.edges[1].get(i));
                edgeWeights.append(weight);
            }

            final double[] score = new double[eval.size()];

            if (edgeWeights.size() == 0) {
                for (int i = 0; i < eval.size(); i++) {
                    for (final List<Integer> family : this.families) {
                        final int tp = 1;
                        final int fp = 0;
                        final int fn = family.size() - tp;
                        score[i] += eval.get(i).eval(tp, fp, fn);
                    }
                }
                return score;
            }

            final Network network = new Network(this.nNodes, false, edges, edgeWeights, false, false);

            final Clustering clustering = algo.findClustering(network);

            for (int i = 0; i < eval.size(); i++) {
                for (final List<Integer> family : this.families) {
                    final Map<Integer, Integer> frequencyMap = new HashMap<>();
                    for (final Integer node : family) {
                        final int element = clustering.getCluster(node);
                        frequencyMap.put(element, frequencyMap.getOrDefault(element, 0) + 1);
                    }

                    Map.Entry<Integer, Integer> x = Collections.max(frequencyMap.entrySet(),
                            Map.Entry.comparingByValue());
                    final int mode = x.getKey();
                    final int tp = x.getValue();
                    final int fp = clustering.getNNodesPerCluster()[mode] - tp;
                    final int fn = family.size() - tp;
                    score[i] += eval.get(i).eval(tp, fp, fn);
                }
            }

            return score;
        }
    }

    private static class TestCorpus {
        public final List<TestGroup> tests;

        public TestCorpus(final PreparedStatement selectEdges, final PreparedStatement selectNodes,
                final List<String> rootGroupIds,
                final List<List<String>> familyGroupIds) throws SQLException {
            this.tests = new ArrayList<>();
            for (int i = 0; i < rootGroupIds.size(); i++)
                this.tests.add(new TestGroup(selectEdges, selectNodes, rootGroupIds.get(i), familyGroupIds.get(i)));
        }

        public double[] test(final ClusteringAlgorithm algo, final double alpha, final List<ExternalEvaluation> eval) {
            final double[] scores = new double[eval.size()];
            int count = 0;

            for (final TestGroup test : this.tests) {
                final double[] testScore = test.test(algo, alpha, eval);
                for (int i = 0; i < testScore.length; i++)
                    scores[i] += testScore[i];
                count += test.families.size();
            }

            for (int i = 0; i < scores.length; i++)
                scores[i] /= count;

            return scores;
        }
    }

    public static class AlgorithmConfig {
        public final Map<String, Object> parameters;
        public final ClusteringAlgorithm algorithm;
        public final double alpha;

        public AlgorithmConfig(final Map<String, Object> parameters, final ClusteringAlgorithm algorithm,
                final double alpha) {
            this.parameters = parameters;
            this.algorithm = algorithm;
            this.alpha = alpha;
        }

        @Override
        public String toString() {
            return String.format("%s%s,alpha(%f)",
                    algorithm.getClass().getSimpleName(), parameters, alpha);
        }
    }

    public static List<AlgorithmConfig> createGridSearchAlgorithms() {
        final List<AlgorithmConfig> result = new ArrayList<>();

        final List<Double> resolutions;
        final List<Double> alphas;

        if (false) {
            resolutions = IntStream.range(1, 30)
                    .mapToObj(i -> 0.01 * i)
                    .collect(Collectors.toList());
            alphas = IntStream.range(0, 10)
                    .mapToObj(i -> 0.1 * i)
                    .collect(Collectors.toList());
            result.add(new AlgorithmConfig(new HashMap<>(), new ComponentsAlgorithm(), 1.0));
        } else if (false) {
            resolutions = IntStream.range(1, 15)
                    .mapToObj(i -> 0.001 * i)
                    .collect(Collectors.toList());
            alphas = IntStream.range(4, 10)
                    .mapToObj(i -> 0.1 * i)
                    .collect(Collectors.toList());
        } else {
            resolutions = IntStream.range(2, 8)
                    .mapToObj(i -> 0.001 * i)
                    .collect(Collectors.toList());
            alphas = IntStream.range(85, 98)
                    .mapToObj(i -> 0.01 * i)
                    .collect(Collectors.toList());
        }

        final int[] nIterationsValues = { 70 };
        final double[] randomnessValues = { 0.1 };

        for (final double alpha : alphas) {
            for (final double resolution : resolutions) {
                for (final int nIter : nIterationsValues) {
                    for (final double randomness : randomnessValues) {
                        final Map<String, Object> params = new HashMap<>();
                        params.put("resolution", resolution);
                        params.put("nIterations", nIter);
                        params.put("randomness", randomness);
                        final LeidenAlgorithm algo = new LeidenAlgorithm(resolution, nIter,
                                randomness, new Random(0));
                        result.add(new AlgorithmConfig(params, algo, alpha));
                    }
                }
            }
        }

        for (final double alpha : alphas) {
            for (final double resolution : resolutions) {
                for (final int nIter : nIterationsValues) {
                    final Map<String, Object> params = new HashMap<>();
                    params.put("resolution", resolution);
                    params.put("nIterations", nIter);
                    final LouvainAlgorithm algo = new LouvainAlgorithm(resolution, nIter, new Random(0));
                    result.add(new AlgorithmConfig(params, algo, alpha));
                }
            }
        }

        return result;
    }

    public static void main(final String[] args) throws Exception {
        try (
                final Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                final PreparedStatement selectEdges = conn.prepareStatement(SELECT_EDGES_SQL,
                        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                final PreparedStatement selectNodes = conn.prepareStatement(SELECT_NODES_SQL,
                        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);) {
            selectEdges.setFetchSize(FETCH_SIZE);
            selectNodes.setFetchSize(FETCH_SIZE);

            final TestCorpus tests = new TestCorpus(selectEdges, selectNodes, testGroupIds, testFamilies);
            System.err.println("loaded test corpus");

            final List<AlgorithmConfig> algos = createGridSearchAlgorithms();
            System.err.printf("grid searching over %d permutations\n", algos.size());

            final List<ExternalEvaluation> eval = List.of(new Jaccard(), new Dice(), new FowlkesMallows(),
                    new FalseDiscoveryRate(), new FalseNegativeRate());
            System.out.println("algo,jaccard,dice,fowlkes_mallows,fdr,fnr");

            algos.parallelStream().forEach(algo -> {
                final double[] results = tests.test(algo.algorithm, algo.alpha, eval);
                System.out.printf("\"%s\",%f,%f,%f,%f,%f\n", algo, results[0], results[1],
                        results[2], results[3], results[4]);
            });
        }
    }
}
