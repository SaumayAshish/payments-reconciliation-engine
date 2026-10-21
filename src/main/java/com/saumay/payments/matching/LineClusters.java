package com.saumay.payments.matching;

import com.saumay.payments.domain.model.LineKey;
import java.util.HashMap;
import java.util.Map;

/**
 * Union-find over lines, used to group things that share a line into clusters. Nodes carry their side, so a
 * statement line and a ledger entry can never be mistaken for each other. The map is used for lookups only;
 * callers decide the order in which clusters are reported.
 */
final class LineClusters {

    /** A line on one specific side of the reconciliation. */
    record Node(boolean statementSide, LineKey key) {

        static Node statement(MatchableLine line) {
            return new Node(true, line.key());
        }

        static Node ledger(MatchableLine line) {
            return new Node(false, line.key());
        }
    }

    private final Map<Node, Node> parent = new HashMap<>();

    void union(Node a, Node b) {
        Node rootA = find(a);
        Node rootB = find(b);
        if (!rootA.equals(rootB)) {
            parent.put(rootB, rootA);
        }
    }

    Node find(Node node) {
        parent.putIfAbsent(node, node);
        Node root = node;
        while (!parent.get(root).equals(root)) {
            root = parent.get(root);
        }
        return root;
    }
}