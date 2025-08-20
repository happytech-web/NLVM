package util;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class IList<T, P> implements Iterable<IList.INode<T, P>> {

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("IList (val: ").append(val).append(", numNode: ").append(numNode).append(") [");
        INode<T, P> current = entry;
        while (current != null) {
            sb.append(current.getVal());
            if (current.next != null) {
                sb.append(" <-> ");
            }
            current = current.next;
        }
        sb.append("]");
        return sb.toString();
    }

    private INode<T, P> entry; // Head of the list
    private INode<T, P> last;  // Tail of the list
    private P val;             // Value associated with the list
    private int numNode;       // Number of nodes in the list

    public IList(P val) {
        this.val = val;
        this.numNode = 0;
        this.entry = null;
        this.last = null;
    }

    public void setVal(P val) {
        this.val = val;
    }

    public int getNumNode() {
        return numNode;
    }

    public P getVal() {
        return val;
    }

    public void clear() {
        this.entry = null;
        this.last = null;
        this.numNode = 0;
    }

    public INode<T, P> getEntry() {
        return entry;
    }

    public INode<T, P> getLast() {
        return last;
    }

    protected void setEntry(INode<T, P> entry) {
        this.entry = entry;
    }

    protected void setLast(INode<T, P> last) {
        this.last = last;
    }

    public Stream<INode<T, P>> stream() {
        return StreamSupport.stream(this.spliterator(), false);
    }


    @Override
    public Iterator<INode<T, P>> iterator() {
        return new IIterator(entry);
    }

    private class IIterator implements Iterator<INode<T, P>> {
        private INode<T, P> current;  // 上一个返回的节点（或 null）
        private INode<T, P> next;

        IIterator(INode<T, P> head) {
            this.current = null;
            this.next = head; // 初始是 entry 节点
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public INode<T, P> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            current = next;
            next = next.getNext();
            return current;
        }

        @Override
        public void remove() {
            if (current == null || current.getParent() == null) {
                throw new IllegalStateException();
            }
            next = current.getNext(); // 删除后从下一个继续
            current.removeSelf();
            current = null;
        }
    }


    /**
     * Static nested class representing a node in the doubly linked list.
     *
     * @param <T> The type of the value stored in the node.
     * @param <P> The type of the value associated with the parent list.
     */
    public static class INode<T, P> {
        private T val;
        private INode<T, P> prev;
        private INode<T, P> next;
        private IList<T, P> parent;

        public INode(T t) {
            this.val = t;
            this.prev = null;
            this.next = null;
            this.parent = null;
        }

        public T getVal() {
            return val;
        }

        public void setVal(T newVal) {
            this.val = newVal;
        }

        public void setParent(IList<T, P> parent) {
            this.parent = parent;
        }

        public IList<T, P> getParent() {
            return parent;
        }

        public INode<T, P> getPrev() {
            return prev;
        }

        public INode<T, P> getNext() {
            return next;
        }

        /**
         * Inserts this node at the beginning of the specified father list.
         *
         * @param father The IList to insert into.
         */
        public void insertAtEntry(IList<T, P> father) {
            if (father == null) {
                throw new IllegalArgumentException("Father list cannot be null.");
            }
            this.setParent(father);
            if (father.getEntry() == null) { // List is empty
                father.numNode++; // Correctly increment numNode for the first node
                father.setEntry(this);
                father.setLast(this);
                this.prev = null;
                this.next = null;
            } else {
                insertBefore(father.getEntry());
            }
        }

        /**
         * Inserts this node at the end of the specified father list.
         *
         * @param father The IList to insert into.
         */
        public void insertAtEnd(IList<T, P> father) {
            if (father == null) {
                throw new IllegalArgumentException("Father list cannot be null.");
            }
            this.setParent(father);
            if (father.getLast() == null) { // List is empty
                father.numNode++; // Correctly increment numNode for the first node
                father.setEntry(this);
                father.setLast(this);
                this.prev = null;
                this.next = null;
            } else {
                insertAfter(father.getLast());
            }
        }

        /**
         * Inserts this node before the specified 'next' node.
         * This node's parent will be set to 'next' node's parent.
         *
         * @param next The node to insert before.
         */
        public void insertBefore(INode<T, P> next) {
            if (next == null || next.getParent() == null) {
                throw new IllegalArgumentException("Next node and its parent cannot be null.");
            }
            this.setParent(next.getParent());
            this.prev = next.prev;
            this.next = next;

            if (next.prev != null) {
                next.prev.next = this;
            } else { // 'next' was the entry node
                next.getParent().setEntry(this);
            }
            next.prev = this;
            this.parent.numNode++;
        }

        /**
         * Inserts this node after the specified 'prev' node.
         * This node's parent will be set to 'prev' node's parent.
         *
         * @param prev The node to insert after.
         */
        public void insertAfter(INode<T, P> prev) {
            if (prev == null || prev.getParent() == null) {
                throw new IllegalArgumentException("Prev node and its parent cannot be null.");
            }
            this.setParent(prev.getParent());
            this.prev = prev;
            this.next = prev.next;

            if (prev.next != null) {
                prev.next.prev = this;
            } else { // 'prev' was the last node
                prev.getParent().setLast(this);
            }
            prev.next = this;
            this.parent.numNode++;
        }

        /**
         * Inserts this node at the second to end position of the specified father list.
         * If the list is empty, it becomes the only node. If it has one node, it becomes the entry.
         *
         * @param father The IList to insert into.
         */
        public void insertAtSecondToEnd(IList<T, P> father) {
            if (father == null) {
                throw new IllegalArgumentException("Father list cannot be null.");
            }
            this.setParent(father);
            if (father.getLast() == null) { // List is empty
                father.numNode++; // Correctly increment numNode for the first node
                father.setEntry(this);
                father.setLast(this);
                this.prev = null;
                this.next = null;
            } else {
                insertBefore(father.getLast());
            }
        }

        /**
         * Removes this node from its parent list.
         *
         * @return The removed node itself.
         */
        public INode<T, P> removeSelf() {
            if (this.parent == null) {
                return this;
            }

            if (this.prev != null) {
                this.prev.next = this.next;
            } else { // This is the entry node
                this.parent.setEntry(this.next);
            }

            if (this.next != null) {
                this.next.prev = this.prev;
            } else { // This is the last node
                this.parent.setLast(this.prev);
            }

            this.parent.numNode--;
            this.prev = null;
            this.next = null;
            this.parent = null;
            return this;
        }
    }
}
