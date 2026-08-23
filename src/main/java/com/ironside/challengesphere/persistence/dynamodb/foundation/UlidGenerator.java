package com.ironside.challengesphere.persistence.dynamodb.foundation;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;

/**
 * Thread-safe monotonic ULID generator with no database or process-global
 * sequence dependency.
 */
public final class UlidGenerator {

    private static final char[] CROCKFORD_BASE32 =
            "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int RANDOM_BYTES = 10;
    private static final int ULID_BYTES = 16;
    private static final int ULID_CHARS = 26;

    private final Clock clock;
    private final SecureRandom secureRandom;
    private final byte[] lastRandom = new byte[RANDOM_BYTES];
    private long lastTimestamp = -1L;

    public UlidGenerator() {
        this(Clock.systemUTC(), new SecureRandom());
    }

    public UlidGenerator(Clock clock) {
        this(clock, new SecureRandom());
    }

    UlidGenerator(Clock clock, SecureRandom secureRandom) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    /**
     * Returns a lexicographically increasing ULID within this generator, even
     * if the wall clock briefly moves backwards.
     */
    public synchronized String next() {
        long timestamp = Math.max(clock.millis(), lastTimestamp);
        if (timestamp > lastTimestamp) {
            secureRandom.nextBytes(lastRandom);
            lastTimestamp = timestamp;
        } else if (!incrementRandomness()) {
            // An 80-bit same-millisecond overflow is practically unreachable,
            // but advancing the logical clock preserves uniqueness without a
            // blocking wait.
            lastTimestamp++;
            timestamp = lastTimestamp;
            Arrays.fill(lastRandom, (byte) 0);
        }

        byte[] bytes = new byte[ULID_BYTES];
        bytes[0] = (byte) (timestamp >>> 40);
        bytes[1] = (byte) (timestamp >>> 32);
        bytes[2] = (byte) (timestamp >>> 24);
        bytes[3] = (byte) (timestamp >>> 16);
        bytes[4] = (byte) (timestamp >>> 8);
        bytes[5] = (byte) timestamp;
        System.arraycopy(lastRandom, 0, bytes, 6, RANDOM_BYTES);
        return encode(bytes);
    }

    private boolean incrementRandomness() {
        for (int index = lastRandom.length - 1; index >= 0; index--) {
            int incremented = (lastRandom[index] & 0xff) + 1;
            lastRandom[index] = (byte) incremented;
            if (incremented <= 0xff) {
                return true;
            }
        }
        return false;
    }

    private static String encode(byte[] bytes) {
        BigInteger value = new BigInteger(1, bytes);
        char[] encoded = new char[ULID_CHARS];
        BigInteger radix = BigInteger.valueOf(32);
        for (int index = encoded.length - 1; index >= 0; index--) {
            BigInteger[] quotientAndRemainder = value.divideAndRemainder(radix);
            encoded[index] = CROCKFORD_BASE32[quotientAndRemainder[1].intValue()];
            value = quotientAndRemainder[0];
        }
        return new String(encoded);
    }
}
