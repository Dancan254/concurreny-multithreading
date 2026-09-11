package org.javaguy.multithread;

public enum ThreadColor {
    ANSI_RESET("\u001B[0m"),
    ANSI_BLACK("\u001B[30m"),
    ANSI_WHITE("\u001b[37m"),
    ANSI_BLUE("\u001B[34m"),
    ANSI_CYAN("\u001B[36m"),
    ANSI_GREEN("\u001B[32m");


    private final String color;

    ThreadColor(String color) {
        this.color = color;
    }

    public String getColor() {
        return color;
    }

}
