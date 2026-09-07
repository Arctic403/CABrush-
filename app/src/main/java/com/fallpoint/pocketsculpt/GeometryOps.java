package com.fallpoint.pocketsculpt;

/**
 * Elementary mutable-topology operators. These exist before DynTopo so each
 * primitive can be fuzzed and verified independently.
 */
final class GeometryOps {
    private GeometryOps() {}

    static GeometryTransaction.Result splitEdge(MeshKernel mesh, int a, int b) {
        return GeometryTransaction.runTopology(mesh, m -> splitEdgeRaw(m, a, b) >= 0, true);
    }

    static GeometryTransaction.Result collapseEdge(MeshKernel mesh, int keep, int remove) {
        return GeometryTransaction.runTopology(mesh, m -> collapseEdgeRaw(m, keep, remove), true);
    }

    static GeometryTransaction.Result flipEdge(MeshKernel mesh, int a, int b) {
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

        float mx = (mesh.x(u) + mesh.x(v)) * 0.5f;
        float my = (mesh.y(u) + mesh.y(v)) * 0.5f;
        float mz = (mesh.z(u) + mesh.z(v)) * 0.5f;
        int mid = mesh.addVertex(mx, my, mz);

        // f1 follows u->v; f2 follows v->u.
        mesh.setFace(f1, u, mid, c);
        mesh.addFace(mid, v, c);
        mesh.setFace(f2, v, mid, d);
        mesh.addFace(mid, u, d);
        mesh.ensureConnectivity();
        mesh.recomputeNormalsAll();
        return mid;
    }

    static boolean collapseEdgeRaw(MeshKernel mesh, int keep, int remove) {
        if (!mesh.isVertexAlive(keep) || !mesh.isVertexAlive(remove) || keep == remove) return false;
        mesh.ensureConnectivity();
        if (mesh.findHalfEdge(keep, remove) == MeshKernel.INVALID
                && mesh.findHalfEdge(remove, keep) == MeshKernel.INVALID) return false;

        // Place kept vertex at midpoint to reduce geometric shock.
        float nx = (mesh.x(keep) + mesh.x(remove)) * 0.5f;
        float ny = (mesh.y(keep) + mesh.y(remove)) * 0.5f;
        float nz = (mesh.z(keep) + mesh.z(remove)) * 0.5f;
        mesh.setVertexPosition(keep, nx, ny, nz);

        for (int f = 0; f < mesh.faceHighWater; f++) {
            if (!mesh.isFaceAlive(f)) continue;
            int a = mesh.faceA[f], b = mesh.faceB[f], c = mesh.faceC[f];
            boolean changed = false;
            if (a == remove) { a = keep; changed = true; }
            if (b == remove) { b = keep; changed = true; }
            if (c == remove) { c = keep; changed = true; }
            if (!changed) continue;

            if (a == b || b == c || c == a) {
                mesh.deleteFace(f);
            } else {
                mesh.setFace(f, a, b, c);
            }
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

        // Do not create a duplicate edge.
        if (mesh.findHalfEdge(c, d) != MeshKernel.INVALID || mesh.findHalfEdge(d, c) != MeshKernel.INVALID) {
            return false;
        }

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
        int[] neighbors = new int[32];
        int count = 0;
        for (int h = 0; h < mesh.halfEdgeCount; h++) {
            if (mesh.heOrigin[h] != vertex) continue;
            int n = mesh.heDest[h];
            boolean seen = false;
            for (int i = 0; i < count; i++) if (neighbors[i] == n) { seen = true; break; }
            if (seen) continue;
            if (count == neighbors.length) {
                int[] grown = new int[neighbors.length * 2];
                System.arraycopy(neighbors, 0, grown, 0, neighbors.length);
                neighbors = grown;
            }
            neighbors[count++] = n;
        }
        if (count < 3) return new GeometryTransaction.Result(false, GeometryValidator.Reason.UNKNOWN, "valence");

        mesh.ensureNormals();
        float cx = 0f, cy = 0f, cz = 0f;
        for (int i = 0; i < count; i++) {
            cx += mesh.x(neighbors[i]); cy += mesh.y(neighbors[i]); cz += mesh.z(neighbors[i]);
        }
        cx /= count; cy /= count; cz /= count;

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
}
