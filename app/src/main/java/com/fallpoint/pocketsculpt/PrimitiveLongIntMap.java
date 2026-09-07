package com.fallpoint.pocketsculpt;

import java.util.Arrays;

/**
 * Tiny allocation-free long -> int hash map used by topology rebuilds.
 * Keys are never zero after mixing; zero in the key table means empty.
 */
final class PrimitiveLongIntMap {
    private long[] keys;
    private int[] values;
    private int mask;
    private int size;
    private int threshold;

    PrimitiveLongIntMap(int expected) {
        int capacity = 1;
        int need = Math.max(16, expected * 2);
        while (capacity < need) capacity <<= 1;
        keys = new long[capacity];
        values = new int[capacity];
        mask = capacity - 1;
        threshold = (int)(capacity * 0.65f);
    }

    void clear() {
        Arrays.fill(keys, 0L);
        size = 0;
    }

    int size() { return size; }

    int get(long rawKey, int missing) {
        long key = normalize(rawKey);
        int slot = slot(key);
        while (true) {
            long existing = keys[slot];
            if (existing == 0L) return missing;
            if (existing == key) return values[slot];
            slot = (slot + 1) & mask;
        }
    }

    int put(long rawKey, int value, int missing) {
        if (size >= threshold) grow();
        long key = normalize(rawKey);
        int slot = slot(key);
        while (true) {
            long existing = keys[slot];
            if (existing == 0L) {
                keys[slot] = key;
                values[slot] = value;
                size++;
                return missing;
            }
            if (existing == key) {
                int old = values[slot];
                values[slot] = value;
                return old;
            }
            slot = (slot + 1) & mask;
        }
    }

    int increment(long rawKey) {
        if (size >= threshold) grow();
        long key = normalize(rawKey);
        int slot = slot(key);
        while (true) {
            long existing = keys[slot];
            if (existing == 0L) {
                keys[slot] = key;
                values[slot] = 1;
                size++;
                return 1;
            }
            if (existing == key) {
                return ++values[slot];
            }
            slot = (slot + 1) & mask;
        }
    }

    long[] rawKeys() { return keys; }
    int[] rawValues() { return values; }

    private void grow() {
        long[] oldKeys = keys;
        int[] oldValues = values;
        keys = new long[oldKeys.length << 1];
        values = new int[keys.length];
        mask = keys.length - 1;
        threshold = (int)(keys.length * 0.65f);
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            long key = oldKeys[i];
            if (key != 0L) putNormalized(key, oldValues[i]);
        }
    }

    private void putNormalized(long key, int value) {
        int slot = slot(key);
        while (keys[slot] != 0L) slot = (slot + 1) & mask;
        keys[slot] = key;
        values[slot] = value;
        size++;
    }

    private int slot(long key) {
        long z = key;
        z ^= z >>> 33;
        z *= 0xff51afd7ed558ccdl;
        z ^= z >>> 33;
        z *= 0xc4ceb9fe1a85ec53l;
        z ^= z >>> 33;
        return ((int)z) & mask;
    }

    private static long normalize(long key) {
        // Directed/undirected mesh keys can be zero for (0,0), which we never
        // intentionally store, but keep the map robust anyway.
        return key == 0L ? 0x9e3779b97f4a7c15L : key;
    }
}
