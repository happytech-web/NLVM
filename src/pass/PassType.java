package pass;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * pass type factory
 */
public interface PassType<T extends Pass> {
    /* constructor */
     Supplier<T> constructor();

    /** default factory method: constructor().get() */
    default T create() {
        return constructor().get();
    }

    /** get the enum name */
    default String getName() {
        return ((Enum<?>) this).name().toLowerCase();
    }
}
