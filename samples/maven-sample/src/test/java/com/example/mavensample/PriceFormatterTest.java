package com.example.mavensample;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PriceFormatterTest {

    @Test
    void formatsCentsAsDecimalAmount() {
        assertEquals("CHF 19.99", new PriceFormatter().format(1999L));
    }

    @Test
    void describesItself() {
        assertEquals("formats cents into a CHF amount", new PriceFormatter().describe());
    }
}
