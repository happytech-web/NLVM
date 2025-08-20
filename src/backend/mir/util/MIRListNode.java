package backend.mir.util;

/**
 * 标记接口，用于表示可以在MIRList中使用的节点,比如Func,BB,Inst等。
 */
public interface MIRListNode {
    MIRList.MIRNode<?, ?> _getNode();
}