package io.undo.test.vega.model;

/** Represents a single options quote tick from an exchange feed. */
public class OptionQuote {

    public enum Side {
        BID,
        ASK
    }

    public enum OptionType {
        CALL,
        PUT
    }

    private final String exchange;
    private final String underlying;
    private final String optionSymbol;
    private final OptionType optionType;
    private final double strike;
    private final long expiryEpochMillis;
    private final Side side;
    private final double price;
    private final int size;
    private final long timestampNanos;
    private final long sequenceNumber;

    public OptionQuote(
            String exchange,
            String underlying,
            String optionSymbol,
            OptionType optionType,
            double strike,
            long expiryEpochMillis,
            Side side,
            double price,
            int size,
            long timestampNanos,
            long sequenceNumber) {
        this.exchange = exchange;
        this.underlying = underlying;
        this.optionSymbol = optionSymbol;
        this.optionType = optionType;
        this.strike = strike;
        this.expiryEpochMillis = expiryEpochMillis;
        this.side = side;
        this.price = price;
        this.size = size;
        this.timestampNanos = timestampNanos;
        this.sequenceNumber = sequenceNumber;
    }

    public String getExchange() {
        return exchange;
    }

    public String getUnderlying() {
        return underlying;
    }

    public String getOptionSymbol() {
        return optionSymbol;
    }

    public OptionType getOptionType() {
        return optionType;
    }

    public double getStrike() {
        return strike;
    }

    public long getExpiryEpochMillis() {
        return expiryEpochMillis;
    }

    public Side getSide() {
        return side;
    }

    public double getPrice() {
        return price;
    }

    public int getSize() {
        return size;
    }

    public long getTimestampNanos() {
        return timestampNanos;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public double getMidPrice(OptionQuote other) {
        if (this.side == other.side) {
            throw new IllegalArgumentException("Cannot compute mid from same side");
        }
        return (this.price + other.price) / 2.0;
    }

    @Override
    public String toString() {
        return String.format(
                "[%s] %s %s %.1f %s %.2f x%d seq=%d",
                exchange, underlying, optionType, strike, side, price, size, sequenceNumber);
    }
}
