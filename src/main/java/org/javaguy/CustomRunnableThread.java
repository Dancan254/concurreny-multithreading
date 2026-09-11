package org.javaguy;

import static java.lang.IO.println;

public class CustomRunnableThread implements Runnable {
    @Override
    public void run() {
        for (int i = 1; i <= 5; i++) {
            try {
                Thread.sleep(500);
                println("Do sth");
            }catch (InterruptedException e){
                println("Interrupted");
            }
        }
    }
}
