package backend.mir.util;

import java.util.*;
import java.util.stream.Stream;

public class MIRList<T, P> implements Iterable<MIRList.MIRNode<T, P>> {

    private MIRNode<T, P> entry;
    private MIRNode<T, P> last;
    private P parent;
    private int size;

    public MIRList(P parent) {
        this.parent = parent;
        this.size = 0;
        this.entry = null;
        this.last = null;
    }

    // === 基础访问方法 ===
    public P getParent() {
        return parent;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public MIRNode<T, P> getEntry() {
        return entry;
    }

    public MIRNode<T, P> getLast() {
        return last;
    }

    protected void setEntry(MIRNode<T, P> entry) {
        this.entry = entry;
    }

    protected void setLast(MIRNode<T, P> last) {
        this.last = last;
    }

    // === 迭代器支持 ===
    @Override
    public Iterator<MIRNode<T, P>> iterator() {
        return new MIRIterator(entry);
    }

    public void clear() {
        MIRNode<T, P> current = entry;
        while (current != null) {
            MIRNode<T, P> next = current.getNext();
            current.removeSelf();
            current = next;
        }
        entry = null;
        last = null;
        size = 0;
    }

    // 添加ArrayList兼容的方法
    public List<T> toList() {
        List<T> result = new ArrayList<>();
        for (MIRNode<T, P> node : this) {
            result.add(node.getValue());
        }
        return result;
    }

    public T get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        MIRNode<T, P> current = entry;
        for (int i = 0; i < index; i++) {
            current = current.getNext();
        }
        return current.getValue();
    }

    /**
     * 在指定位置插入元素列表
     */
    @SuppressWarnings("unchecked")
    public void addAll(int index, List<T> items) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }

        if (items.isEmpty()) {
            return;
        }

        if (index == 0) {
            // 在开头插入
            for (int i = items.size() - 1; i >= 0; i--) {
                T item = items.get(i);
                if (item instanceof MIRListNode) {
                    MIRNode<T, P> node = (MIRNode<T, P>) ((MIRListNode) item)._getNode();
                    if (entry == null) {
                        node.insertAtEnd(this);
                    } else {
                        node.insertBefore(entry);
                    }
                }
            }
        } else if (index == size) {
            // 在末尾插入
            for (T item : items) {
                if (item instanceof MIRListNode) {
                    MIRNode<T, P> node = (MIRNode<T, P>) ((MIRListNode) item)._getNode();
                    node.insertAtEnd(this);
                }
            }
        } else {
            // 在中间插入
            MIRNode<T, P> target = entry;
            for (int i = 0; i < index; i++) {
                target = target.getNext();
            }

            for (T item : items) {
                if (item instanceof MIRListNode) {
                    MIRNode<T, P> node = (MIRNode<T, P>) ((MIRListNode) item)._getNode();
                    node.insertBefore(target);
                }
            }
        }
    }

    /**
     * 替换指定索引的元素
     */
    @SuppressWarnings("unchecked")
    public T set(int index, T element) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }

        MIRNode<T, P> current = entry;
        for (int i = 0; i < index; i++) {
            current = current.getNext();
        }

        T oldValue = current.getValue();

        if (element instanceof MIRListNode) {
            MIRNode<T, P> newNode = (MIRNode<T, P>) ((MIRListNode) element)._getNode();
            newNode.insertBefore(current);
            current.removeSelf();
        }

        return oldValue;
    }

    /**
     * 批量替换所有元素
     */
    public void setAll(List<T> newItems) {
        clear();
        addAll(0, newItems);
    }

    /**
     * 检查是否包含指定元素
     */
    public boolean contains(T item) {
        for (MIRNode<T, P> node : this) {
            if (Objects.equals(node.getValue(), item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 查找元素的索引
     */
    public int indexOf(T item) {
        int index = 0;
        for (MIRNode<T, P> node : this) {
            if (Objects.equals(node.getValue(), item)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private class MIRIterator implements Iterator<MIRNode<T, P>> {
        private MIRNode<T, P> current;
        private MIRNode<T, P> next;

        MIRIterator(MIRNode<T, P> head) {
            this.current = null;
            this.next = head;
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public MIRNode<T, P> next() {
            if (!hasNext())
                throw new NoSuchElementException();
            current = next;
            next = next.getNext();
            return current;
        }

        @Override
        public void remove() {
            if (current == null || current.getParent() == null) {
                throw new IllegalStateException();
            }
            next = current.getNext();
            current.removeSelf();
            current = null;
        }
    }

    public Stream<MIRNode<T,P>> stream() {
        Iterator<MIRNode<T,P>> it = this.iterator();
        Iterable<MIRNode<T,P>> iterable = () -> it;
        return java.util.stream.StreamSupport.stream(iterable.spliterator(), false);
    }

    /**
     * MIR链表节点
     */
    public static class MIRNode<T, P> {
        private T value;
        private MIRNode<T, P> prev;
        private MIRNode<T, P> next;
        private MIRList<T, P> parent;

        public MIRNode(T value) {
            this.value = value;
            this.prev = null;
            this.next = null;
            this.parent = null;
        }

        // === 访问方法 ===
        public T getValue() {
            return value;
        }

        public MIRNode<T, P> getPrev() {
            return prev;
        }

        public MIRNode<T, P> getNext() {
            return next;
        }

        public MIRList<T, P> getParent() {
            return parent;
        }

        public void setParent(MIRList<T, P> parent) {
            this.parent = parent;
        }

        // === 插入操作 ===
        public void insertAtEnd(MIRList<T, P> parentList) {
            if (parentList == null) {
                throw new IllegalArgumentException("Parent list cannot be null");
            }

            this.setParent(parentList);

            if (parentList.getLast() == null) {
                parentList.size++;
                parentList.setEntry(this);
                parentList.setLast(this);
                this.prev = null;
                this.next = null;
            } else {
                insertAfter(parentList.getLast());
            }
        }

        public void insertBefore(MIRNode<T, P> nextNode) {
            if (nextNode == null || nextNode.getParent() == null) {
                throw new IllegalArgumentException("Next node and its parent cannot be null");
            }

            this.setParent(nextNode.getParent());
            this.prev = nextNode.prev;
            this.next = nextNode;

            if (nextNode.prev != null) {
                nextNode.prev.next = this;
            } else {
                nextNode.getParent().setEntry(this);
            }

            nextNode.prev = this;
            this.parent.size++;
        }

        public void insertAfter(MIRNode<T, P> prevNode) {
            if (prevNode == null || prevNode.getParent() == null) {
                throw new IllegalArgumentException("Prev node and its parent cannot be null");
            }

            this.setParent(prevNode.getParent());
            this.prev = prevNode;
            this.next = prevNode.next;

            if (prevNode.next != null) {
                prevNode.next.prev = this;
            } else {
                prevNode.getParent().setLast(this);
            }

            prevNode.next = this;
            this.parent.size++;
        }

        public MIRNode<T, P> removeSelf() {
            if (this.parent == null)
                return this;

            if (this.prev != null) {
                this.prev.next = this.next;
            } else {
                this.parent.setEntry(this.next);
            }

            if (this.next != null) {
                this.next.prev = this.prev;
            } else {
                this.parent.setLast(this.prev);
            }

            this.parent.size--;
            this.prev = null;
            this.next = null;
            this.parent = null;
            return this;
        }
    }

}
