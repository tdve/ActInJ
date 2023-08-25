package com.actinj.immutable;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

class SinglyLinkedListTest {

    @Test
    @DisplayName("Reverse List Collector")
    void testCollector() {
        final SinglyLinkedList<String> december = Arrays.asList("January", "February", "March", "April", "May", "June",
                "July", "August", "September", "October", "November", "December").stream()
                .collect(new ReverseListCollector<>());
        Assertions.assertEquals("December", december.peek());
        final SinglyLinkedList<String> november = december.tail();
        Assertions.assertEquals("November", november.peek());
        final SinglyLinkedList<String> october = november.tail();
        Assertions.assertEquals("October", october.peek());
        final SinglyLinkedList<String> september = october.tail();
        Assertions.assertEquals("September", september.peek());
        final SinglyLinkedList<String> august = september.tail();
        Assertions.assertEquals("August", august.peek());
        final SinglyLinkedList<String> july = august.tail();
        Assertions.assertEquals("July", july.peek());
        final SinglyLinkedList<String> june = july.tail();
        Assertions.assertEquals("June", june.peek());
        final SinglyLinkedList<String> may = june.tail();
        Assertions.assertEquals("May", may.peek());
        final SinglyLinkedList<String> april = may.tail();
        Assertions.assertEquals("April", april.peek());
        final SinglyLinkedList<String> march = april.tail();
        Assertions.assertEquals("March", march.peek());
        final SinglyLinkedList<String> february = march.tail();
        Assertions.assertEquals("February", february.peek());
        final SinglyLinkedList<String> january = february.tail();
        Assertions.assertEquals("January", january.peek());
        final SinglyLinkedList<String> empty = january.tail();
        Assertions.assertTrue(empty.isEmpty());
        Assertions.assertEquals(12, december.size());
    }

    @Test
    @DisplayName("Test Stream")
    void testStream() {
        SinglyLinkedList<Integer> testList = new SinglyLinkedList<>();
        for (int i = 0; i < 20; i++)
            testList = testList.addFirst(i);
        List<Integer> collected = testList.stream().collect(Collectors.toList());
        Assertions.assertEquals(Arrays.asList(19, 18, 17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
                collected);
    }

    @Test
    @DisplayName("Test Iterator")
    void testIterator() {
        SinglyLinkedList<Double> empty = new SinglyLinkedList<>();
        SinglyLinkedList<Double> one = empty.addFirst(1.1);
        SinglyLinkedList<Double> two = one.addFirst(2.2);
        SinglyLinkedList<Double> three = two.addFirst(3.3);
        Iterator<Double> it = three.iterator();
        Assertions.assertTrue(it.hasNext());
        Assertions.assertEquals(3.3, it.next());
        Assertions.assertTrue(it.hasNext());
        Assertions.assertEquals(2.2, it.next());
        Assertions.assertTrue(it.hasNext());
        Assertions.assertEquals(1.1, it.next());
        Assertions.assertFalse(it.hasNext());
    }

}
