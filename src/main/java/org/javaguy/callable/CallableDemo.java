package org.javaguy.callable;

import org.javaguy.multithread.ThreadColor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;

import static java.lang.IO.print;
import static java.lang.IO.println;

public class CallableDemo {

    private static int sum(int start, int end, int delta, String colorString) {
        var threadColor = ThreadColor.ANSI_RESET;
        try {
            threadColor = ThreadColor.valueOf("ANSI_" + colorString.toUpperCase());
        } catch (IllegalArgumentException e) {
            //throw new RuntimeException(e);
        }

        String color = threadColor.getColor();
        int sum = 0;
        for (int i = start; i <= end; i += delta) {
            sum += i;
        }
        return sum;
    }

    static void main() {
        var exec = Executors.newCachedThreadPool();
        List<Callable<Integer>> tasks = List.of(
                () -> CallableDemo.sum(1, 10, 1, "cyan"),
                () -> CallableDemo.sum(10, 100, 10, "blue"),
                () -> CallableDemo.sum(2, 20, 2, "green")
        );

        try {
            var results = exec.invokeAll(tasks);
            for (var result : results) {
                println(result.get(500, TimeUnit.MILLISECONDS));
            }
        } catch (InterruptedException | ExecutionException | TimeoutException e) {

        } finally {
            exec.shutdown();
        }
    }

    static void cachedmain() {
        var exec = Executors.newCachedThreadPool();
        try {
            exec.execute(() -> sum(1, 10, 1, "cyan"));
            exec.execute(() -> sum(10, 100, 10, "blue"));
            exec.execute(() -> sum(2, 20, 2, "green"));
        } finally {
            exec.shutdown();
        }
    }

    static void fmain() {
//        Callable<String> task = () -> {
//            Thread.sleep(200);
//            return "task complete";
//        };
//
//        //runnable you have to catch exceptions making callable better option
//        Runnable badTask = () -> {
//            try {
//                Files.readAllBytes(Path.of("data.txt"));
//            } catch (IOException e) {
//                throw new RuntimeException(e);
//            }
//        };
//
//        //how callable looks like
//        Callable<byte[]> goodTask = () -> Files.readAllBytes(Path.of("data.txt"));
//
//        //when an api expects a callable but you have a runnable
//        Runnable runnable = () -> println("Doing sth");
//        Callable<String> asCallable = Executors.callable(runnable, "Done!");
//
//        //future
//        ExecutorService executor = Executors.newFixedThreadPool(2);
//        //executor.execute();
//        Future<Integer> future = executor.submit(() -> {
//            Thread.sleep(200);
//            ;
//            return 10;
//        });
//
//        //blocking get
//        try {
//            Integer result = future.get();
//            println(result);
//        } catch (InterruptedException e) {
//            Thread.currentThread().interrupt();
//        } catch (ExecutionException e) {
//            Throwable cause = e.getCause();
//            println("cause: " + cause);
//        }
//
////        Callable<Integer> risky = () -> {
////            if (true) throw new IllegalStateException("Something broke");
////            return 1;
////        };
////
////        Future<Integer> futures = executor.submit(risky);
////         boolean cancel = futures.cancel(true);
////        // ... nothing happens yet, no exception is printed anywhere ...
////
////        try {
////            futures.get(); // THIS is where the exception finally surfaces
////        } catch (ExecutionException e) {
////            System.out.println("Caused by: " + e.getCause()); // IllegalStateException
////        } catch (InterruptedException e) {
////            Thread.currentThread().interrupt();
////        }
////
////        if (futures.isDone()){
////            println("Future is done");
////        }
//
        //what ahppens when you have a list of futures to iterate
        ExecutorService executorService = Executors.newFixedThreadPool(3);

        CompletionService<Integer> completionService = new ExecutorCompletionService<>(executorService);

        List<Callable<Integer>> tasks = List.of(
                () -> {
                    Thread.sleep(300);
                    return 1;
                },
                () -> {
                    Thread.sleep(200);
                    return 2;
                },
                () -> {
                    Thread.sleep(200);
                    return 3;
                }
        );

        for (var taski : tasks) {
            completionService.submit(taski);
        }

        for (int i = 0; i < tasks.size(); i++) {
            Future<Integer> completed = null;
            try {
                completed = completionService.take();
                println("Got result: " + completed.get());
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
        }
        executorService.shutdown();
//        println(Thread.currentThread().getState());

//        FutureTask<String> ft = new FutureTask<>(()-> {
//            Thread.sleep(200);
//            return "task complete";
//        });
//
//        Thread tr = new Thread(ft);
//        tr.start();
//
//        try {
//            String st = ft.get();
//            println(st);
//        } catch (InterruptedException e) {
//            System.err.println("Interrupted");
//            Thread.currentThread().interrupt();
//        } catch (ExecutionException e) {
//            System.err.println("Execution exception: " + e.getCause());
//        }
    }
}
