package com.actinj.immutable;

import java.util.Iterator;
import java.util.function.Consumer;

public class SinglyLinkedListIterator<T> implements Iterator<T> {
    SinglyLinkedList<T> current;

    public SinglyLinkedListIterator(final SinglyLinkedList<T> list) {
        this.current = list;
    }

    @Override
    public boolean hasNext() {
        return !current.isEmpty();
    }

    @Override
    public T next() {
        final T result = current.peek();
        current = current.tail();
        return result;
    }

    @Override
    public void remove() {
        // Not supported
        Iterator.super.remove();
    }

    @Override
    public void forEachRemaining(Consumer<? super T> action) {
        Iterator.super.forEachRemaining(action);
    }
}
