package com.fallpoint.pocketsculpt;

import java.util.Arrays;

/**
 * Elementary mutable-topology operators.
 *
 * Core 0.3.1 treats an operator as a geometric operation, not merely a
 * connectivity edit. Every collapse obeys the closed-manifold link condition
 * and uses a scored placement. Flips are rejected when they degrade local
 * triangle quality or rotate the surface patch too aggressively.
 */
final class GeometryOps {
    private static final float MIN_PLACEMENT_QUALITY = 0.10f;
    private static final float MIN_PLACEMENT_NORMAL_DOT = 0.50f;
    private static final float MAX_COLLAPSE_EDGE_TO_LOCAL_MEAN = 0.82f;
    private static final float MAX_FLIP_NEW_EDGE_TO_OLD_MAX = 1.20f;

    private GeometryOps() {}

    static GeometryTransaction.Result splitEdge(MeshKernel mesh, int a, int b) {
        return GeometryTransaction.runTopology(mesh, m -> splitEdgeRaw(m, a, b) >= 0, true);
    }

    static GeometryTransaction.Result collapseEdge(MeshKernel mesh, int keep, int remove) {
        if (!GeometryValidator.satisfiesClosedCollapseLinkCondition(mesh, keep, remove)) {
            return new GeometryTransaction.Result(false, GeometryValidator.Reason.LINK_CONDITION,
                    "closed 2-manifold link condition failed");
        }
        float edge = GeometryValidator.edge(mesh, keep, remove);
        float localMean = localMeanEdge(mesh, keep, remove);
        if (!(localMean > 1e-8f) || edge > localMean * MAX_COLLAPSE_EDGE_TO_LOCAL_MEAN) {
            return new GeometryTransaction.Result(false, GeometryValidator.Reason.SURFACE_ENVELOPE,
                    "collapse edge=" + edge + " localMean=" + localMean);
        }

        float[] placement = bestCollapsePlacement(mesh, keep, remove, localMean);
        if (placement == null) {
            return new GeometryTransaction.Result(false, GeometryValidator.Reason.PLACEMENT_QUALITY,
                    "no candidate preserves local normals/quality");
        }
        return GeometryTransaction.runTopology(mesh,
                m -> collapseEdgeRaw(m, keep, remove, placement[0], placement[1], placement[2]), true);
    }

    static GeometryTransaction.Result flipEdge(MeshKernel mesh, int a, int b) {
        if (!flipPreflight(mesh, a, b)) {
            return new GeometryTransaction.Result(false, GeometryValidator.Reason.PLACEMENT_QUALITY,
                    "flip degrades local quality/normal envelope");
        }
        return GeometryTransaction.runTopology(mesh, m -> flipEdgeRaw(m, a, b), true);
    }

    static int splitEdgeRaw(MeshKernel mesh, int a, int b) {
        mesh.ensureConnectivity();
        int h = mesh.findHalfEdge(a, b);
        if (h == MeshKernel.INVALID) h = mesh.findHalfEdge(b, a);
        if (h == MeshKernel.INVALID) return MeshKernel.INVALID;
        int twin = mesh.heTwin[h];
        if (twin == MeshKernel.INVALID) return MeshKernel.INVALID;

        int u = mesh.heOrigin[h];
        int v = mesh.heDest[h];
        int f1 = mesh.heFace[h];
        int f2 = mesh.heFace[twin];
        int c = mesh.oppositeVertex(f1, u, v);
        int d = mesh.oppositeVertex(f2, u, v);
        if (c == MeshKernel.INVALID || d == MeshKernel.INVALID || c == d) return MeshKernel.INVALID;

        float oldQ = Math.min(faceQuality(mesh, f1), faceQuality(mesh, f2));

        float mx = (mesh.x(u) + mesh.x(v)) * 0.5f;
        float my = (mesh.y(u) + mesh.y(v)) * 0.5f;
        float mz = (mesh.z(u) + mesh.z(v)) * 0.5f;

        // Midpoint split exactly preserves the piecewise-linear surface.
        // Reject splitting already pathological triangles instead of making
        // their conditioning worse and calling it "valid topology".
        float q1 = qualityAt(mesh, u, mx, my, mz, c);
        float q2 = qualityAt(mesh, v, mx, my, mz, c);
        float q3 = qualityAt(mesh, v, mx, my, mz, d);
        float q4 = qualityAt(mesh, u, mx, my, mz, d);
        float childQ = Math.min(Math.min(q1, q2), Math.min(q3, q4));
        if (childQ < Math.max(GeometryValidator.MIN_TRIANGLE_QUALITY, oldQ * 0.42f)) {
            return MeshKernel.INVALID;
        }

        int mid = mesh.addVertex(mx, my, mz);
        mesh.setFace(f1, u, mid, c);
        mesh.addFace(mid, v, c);
        mesh.setFace(f2, v, mid, d);
        mesh.addFace(mid, u, d);
        mesh.ensureConnectivity();
        mesh.recomputeNormalsAll();
        return mid;
    }

    static boolean collapseEdgeRaw(MeshKernel mesh, int keep, int remove) {
        float[] p = bestCollapsePlacement(mesh, keep, remove, localMeanEdge(mesh, keep, remove));
        return p != null && collapseEdgeRaw(mesh, keep, remove, p[0], p[1], p[2]);
    }

    private static boolean collapseEdgeRaw(MeshKernel mesh, int keep, int remove,
            float px, float py, float pz) {
        if (!mesh.isVertexAlive(keep) || !mesh.isVertexAlive(remove) || keep == remove) return false;
        mesh.ensureConnectivity();
        if (!GeometryValidator.satisfiesClosedCollapseLinkCondition(mesh, keep, remove)) return false;

        mesh.setVertexPosition(keep, px, py, pz);

        for (int f = 0; f < mesh.faceHighWater; f++) {
            if (!mesh.isFaceAlive(f)) continue;
            int a = mesh.faceA[f], b = mesh.faceB[f], c = mesh.faceC[f];
            boolean changed = false;
            if (a == remove) { a = keep; changed = true; }
            if (b == remove) { b = keep; changed = true; }
            if (c == remove) { c = keep; changed = true; }
            if (!changed) continue;

            if (a == b || b == c || c == a) mesh.deleteFace(f);
            else mesh.setFace(f, a, b, c);
        }
        if (mesh.vertexHasLiveFace(remove)) return false;
        mesh.deleteVertex(remove);
        mesh.ensureConnectivity();
        mesh.recomputeNormalsAll();
        return true;
    }

    static boolean flipEdgeRaw(MeshKernel mesh, int a, int b) {
        mesh.ensureConnectivity();
        int h = mesh.findHalfEdge(a, b);
        if (h == MeshKernel.INVALID) h = mesh.findHalfEdge(b, a);
        if (h == MeshKernel.INVALID) return false;
        int twin = mesh.heTwin[h];
        if (twin == MeshKernel.INVALID) return false;

        int u = mesh.heOrigin[h];
        int v = mesh.heDest[h];
        int f1 = mesh.heFace[h];
        int f2 = mesh.heFace[twin];
        int c = mesh.oppositeVertex(f1, u, v);
        int d = mesh.oppositeVertex(f2, u, v);
        if (c == MeshKernel.INVALID || d == MeshKernel.INVALID || c == d) return false;
        if (mesh.findHalfEdge(c, d) != MeshKernel.INVALID || mesh.findHalfEdge(d, c) != MeshKernel.INVALID) {
            return false;
        }
        if (!flipPreflight(mesh, u, v)) return false;

        mesh.setFace(f1, c, d, v);
        mesh.setFace(f2, d, c, u);
        mesh.ensureConnectivity();
        mesh.recomputeNormalsAll();
        return true;
    }

    static GeometryTransaction.Result relaxVertex(MeshKernel mesh, int vertex, float alpha) {
        if (!mesh.isVertexAlive(vertex)) {
            return new GeometryTransaction.Result(false, GeometryValidator.Reason.UNKNOWN, "dead vertex");
        }
        mesh.ensureConnectivity();
        int[] neighbors = uniqueNeighbors(mesh, vertex);
        if (neighbors.length < 3) {
            return new GeometryTransaction.Result(false, GeometryValidator.Reason.UNKNOWN, "valence");
        }

        mesh.ensureNormals();
        float cx = 0f, cy = 0f, cz = 0f;
        for (int n : neighbors) {
            cx += mesh.x(n); cy += mesh.y(n); cz += mesh.z(n);
        }
        cx /= neighbors.length; cy /= neighbors.length; cz /= neighbors.length;

        float px = mesh.x(vertex), py = mesh.y(vertex), pz = mesh.z(vertex);
        float dx = cx - px, dy = cy - py, dz = cz - pz;
        float nx = mesh.nx(vertex), ny = mesh.ny(vertex), nz = mesh.nz(vertex);
        float normalPart = dx * nx + dy * ny + dz * nz;
        dx -= nx * normalPart; dy -= ny * normalPart; dz -= nz * normalPart;
        float t = Math.max(0f, Math.min(1f, alpha));

        GeometryTransaction transaction = new GeometryTransaction(mesh);
        int[] one = {vertex};
        transaction.beginVertexEdit(one, 1);
        mesh.setVertexPosition(vertex, px + dx * t, py + dy * t, pz + dz * t);
        return transaction.commitVertexEdit();
    }

    private static boolean flipPreflight(MeshKernel mesh, int a, int b) {
        mesh.ensureConnectivity();
        int h = mesh.findHalfEdge(a, b);
        if (h == MeshKernel.INVALID) h = mesh.findHalfEdge(b, a);
        if (h == MeshKernel.INVALID || mesh.heTwin[h] == MeshKernel.INVALID) return false;
        int t = mesh.heTwin[h];
        int u = mesh.heOrigin[h], v = mesh.heDest[h];
        int f1 = mesh.heFace[h], f2 = mesh.heFace[t];
        int c = mesh.oppositeVertex(f1, u, v);
        int d = mesh.oppositeVertex(f2, u, v);
        if (c == MeshKernel.INVALID || d == MeshKernel.INVALID || c == d) return false;
        if (mesh.findHalfEdge(c, d) != MeshKernel.INVALID || mesh.findHalfEdge(d, c) != MeshKernel.INVALID) {
            return false;
        }

        float oldMinQ = Math.min(faceQuality(mesh, f1), faceQuality(mesh, f2));
        float newQ1 = qualityVertices(mesh, c, d, v);
        float newQ2 = qualityVertices(mesh, d, c, u);
        float newMinQ = Math.min(newQ1, newQ2);
        if (newMinQ < GeometryValidator.MIN_TRIANGLE_QUALITY
                || newMinQ + 1e-5f < oldMinQ * 0.96f) return false;

        float oldMax = maxPatchEdge(mesh, u, v, c, d);
        float newEdge = GeometryValidator.edge(mesh, c, d);
        if (newEdge > oldMax * MAX_FLIP_NEW_EDGE_TO_OLD_MAX) return false;

        float[] patchN = add(faceNormal(mesh, f1), faceNormal(mesh, f2));
        float patchLen = len(patchN);
        if (patchLen < 1e-9f) return false;
        float[] n1 = normalVertices(mesh, c, d, v);
        float[] n2 = normalVertices(mesh, d, c, u);
        if (normalDot(patchN, n1) < MIN_PLACEMENT_NORMAL_DOT
                || normalDot(patchN, n2) < MIN_PLACEMENT_NORMAL_DOT) return false;
        return true;
    }

    private static float[] bestCollapsePlacement(MeshKernel mesh, int keep, int remove, float localMean) {
        if (!mesh.isVertexAlive(keep) || !mesh.isVertexAlive(remove)) return null;
        float[][] candidates = {
                {mesh.x(keep), mesh.y(keep), mesh.z(keep)},
                {mesh.x(remove), mesh.y(remove), mesh.z(remove)},
                {(mesh.x(keep)+mesh.x(remove))*0.5f,
                        (mesh.y(keep)+mesh.y(remove))*0.5f,
                        (mesh.z(keep)+mesh.z(remove))*0.5f}
        };
        float bestScore = -Float.MAX_VALUE;
        float[] best = null;
        for (float[] c : candidates) {
            float score = collapsePlacementScore(mesh, keep, remove, c[0], c[1], c[2], localMean);
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return bestScore > -1e20f ? Arrays.copyOf(best, 3) : null;
    }

    private static float collapsePlacementScore(MeshKernel mesh, int keep, int remove,
            float px, float py, float pz, float localMean) {
        float minQ = 1f;
        float minDot = 1f;
        float maxEdge = 0f;
        int surviving = 0;

        for (int f = 0; f < mesh.faceHighWater; f++) {
            if (!mesh.isFaceAlive(f)) continue;
            int a = mesh.faceA[f], b = mesh.faceB[f], c = mesh.faceC[f];
            boolean hasKeep = a == keep || b == keep || c == keep;
            boolean hasRemove = a == remove || b == remove || c == remove;
            if (!hasKeep && !hasRemove) continue;
            if (hasKeep && hasRemove) continue; // edge-adjacent faces disappear

            float[] oldN = faceNormal(mesh, f);
            int ra = a == remove ? keep : a;
            int rb = b == remove ? keep : b;
            int rc = c == remove ? keep : c;
            if (ra == rb || rb == rc || rc == ra) return -Float.MAX_VALUE;

            float[] A = pointCandidate(mesh, ra, keep, px, py, pz);
            float[] B = pointCandidate(mesh, rb, keep, px, py, pz);
            float[] C = pointCandidate(mesh, rc, keep, px, py, pz);
            float ab = distance(A, B), bc = distance(B, C), ca = distance(C, A);
            float[] newN = cross(sub(B, A), sub(C, A));
            float area2 = len(newN);
            float q = GeometryQuality.triangleQuality(area2, ab, bc, ca);
            float dot = normalDot(oldN, newN);
            if (!Float.isFinite(q) || q < MIN_PLACEMENT_QUALITY
                    || !Float.isFinite(dot) || dot < MIN_PLACEMENT_NORMAL_DOT) return -Float.MAX_VALUE;
            minQ = Math.min(minQ, q);
            minDot = Math.min(minDot, dot);
            maxEdge = Math.max(maxEdge, Math.max(ab, Math.max(bc, ca)));
            surviving++;
        }
        if (surviving < 3) return -Float.MAX_VALUE;
        if (localMean > 1e-8f && maxEdge > localMean * 2.25f) return -Float.MAX_VALUE;

        float move = MeshKernel.length(px-mesh.x(keep), py-mesh.y(keep), pz-mesh.z(keep));
        float movementPenalty = localMean > 1e-8f ? move / localMean : move;
        return minQ * 2.0f + minDot - movementPenalty * 0.15f;
    }

    private static float localMeanEdge(MeshKernel mesh, int a, int b) {
        mesh.ensureConnectivity();
        double sum = 0.0;
        int count = 0;
        for (int h = 0; h < mesh.halfEdgeCount; h++) {
            int o = mesh.heOrigin[h], d = mesh.heDest[h];
            if (o != a && d != a && o != b && d != b) continue;
            if (o > d) continue; // undirected once where possible
            sum += GeometryValidator.edge(mesh, o, d);
            count++;
        }
        return count == 0 ? GeometryValidator.edge(mesh, a, b) : (float)(sum / count);
    }

    private static int[] uniqueNeighbors(MeshKernel mesh, int vertex) {
        int[] tmp = new int[16];
        int count = 0;
        for (int h = 0; h < mesh.halfEdgeCount; h++) {
            if (mesh.heOrigin[h] != vertex) continue;
            int n = mesh.heDest[h];
            boolean seen = false;
            for (int i = 0; i < count; i++) if (tmp[i] == n) { seen = true; break; }
            if (seen) continue;
            if (count == tmp.length) tmp = Arrays.copyOf(tmp, tmp.length * 2);
            tmp[count++] = n;
        }
        return Arrays.copyOf(tmp, count);
    }

    private static float faceQuality(MeshKernel mesh, int f) {
        int a=mesh.faceA[f], b=mesh.faceB[f], c=mesh.faceC[f];
        return qualityVertices(mesh,a,b,c);
    }

    private static float qualityVertices(MeshKernel mesh, int a, int b, int c) {
        float ab=GeometryValidator.edge(mesh,a,b);
        float bc=GeometryValidator.edge(mesh,b,c);
        float ca=GeometryValidator.edge(mesh,c,a);
        float area2=GeometryValidator.area2(mesh,a,b,c);
        return GeometryQuality.triangleQuality(area2,ab,bc,ca);
    }

    private static float qualityAt(MeshKernel mesh, int a,
            float mx, float my, float mz, int c) {
        float[] A={mesh.x(a),mesh.y(a),mesh.z(a)};
        float[] M={mx,my,mz};
        float[] C={mesh.x(c),mesh.y(c),mesh.z(c)};
        float ab=distance(A,M), bc=distance(M,C), ca=distance(C,A);
        return GeometryQuality.triangleQuality(len(cross(sub(M,A),sub(C,A))),ab,bc,ca);
    }

    private static float maxPatchEdge(MeshKernel mesh, int a, int b, int c, int d) {
        float max=0f;
        int[] v={a,b,c,d};
        for(int i=0;i<4;i++) for(int j=i+1;j<4;j++) {
            if (mesh.findHalfEdge(v[i],v[j])!=MeshKernel.INVALID
                    || mesh.findHalfEdge(v[j],v[i])!=MeshKernel.INVALID) {
                max=Math.max(max,GeometryValidator.edge(mesh,v[i],v[j]));
            }
        }
        return max;
    }

    private static float[] faceNormal(MeshKernel mesh, int f) {
        return normalVertices(mesh,mesh.faceA[f],mesh.faceB[f],mesh.faceC[f]);
    }

    private static float[] normalVertices(MeshKernel mesh, int a, int b, int c) {
        float[] A={mesh.x(a),mesh.y(a),mesh.z(a)};
        float[] B={mesh.x(b),mesh.y(b),mesh.z(b)};
        float[] C={mesh.x(c),mesh.y(c),mesh.z(c)};
        return cross(sub(B,A),sub(C,A));
    }

    private static float[] pointCandidate(MeshKernel mesh, int v, int keep,
            float px,float py,float pz) {
        return v==keep ? new float[]{px,py,pz} : new float[]{mesh.x(v),mesh.y(v),mesh.z(v)};
    }

    private static float normalDot(float[] a, float[] b) {
        float la=len(a), lb=len(b);
        if (la<1e-10f || lb<1e-10f) return -1f;
        return (a[0]*b[0]+a[1]*b[1]+a[2]*b[2])/(la*lb);
    }

    private static float[] add(float[] a,float[] b) {
        return new float[]{a[0]+b[0],a[1]+b[1],a[2]+b[2]};
    }
    private static float[] sub(float[] a,float[] b) {
        return new float[]{a[0]-b[0],a[1]-b[1],a[2]-b[2]};
    }
    private static float[] cross(float[] a,float[] b) {
        return new float[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};
    }
    private static float len(float[] a) { return MeshKernel.length(a[0],a[1],a[2]); }
    private static float distance(float[] a,float[] b) { return len(sub(a,b)); }
}
