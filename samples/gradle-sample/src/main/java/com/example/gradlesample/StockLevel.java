package com.example.gradlesample;

public final class StockLevel {

	private static final int REORDER_THRESHOLD = 5;

	private final int units;

	public StockLevel(int units) {
		this.units = units;
	}

	public String available() {
		return units + " units in " + WarehouseCodes.DEFAULT;
	}

	public String label() {
		return units < REORDER_THRESHOLD ? "low stock" : "in stock";
	}

	private String legacyExportName() {
		return "stock-" + units + ".csv";
	}

}
