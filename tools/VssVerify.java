package com.fallpoint.pocketsculpt;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * CABrush AVS Verification Snapshot System v1.
 *
 * Tests the sparse field and disposable surface cache rather than mutable-mesh
 * survival. Emits screenshots + a machine-readable dump before Android builds.
 */
public final class VssVerify {
    private static final int IMAGE = 640;
    private static final long SEED = 0xCA_BA_2026_0907L;
    private static final List<String> failures = new ArrayList<>();
    private static final List<Scenario> scenarios = new ArrayList<>();
    private static final List<String> screenshots = new ArrayList<>();

    private static double rayP50Us, rayP95Us, rayP99Us;
    private static double meshP50Ms, meshP95Ms, meshP99Ms;

    private static final class Scenario {
        String name;
        boolean pass = true;
        long ms;
        int attempted, accepted, rejected;
        int bricks, chunks, vertices, triangles;
        long fieldBytes, surfaceBytes;
        String hash = "";
        String detail = "";
    }

    private static final class WeldReport {
        int vertices;
        int triangles;
        int boundaryEdges;
        int nonManifoldEdges;
        int degenerateTriangles;
    }

    private static final class Tri {
        float[] p0,p1,p2;
        float depth, shade;
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US);
        System.setProperty("java.awt.headless","true");
        File out = new File(args.length > 0 ? args[0] : "build/vss/report");
        if (!out.exists() && !out.mkdirs()) throw new IllegalStateException("cannot create "+out);

        long start = System.currentTimeMillis();
        List<BufferedImage> images = new ArrayList<>();
        List<String> labels = new ArrayList<>();

        try {
            baseline(out,images,labels);
            clayGrowth(out,images,labels);
            claySubtract(out,images,labels);
            mixedStress(out,images,labels);
            snapshotRoundTrip();
            determinism();
            dirtyLocality();
            benchmark();
        } catch (Throwable t) {
            fail("uncaught: "+t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage()));
            t.printStackTrace(System.err);
        }

        try {
            BufferedImage sheet = contactSheet(images,labels);
            File f = new File(out,"99-contact-sheet.png");
            ImageIO.write(sheet,"png",f);
            screenshots.add(f.getName());
        } catch (Throwable t) {
            fail("contact-sheet: "+t.getMessage());
        }

        long duration = System.currentTimeMillis()-start;
        writeJson(out,duration);
        writeText(out,duration);

        boolean pass=failures.isEmpty();
        System.out.println("CABrush AVS 0.1 VSS: "+(pass?"PASS":"FAIL"));
        System.out.println("Scenarios="+scenarios.size()+" failures="+failures.size());
        System.out.println("Evidence="+out.getAbsolutePath());
        if(!pass){for(String f:failures)System.err.println("FAIL: "+f);System.exit(2);}
    }

    private static void baseline(File out,List<BufferedImage> images,List<String> labels)throws Exception{
        Scenario s=scenario("baseline-sdf-surface");
        long st=System.currentTimeMillis();
        AvsVolume v=AvsVolume.createSphere(1f);
        check(v.sample(0,0,0)<0f,s,"center must be inside");
        check(v.sample(1.4f,0,0)>0f,s,"outside point must be positive");

        AvsVolume.RayHit hit=v.raycast(0,0,4,0,0,-1,new AvsVolume.RayHit());
        check(hit.hit,s,"sphere raycast missed");
        if(hit.hit) check(Math.abs(hit.z-1f)<AvsVolume.VOXEL_SIZE*1.6f,s,"ray z="+hit.z);

        AvsSurfaceCache surf=new AvsSurfaceCache(v);
        AvsSurfaceCache.RenderPlan plan=surf.currentPlan();
        WeldReport wr=weldCheck(plan);
        check(wr.boundaryEdges==0,s,"surface has seam/boundary edges="+wr.boundaryEdges);
        check(wr.nonManifoldEdges==0,s,"surface non-manifold edges="+wr.nonManifoldEdges);
        check(wr.degenerateTriangles==0,s,"degenerate triangles="+wr.degenerateTriangles);

        fillStats(s,v,plan);
        s.hash=v.fieldHash();
        addScreenshot(out,"00-avs-baseline.png",plan,"AVS BASELINE",images,labels);
        s.ms=System.currentTimeMillis()-st;
    }

    private static void clayGrowth(File out,List<BufferedImage> images,List<String> labels)throws Exception{
        Scenario s=scenario("clay-add-unbounded-growth");
        long st=System.currentTimeMillis();
        AvsVolume v=AvsVolume.createSphere(1f);
        AvsSurfaceCache surf=new AvsSurfaceCache(v);
        AvsVolume.RayHit h=new AvsVolume.RayHit();

        float lastZ=0f;
        for(int i=1;i<=240;i++){
            float originZ=v.stats().maxZ+1.5f;
            h=v.raycast(0,0,originZ,0,0,-1,h);
            s.attempted++;
            if(!h.hit){s.rejected++;failScenario(s,"ray miss at "+i);break;}
            AvsVolume.BrushResult r=v.applyClay(h.x,h.y,h.z,h.nx,h.ny,h.nz,0.30f,0.014f,BrushMode.ADD);
            if(r.changed)s.accepted++;else{s.rejected++;failScenario(s,"add rejected at "+i);break;}
            if(i%40==0){
                float checkOrigin=v.stats().maxZ+1.5f;
                AvsVolume.RayHit checkHit=v.raycast(0,0,checkOrigin,0,0,-1,new AvsVolume.RayHit());
                check(checkHit.hit,s,"growth checkpoint ray miss "+i);
                if(checkHit.hit){
                    check(checkHit.z>lastZ+AvsVolume.VOXEL_SIZE*0.20f,s,
                            "Clay+ stopped growing at "+i+" z="+checkHit.z+" last="+lastZ);
                    lastZ=checkHit.z;
                }
            }
            if(i==60||i==120||i==240){
                AvsSurfaceCache.RenderPlan p=surf.currentPlan();
                addScreenshot(out,String.format(Locale.US,"01-clay-add-%03d.png",i),p,
                        "CLAY+ "+i,images,labels);
            }
        }
        AvsSurfaceCache.RenderPlan plan=surf.currentPlan();
        WeldReport wr=weldCheck(plan);
        check(wr.boundaryEdges==0,s,"growth surface boundary edges="+wr.boundaryEdges);
        check(wr.nonManifoldEdges==0,s,"growth nonmanifold="+wr.nonManifoldEdges);
        check(s.rejected==0,s,"Clay+ rejected "+s.rejected+" dabs");
        check(lastZ>2.0f,s,"Clay+ extension too small finalZ="+lastZ);
        fillStats(s,v,plan);s.hash=v.fieldHash();s.ms=System.currentTimeMillis()-st;
    }

    private static void claySubtract(File out,List<BufferedImage> images,List<String> labels)throws Exception{
        Scenario s=scenario("clay-subtract-volume-removal");
        long st=System.currentTimeMillis();
        AvsVolume v=AvsVolume.createSphere(1f);
        long before=v.stats().negativeSamples;
        AvsVolume.RayHit h=new AvsVolume.RayHit();

        for(int i=0;i<70;i++){
            h=v.raycast(0,0,4,0,0,-1,h);
            s.attempted++;
            if(!h.hit){s.rejected++;break;}
            AvsVolume.BrushResult r=v.applyClay(h.x,h.y,h.z,h.nx,h.ny,h.nz,0.24f,0.016f,BrushMode.SUBTRACT);
            if(r.changed)s.accepted++;else s.rejected++;
        }
        long after=v.stats().negativeSamples;
        check(after<before,s,"Clay- did not reduce negative sample volume: "+before+" -> "+after);
        check(s.accepted>=30,s,"too few subtract dabs accepted="+s.accepted);

        AvsSurfaceCache surf=new AvsSurfaceCache(v);
        AvsSurfaceCache.RenderPlan plan=surf.currentPlan();
        WeldReport wr=weldCheck(plan);
        check(wr.boundaryEdges==0,s,"subtract surface boundary edges="+wr.boundaryEdges);
        addScreenshot(out,"02-clay-subtract.png",plan,"CLAY- CARVE",images,labels);
        fillStats(s,v,plan);s.hash=v.fieldHash();s.ms=System.currentTimeMillis()-st;
    }

    private static void mixedStress(File out,List<BufferedImage> images,List<String> labels)throws Exception{
        Scenario s=scenario("mixed-csg-2000");
        long st=System.currentTimeMillis();
        AvsVolume v=AvsVolume.createSphere(1f);
        Random rnd=new Random(SEED);
        AvsVolume.RayHit h=new AvsVolume.RayHit();

        for(int i=0;i<2000;i++){
            float[] d=randomDirection(rnd);
            h=v.raycast(d[0]*7f,d[1]*7f,d[2]*7f,-d[0],-d[1],-d[2],h);
            s.attempted++;
            if(!h.hit){s.rejected++;continue;}
            BrushMode mode=(i%5==0||i%7==0)?BrushMode.SUBTRACT:BrushMode.ADD;
            float radius=0.09f+rnd.nextFloat()*0.17f;
            float strength=0.006f+rnd.nextFloat()*0.014f;
            AvsVolume.BrushResult r=v.applyClay(h.x,h.y,h.z,h.nx,h.ny,h.nz,radius,strength,mode);
            if(r.changed)s.accepted++;else s.rejected++;

            if((i+1)%500==0){
                check(v.brickCount()<AvsVolume.MAX_BRICKS,s,"brick budget runaway at "+(i+1));
            }
        }

        AvsSurfaceCache surf=new AvsSurfaceCache(v);
        AvsSurfaceCache.RenderPlan plan=surf.currentPlan();
        WeldReport wr=weldCheck(plan);
        check(wr.boundaryEdges==0,s,"stress surface boundary edges="+wr.boundaryEdges);
        check(wr.nonManifoldEdges==0,s,"stress nonmanifold="+wr.nonManifoldEdges);
        check(wr.degenerateTriangles==0,s,"stress degenerate triangles="+wr.degenerateTriangles);
        check(s.accepted>1500,s,"too many stress rejections accepted="+s.accepted+" rejected="+s.rejected);

        // Picking must still hit after complex edits.
        boolean anyHit=false;
        for(int i=0;i<32;i++){
            float[] d=randomDirection(rnd);
            if(v.raycast(d[0]*7f,d[1]*7f,d[2]*7f,-d[0],-d[1],-d[2],h).hit){anyHit=true;break;}
        }
        check(anyHit,s,"raycast lost stressed surface");
        addScreenshot(out,"03-mixed-csg-2000.png",plan,"MIXED CSG 2000",images,labels);
        fillStats(s,v,plan);s.hash=v.fieldHash();s.ms=System.currentTimeMillis()-st;
    }

    private static void snapshotRoundTrip(){
        Scenario s=scenario("snapshot-bit-exact");
        long st=System.currentTimeMillis();
        AvsVolume v=AvsVolume.createSphere(1f);
        for(int i=0;i<18;i++) v.applySphereCsg(0.2f*i,0,0,0.18f,true);
        AvsSnapshot snap=v.snapshot();
        String before=v.fieldHash();
        for(int i=0;i<12;i++) v.applySphereCsg(0,0,0.15f*i,0.16f,false);
        snap.restoreInto(v);
        String after=v.fieldHash();
        check(before.equals(after),s,"snapshot hash mismatch");
        s.hash=after;s.ms=System.currentTimeMillis()-st;
    }

    private static void determinism(){
        Scenario s=scenario("deterministic-replay");
        long st=System.currentTimeMillis();
        String a=replayHash(SEED+10);
        String b=replayHash(SEED+10);
        check(a.equals(b),s,"same seed produced different field hashes");
        s.hash=a;s.ms=System.currentTimeMillis()-st;
    }

    private static String replayHash(long seed){
        AvsVolume v=AvsVolume.createSphere(1f);
        Random r=new Random(seed);
        AvsVolume.RayHit h=new AvsVolume.RayHit();
        for(int i=0;i<240;i++){
            float[] d=randomDirection(r);
            h=v.raycast(d[0]*5f,d[1]*5f,d[2]*5f,-d[0],-d[1],-d[2],h);
            if(!h.hit)continue;
            BrushMode m=(i&3)==0?BrushMode.SUBTRACT:BrushMode.ADD;
            v.applyClay(h.x,h.y,h.z,h.nx,h.ny,h.nz,0.12f+r.nextFloat()*0.10f,
                    0.007f+r.nextFloat()*0.010f,m);
        }
        return v.fieldHash();
    }

    private static void dirtyLocality(){
        Scenario s=scenario("dirty-chunk-locality");
        long st=System.currentTimeMillis();
        AvsVolume v=AvsVolume.createSphere(1f);
        AvsSurfaceCache surf=new AvsSurfaceCache(v);
        AvsSurfaceCache.RenderPlan initial=surf.currentPlan();
        int totalBricks=v.brickCount();
        AvsVolume.RayHit h=v.raycast(0,0,4,0,0,-1,new AvsVolume.RayHit());
        check(h.hit,s,"locality ray miss");
        if(h.hit)v.applyClay(h.x,h.y,h.z,h.nx,h.ny,h.nz,0.12f,0.008f,BrushMode.ADD);
        AvsSurfaceCache.RenderPlan next=surf.currentPlan();
        check(next.rebuiltChunks>0,s,"no dirty chunks rebuilt");
        check(next.rebuiltChunks<totalBricks/2,s,
                "local dab rebuilt too much: "+next.rebuiltChunks+"/"+totalBricks);
        s.bricks=v.brickCount();s.chunks=next.chunks.length;s.vertices=next.totalVertices;s.triangles=next.totalTriangles;
        s.fieldBytes=v.stats().sampleBytes;s.surfaceBytes=next.estimatedBytes();s.hash=v.fieldHash();
        s.detail="rebuilt="+next.rebuiltChunks+" totalBricks="+totalBricks;
        s.ms=System.currentTimeMillis()-st;
    }

    private static void benchmark(){
        Scenario s=scenario("performance-smoke");
        long st=System.currentTimeMillis();
        AvsVolume v=AvsVolume.createSphere(1f);
        Random rnd=new Random(SEED+99);
        AvsVolume.RayHit hit=new AvsVolume.RayHit();
        for(int i=0;i<220;i++){
            float[] d=randomDirection(rnd);
            hit=v.raycast(d[0]*5f,d[1]*5f,d[2]*5f,-d[0],-d[1],-d[2],hit);
            if(hit.hit)v.applyClay(hit.x,hit.y,hit.z,hit.nx,hit.ny,hit.nz,0.16f,0.010f,BrushMode.ADD);
        }

        double[] ray=new double[240];
        for(int i=0;i<ray.length;i++){
            float[] d=randomDirection(rnd);
            long t0=System.nanoTime();
            v.raycast(d[0]*6f,d[1]*6f,d[2]*6f,-d[0],-d[1],-d[2],hit);
            ray[i]=(System.nanoTime()-t0)/1000.0;
        }
        Arrays.sort(ray);rayP50Us=pct(ray,.50);rayP95Us=pct(ray,.95);rayP99Us=pct(ray,.99);

        double[] mesh=new double[20];
        for(int i=0;i<mesh.length;i++){
            AvsSurfaceCache surf=new AvsSurfaceCache(v);
            v.markAllDirty();
            long t0=System.nanoTime();
            AvsSurfaceCache.RenderPlan p=surf.currentPlan();
            mesh[i]=(System.nanoTime()-t0)/1_000_000.0;
            if(p.totalTriangles==0)failScenario(s,"empty benchmark surface");
        }
        Arrays.sort(mesh);meshP50Ms=pct(mesh,.50);meshP95Ms=pct(mesh,.95);meshP99Ms=pct(mesh,.99);
        s.detail=String.format(Locale.US,"ray p95=%.2fus full-extract p95=%.2fms",rayP95Us,meshP95Ms);
        s.ms=System.currentTimeMillis()-st;
    }

    private static WeldReport weldCheck(AvsSurfaceCache.RenderPlan plan){
        WeldReport r=new WeldReport();
        Map<VertexKey,Integer> vertices=new HashMap<>();
        Map<Long,Integer> edges=new HashMap<>();
        int next=0;
        for(AvsSurfaceCache.Chunk c:plan.chunks){
            int localCount=c.positions.length/3;
            int[] map=new int[localCount];
            for(int i=0;i<localCount;i++){
                int b=i*3;
                VertexKey key=new VertexKey(c.positions[b],c.positions[b+1],c.positions[b+2]);
                Integer id=vertices.get(key);
                if(id==null){id=next++;vertices.put(key,id);}
                map[i]=id;
            }
            for(int i=0;i<c.indices.length;i+=3){
                int a=map[c.indices[i]&0xffff],b=map[c.indices[i+1]&0xffff],d=map[c.indices[i+2]&0xffff];
                if(a==b||b==d||d==a){r.degenerateTriangles++;continue;}
                addEdge(edges,a,b);addEdge(edges,b,d);addEdge(edges,d,a);r.triangles++;
            }
        }
        r.vertices=vertices.size();
        for(int count:edges.values()){
            if(count==1)r.boundaryEdges++;
            else if(count!=2)r.nonManifoldEdges++;
        }
        return r;
    }

    private static void addEdge(Map<Long,Integer> edges,int a,int b){
        int lo=Math.min(a,b),hi=Math.max(a,b);
        long key=((long)lo<<32)|(hi&0xffffffffL);
        edges.put(key,edges.getOrDefault(key,0)+1);
    }

    private static final class VertexKey {
        final int x,y,z;
        VertexKey(float x,float y,float z){
            // Surface Nets boundary calculations are deterministic; quantization
            // absorbs only tiny floating-point order noise across chunk rebuilds.
            this.x=Math.round(x*100000f);this.y=Math.round(y*100000f);this.z=Math.round(z*100000f);
        }
        public boolean equals(Object o){if(!(o instanceof VertexKey))return false;VertexKey k=(VertexKey)o;return x==k.x&&y==k.y&&z==k.z;}
        public int hashCode(){int h=x*73856093;h^=y*19349663;h^=z*83492791;return h;}
    }

    private static void fillStats(Scenario s,AvsVolume v,AvsSurfaceCache.RenderPlan p){
        AvsVolume.Stats st=v.stats();
        s.bricks=st.bricks;s.fieldBytes=st.sampleBytes;s.chunks=p.chunks.length;
        s.vertices=p.totalVertices;s.triangles=p.totalTriangles;s.surfaceBytes=p.estimatedBytes();
    }

    private static float[] randomDirection(Random r){
        float z=r.nextFloat()*2f-1f;
        float a=r.nextFloat()*(float)(Math.PI*2);
        float q=(float)Math.sqrt(Math.max(0f,1f-z*z));
        return new float[]{q*(float)Math.cos(a),z,q*(float)Math.sin(a)};
    }

    private static void addScreenshot(File out,String name,AvsSurfaceCache.RenderPlan p,String label,
                                      List<BufferedImage> images,List<String> labels)throws Exception{
        BufferedImage img=render(p,label);
        File f=new File(out,name);ImageIO.write(img,"png",f);
        screenshots.add(name);images.add(img);labels.add(label);
    }

    private static BufferedImage render(AvsSurfaceCache.RenderPlan plan,String title){
        BufferedImage img=new BufferedImage(IMAGE,IMAGE,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(18,21,25));g.fillRect(0,0,IMAGE,IMAGE);

        float yaw=(float)Math.toRadians(28),pitch=(float)Math.toRadians(-12);
        float cy=(float)Math.cos(yaw),sy=(float)Math.sin(yaw),cp=(float)Math.cos(pitch),sp=(float)Math.sin(pitch);
        List<Tri> tris=new ArrayList<>();
        float max=1f;

        for(AvsSurfaceCache.Chunk c:plan.chunks){
            float[] t=new float[c.positions.length];
            for(int i=0;i<c.positions.length;i+=3){
                float x=c.positions[i],y=c.positions[i+1],z=c.positions[i+2];
                float x1=cy*x+sy*z, z1=-sy*x+cy*z, y1=cp*y-sp*z1, z2=sp*y+cp*z1;
                t[i]=x1;t[i+1]=y1;t[i+2]=z2;
                max=Math.max(max,Math.max(Math.abs(x1),Math.abs(y1)));
            }
            for(int i=0;i<c.indices.length;i+=3){
                int ia=(c.indices[i]&0xffff)*3,ib=(c.indices[i+1]&0xffff)*3,ic=(c.indices[i+2]&0xffff)*3;
                float abx=t[ib]-t[ia],aby=t[ib+1]-t[ia+1],abz=t[ib+2]-t[ia+2];
                float acx=t[ic]-t[ia],acy=t[ic+1]-t[ia+1],acz=t[ic+2]-t[ia+2];
                float nx=aby*acz-abz*acy,ny=abz*acx-abx*acz,nz=abx*acy-aby*acx;
                float nl=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);if(nl<1e-8f)continue;
                nx/=nl;ny/=nl;nz/=nl;if(nz<=0f)continue;
                Tri tr=new Tri();
                tr.p0=new float[]{t[ia],t[ia+1],t[ia+2]};tr.p1=new float[]{t[ib],t[ib+1],t[ib+2]};tr.p2=new float[]{t[ic],t[ic+1],t[ic+2]};
                tr.depth=(t[ia+2]+t[ib+2]+t[ic+2])/3f;
                float light=Math.max(0f,Math.min(1f,nx*-0.25f+ny*0.55f+nz*0.78f));
                tr.shade=0.25f+light*0.68f;tris.add(tr);
            }
        }
        tris.sort((a,b)->Float.compare(a.depth,b.depth));
        float scale=IMAGE*0.39f/max,cx=IMAGE*0.5f,cyy=IMAGE*0.52f;
        for(Tri t:tris){
            int[] xs={Math.round(cx+t.p0[0]*scale),Math.round(cx+t.p1[0]*scale),Math.round(cx+t.p2[0]*scale)};
            int[] ys={Math.round(cyy-t.p0[1]*scale),Math.round(cyy-t.p1[1]*scale),Math.round(cyy-t.p2[1]*scale)};
            int gray=Math.max(0,Math.min(255,Math.round(t.shade*255f)));
            g.setColor(new Color(gray,gray,gray));g.fillPolygon(new Polygon(xs,ys,3));
        }
        g.setColor(Color.WHITE);g.setFont(new Font(Font.MONOSPACED,Font.BOLD,17));g.drawString(title,18,28);
        g.setFont(new Font(Font.MONOSPACED,Font.PLAIN,13));
        g.drawString("chunks="+plan.chunks.length+" verts="+plan.totalVertices+" tris="+plan.totalTriangles,18,49);
        g.dispose();return img;
    }

    private static BufferedImage contactSheet(List<BufferedImage> imgs,List<String> labels){
        int cols=2,cell=330,labelH=34,rows=(imgs.size()+1)/2;
        BufferedImage out=new BufferedImage(cols*cell,rows*(cell+labelH),BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=out.createGraphics();g.setColor(new Color(12,14,17));g.fillRect(0,0,out.getWidth(),out.getHeight());
        g.setColor(Color.WHITE);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,12));
        for(int i=0;i<imgs.size();i++){int x=(i%2)*cell,y=(i/2)*(cell+labelH);g.drawImage(imgs.get(i),x,y,cell,cell,null);g.drawString(labels.get(i),x+8,y+cell+21);}
        g.dispose();return out;
    }

    private static Scenario scenario(String name){Scenario s=new Scenario();s.name=name;scenarios.add(s);return s;}
    private static void check(boolean ok,Scenario s,String detail){if(!ok)failScenario(s,detail);}
    private static void failScenario(Scenario s,String detail){s.pass=false;if(!s.detail.isEmpty())s.detail+="; ";s.detail+=detail;failures.add(s.name+": "+detail);}
    private static void fail(String detail){failures.add(detail);}

    private static double pct(double[] a,double p){
        if(a.length==0)return 0;int i=(int)Math.floor((a.length-1)*p);return a[Math.max(0,Math.min(a.length-1,i))];
    }

    private static void writeJson(File out,long duration)throws Exception{
        try(FileWriter w=new FileWriter(new File(out,"dump.json"))){
            w.write("{\n  \"schema\":\"cabrush-avs-dump-v1\",\n");
            w.write("  \"generated_at\":\""+Instant.now()+"\",\n");
            w.write("  \"result\":\""+(failures.isEmpty()?"PASS":"FAIL")+"\",\n");
            w.write("  \"duration_ms\":"+duration+",\n");
            w.write("  \"config\":{\"brick_size\":8,\"samples_per_brick\":512,\"sample_bytes\":2,\"voxel_size\":"+AvsVolume.VOXEL_SIZE+",\"max_bricks\":"+AvsVolume.MAX_BRICKS+"},\n");
            w.write(String.format(Locale.US,"  \"performance\":{\"ray_p50_us\":%.3f,\"ray_p95_us\":%.3f,\"ray_p99_us\":%.3f,\"full_extract_p50_ms\":%.3f,\"full_extract_p95_ms\":%.3f,\"full_extract_p99_ms\":%.3f},\n",
                    rayP50Us,rayP95Us,rayP99Us,meshP50Ms,meshP95Ms,meshP99Ms));
            w.write("  \"screenshots\":[");
            for(int i=0;i<screenshots.size();i++){if(i>0)w.write(",");w.write("\""+screenshots.get(i)+"\"");}
            w.write("],\n  \"scenarios\":[\n");
            for(int i=0;i<scenarios.size();i++){
                Scenario s=scenarios.get(i);
                w.write("    {\"name\":\""+esc(s.name)+"\",\"pass\":"+s.pass+",\"duration_ms\":"+s.ms+
                        ",\"attempted\":"+s.attempted+",\"accepted\":"+s.accepted+",\"rejected\":"+s.rejected+
                        ",\"bricks\":"+s.bricks+",\"field_bytes\":"+s.fieldBytes+",\"chunks\":"+s.chunks+
                        ",\"surface_vertices\":"+s.vertices+",\"surface_triangles\":"+s.triangles+
                        ",\"surface_bytes\":"+s.surfaceBytes+",\"field_sha256\":\""+esc(s.hash)+"\",\"detail\":\""+esc(s.detail)+"\"}"+
                        (i+1<scenarios.size()?",":"")+"\n");
            }
            w.write("  ],\n  \"failures\":[");
            for(int i=0;i<failures.size();i++){if(i>0)w.write(",");w.write("\""+esc(failures.get(i))+"\"");}
            w.write("]\n}\n");
        }
    }

    private static void writeText(File out,long duration)throws Exception{
        try(FileWriter w=new FileWriter(new File(out,"dump.txt"))){
            w.write("CABrush AVS 0.1 Verification Snapshot System\nRESULT: "+(failures.isEmpty()?"PASS":"FAIL")+"\nDuration: "+duration+" ms\n");
            w.write(String.format(Locale.US,"Ray p50/p95/p99: %.2f / %.2f / %.2f us\n",rayP50Us,rayP95Us,rayP99Us));
            w.write(String.format(Locale.US,"Full extract p50/p95/p99: %.2f / %.2f / %.2f ms\n",meshP50Ms,meshP95Ms,meshP99Ms));
            for(Scenario s:scenarios){
                w.write("\n["+s.name+"] "+(s.pass?"PASS":"FAIL")+" "+s.ms+"ms\n");
                if(s.attempted>0)w.write("ops="+s.attempted+" accepted="+s.accepted+" rejected="+s.rejected+"\n");
                if(s.bricks>0)w.write("bricks="+s.bricks+" fieldBytes="+s.fieldBytes+" chunks="+s.chunks+" verts="+s.vertices+" tris="+s.triangles+" surfaceBytes="+s.surfaceBytes+"\n");
                if(!s.hash.isEmpty())w.write("fieldSHA256="+s.hash+"\n");
                if(!s.detail.isEmpty())w.write("detail="+s.detail+"\n");
            }
            if(!failures.isEmpty()){w.write("\nFAILURES\n");for(String f:failures)w.write("- "+f+"\n");}
        }
    }

    private static String esc(String s){return s==null?"":s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");}
}
