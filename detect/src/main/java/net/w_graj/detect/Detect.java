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
import nl.cwts.networkanalysis.FastLocalMovingAlgorithm;
import nl.cwts.networkanalysis.LeidenAlgorithm;
import nl.cwts.networkanalysis.LocalMergingAlgorithm;
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

    private static final String SELECT_NODES_SQL = "SELECT id, group_id, artifact_id FROM artifacts WHERE root_group_id = ?";

    private static final String SELECT_EDGES_SQL = "SELECT coalesce(p.a, s.a_artifact_id) AS a_artifact_id, coalesce(p.b, s.b_artifact_id) AS b_artifact_id, p.is_parent, s.coeff FROM ( SELECT LEAST(from_artifact_id, to_artifact_id) AS a, GREATEST(from_artifact_id, to_artifact_id) AS b, TRUE AS is_parent FROM parents) p FULL OUTER JOIN self_artifact_overlap_coefficients s ON p.a = s.a_artifact_id AND p.b = s.b_artifact_id JOIN artifacts a1 ON a1.id = coalesce(p.a, s.a_artifact_id) AND a1.root_group_id = ? JOIN artifacts a2 ON a2.id = coalesce(p.b, s.b_artifact_id) AND a2.root_group_id = ?;";

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
            selectEdges.setString(2, rootGroupId);
            selectNodes.setString(1, rootGroupId);
            final Map<Integer, Integer> nodes = new HashMap<>();
            this.families = IntStream.range(0, familyGroupIds.size())
                    .mapToObj(i -> new ArrayList<Integer>())
                    .collect(Collectors.toList());

            final ResultSet nodeRs = selectNodes.executeQuery();
            int i = 0;
            while (nodeRs.next()) {
                int id = nodeRs.getInt(1);
                String groupId = nodeRs.getString(2);
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
            LargeDoubleArray edgeWeights = new LargeDoubleArray(0);
            for (int i = 0; i < this.edgeParents.size(); i++) {
                double x;
                final double coeff = this.edgeCoeffs.get(i);
                final boolean parent = this.edgeParents.get(i);
                if (parent) {
                    if (coeff == 0.0)
                        x = alpha;
                    else
                        x = coeff + (1.0 - coeff) * alpha;
                } else {
                    x = coeff;
                }
                edgeWeights.append(x);
            }

            Network network = new Network(this.nNodes, false, edges, edgeWeights, false, true);
            double[] score = new double[eval.size()];

            final Clustering clustering = algo.findClustering(network);

            for (int i = 0; i < eval.size(); i++) {
                for (List<Integer> family : this.families) {
                    final Map<Integer, Integer> frequencyMap = new HashMap<>();
                    for (Integer node : family) {
                        final int element = clustering.getCluster(node);
                        frequencyMap.put(element, frequencyMap.getOrDefault(element, 0) + 1);
                    }

                    Map.Entry<Integer, Integer> x = Collections.max(frequencyMap.entrySet(),
                            Map.Entry.comparingByValue());
                    final int mode = x.getKey();
                    final int tp = x.getValue();
                    final int fp = clustering.getNNodesPerCluster()[mode] - tp;
                    final int fn = family.size() - tp;
                    //System.out.printf("%d,%d,%d\n", tp, fp, fn);
                    score[i] += eval.get(i).eval(tp, fp, fn);
                }
            }

            return score;
        }
    }

    private static class TestCorpus {
        public final List<TestGroup> tests;

        public TestCorpus(PreparedStatement selectEdges, PreparedStatement selectNodes, List<String> rootGroupIds,
                List<List<String>> familyGroupIds) throws SQLException {
            this.tests = new ArrayList<>();
            for (int i = 0; i < rootGroupIds.size(); i++)
                this.tests.add(new TestGroup(selectEdges, selectNodes, rootGroupIds.get(i), familyGroupIds.get(i)));
        }

        public double[] test(ClusteringAlgorithm algo, double alpha, List<ExternalEvaluation> eval) {
            double[] scores = new double[eval.size()];
            int count = 0;

            for (TestGroup test : this.tests) {
                double[] testScore = test.test(algo, alpha, eval);
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

        public AlgorithmConfig(Map<String, Object> parameters, ClusteringAlgorithm algorithm, double alpha) {
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

        final List<Double> resolutions = IntStream.range(1, 20)
                .mapToObj(i -> 0.01 * i)
                .collect(Collectors.toList());
        final List<Double> alphas = IntStream.range(1, 10)
                .mapToObj(i -> 0.02 * i)
                .collect(Collectors.toList());
        final int[] nIterationsValues = { 50 };
        final double[] randomnessValues = { 0.1 };

        // for (final double resolution : resolutions) {
        // for (final int nIter : nIterationsValues) {
        // final Map<String, Object> params = new HashMap<>();
        // params.put("resolution", resolution);
        // params.put("nIterations", nIter);
        // final FastLocalMovingAlgorithm algo = new
        // FastLocalMovingAlgorithm(resolution, nIter,
        // new Random(0));
        // result.add(new AlgorithmConfig(params, algo, combo));
        // }
        // }

        // for (final double resolution : resolutions) {
        // for (final int nIter : nIterationsValues) {
        // for (final double randomness : randomnessValues) {
        // final Map<String, Object> params = new HashMap<>();
        // params.put("resolution", resolution);
        // params.put("nIterations", nIter);
        // params.put("randomness", randomness);
        // final LeidenAlgorithm algo = new LeidenAlgorithm(resolution, nIter,
        // randomness, new Random(0));
        // result.add(new AlgorithmConfig(params, algo));
        // }
        // }
        // }

        // for (final double resolution : resolutions) {
        // for (final double randomness : randomnessValues) {
        // final Map<String, Object> params = new HashMap<>();
        // params.put("resolution", resolution);
        // params.put("randomness", randomness);
        // final LocalMergingAlgorithm algo = new LocalMergingAlgorithm(resolution,
        // randomness, new Random(0));
        // result.add(new AlgorithmConfig(params, algo, combo));
        // }
        // }

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

        // result.add(new AlgorithmConfig(new HashMap<>(), new ComponentsAlgorithm()));

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

            algos.parallelStream().forEach(algo -> {
                final List<ExternalEvaluation> eval = List.of(new Jaccard(), new Dice(), new FowlkesMallows());
                final double[] results = tests.test(algo.algorithm, algo.alpha, eval);
                System.out.printf("\"%s\",%f,%f,%f\n", algo, results[0], results[1],
                        results[2]);
            });

            // final ClusteringAlgorithm algo = new LeidenAlgorithm(0.008, 50, 0.1, new
            // Random(0));
            // final Combination combi = new WeightedLinearCombination(0.1);
            // final List<ExternalEvaluation> eval = List.of(new Jaccard());
            // tests.test(algo, combi, eval);
        }
    }
}
