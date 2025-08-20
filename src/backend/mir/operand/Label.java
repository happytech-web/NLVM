package backend.mir.operand;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 标签操作数
 * 用于分支指令的目标
 */
public class Label extends Operand {
    private static final AtomicInteger ANON_LABEL_COUNTER = new AtomicInteger(0);
    
    private String name;
    
    public Label(String name) {
        this.name = name;
    }
    
    /**
     * 创建具名标签
     */
    public static Label named(String name) {
        return new Label(name);
    }
    
    /**
     * 创建匿名标签
     */
    public static Label anon() {
        return new Label(".L" + ANON_LABEL_COUNTER.getAndIncrement());
    }
    
    public String getName() {
        return name;
    }
    
    @Override
    public boolean isLabel() {
        return true;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Label label = (Label) obj;
        return Objects.equals(name, label.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return name;
    }
}
