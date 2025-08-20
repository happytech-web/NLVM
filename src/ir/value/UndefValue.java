package ir.value;

import ir.type.Type;
import java.util.HashMap;
import java.util.Map;

// helper class for dead code elimination
public class UndefValue extends Value {
    private static final Map<Type, UndefValue> undefs = new HashMap<>();

    private UndefValue(Type type) {
        super(type, "undef");
    }

    /**
     * Returns a cached undef value per type. Note: this is a shared singleton per
     * type.
     * Do NOT use this in places where a unique placeholder identity is required.
     */
    public static UndefValue get(Type type) {
        return undefs.computeIfAbsent(type, UndefValue::new);
    }

    /**
     * Create a fresh undef value instance for the given type.
     * This avoids aliasing when a unique placeholder object is needed.
     */
    public static UndefValue createUnique(Type type) {
        return new UndefValue(type);
    }

    @Override
    public String getReference() {
        return "undef";
    }

    @Override
    public String toNLVM() {
        return "undef";
    }

    @Override
    public String getHash() {
        return "UNDEF" + getType().getHash();
    }
}