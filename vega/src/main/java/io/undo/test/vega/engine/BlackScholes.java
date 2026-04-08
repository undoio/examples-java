package io.undo.test.vega.engine;

import io.undo.test.vega.model.OptionQuote;

/**
 * Black-Scholes option pricing and implied volatility solver.
 *
 * <p>Uses Newton-Raphson iteration to invert the BS formula and recover implied vol from observed
 * market prices. This is the bread-and-butter calculation in any options analytics engine.
 */
public class BlackScholes {

    private static final int MAX_ITERATIONS = 100;
    private static final double TOLERANCE = 1e-8;
    private static final double VOL_LOWER = 0.001;
    private static final double VOL_UPPER = 5.0;

    public static double price(
            double spot,
            double strike,
            double timeToExpiry,
            double vol,
            double riskFreeRate,
            OptionQuote.OptionType type) {
        if (timeToExpiry <= 0) {
            return intrinsicValue(spot, strike, type);
        }

        double sqrtT = Math.sqrt(timeToExpiry);
        double d1 =
                (Math.log(spot / strike) + (riskFreeRate + 0.5 * vol * vol) * timeToExpiry)
                        / (vol * sqrtT);
        double d2 = d1 - vol * sqrtT;
        double discount = Math.exp(-riskFreeRate * timeToExpiry);

        if (type == OptionQuote.OptionType.CALL) {
            return spot * normalCDF(d1) - strike * discount * normalCDF(d2);
        } else {
            return strike * discount * normalCDF(-d2) - spot * normalCDF(-d1);
        }
    }

    public static double impliedVol(
            double spot,
            double strike,
            double timeToExpiry,
            double riskFreeRate,
            double marketPrice,
            OptionQuote.OptionType type) {
        if (timeToExpiry <= 0 || marketPrice <= 0) {
            return Double.NaN;
        }

        double intrinsic = intrinsicValue(spot, strike, type);
        if (marketPrice < intrinsic - TOLERANCE) {
            return Double.NaN;
        }

        // Initial guess using Brenner-Subrahmanyam approximation
        double vol = Math.sqrt(2.0 * Math.PI / timeToExpiry) * (marketPrice / spot);
        vol = Math.max(VOL_LOWER, Math.min(VOL_UPPER, vol));

        // Newton-Raphson
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            double bsPrice = price(spot, strike, timeToExpiry, vol, riskFreeRate, type);
            double vega = vega(spot, strike, timeToExpiry, vol, riskFreeRate);

            if (Math.abs(vega) < 1e-12) {
                // Vega too small — switch to bisection
                return bisectionImpliedVol(
                        spot, strike, timeToExpiry, riskFreeRate, marketPrice, type);
            }

            double diff = bsPrice - marketPrice;
            if (Math.abs(diff) < TOLERANCE) {
                return vol;
            }

            vol = vol - diff / vega;
            vol = Math.max(VOL_LOWER, Math.min(VOL_UPPER, vol));
        }

        // Fallback to bisection if Newton didn't converge
        return bisectionImpliedVol(spot, strike, timeToExpiry, riskFreeRate, marketPrice, type);
    }

    private static double bisectionImpliedVol(
            double spot,
            double strike,
            double timeToExpiry,
            double riskFreeRate,
            double marketPrice,
            OptionQuote.OptionType type) {
        double lo = VOL_LOWER;
        double hi = VOL_UPPER;

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            double mid = (lo + hi) / 2.0;
            double bsPrice = price(spot, strike, timeToExpiry, mid, riskFreeRate, type);

            if (Math.abs(bsPrice - marketPrice) < TOLERANCE) {
                return mid;
            }

            if (bsPrice > marketPrice) {
                lo = mid;
            } else {
                hi = mid;
            }
        }

        return (lo + hi) / 2.0;
    }

    public static double vega(
            double spot, double strike, double timeToExpiry, double vol, double riskFreeRate) {
        if (timeToExpiry <= 0) return 0;

        double sqrtT = Math.sqrt(timeToExpiry);
        double d1 =
                (Math.log(spot / strike) + (riskFreeRate + 0.5 * vol * vol) * timeToExpiry)
                        / (vol * sqrtT);

        return spot * sqrtT * normalPDF(d1);
    }

    public static double delta(
            double spot,
            double strike,
            double timeToExpiry,
            double vol,
            double riskFreeRate,
            OptionQuote.OptionType type) {
        if (timeToExpiry <= 0) {
            if (type == OptionQuote.OptionType.CALL) {
                return spot > strike ? 1.0 : 0.0;
            } else {
                return spot < strike ? -1.0 : 0.0;
            }
        }

        double sqrtT = Math.sqrt(timeToExpiry);
        double d1 =
                (Math.log(spot / strike) + (riskFreeRate + 0.5 * vol * vol) * timeToExpiry)
                        / (vol * sqrtT);

        if (type == OptionQuote.OptionType.CALL) {
            return normalCDF(d1);
        } else {
            return normalCDF(d1) - 1.0;
        }
    }

    public static double gamma(
            double spot, double strike, double timeToExpiry, double vol, double riskFreeRate) {
        if (timeToExpiry <= 0) return 0;

        double sqrtT = Math.sqrt(timeToExpiry);
        double d1 =
                (Math.log(spot / strike) + (riskFreeRate + 0.5 * vol * vol) * timeToExpiry)
                        / (vol * sqrtT);

        return normalPDF(d1) / (spot * vol * sqrtT);
    }

    public static double theta(
            double spot,
            double strike,
            double timeToExpiry,
            double vol,
            double riskFreeRate,
            OptionQuote.OptionType type) {
        if (timeToExpiry <= 0) return 0;

        double sqrtT = Math.sqrt(timeToExpiry);
        double d1 =
                (Math.log(spot / strike) + (riskFreeRate + 0.5 * vol * vol) * timeToExpiry)
                        / (vol * sqrtT);
        double d2 = d1 - vol * sqrtT;

        double term1 = -(spot * normalPDF(d1) * vol) / (2.0 * sqrtT);

        if (type == OptionQuote.OptionType.CALL) {
            return term1
                    - riskFreeRate
                            * strike
                            * Math.exp(-riskFreeRate * timeToExpiry)
                            * normalCDF(d2);
        } else {
            return term1
                    + riskFreeRate
                            * strike
                            * Math.exp(-riskFreeRate * timeToExpiry)
                            * normalCDF(-d2);
        }
    }

    private static double intrinsicValue(double spot, double strike, OptionQuote.OptionType type) {
        if (type == OptionQuote.OptionType.CALL) {
            return Math.max(0, spot - strike);
        } else {
            return Math.max(0, strike - spot);
        }
    }

    static double normalCDF(double x) {
        return 0.5 * (1.0 + erf(x / Math.sqrt(2.0)));
    }

    private static double normalPDF(double x) {
        return Math.exp(-0.5 * x * x) / Math.sqrt(2.0 * Math.PI);
    }

    private static double erf(double x) {
        double t = 1.0 / (1.0 + 0.3275911 * Math.abs(x));
        double poly =
                t
                        * (0.254829592
                                + t
                                        * (-0.284496736
                                                + t
                                                        * (1.421413741
                                                                + t
                                                                        * (-1.453152027
                                                                                + t
                                                                                        * 1.061405429))));
        double result = 1.0 - poly * Math.exp(-x * x);
        return x >= 0 ? result : -result;
    }
}
