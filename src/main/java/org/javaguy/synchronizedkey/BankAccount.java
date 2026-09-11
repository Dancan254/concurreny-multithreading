package org.javaguy.synchronizedkey;

public class BankAccount {
    private double balance;

    public void deposit(double amount) {
        synchronized (this){
            balance += amount;
        }
    }

    public synchronized void withdraw(double amount) {
        balance -= amount;
    }
}
