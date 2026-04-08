package io.undo.test.vega.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Defines the universe of securities that the engine processes. In a real system this would come
 * from a configuration service.
 */
public class SecurityUniverse {

    public static class UnderlyingSecurity {
        private final String symbol;
        private final double referencePrice;
        private final double annualVol;
        private final double[] strikeOffsets;

        public UnderlyingSecurity(
                String symbol, double referencePrice, double annualVol, double[] strikeOffsets) {
            this.symbol = symbol;
            this.referencePrice = referencePrice;
            this.annualVol = annualVol;
            this.strikeOffsets = strikeOffsets;
        }

        public String getSymbol() {
            return symbol;
        }

        public double getReferencePrice() {
            return referencePrice;
        }

        public double getAnnualVol() {
            return annualVol;
        }

        public double[] getStrikeOffsets() {
            return strikeOffsets;
        }
    }

    private static final double[] STANDARD_STRIKES = {
        0.80, 0.85, 0.90, 0.95, 0.975, 1.0, 1.025, 1.05, 1.10, 1.15, 1.20
    };

    private static final List<UnderlyingSecurity> SECURITIES =
            Arrays.asList(
                    new UnderlyingSecurity("SPY", 450.0, 0.18, STANDARD_STRIKES),
                    new UnderlyingSecurity("QQQ", 380.0, 0.22, STANDARD_STRIKES),
                    new UnderlyingSecurity("IWM", 200.0, 0.24, STANDARD_STRIKES),
                    new UnderlyingSecurity("AAPL", 175.0, 0.28, STANDARD_STRIKES),
                    new UnderlyingSecurity("MSFT", 410.0, 0.25, STANDARD_STRIKES),
                    new UnderlyingSecurity("AMZN", 180.0, 0.32, STANDARD_STRIKES),
                    new UnderlyingSecurity("TSLA", 250.0, 0.55, STANDARD_STRIKES),
                    new UnderlyingSecurity("NVDA", 800.0, 0.45, STANDARD_STRIKES),
                    new UnderlyingSecurity("META", 500.0, 0.35, STANDARD_STRIKES),
                    new UnderlyingSecurity("GOOG", 155.0, 0.27, STANDARD_STRIKES));

    public static List<UnderlyingSecurity> getSecurities() {
        return Collections.unmodifiableList(SECURITIES);
    }

    public static UnderlyingSecurity getBySymbol(String symbol) {
        for (UnderlyingSecurity sec : SECURITIES) {
            if (sec.getSymbol().equals(symbol)) {
                return sec;
            }
        }
        return null;
    }
}
