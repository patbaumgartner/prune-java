package com.example.mavensample;

public final class PriceFormatter {

	private static final String LEGACY_SUFFIX = " (legacy)";

	public String format(long cents) {
		return CurrencyCodes.DEFAULT + " " + (cents / 100) + "." + String.format("%02d", cents % 100);
	}

	public String describe() {
		return "formats cents into a " + CurrencyCodes.DEFAULT + " amount";
	}

	private long roundToNearestUnit(long cents) {
		return Math.round(cents / 100.0d) * 100L;
	}

	private static final class LegacyRounding {

		static long apply(long cents) {
			return cents - cents % 5;
		}

		private LegacyRounding() {
		}

	}

}
