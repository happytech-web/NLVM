package pass.MCPass.util;

import backend.mir.operand.reg.VReg;
import backend.mir.operand.reg.PReg;
import backend.mir.operand.reg.Register;
import java.util.*;

/**
 * 干扰图实现 - 支持预着色节点
 * 用于图着色寄存器分配算法
 */
public class InterferenceGraph {

    /**
     * 干扰图节点 - 支持VReg和PReg
     */
    public static class Node {
        public final Register register;            // 寄存器（VReg或PReg）
        public final Set<Node> neighbors = new HashSet<>();  // 邻居节点
        public PReg color = null;                  // 分配的物理寄存器
        public int degree = 0;                     // 节点度数
        public double spillCost = 0.0;             // 溢出代价
        public final boolean isPrecolored;         // 是否为预着色节点

        public Node(Register register) {
            this.register = register;
            this.isPrecolored = register instanceof PReg;
            if (isPrecolored) {
                this.color = (PReg) register;  // PReg节点预着色
            }
        }

        /**
         * 添加邻居节点（双向）
         */
        public void addNeighbor(Node neighbor) {
            if (!neighbors.contains(neighbor) && neighbor != this) {
                neighbors.add(neighbor);
                neighbor.neighbors.add(this);
                degree++;
                neighbor.degree++;
            }
        }

        /**
         * 移除邻居节点（双向）
         */
        public void removeNeighbor(Node neighbor) {
            if (neighbors.contains(neighbor)) {
                neighbors.remove(neighbor);
                neighbor.neighbors.remove(this);
                degree--;
                neighbor.degree--;
            }
        }

        /**
         * 检查节点是否可简化（度数 < k 且不是预着色节点）
         */
        public boolean isSimplifiable(int k) {
            return !isPrecolored && degree < k;
        }

        /**
         * 获取VReg（如果是VReg节点）
         */
        public VReg getVReg() {
            return register instanceof VReg ? (VReg) register : null;
        }

        /**
         * 获取PReg（如果是PReg节点）
         */
        public PReg getPReg() {
            return register instanceof PReg ? (PReg) register : null;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            Node node = (Node) obj;
            return Objects.equals(register, node.register);
        }

        @Override
        public int hashCode() {
            return Objects.hash(register);
        }

        @Override
        public String toString() {
            return String.format("Node{reg=%s, precolored=%s, degree=%d, spillCost=%.2f, color=%s}",
                    register, isPrecolored, degree, spillCost, color);
        }
    }

    // 图的节点映射
    private final Map<Register, Node> nodes = new HashMap<>();

    /**
     * 添加VReg节点到干扰图
     */
    public Node addVRegNode(VReg vreg) {
        return nodes.computeIfAbsent(vreg, Node::new);
    }

    /**
     * 添加PReg节点到干扰图（预着色节点）
     */
    public Node addPRegNode(PReg preg) {
        return nodes.computeIfAbsent(preg, Node::new);
    }

    /**
     * 添加干扰边
     * 表示两个寄存器不能分配相同的物理寄存器
     */
    public void addEdge(Register r1, Register r2) {
        if (r1.equals(r2)) {
            return; // 不允许自环
        }

        Node n1 = nodes.computeIfAbsent(r1, Node::new);
        Node n2 = nodes.computeIfAbsent(r2, Node::new);
        n1.addNeighbor(n2);
    }

    /**
     * 计算所有VReg节点的溢出代价
     * 溢出代价 = (使用次数 * 10^循环深度) / max(度数, 1)
     * PReg节点不计算溢出代价（永远不溢出）
     */
    public void calculateSpillCosts(Map<VReg, Double> useCounts, Map<VReg, Double> loopDepths) {
        for (Node node : nodes.values()) {
            if (!node.isPrecolored && node.getVReg() != null) {
                VReg vreg = node.getVReg();
                double useCount = useCounts.getOrDefault(vreg, 1.0);
                double loopDepth = loopDepths.getOrDefault(vreg, 1.0);

                // 溢出代价公式：使用频率越高，循环嵌套越深，代价越高
                // 度数越高的节点，溢出后对其他节点的影响越大，所以代价降低
                node.spillCost = useCount * Math.pow(10, loopDepth) / Math.max(node.degree, 1);
            }
        }
    }

    /**
     * 获取指定寄存器的节点
     */
    public Node getNode(Register register) {
        return nodes.get(register);
    }

    /**
     * 获取指定虚拟寄存器的节点
     */
    public Node getVRegNode(VReg vreg) {
        return nodes.get(vreg);
    }

    /**
     * 获取指定物理寄存器的节点
     */
    public Node getPRegNode(PReg preg) {
        return nodes.get(preg);
    }

    /**
     * 获取所有节点
     */
    public Collection<Node> getNodes() {
        return new ArrayList<>(nodes.values());
    }

    /**
     * 获取图的节点数量
     */
    public int getNodeCount() {
        return nodes.size();
    }

    /**
     * 获取图的边数量
     */
    public int getEdgeCount() {
        int edges = 0;
        for (Node node : nodes.values()) {
            edges += node.neighbors.size();
        }
        return edges / 2; // 每条边被计算两次
    }

    /**
     * 检查两个寄存器是否干扰
     */
    public boolean interferes(Register r1, Register r2) {
        Node n1 = nodes.get(r1);
        Node n2 = nodes.get(r2);

        if (n1 == null || n2 == null) {
            return false;
        }

        return n1.neighbors.contains(n2);
    }

    /**
     * 获取VReg节点的有效度数（只考虑未着色的VReg邻居，PReg邻居永远算在内）
     */
    public int getEffectiveDegree(VReg vreg) {
        Node node = nodes.get(vreg);
        if (node == null) {
            return 0;
        }

        int effectiveDegree = 0;
        for (Node neighbor : node.neighbors) {
            // PReg节点永远算在度数内，VReg节点只有未着色时才算
            if (neighbor.isPrecolored || neighbor.color == null) {
                effectiveDegree++;
            }
        }

        return effectiveDegree;
    }

    /**
     * 获取节点邻居使用的颜色集合（包括PReg的预着色）
     */
    public Set<PReg> getNeighborColors(Register register) {
        Node node = nodes.get(register);
        if (node == null) {
            return new HashSet<>();
        }

        Set<PReg> usedColors = new HashSet<>();
        for (Node neighbor : node.neighbors) {
            if (neighbor.color != null) {
                usedColors.add(neighbor.color);
            }
        }

        return usedColors;
    }

    /**
     * 为VReg节点着色（PReg节点不能重新着色）
     */
    public boolean colorVRegNode(VReg vreg, PReg color) {
        Node node = nodes.get(vreg);
        if (node == null || node.isPrecolored) {
            return false;
        }

        // 检查颜色冲突
        for (Node neighbor : node.neighbors) {
            if (color.equals(neighbor.color)) {
                return false; // 颜色冲突
            }
        }

        node.color = color;
        return true;
    }

    /**
     * 移除VReg节点的颜色（PReg节点不能移除颜色）
     */
    public void uncolorVRegNode(VReg vreg) {
        Node node = nodes.get(vreg);
        if (node != null && !node.isPrecolored) {
            node.color = null;
        }
    }

    /**
     * 检查着色是否有效
     */
    public boolean isValidColoring() {
        for (Node node : nodes.values()) {
            if (node.color != null) {
                for (Node neighbor : node.neighbors) {
                    if (node.color.equals(neighbor.color)) {
                        return false; // 发现颜色冲突
                    }
                }
            }
        }
        return true;
    }

    /**
     * 获取已着色的VReg节点数量
     */
    public int getColoredVRegNodeCount() {
        int count = 0;
        for (Node node : nodes.values()) {
            if (!node.isPrecolored && node.color != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * 获取未着色的VReg节点数量
     */
    public int getUncoloredVRegNodeCount() {
        int count = 0;
        for (Node node : nodes.values()) {
            if (!node.isPrecolored && node.color == null) {
                count++;
            }
        }
        return count;
    }

    /**
     * 获取VReg节点总数
     */
    public int getVRegNodeCount() {
        int count = 0;
        for (Node node : nodes.values()) {
            if (!node.isPrecolored) {
                count++;
            }
        }
        return count;
    }

    /**
     * 获取PReg节点总数
     */
    public int getPRegNodeCount() {
        int count = 0;
        for (Node node : nodes.values()) {
            if (node.isPrecolored) {
                count++;
            }
        }
        return count;
    }

    /**
     * 重置所有VReg节点的颜色（PReg节点保持预着色）
     */
    public void resetVRegColors() {
        for (Node node : nodes.values()) {
            if (!node.isPrecolored) {
                node.color = null;
            }
        }
    }

    /**
     * 获取图的统计信息
     */
    public String getStatistics() {
        int totalNodes = getNodeCount();
        int vregNodes = getVRegNodeCount();
        int pregNodes = getPRegNodeCount();
        int totalEdges = getEdgeCount();
        int coloredVRegs = getColoredVRegNodeCount();

        double avgDegree = totalNodes > 0 ? (2.0 * totalEdges) / totalNodes : 0.0;

        return String.format(
                "干扰图统计: 总节点=%d (VReg=%d, PReg=%d), 边=%d, 平均度数=%.2f, 已着色VReg=%d, 未着色VReg=%d",
                totalNodes, vregNodes, pregNodes, totalEdges, avgDegree, coloredVRegs, vregNodes - coloredVRegs
        );
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("InterferenceGraph{\n");
        sb.append("  ").append(getStatistics()).append("\n");

        // 按寄存器名称排序输出
        List<Node> sortedNodes = new ArrayList<>(nodes.values());
        sortedNodes.sort(Comparator.comparing(n -> n.register.toString()));

        for (Node node : sortedNodes) {
            sb.append("  ").append(node).append("\n");
            sb.append("    邻居: ");

            List<Node> sortedNeighbors = new ArrayList<>(node.neighbors);
            sortedNeighbors.sort(Comparator.comparing(n -> n.register.toString()));

            for (int i = 0; i < sortedNeighbors.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(sortedNeighbors.get(i).register);
            }
            sb.append("\n");
        }
        sb.append("}");

        return sb.toString();
    }
}