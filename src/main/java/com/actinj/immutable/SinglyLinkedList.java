package com.actinj.immutable;

import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * An immutable Singly Linked List that integrates nicely in Java streams. Only the minimum necessary operations are
 * provided, as most other functionality can trivially be implemented with streams
 *
 * @param <T>
 *            The type to be stored in the list
 */
public class SinglyLinkedList<T> implements Iterable<T> {
    final T head;
    final SinglyLinkedList<T> tail;

    private SinglyLinkedList(final T head, final SinglyLinkedList<T> tail) {
        this.head = head;
        this.tail = tail;
    }

    public SinglyLinkedList() {
        this(null, null);
    }

    public SinglyLinkedList<T> addFirst(final T newHead) {
        return new SinglyLinkedList<>(newHead, this);
    }

    public int size() {
        int result = 0;
        for (final T element : this)
            result++;
        return result;
    }

    public boolean isEmpty() {
        return null == tail;
    }

    public T peek() {
        return head;
    }

    public SinglyLinkedList<T> tail() {
        return tail;
    }

    public Stream<T> stream() {
        return StreamSupport.stream(spliterator(), false);
    }

    @Override
    public Iterator<T> iterator() {
        return new SinglyLinkedListIterator<>(this);
    }

    @Override
    public void forEach(Consumer<? super T> action) {
        Iterable.super.forEach(action);
    }

    @Override
    public Spliterator<T> spliterator() {
        return Spliterators.spliterator(iterator(), size(),
                Spliterator.SIZED | Spliterator.IMMUTABLE | Spliterator.ORDERED | Spliterator.SUBSIZED);
    }

    // TODO: equals and hashcode
}
