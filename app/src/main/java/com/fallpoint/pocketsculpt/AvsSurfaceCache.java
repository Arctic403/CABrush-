package com.fallpoint.pocketsculpt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Disposable AVS render cache using a globally conforming Freudenthal
 * tetrahedralization of each grid cube.
 *
 * Marching tetrahedra is intentionally used for AVS 0.1 because it has no
 * ambiguous cube cases: every cube is split into the same six tetrahedra and
 * neighboring cubes share the same face diagonals. That keeps chunk seams
 * deterministic while the field remains the only sculpt truth.
 */
final class AvsSurfaceCache {
    static final class Chunk {
        final int brickId;
        final int bx,by,bz;
        final long revision;
        final float[] positions;
        final float[] normals;
        final short[] indices;
        final float minX,minY,minZ,maxX,maxY,maxZ;

        Chunk(int brickId,int bx,int by,int bz,long revision,
              float[] positions,float[] normals,short[] indices,
              float minX,float minY,float minZ,float maxX,float maxY,float maxZ){
            this.brickId=brickId;this.bx=bx;this.by=by;this.bz=bz;this.revision=revision;
            this.positions=positions;this.normals=normals;this.indices=indices;
            this.minX=minX;this.minY=minY;this.minZ=minZ;this.maxX=maxX;this.maxY=maxY;this.maxZ=maxZ;
        }
        int triangleCount(){return indices.length/3;}
        long estimatedBytes(){return (long)(positions.length+normals.length)*4L+(long)indices.length*2L+64L;}
    }

    static final class RenderPlan {
        Chunk[] chunks=new Chunk[0];
        long fieldVersion=Long.MIN_VALUE;
        long surfaceVersion;
        int rebuiltChunks;
        long rebuiltBytes;
        int totalTriangles,totalVertices;
        long estimatedBytes(){long n=0;for(Chunk c:chunks)n+=c.estimatedBytes();return n;}
    }

    // Cube corner index = x + 2*y + 4*z.
    private static final int[][] TETS={
            {0,1,3,7}, // xyz
            {0,1,5,7}, // xzy
            {0,2,3,7}, // yxz
            {0,2,6,7}, // yzx
            {0,4,5,7}, // zxy
            {0,4,6,7}  // zyx
    };

    private final AvsVolume volume;
    private Chunk[] chunkByBrick=new Chunk[256];
    private final RenderPlan plan=new RenderPlan();
    private long nextRevision=1L;
    private final float[] gradientScratch=new float[3];

    AvsSurfaceCache(AvsVolume volume){this.volume=volume;}

    RenderPlan currentPlan(){
        if(plan.fieldVersion!=volume.fieldVersion||volume.dirtyBrickCount()>0)rebuildDirty();
        return plan;
    }

    void rebuildAll(){volume.markAllDirty();rebuildDirty();}

    private void rebuildDirty(){
        ensureChunkCapacity(volume.brickCount());
        int rebuilt=0;long bytes=0;
        for(int id=0;id<volume.brickCount();id++){
            if(!volume.isBrickDirty(id))continue;
            Chunk c=buildChunk(id);chunkByBrick[id]=c;volume.clearBrickDirty(id);
            rebuilt++;if(c!=null)bytes+=c.estimatedBytes();
        }
        List<Chunk> live=new ArrayList<>();
        int tris=0,verts=0;
        for(int i=0;i<volume.brickCount();i++){
            Chunk c=chunkByBrick[i];if(c==null||c.indices.length==0)continue;
            live.add(c);tris+=c.indices.length/3;verts+=c.positions.length/3;
        }
        plan.chunks=live.toArray(new Chunk[0]);
        plan.fieldVersion=volume.fieldVersion;plan.surfaceVersion++;
        plan.rebuiltChunks=rebuilt;plan.rebuiltBytes=bytes;plan.totalTriangles=tris;plan.totalVertices=verts;
    }

    private Chunk buildChunk(int brickId){
        AvsVolume.Brick b=volume.brick(brickId);if(b==null)return null;
        int baseX=b.bx*AvsVolume.BRICK_SIZE,baseY=b.by*AvsVolume.BRICK_SIZE,baseZ=b.bz*AvsVolume.BRICK_SIZE;

        FloatList pos=new FloatList(4096),nor=new FloatList(4096);
        ShortList idx=new ShortList(8192);
        PrimitiveLongIntMap edgeVertex=new PrimitiveLongIntMap(2048);

        int[] pointId=new int[8];
        int[] gx=new int[8],gy=new int[8],gz=new int[8];
        float[] val=new float[8];

        for(int lz=0;lz<AvsVolume.BRICK_SIZE;lz++){
            for(int ly=0;ly<AvsVolume.BRICK_SIZE;ly++){
                for(int lx=0;lx<AvsVolume.BRICK_SIZE;lx++){
                    boolean neg=false,posi=false;
                    for(int c=0;c<8;c++){
                        int cx=c&1,cy=(c>>1)&1,cz=(c>>2)&1;
                        int plx=lx+cx,ply=ly+cy,plz=lz+cz;
                        pointId[c]=plx+9*(ply+9*plz);
                        gx[c]=baseX+plx;gy[c]=baseY+ply;gz[c]=baseZ+plz;
                        val[c]=volume.sampleGrid(gx[c],gy[c],gz[c]);
                        if(val[c]<0f)neg=true;else posi=true;
                    }
                    if(!(neg&&posi))continue;
                    for(int[] t:TETS) polygonizeTet(t,pointId,gx,gy,gz,val,edgeVertex,pos,nor,idx);
                }
            }
        }

        if(idx.size==0){
            return new Chunk(brickId,b.bx,b.by,b.bz,nextRevision++,new float[0],new float[0],new short[0],0,0,0,0,0,0);
        }
        float[] p=Arrays.copyOf(pos.data,pos.size),n=Arrays.copyOf(nor.data,nor.size);
        short[] ii=Arrays.copyOf(idx.data,idx.size);
        float minX=Float.POSITIVE_INFINITY,minY=Float.POSITIVE_INFINITY,minZ=Float.POSITIVE_INFINITY;
        float maxX=Float.NEGATIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY,maxZ=Float.NEGATIVE_INFINITY;
        for(int i=0;i<p.length;i+=3){
            minX=Math.min(minX,p[i]);maxX=Math.max(maxX,p[i]);
            minY=Math.min(minY,p[i+1]);maxY=Math.max(maxY,p[i+1]);
            minZ=Math.min(minZ,p[i+2]);maxZ=Math.max(maxZ,p[i+2]);
        }
        return new Chunk(brickId,b.bx,b.by,b.bz,nextRevision++,p,n,ii,minX,minY,minZ,maxX,maxY,maxZ);
    }

    private void polygonizeTet(int[] tet,int[] pointId,int[] gx,int[] gy,int[] gz,float[] val,
                               PrimitiveLongIntMap edgeVertex,FloatList pos,FloatList nor,ShortList idx){
        int[] inside=new int[4],outside=new int[4];int ni=0,no=0;
        for(int q=0;q<4;q++){int c=tet[q];if(val[c]<0f)inside[ni++]=c;else outside[no++]=c;}
        if(ni==0||ni==4)return;

        if(ni==1){
            int a=inside[0];
            int v0=edgeVertex(a,outside[0],pointId,gx,gy,gz,val,edgeVertex,pos,nor);
            int v1=edgeVertex(a,outside[1],pointId,gx,gy,gz,val,edgeVertex,pos,nor);
            int v2=edgeVertex(a,outside[2],pointId,gx,gy,gz,val,edgeVertex,pos,nor);
            emit(v0,v1,v2,pos,nor,idx);
        }else if(ni==3){
            int o=outside[0];
            int v0=edgeVertex(o,inside[0],pointId,gx,gy,gz,val,edgeVertex,pos,nor);
            int v1=edgeVertex(o,inside[1],pointId,gx,gy,gz,val,edgeVertex,pos,nor);
            int v2=edgeVertex(o,inside[2],pointId,gx,gy,gz,val,edgeVertex,pos,nor);
            emit(v0,v1,v2,pos,nor,idx);
        }else{
            int a=inside[0],bb=inside[1],c=outside[0],d=outside[1];
            int p0=edgeVertex(a,c,pointId,gx,gy,gz,val,edgeVertex,pos,nor);
            int p1=edgeVertex(a,d,pointId,gx,gy,gz,val,edgeVertex,pos,nor);
            int p2=edgeVertex(bb,c,pointId,gx,gy,gz,val,edgeVertex,pos,nor);
            int p3=edgeVertex(bb,d,pointId,gx,gy,gz,val,edgeVertex,pos,nor);
            emit(p0,p1,p3,pos,nor,idx);
            emit(p0,p3,p2,pos,nor,idx);
        }
    }

    private int edgeVertex(int ca,int cb,int[] pointId,int[] gx,int[] gy,int[] gz,float[] val,
                           PrimitiveLongIntMap map,FloatList pos,FloatList nor){
        int pa=pointId[ca],pb=pointId[cb],lo=Math.min(pa,pb),hi=Math.max(pa,pb);
        long key=((long)lo<<32)|(hi&0xffffffffL);
        int old=map.get(key,-1);if(old>=0)return old;
        float va=val[ca],vb=val[cb],den=va-vb;
        float t=Math.abs(den)<1e-12f?0.5f:va/den;t=Math.max(0f,Math.min(1f,t));
        float x=(gx[ca]+(gx[cb]-gx[ca])*t)*AvsVolume.VOXEL_SIZE;
        float y=(gy[ca]+(gy[cb]-gy[ca])*t)*AvsVolume.VOXEL_SIZE;
        float z=(gz[ca]+(gz[cb]-gz[ca])*t)*AvsVolume.VOXEL_SIZE;
        int id=pos.size/3;
        if(id>=65535)throw new IllegalStateException("AVS chunk exceeds 16-bit vertex range");
        pos.add(x);pos.add(y);pos.add(z);
        volume.gradient(x,y,z,gradientScratch);
        nor.add(gradientScratch[0]);nor.add(gradientScratch[1]);nor.add(gradientScratch[2]);
        map.put(key,id,-1);return id;
    }

    private static void emit(int a,int b,int c,FloatList pos,FloatList nor,ShortList idx){
        if(a==b||b==c||c==a)return;
        int ia=a*3,ib=b*3,ic=c*3;
        float abx=pos.data[ib]-pos.data[ia],aby=pos.data[ib+1]-pos.data[ia+1],abz=pos.data[ib+2]-pos.data[ia+2];
        float acx=pos.data[ic]-pos.data[ia],acy=pos.data[ic+1]-pos.data[ia+1],acz=pos.data[ic+2]-pos.data[ia+2];
        float fx=aby*acz-abz*acy,fy=abz*acx-abx*acz,fz=abx*acy-aby*acx;
        float nx=nor.data[ia]+nor.data[ib]+nor.data[ic];
        float ny=nor.data[ia+1]+nor.data[ib+1]+nor.data[ic+1];
        float nz=nor.data[ia+2]+nor.data[ib+2]+nor.data[ic+2];
        if(fx*nx+fy*ny+fz*nz<0f){int tmp=b;b=c;c=tmp;}
        idx.add((short)a);idx.add((short)b);idx.add((short)c);
    }

    private void ensureChunkCapacity(int needed){
        if(chunkByBrick.length>=needed)return;int cap=chunkByBrick.length;
        while(cap<needed)cap=cap+cap/2+64;chunkByBrick=Arrays.copyOf(chunkByBrick,cap);
    }

    private static final class FloatList{
        float[] data;int size;FloatList(int c){data=new float[Math.max(16,c)];}
        void add(float v){if(size==data.length)data=Arrays.copyOf(data,data.length+data.length/2+64);data[size++]=v;}
    }
    private static final class ShortList{
        short[] data;int size;ShortList(int c){data=new short[Math.max(16,c)];}
        void add(short v){if(size==data.length)data=Arrays.copyOf(data,data.length+data.length/2+64);data[size++]=v;}
    }
}
