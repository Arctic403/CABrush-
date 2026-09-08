package com.fallpoint.pocketsculpt;

/**
 * Thin runtime facade for CABrush AVS 0.1.
 *
 * The signed-distance volume is sculpt truth. AvsSurfaceCache is disposable
 * rendering cache and can be rebuilt at any time.
 */
final class SculptMesh {
    static final class Hit {
        float x,y,z,nx,ny,nz,t;
        Hit set(AvsVolume.RayHit h) {
            x=h.x; y=h.y; z=h.z; nx=h.nx; ny=h.ny; nz=h.nz; t=h.t;
            return this;
        }
    }

    final AvsVolume volume;
    final AvsSurfaceCache surface;
    private final AvsSnapshot resetSnapshot;
    private final AvsVolume.RayHit rayScratch = new AvsVolume.RayHit();
    private final Hit hitScratch = new Hit();

    private SculptMesh(AvsVolume volume) {
        this.volume = volume;
        this.surface = new AvsSurfaceCache(volume);
        this.resetSnapshot = volume.snapshot();
        this.surface.rebuildAll();
    }

    static SculptMesh createSphere() {
        return new SculptMesh(AvsVolume.createSphere(1f));
    }

    Hit raycast(float[] origin, float[] direction) {
        if (origin == null || direction == null || origin.length < 3 || direction.length < 3) return null;
        AvsVolume.RayHit h = volume.raycast(
                origin[0],origin[1],origin[2],
                direction[0],direction[1],direction[2],
                rayScratch
        );
        return h.hit ? hitScratch.set(h) : null;
    }

    AvsVolume.BrushResult applyClay(float hitX,float hitY,float hitZ,
                                     float normalX,float normalY,float normalZ,
                                     float radius,float strength,BrushMode mode) {
        return volume.applyClay(
                hitX,hitY,hitZ,normalX,normalY,normalZ,radius,strength,mode
        );
    }

    void reset() {
        long start = EngineDiagnostics.nowNanos();
        resetSnapshot.restoreInto(volume);
        surface.rebuildAll();
        if (EngineDiagnostics.isEnabled()) {
            EngineDiagnostics.timed("mesh", "reset", start,
                    "bricks=" + volume.brickCount()
                            + " field_hash=" + volume.fieldHash());
        }
    }

    AvsSurfaceCache.RenderPlan renderPlan() {
        return surface.currentPlan();
    }

    AvsVolume.Stats stats() { return volume.stats(); }

    String fieldHash() { return volume.fieldHash(); }
}
