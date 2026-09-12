package com.example.gradlesample;

final class ObsoleteStockExporter {

	String export(StockLevel level) {
		return "legacy-export:" + level.available();
	}

}
