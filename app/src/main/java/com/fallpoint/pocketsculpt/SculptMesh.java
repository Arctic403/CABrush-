package com.fallpoint.pocketsculpt;

/**
 * Runtime facade kept intentionally thin. Core 0.3 truth lives in MeshKernel;
 * brushes, BVH, transactions and render staging are separate subsystems.
 */
final class SculptMesh {
    static final class Hit {
        float x, y, z, nx, ny, nz, t;
        int faceId;

        Hit set(SculptBvh.RayHit hit) {
            x = hit.x; y = hit.y; z = hit.z;
            nx = hit.nx; ny = hit.ny; nz = hit.nz;
            t = hit.t; faceId = hit.faceId;
            return this;
        }
    }

    final MeshKernel kernel;
    final SculptBvh bvh;
    final SculptEngine sculptEngine;
    final RenderChunkBuilder renderChunks;

    private final MeshSnapshot resetSnapshot;
    private final SculptBvh.RayHit rayScratch = new SculptBvh.RayHit();
    private final Hit hitScratch = new Hit();
    private final StrokeContext strokeScratch = new StrokeContext();

    private SculptMesh(MeshKernel kernel) {
        this.kernel = kernel;
        this.resetSnapshot = MeshSnapshot.capture(kernel);
        this.bvh = new SculptBvh(kernel);
        this.sculptEngine = new SculptEngine(kernel, bvh);
        this.renderChunks = new RenderChunkBuilder(kernel);
    }

    static SculptMesh createSphere() {
        return new SculptMesh(MeshKernel.createIcoSphere(4, 1f));
    }

    Hit raycast(float[] origin, float[] direction) {
        if (origin == null || direction == null || origin.length < 3 || direction.length < 3) return null;
        SculptBvh.RayHit hit = bvh.raycast(
                origin[0], origin[1], origin[2],
                direction[0], direction[1], direction[2],
                rayScratch
        );
        return hit.faceId == MeshKernel.INVALID ? null : hitScratch.set(hit);
    }

    boolean applyClay(
            float hitX, float hitY, float hitZ,
            float normalX, float normalY, float normalZ,
            float[] viewDirection,
            float radius, float strength, BrushMode mode,
            float accumulatedDistance,
            int hitFace
    ) {
        if (viewDirection == null || viewDirection.length < 3) return false;
        strokeScratch.set(
                hitX, hitY, hitZ,
                normalX, normalY, normalZ,
                viewDirection[0], viewDirection[1], viewDirection[2],
                radius, strength, accumulatedDistance, mode, hitFace
        );
        return sculptEngine.applyClay(strokeScratch);
    }

    void reset() {
        resetSnapshot.restoreInto(kernel);
        bvh.rebuild();
        renderChunks.rebuild();
    }

    RenderChunkBuilder.RenderPlan renderPlan() {
        return renderChunks.currentPlan();
    }

    GeometryValidator.Report validate() {
        return GeometryValidator.validate(kernel, true);
    }

    MeshSnapshot snapshot() {
        return MeshSnapshot.capture(kernel);
    }
}
