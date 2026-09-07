package com.fallpoint.pocketsculpt;

/**
 * Reusable per-dab runtime state shared by future sculpt brushes.
 * Mutable by design to avoid a heap allocation for every brush sample.
 */
final class StrokeContext {
    float hitX, hitY, hitZ;
    float normalX, normalY, normalZ;
    float viewX, viewY, viewZ;
    float radius;
    float strength;
    float accumulatedDistance;
    BrushMode mode;
    int hitFace;

    StrokeContext set(
            float hitX, float hitY, float hitZ,
            float normalX, float normalY, float normalZ,
            float viewX, float viewY, float viewZ,
            float radius, float strength, float accumulatedDistance,
            BrushMode mode, int hitFace
    ) {
        this.hitX = hitX; this.hitY = hitY; this.hitZ = hitZ;
        this.normalX = normalX; this.normalY = normalY; this.normalZ = normalZ;
        this.viewX = viewX; this.viewY = viewY; this.viewZ = viewZ;
        this.radius = radius; this.strength = strength;
        this.accumulatedDistance = accumulatedDistance;
        this.mode = mode;
        this.hitFace = hitFace;
        return this;
    }
}
