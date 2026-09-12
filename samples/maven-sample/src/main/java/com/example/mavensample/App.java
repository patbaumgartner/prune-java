package com.example.mavensample;

import com.google.gson.Gson;

public final class App {

    public static void main(String[] args) {
        var formatter = new PriceFormatter();
        System.out.println(new Gson().toJson(formatter.format(1999L)));
    }
}
