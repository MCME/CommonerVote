package com.mcmiddleearth.commonervote.domain;

/**
 * An inclusive range of whole numbers, used for the bounds of the numeric settings.
 */
public record IntRange(int min, int max) {

    public boolean contains(int v) {
        return v >= min && v <= max;
    }

    /** The value itself when it lies inside the range, else the nearest bound. */
    public int clamp(int v) {
        return Math.max(min, Math.min(max, v));
    }
}
