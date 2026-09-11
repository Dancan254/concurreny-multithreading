package org.javaguy.concurrentcollections;

import java.util.*;
import java.util.concurrent.*;

import static java.lang.IO.println;

public class ConcurrentHashMapDemo {
    static void main() throws InterruptedException {
//        Map<Integer, String> map = new ConcurrentHashMap<>();
//        map.put(1, "A");
//        map.put(2, "B");
//
//        Iterator<Integer> iterator = map.keySet().iterator();
//        while (iterator.hasNext()) {
//            Integer key = iterator.next();
//            map.put(3, "C"); // Modifying the map during iteration
//        }
//        //println(map);
        Map<String, Integer> counts = new ConcurrentHashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(8);

        println(Thread.activeCount());
        int totalIncrements = 8000;

        for (int i = 0; i < 8; i++) {

            executor.submit(() -> {
                for (int j = 0; j < totalIncrements; j++) {
                    counts.merge("apple", 1, Integer::sum);
                }
            });
        }

        println(Thread.activeCount());
//        for (int i = 0; i < totalIncrements; i++) {
//            executor.submit(() -> {
//                counts.put("apple", counts.getOrDefault("apple", 1) + 1);
////                counts.merge("apple", 1, Integer::sum);
////                //or this
////                counts.compute("apple", (k, v) -> v == null ? 1 : v + 1);
////                if (counts.get("apple") == null){
////                    counts.put("apple", 1);
////                } else{
////                    counts.put("apple", counts.get("apple") + 1);
////                }
//            });
//        }

        List<String> words = List.of("java", "thread", "java", "map", "thread", "java");
        Map<String, Integer> frequency = countFrequency(words, executor);
        frequency.forEach((word, count) -> println(word + " = " + count));
        println(frequency);

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        println("Expected: " + totalIncrements);
        println("Actual: " + counts.get("apple"));
    }

    public static Map<String, Integer> countFrequency(List<String> words, ExecutorService executor){

        Map<String, Integer> frequency = new ConcurrentHashMap<>();
        for(String word : words){
            executor.submit(() -> frequency.merge(word, 1, Integer::sum));
        }
        return frequency;
    }

    public static Map<String, Integer> wordFrequency(List<String> words, ExecutorService executor) throws InterruptedException {
        ConcurrentHashMap<String, Integer> frequency = new ConcurrentHashMap<>();
        List<Callable<Void>> tasks = new ArrayList<>();

        for(String word : words){
            tasks.add(() -> {
                frequency.merge(word, 1, Integer::sum);
                return null;
            });
        }
        executor.invokeAll(tasks);
        return frequency;
    }
}
