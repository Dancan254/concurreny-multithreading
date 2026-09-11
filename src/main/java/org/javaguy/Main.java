package org.javaguy;


import java.util.concurrent.TimeUnit;

import static java.lang.IO.println;

public class Main {
    static void main() {
        var thread = Thread.currentThread();
        thread.setPriority(Thread.MAX_PRIORITY);
        printThreadInfo(thread);

        CustomThread customThread = new CustomThread();
        customThread.start();
        Runnable runnable  = () -> {
            for (int i = 1; i <= 3; i++) {
                println(" 2 ");
                try {
                    TimeUnit.MILLISECONDS.sleep(100);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        Thread thread2 = new Thread(runnable);
        thread2.start();

        for (int i = 1; i <= 3; i++) {
            println(" 0 ");
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        CustomRunnableThread run = new CustomRunnableThread();
        Thread thread3 = new Thread(run);
        thread3.start();
        thread3.interrupt();
    }

    static void printThreadInfo(Thread thread) {
        println("name: " + thread.getName());
        println("thread id: " + thread.threadId());
        println("state: " + thread.getState());
        println("isAlive: " + thread.isAlive());
        println("isDaemon: " + thread.isDaemon());
        println("context Loader: " + thread.getContextClassLoader());
        println("priority: " + thread.getPriority());
    }
}
