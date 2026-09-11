package org.javaguy;

import static java.lang.IO.println;

public class CustomThread extends Thread{

    @Override
    public void run() {
        for (int i = 1; i <= 5; i++) {
            println(" 1 ");
            try{
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}
