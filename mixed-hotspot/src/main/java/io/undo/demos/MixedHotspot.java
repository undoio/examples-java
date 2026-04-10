package io.undo.demos;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * Mixed workload with four distinct hotspots for profiling demos.
 *
 * Each method exercises a different part of the JVM and produces a
 * distinct signature in both Java and native profilers:
 *
 *   sievePrimes   — pure arithmetic, JIT-compiled tight loop
 *   cryptoHash    — deep Java security framework + native crypto
 *   stringChurn   — String/regex allocation pressure, GC activity
 *   sortArrays    — comparison-heavy, memory-bound (DualPivotQuicksort)
 *
 * Usage:
 *   java io.undo.demos.MixedHotspot [iterations]
 *
 * Default is 2000 iterations. Each iteration calls all four methods.
 */
public class MixedHotspot {

    public static void main(String[] args) throws Exception {
        int iterations = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        new MixedHotspot().run(iterations);
    }

    void run(int iterations) throws Exception {
        System.out.println("MixedHotspot: " + iterations + " iterations");
        for (int i = 0; i < iterations; i++) {
            sievePrimes();
            cryptoHash();
            stringChurn();
            sortArrays();
        }
        System.out.println("Done");
    }

    /* -------- 1. Pure CPU: prime sieve -------- */

    /**
     * Naive Sieve of Eratosthenes up to 500K.  A tight inner loop over a
     * boolean array — JIT-compiles to very efficient native code.
     * In the Java profiler this shows as a flat stack (no callees).
     * In the native profiler it shows as JIT-compiled code in libjvm.
     */
    void sievePrimes() {
        int limit = 500_000;
        boolean[] composite = new boolean[limit + 1];
        for (int i = 2; (long) i * i <= limit; i++) {
            if (!composite[i]) {
                for (int j = i * i; j <= limit; j += i) {
                    composite[j] = true;
                }
            }
        }
        int count = 0;
        for (int i = 2; i <= limit; i++) {
            if (!composite[i]) count++;
        }
        if (count == 0) throw new AssertionError();
    }

    /* -------- 2. Crypto framework: SHA-256 -------- */

    /**
     * 200 rounds of SHA-256 hashing on 256-byte random blocks.
     * Produces deep call chains through java.security → sun.security.provider.
     * In the native profiler this lights up the JVM's crypto internals.
     */
    void cryptoHash() throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        Random rnd = new Random(42);
        for (int i = 0; i < 200; i++) {
            byte[] data = new byte[256];
            rnd.nextBytes(data);
            md.update(data);
            md.digest();
        }
    }

    /* -------- 3. String/regex: allocation-heavy -------- */

    private static final Pattern COMMA = Pattern.compile(",");

    /**
     * Builds a ~3KB CSV string, splits it with regex, sorts the tokens,
     * and joins them back.  Exercises StringBuilder, Pattern.split,
     * Arrays.sort(Object[]), and String.join.  Produces heavy allocation
     * and GC pressure.
     */
    void stringChurn() {
        StringBuilder sb = new StringBuilder(4096);
        Random rnd = new Random(123);
        for (int i = 0; i < 300; i++) {
            if (i > 0) sb.append(',');
            sb.append("item_").append(rnd.nextInt(100_000));
        }
        String csv = sb.toString();

        String[] parts = COMMA.split(csv);
        Arrays.sort(parts);
        String result = String.join("|", parts);
        if (result.isEmpty()) throw new AssertionError();
    }

    /* -------- 4. Sorting: comparisons + memory -------- */

    /**
     * Generates and sorts a 100K-element int array.
     * DualPivotQuicksort is comparison- and swap-heavy — shows up as
     * memory-bound work in the native profiler with lots of array access.
     */
    void sortArrays() {
        Random rnd = new Random(456);
        int size = 100_000;
        int[] arr = new int[size];
        for (int i = 0; i < size; i++) {
            arr[i] = rnd.nextInt();
        }
        Arrays.sort(arr);
        if (arr[0] > arr[size - 1]) throw new AssertionError();
    }
}
