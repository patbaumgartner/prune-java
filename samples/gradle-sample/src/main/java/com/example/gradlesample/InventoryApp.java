package com.example.gradlesample;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class InventoryApp {

	private static final Logger LOG = LoggerFactory.getLogger(InventoryApp.class);

	public static void main(String[] args) {
		LOG.info("{}", new StockLevel(42).available());
	}

}
