package org.javaguy.multithread;

import java.util.concurrent.TimeUnit;

class StopWatch {
    private TimeUnit timeUnit;

    public StopWatch(TimeUnit timeUnit) {
        this.timeUnit = timeUnit;
    }

    void countDown(){
        countDown(5);
    }
    public void countDown(int unitCount) {
        String threadName = Thread.currentThread().getName();

        ThreadColor threadColor = ThreadColor.ANSI_RESET;
        try {
            threadColor = ThreadColor.valueOf(threadName);
        } catch (IllegalArgumentException ignore) {
            //do nth
        }

        String color = threadColor.getColor();
        for (int i = unitCount; i > 0; i--) {
            try {
                timeUnit.sleep(1);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.out.printf("%s%s Thread : i = %d\n", color, threadName, i);
        }

    }
}
public class Main {

    static void main() {
        StopWatch greenWatch = new StopWatch(TimeUnit.SECONDS);
        StopWatch blueWatch = new StopWatch(TimeUnit.SECONDS);
        StopWatch whiteWatch = new StopWatch(TimeUnit.SECONDS);
        Thread green = new Thread(greenWatch::countDown, ThreadColor.ANSI_GREEN.name());
        Thread blue = new Thread(() -> blueWatch.countDown(7), ThreadColor.ANSI_BLUE.name());
        Thread white = new Thread(whiteWatch::countDown, ThreadColor.ANSI_WHITE.name());
        green.start();
        blue.start();
        white.start();

    }

}



