package com.fren_gor.ultimateAdvancementAPI.nms.util;

import com.fren_gor.ultimateAdvancementAPI.nms.wrappers.AbstractWrapper;
import com.google.common.base.Preconditions;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Range;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Immutable copy of the non-null elements of a Set.
 * <p>The implementation uses an immutable array-based list to store the elements of the original Set
 * in order to minimize the copy operation cost.
 * <p>Since ListSet is immutable and contains only the elements of one other Set,
 * it respects all the properties of a Set.
 * <p><strong>This class is thread safe.</strong>
 *
 * @param <E> The type of the elements of this Set.
 */
public final class ListSet<E> extends AbstractSet<E> implements Set<E> {

    private final E[] elements;
    private final int size;

    /**
     * Creates a new ListSet containing the elements of the provided Set.
     *
     * @param elements The elements to copy into this Set. null elements are not added to the ListSet.
     * @throws IllegalArgumentException If the provided Set is null.
     */
    public ListSet(@NotNull Set<E> elements) {
        Preconditions.checkNotNull(elements, "Set is null.");
        @SuppressWarnings("unchecked")
        E[] array = (E[]) new Object[elements.size()];
        int i = 0;
        for (E e : elements) {
            if (e != null)
                array[i++] = e;
        }
        this.elements = array;
        size = i;
    }

    private ListSet(@NotNull E[] elements, @Range(from = 0, to = Integer.MAX_VALUE) int size) {
        this.elements = elements;
        this.size = size;
    }

    /**
     * Creates a new ListSet containing the NMS objects associated with the elements of the provided Set.
     * <p>AbstractWrapper#toNMS() is called on every non-null element of the provided Set.
     *
     * @param elements The AbstractWrappers to convert to their NMS associated
     * @param <T> The type of the elements in the provided Set.
     * @return A new ListSet containing the NMS objects associated with the elements of the provided Set.
     * @throws IllegalArgumentException If the provided Set is null.
     */
    @NotNull
    @Contract(pure = true, value = "_ -> new")
    public static <T extends AbstractWrapper> ListSet<?> fromWrapperSet(@NotNull Set<T> elements) {
        Preconditions.checkNotNull(elements, "Set is null.");
        Object[] array = new Object[elements.size()];
        int i = 0;
        for (T t : elements) {
            if (t != null) {
                Object nms = t.toNMS();
                if (nms != null) // Double check not-nullity
                    array[i++] = nms;
            }
        }
        return new ListSet<>(array, i);
    }

    /**
     * 
     */
    @Override
    @NotNull
    public Iterator<E> iterator() {
        return new Iterator<>() {
            private final AtomicInteger current = new AtomicInteger(0);

            @Override
            public boolean hasNext() {
                return current.get() < size;
            }

            @Override
            public E next() {
                // It is thread-safe to not synchronize accesses to elements array
                // since it cannot be modified after being populated by the constructor
                return elements[current.getAndIncrement()];
            }
        };
    }

    /**
     * 
     */
    @Override
    public int size() {
        return size;
    }
}
