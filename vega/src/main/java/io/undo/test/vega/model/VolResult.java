package io.undo.test.vega.model;

/** The result of an implied volatility calculation for a given option. */
public class VolResult {

    private final String underlying;
    private final String optionSymbol;
    private final OptionQuote.OptionType optionType;
    private final double strike;
    private final long expiryEpochMillis;
    private final double impliedVol;
    private final double underlyingPrice;
    private final double timeToExpiry;
    private final double optionMidPrice;
    private final long computeTimeNanos;
    private final long timestampNanos;
    private final String exchange;

    public VolResult(
            String underlying,
            String optionSymbol,
            OptionQuote.OptionType optionType,
            double strike,
            long expiryEpochMillis,
            double impliedVol,
            double underlyingPrice,
            double timeToExpiry,
            double optionMidPrice,
            long computeTimeNanos,
            long timestampNanos,
            String exchange) {
        this.underlying = underlying;
        this.optionSymbol = optionSymbol;
        this.optionType = optionType;
        this.strike = strike;
        this.expiryEpochMillis = expiryEpochMillis;
        this.impliedVol = impliedVol;
        this.underlyingPrice = underlyingPrice;
        this.timeToExpiry = timeToExpiry;
        this.optionMidPrice = optionMidPrice;
        this.computeTimeNanos = computeTimeNanos;
        this.timestampNanos = timestampNanos;
        this.exchange = exchange;
    }

    public String getUnderlying() {
        return underlying;
    }

    public String getOptionSymbol() {
        return optionSymbol;
    }

    public OptionQuote.OptionType getOptionType() {
        return optionType;
    }

    public double getStrike() {
        return strike;
    }

    public long getExpiryEpochMillis() {
        return expiryEpochMillis;
    }

    public double getImpliedVol() {
        return impliedVol;
    }

    public double getUnderlyingPrice() {
        return underlyingPrice;
    }

    public double getTimeToExpiry() {
        return timeToExpiry;
    }

    public double getOptionMidPrice() {
        return optionMidPrice;
    }

    public long getComputeTimeNanos() {
        return computeTimeNanos;
    }

    public long getTimestampNanos() {
        return timestampNanos;
    }

    public String getExchange() {
        return exchange;
    }

    public double getMoneyness() {
        return underlyingPrice / strike;
    }

    @Override
    public String toString() {
        return String.format(
                "[%s] %s %s K=%.1f IV=%.4f mid=%.2f S=%.2f T=%.4f (%d us)",
                exchange,
                underlying,
                optionType,
                strike,
                impliedVol,
                optionMidPrice,
                underlyingPrice,
                timeToExpiry,
                computeTimeNanos / 1000);
    }
}
