package com.actinj.immutable;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;

public class ReverseListCollector<T>
        implements Collector<T, AtomicReference<SinglyLinkedList<T>>, SinglyLinkedList<T>> {
    @Override
    public Supplier<AtomicReference<SinglyLinkedList<T>>> supplier() {
        return () -> new AtomicReference<>(new SinglyLinkedList<>());
    }

    @Override
    public BiConsumer<AtomicReference<SinglyLinkedList<T>>, T> accumulator() {
        return (ref, head) -> ref.set(ref.get().addFirst(head));
    }

    @Override
    public BinaryOperator<AtomicReference<SinglyLinkedList<T>>> combiner() {
        // this will mess with the order. But will it be called if we did not include concurrent in the characteristics?
        return (a, b) -> {
            b.get().forEach(element -> a.set(a.get().addFirst(element)));
            return a;
        };
    }

    @Override
    public Function<AtomicReference<SinglyLinkedList<T>>, SinglyLinkedList<T>> finisher() {
        return AtomicReference::get;
    }

    @Override
    public Set<Characteristics> characteristics() {
        return Collections.emptySet();
    }
}
