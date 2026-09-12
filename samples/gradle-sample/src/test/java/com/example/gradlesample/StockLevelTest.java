package com.example.gradlesample;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StockLevelTest {

    @Test
    void reportsAvailableUnitsWithTheWarehouseCode() {
        assertEquals("42 units in ZRH-1", new StockLevel(42).available());
    }

    @Test
    void labelsAnEmptyStockLevel() {
        assertEquals("low stock", new StockLevel(0).label());
    }
}
