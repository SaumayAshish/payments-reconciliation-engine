package com.saumay.payments.matching;

import com.saumay.payments.domain.model.LineKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the eligible pairs of one tier into matches without depending on input order (ADR-0003).
 *
 * <p>Pairs are processed in groups of equal score, best score first. Within a group, a pair is accepted only
 * when neither of its lines appears in any other live pair of that group. If a line has two equally good
 * partners, nobody wins: every line involved is held back as an {@link AmbiguousGroup}. Lines claimed or held
 * back in a better group never compete in a worse one. Hash maps and sets are used for lookups only; every
 * result list is built in a sorted order.
 */
public final class PairResolver {

    private PairResolver() {
    }

    /** A line on one specific side, so a statement line and a ledger entry can never be confused. */
    private record Node(boolean statementSide, LineKey key) {
    }

    public static TierOutcome resolve(List<MatchableLine> statements, List<MatchableLine> ledger,
                                      List<ScoredPair> pairs, MatchingConfig config) {
        requireSingleTier(pairs);
        List<ScoredPair> sorted = new ArrayList<>(pairs);
        sorted.sort(ScoredPair.ORDER);

        Set<LineKey> takenStatements = new HashSet<>();
        Set<LineKey> takenLedger = new HashSet<>();
        List<Match> matches = new ArrayList<>();
        List<AmbiguousGroup> ambiguous = new ArrayList<>();

        int start = 0;
        while (start < sorted.size()) {
            MatchScore score = sorted.get(start).score();
            int end = start;
            while (end < sorted.size() && sorted.get(end).score().equals(score)) {
                end++;
            }
            resolveGroup(sorted.subList(start, end), takenStatements, takenLedger, config, matches, ambiguous);
            start = end;
        }
        return new TierOutcome(matches, ambiguous, remaining(statements, takenStatements),
                remaining(ledger, takenLedger));
    }

    private static void requireSingleTier(List<ScoredPair> pairs) {
        for (ScoredPair pair : pairs) {
            if (pair.tier() != pairs.get(0).tier()) {
                throw new IllegalArgumentException("All pairs resolved together must come from one tier");
            }
        }
    }

    private static void resolveGroup(List<ScoredPair> group, Set<LineKey> takenStatements,
                                     Set<LineKey> takenLedger, MatchingConfig config, List<Match> matches,
                                     List<AmbiguousGroup> ambiguous) {
        List<ScoredPair> live = new ArrayList<>();
        for (ScoredPair pair : group) {
            if (!takenStatements.contains(pair.statement().key()) && !takenLedger.contains(pair.ledger().key())) {
                live.add(pair);
            }
        }
        Map<LineKey, Integer> statementDegree = new HashMap<>();
        Map<LineKey, Integer> ledgerDegree = new HashMap<>();
        for (ScoredPair pair : live) {
            statementDegree.merge(pair.statement().key(), 1, Integer::sum);
            ledgerDegree.merge(pair.ledger().key(), 1, Integer::sum);
        }
        List<ScoredPair> contested = new ArrayList<>();
        for (ScoredPair pair : live) {
            if (statementDegree.get(pair.statement().key()) == 1 && ledgerDegree.get(pair.ledger().key()) == 1) {
                matches.add(Match.ofPair(pair, config));
                takenStatements.add(pair.statement().key());
                takenLedger.add(pair.ledger().key());
            } else {
                contested.add(pair);
            }
        }
        ambiguous.addAll(components(contested));
        for (ScoredPair pair : contested) {
            takenStatements.add(pair.statement().key());
            takenLedger.add(pair.ledger().key());
        }
    }

    /** Groups contested pairs into connected clusters of lines, in the order the clusters first appear. */
    private static List<AmbiguousGroup> components(List<ScoredPair> contested) {
        Map<Node, Node> parent = new HashMap<>();
        for (ScoredPair pair : contested) {
            union(parent, new Node(true, pair.statement().key()), new Node(false, pair.ledger().key()));
        }
        Map<Node, List<ScoredPair>> byRoot = new LinkedHashMap<>();
        for (ScoredPair pair : contested) {
            Node root = find(parent, new Node(true, pair.statement().key()));
            byRoot.computeIfAbsent(root, r -> new ArrayList<>()).add(pair);
        }
        List<AmbiguousGroup> groups = new ArrayList<>();
        for (List<ScoredPair> cluster : byRoot.values()) {
            Map<LineKey, MatchableLine> statements = new LinkedHashMap<>();
            Map<LineKey, MatchableLine> ledger = new LinkedHashMap<>();
            for (ScoredPair pair : cluster) {
                statements.putIfAbsent(pair.statement().key(), pair.statement());
                ledger.putIfAbsent(pair.ledger().key(), pair.ledger());
            }
            List<MatchableLine> statementSide = new ArrayList<>(statements.values());
            List<MatchableLine> ledgerSide = new ArrayList<>(ledger.values());
            statementSide.sort(MatchableLine.CANONICAL_ORDER);
            ledgerSide.sort(MatchableLine.CANONICAL_ORDER);
            groups.add(new AmbiguousGroup(cluster.get(0).tier(), cluster.get(0).score(), statementSide,
                    ledgerSide));
        }
        return groups;
    }

    private static void union(Map<Node, Node> parent, Node a, Node b) {
        parent.putIfAbsent(a, a);
        parent.putIfAbsent(b, b);
        Node rootA = find(parent, a);
        Node rootB = find(parent, b);
        if (!rootA.equals(rootB)) {
            parent.put(rootB, rootA);
        }
    }

    private static Node find(Map<Node, Node> parent, Node node) {
        Node root = node;
        while (!parent.get(root).equals(root)) {
            root = parent.get(root);
        }
        return root;
    }

    private static List<MatchableLine> remaining(List<MatchableLine> lines, Set<LineKey> taken) {
        List<MatchableLine> result = new ArrayList<>();
        for (MatchableLine line : lines) {
            if (!taken.contains(line.key())) {
                result.add(line);
            }
        }
        result.sort(MatchableLine.CANONICAL_ORDER);
        return result;
    }
}