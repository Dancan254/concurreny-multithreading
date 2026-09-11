package org.javaguy.executors;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ExecutorThread {

    static void main() {
        ExecutorService executor = Executors.newSingleThreadExecutor();

        executor.submit(() -> System.out.println("Hello"));
        executor.shutdown();
    }
}
