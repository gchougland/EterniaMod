package com.hexvane.eterniamod.town;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Plans the ground for the town so it follows the terrain (docs/implementation/town-placement.md). Pure: it takes the
 * surveyed natural ground height of each column and the town plan, and returns the planned surface height of every column
 * the town touches, the base of every lot, tower and gate, and each road's profile.
 *
 * Heights are the y of the top solid block of a column. A prefab's ground (its y -1) goes on that block, so a piece is
 * pasted one block above its base.
 */
public final class TownTerrainPlanner {
    public static final byte NONE=0,PAD=1,STREET=2,LOT=3,FEATURE=4,GARDEN=5,WALL=6,TOWER=7,GATE=8,OUTSIDE=9,ROAD=10,SHOULDER=11;
    /** Streets, walls and roads rise at most half a block per block (one step every two); gardens and banks one per block. */
    public static final double STREET_SLOPE=.5,GROUND_SLOPE=1;
    /** The town's land reaches this far out on every side; the ground beyond is left alone. */
    public static final int REACH=128,BANK=6;
    public static final int UNKNOWN=Integer.MIN_VALUE;

    /** Natural ground height at a column, relative to the anchor; UNKNOWN where it was not surveyed or has no ground. */
    @FunctionalInterface public interface Ground {int natural(int x,int z);}

    /** A rectangle of columns, inclusive, relative to the anchor. */
    public record Area(int minX,int minZ,int maxX,int maxZ){
        public boolean contains(int x,int z){return x>=minX&&x<=maxX&&z>=minZ&&z<=maxZ;}
    }
    public static final class Grid {
        public final int minX,minZ,w,h;
        Grid(int minX,int minZ,int maxX,int maxZ){this.minX=minX;this.minZ=minZ;w=maxX-minX+1;h=maxZ-minZ+1;}
        public boolean in(int x,int z){return x>=minX&&z>=minZ&&x<minX+w&&z<minZ+h;}
        public int index(int x,int z){return (z-minZ)*w+(x-minX);}
        public int x(int i){return minX+i%w;}
        public int z(int i){return minZ+i/w;}
        public int size(){return w*h;}
    }
    /** A road's planned line: its cells' surface heights and its nodes with the y the Road Designer scans from. */
    public record RoadProfile(TownPlan.Road road,List<int[]> nodes,int start,int end){}
    public static final class Result {
        public final Grid grid;public final byte[] kind;public final int[] natural,target;
        public int padSurface;public int[] lotBase,featureBase,towerBase,gateBase,treeBase;
        public final List<RoadProfile> roads=new ArrayList<>();
        final int[] ring;final TownPlan plan;
        Result(TownPlan plan,Grid grid){this.plan=plan;this.grid=grid;kind=new byte[grid.size()];natural=new int[grid.size()];target=new int[grid.size()];Arrays.fill(target,UNKNOWN);ring=new int[ringLength(plan)];}
        public int kind(int x,int z){return grid.in(x,z)?kind[grid.index(x,z)]:NONE;}
        public int target(int x,int z){return grid.in(x,z)?target[grid.index(x,z)]:UNKNOWN;}
        public int natural(int x,int z){return grid.in(x,z)?natural[grid.index(x,z)]:UNKNOWN;}
        /** The wall's base (ground block y) under a wall, tower or gate column. */
        public int wallBase(int x,int z){
            var w=plan.walls;
            for(int i=0;i<w.towers.size();i++){var t=w.towers.get(i);if(Math.hypot(x-t.x,z-t.z)<=t.r+.5)return towerBase[i];}
            return ring[ringIndex(plan,x,z)];
        }
        /** The pad's walking height: the town square's anchor y. */
        public int anchorY(){return padSurface+1;}
    }

    /** The columns to survey: the town's land and a corridor round every road. */
    public static List<Area> survey(TownPlan plan){
        var list=new ArrayList<Area>();list.add(new Area(-REACH,-REACH,REACH,REACH));
        for(var road:plan.roads){int pad=road.width/2+BANK+2;int minX=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
            for(var n:road.nodes){minX=Math.min(minX,n[0]);maxX=Math.max(maxX,n[0]);minZ=Math.min(minZ,n[1]);maxZ=Math.max(maxZ,n[1]);}
            list.add(new Area(minX-pad,minZ-pad,maxX+pad,maxZ+pad));}
        return list;
    }

    public static Result plan(TownPlan plan,Ground ground){
        var areas=survey(plan);
        int minX=areas.stream().mapToInt(Area::minX).min().orElseThrow(),minZ=areas.stream().mapToInt(Area::minZ).min().orElseThrow();
        int maxX=areas.stream().mapToInt(Area::maxX).max().orElseThrow(),maxZ=areas.stream().mapToInt(Area::maxZ).max().orElseThrow();
        var g=new Grid(minX,minZ,maxX,maxZ);var r=new Result(plan,g);
        for(int i=0;i<g.size();i++){int x=g.x(i),z=g.z(i);r.natural[i]=areas.stream().anyMatch(a->a.contains(x,z))?ground.natural(x,z):UNKNOWN;}
        double[] smooth=smooth(g,r.natural,4);smooth=smoothDoubles(g,smooth,4);
        classify(plan,r);

        // The pad: one level at the median of the smoothed ground under it.
        var under=new ArrayList<Double>();for(int i=0;i<g.size();i++)if(r.kind[i]==PAD&&!Double.isNaN(smooth[i]))under.add(smooth[i]);
        under.sort(null);r.padSurface=under.isEmpty()?0:(int)Math.round(under.get(under.size()/2));

        // Streets: joined to the pad, at most half a block of rise per block.
        double[] fixed=nan(g.size());boolean[] active=new boolean[g.size()];
        for(int i=0;i<g.size();i++){if(r.kind[i]==PAD){active[i]=true;fixed[i]=r.padSurface;}else if(r.kind[i]==STREET)active[i]=true;}
        double[] street=solve(g,smooth,active,fixed,STREET_SLOPE,r.padSurface);

        // Lots sit at the street in front of their doors; features and trees on the gardens round them.
        r.lotBase=new int[plan.lots.size()];
        for(int n=0;n<plan.lots.size();n++){var lot=plan.lots.get(n);int[] f=lot.front;
            double base=f!=null&&g.in(f[0],f[1])&&!Double.isNaN(street[g.index(f[0],f[1])])?street[g.index(f[0],f[1])]:at(g,smooth,lot.at[0],lot.at[1],r.padSurface);
            r.lotBase[n]=(int)Math.round(base);fill(g,r.target,lot.min,lot.max,r.lotBase[n]);}
        fixed=nan(g.size());active=new boolean[g.size()];
        for(int i=0;i<g.size();i++){int k=r.kind[i];
            if(k==PAD){active[i]=true;fixed[i]=r.padSurface;}
            else if(k==STREET){active[i]=true;fixed[i]=Math.round(street[i]);}
            else if(k==LOT){active[i]=true;fixed[i]=r.target[i];}
            else if(k==GARDEN||k==FEATURE)active[i]=true;}
        double[] garden=solve(g,smooth,active,fixed,GROUND_SLOPE,r.padSurface);
        r.featureBase=new int[plan.features.size()];
        for(int n=0;n<plan.features.size();n++){var f=plan.features.get(n);r.featureBase[n]=(int)Math.round(at(g,garden,f.at[0],f.at[1],r.padSurface));fill(g,r.target,f.min,f.max,r.featureBase[n]);}
        for(int i=0;i<g.size();i++){int k=r.kind[i];
            if(k==PAD)r.target[i]=r.padSurface;else if(k==STREET)r.target[i]=(int)Math.round(street[i]);else if(k==GARDEN)r.target[i]=(int)Math.round(garden[i]);}
        r.treeBase=new int[plan.trees.size()];for(int n=0;n<plan.trees.size();n++){var t=plan.trees.get(n);r.treeBase[n]=r.target(t[0],t[1]);}

        walls(plan,r,smooth,street);
        roads(plan,r,smooth);
        outside(plan,r,smooth);
        return r;
    }

    // ---- kinds ---------------------------------------------------------------------------------------------------------
    static int cheb(int x,int z){return Math.max(Math.abs(x),Math.abs(z));}
    private static void classify(TownPlan plan,Result r){
        var g=r.grid;var w=plan.walls;int outer=w.inner+w.thick;
        for(int i=0;i<g.size();i++){int x=g.x(i),z=g.z(i);if(Math.abs(x)>REACH||Math.abs(z)>REACH)continue;int c=cheb(x,z);
            r.kind[i]=c<=plan.pad.radius?PAD:c<w.inner?GARDEN:c<outer?WALL:OUTSIDE;}
        for(var s:plan.streets)if(g.in(s[0],s[1])){int i=g.index(s[0],s[1]);if(r.kind[i]==GARDEN)r.kind[i]=STREET;}
        for(var lot:plan.lots)mark(g,r.kind,lot.min,lot.max,LOT);
        for(var f:plan.features)mark(g,r.kind,f.min,f.max,FEATURE);
        for(var gate:w.gates){var s=w.sides.get(gate.side);
            for(int a=gate.at-gate.half;a<=gate.at+gate.half;a++)for(int q=w.inner-8;q<=outer+4;q++){int x=s.ax*a+s.qx*q,z=s.az*a+s.qz*q;
                if(!g.in(x,z))continue;int i=g.index(x,z);if(r.kind[i]==WALL||r.kind[i]==OUTSIDE||r.kind[i]==GARDEN)r.kind[i]=GATE;}}
        for(var t:w.towers)for(int x=t.x-t.r;x<=t.x+t.r;x++)for(int z=t.z-t.r;z<=t.z+t.r;z++)
            if(g.in(x,z)&&Math.hypot(x-t.x,z-t.z)<=t.r+.5){int i=g.index(x,z);if(r.kind[i]!=GATE&&r.kind[i]!=STREET)r.kind[i]=TOWER;}
        for(var road:plan.roads)for(var cell:swath(road))if(g.in(cell[0],cell[1])){int i=g.index(cell[0],cell[1]);if(r.kind[i]==OUTSIDE||r.kind[i]==NONE)r.kind[i]=ROAD;}
    }
    private static void mark(Grid g,byte[] kind,int[] min,int[] max,byte value){for(int x=min[0];x<=max[0];x++)for(int z=min[1];z<=max[1];z++)if(g.in(x,z))kind[g.index(x,z)]=value;}
    private static void fill(Grid g,int[] target,int[] min,int[] max,int value){for(int x=min[0];x<=max[0];x++)for(int z=min[1];z<=max[1];z++)if(g.in(x,z))target[g.index(x,z)]=value;}

    /** A road's cells: straight runs between its nodes (roads run along x or z), `width` across, centred. */
    public static List<int[]> swath(TownPlan.Road road){
        var cells=new ArrayList<int[]>();int lo=-(road.width/2),hi=road.width-1-road.width/2;
        for(var c:centre(road))for(int o=lo;o<=hi;o++)cells.add(c[2]==0?new int[]{c[0],c[1]+o}:new int[]{c[0]+o,c[1]});
        return cells;
    }
    /** The road's centre line, one cell per block, as [x, z, runsAlongZ]. */
    static List<int[]> centre(TownPlan.Road road){
        var cells=new ArrayList<int[]>();
        for(int n=0;n+1<road.nodes.size();n++){int[] a=road.nodes.get(n),b=road.nodes.get(n+1);
            if(a[0]!=b[0]&&a[1]!=b[1])throw new IllegalArgumentException("Road "+road.id+" bends; town roads run straight along x or z");
            int dx=Integer.signum(b[0]-a[0]),dz=Integer.signum(b[1]-a[1]),steps=Math.max(Math.abs(b[0]-a[0]),Math.abs(b[1]-a[1]));
            for(int s=n==0?0:1;s<=steps;s++)cells.add(new int[]{a[0]+dx*s,a[1]+dz*s,dz!=0?1:0});}
        return cells;
    }

    // ---- walls ---------------------------------------------------------------------------------------------------------
    /** The wall's line: the ring of columns one in from its inner face, walked clockwise from the north west corner. */
    static int ringLength(TownPlan plan){return 8*(plan.walls.inner+1);}
    static int ringIndex(TownPlan plan,int x,int z){
        int k=plan.walls.inner+1;int px,pz;
        if(Math.abs(x)>=Math.abs(z)){px=Integer.signum(x)*k;pz=Math.max(-k,Math.min(k,z));}else{pz=Integer.signum(z)*k;px=Math.max(-k,Math.min(k,x));}
        if(pz==-k&&px<k)return px+k;                    // north side, west to east
        if(px==k&&pz<k)return 2*k+(pz+k);                // east side, north to south
        if(pz==k&&px>-k)return 4*k+(k-px);               // south side, east to west
        return 6*k+(k-pz);                               // west side, south to north
    }
    static int[] ringCell(TownPlan plan,int i){
        int k=plan.walls.inner+1;i=Math.floorMod(i,8*k);
        if(i<2*k)return new int[]{i-k,-k};if(i<4*k)return new int[]{k,i-2*k-k};if(i<6*k)return new int[]{k-(i-4*k),k};return new int[]{-k,k-(i-6*k)};
    }
    private static void walls(TownPlan plan,Result r,double[] smooth,double[] street){
        var w=plan.walls;var g=r.grid;int n=r.ring.length;
        double[] desired=new double[n],fixed=nan(n);
        for(int i=0;i<n;i++){int[] c=ringCell(plan,i);desired[i]=at(g,smooth,c[0],c[1],r.padSurface);}
        r.gateBase=new int[w.gates.size()];
        for(int k=0;k<w.gates.size();k++){var gate=w.gates.get(k);var s=w.sides.get(gate.side);
            int ix=s.ax*gate.at+s.qx*(w.inner-1),iz=s.az*gate.at+s.qz*(w.inner-1);
            double v=g.in(ix,iz)&&!Double.isNaN(street[g.index(ix,iz)])?street[g.index(ix,iz)]:at(g,smooth,ix,iz,r.padSurface);
            r.gateBase[k]=(int)Math.round(v);
            for(int a=gate.at-gate.half;a<=gate.at+gate.half;a++)fixed[ringIndex(plan,s.ax*a+s.qx*(w.inner+1),s.az*a+s.qz*(w.inner+1))]=r.gateBase[k];}
        double[] line=solveRing(desired,fixed,STREET_SLOPE);
        r.towerBase=new int[w.towers.size()];
        for(int k=0;k<w.towers.size();k++){var t=w.towers.get(k);r.towerBase[k]=(int)Math.round(line[ringIndex(plan,t.x,t.z)]);
            for(int i=0;i<n;i++){int[] c=ringCell(plan,i);if(Math.max(Math.abs(c[0]-t.x),Math.abs(c[1]-t.z))<=t.r+1)fixed[i]=r.towerBase[k];}}
        line=solveRing(desired,fixed,STREET_SLOPE);
        for(int i=0;i<n;i++)r.ring[i]=(int)Math.round(line[i]);
        for(int i=0;i<g.size();i++){int k=r.kind[i];if(k==WALL||k==TOWER)r.target[i]=r.wallBase(g.x(i),g.z(i));}
        for(int k=0;k<w.gates.size();k++){var gate=w.gates.get(k);var s=w.sides.get(gate.side);int outer=w.inner+w.thick;
            for(int a=gate.at-gate.half;a<=gate.at+gate.half;a++)for(int q=w.inner-8;q<=outer+4;q++){int x=s.ax*a+s.qx*q,z=s.az*a+s.qz*q;if(g.in(x,z)&&r.kind[g.index(x,z)]==GATE)r.target[g.index(x,z)]=r.gateBase[k];}}
    }
    /** A cyclic line's largest profile that rises at most `slope` per step, keeps the fixed values and is no lower than they allow. */
    static double[] solveRing(double[] desired,double[] fixed,double slope){
        int n=desired.length;double[] lower=new double[n];Arrays.fill(lower,Double.NEGATIVE_INFINITY);
        for(int i=0;i<n;i++)if(!Double.isNaN(fixed[i]))lower[i]=fixed[i];
        for(int round=0;round<3;round++){for(int i=0;i<2*n;i++){int a=i%n,b=(i+1)%n;lower[b]=Math.max(lower[b],lower[a]-slope);}for(int i=2*n;i>0;i--){int a=i%n,b=(i-1+n)%n;lower[b]=Math.max(lower[b],lower[a]-slope);}}
        double[] v=new double[n];for(int i=0;i<n;i++)v[i]=!Double.isNaN(fixed[i])?fixed[i]:Math.max(Double.isNaN(desired[i])?Double.POSITIVE_INFINITY:desired[i],lower[i]);
        for(int round=0;round<3;round++){
            for(int i=0;i<2*n;i++){int a=i%n,b=(i+1)%n;if(Double.isNaN(fixed[b]))v[b]=Math.min(v[b],v[a]+slope);}
            for(int i=2*n;i>0;i--){int a=i%n,b=(i-1+n)%n;if(Double.isNaN(fixed[b]))v[b]=Math.min(v[b],v[a]+slope);}}
        return v;
    }

    // ---- roads ---------------------------------------------------------------------------------------------------------
    private static void roads(TownPlan plan,Result r,double[] smooth){
        var g=r.grid;var w=plan.walls;
        for(var road:plan.roads){
            var line=centre(road);int len=line.size();double[] desired=new double[len];int lo=-(road.width/2),hi=road.width-1-road.width/2;
            for(int t=0;t<len;t++){int[] c=line.get(t);double sum=0;int count=0;for(int o=lo;o<=hi;o++){int x=c[2]==0?c[0]:c[0]+o,z=c[2]==0?c[1]+o:c[1];double v=g.in(x,z)?smooth[g.index(x,z)]:Double.NaN;if(!Double.isNaN(v)){sum+=v;count++;}}desired[t]=count==0?Double.NaN:sum/count;}
            // Where it starts: the gate it leaves from, or the road it branches off.
            int[] first=road.nodes.get(0);double start=Double.NaN;
            for(int k=0;k<w.gates.size();k++){var o=w.gates.get(k).origin;if(Math.max(Math.abs(o[0]-first[0]),Math.abs(o[1]-first[1]))<=w.gates.get(k).half+8)start=r.gateBase[k];}
            if(Double.isNaN(start)){int best=road.width/2+4;
                for(var done:r.roads)for(var cell:swath(done.road())){int d=Math.max(Math.abs(cell[0]-first[0]),Math.abs(cell[1]-first[1]));
                    if(d<best&&g.in(cell[0],cell[1])&&r.target(cell[0],cell[1])!=UNKNOWN){best=d;start=r.target(cell[0],cell[1]);}}}
            if(Double.isNaN(start))start=Double.isNaN(desired[0])?r.padSurface:Math.round(desired[0]);
            double[] v=new double[len];v[0]=start;for(int t=1;t<len;t++)v[t]=Math.max(Double.isNaN(desired[t])?Double.POSITIVE_INFINITY:desired[t],start-STREET_SLOPE*t);
            for(int t=1;t<len;t++)v[t]=Math.min(v[t],v[t-1]+STREET_SLOPE);for(int t=len-2;t>=1;t--)v[t]=Math.min(v[t],v[t+1]+STREET_SLOPE);
            for(int t=1;t<len;t++)v[t]=Math.max(v[t],v[t-1]-STREET_SLOPE);
            int[] y=new int[len];for(int t=0;t<len;t++)y[t]=(int)Math.round(v[t]);
            for(int t=0;t<len;t++){int[] c=line.get(t);for(int o=lo;o<=hi;o++){int x=c[2]==0?c[0]:c[0]+o,z=c[2]==0?c[1]+o:c[1];if(g.in(x,z)&&r.kind[g.index(x,z)]==ROAD)r.target[g.index(x,z)]=y[t];}}
            var nodes=new ArrayList<int[]>();int t=0;for(int n=0;n<road.nodes.size();n++){int[] node=road.nodes.get(n);
                while(t<len-1&&(line.get(t)[0]!=node[0]||line.get(t)[1]!=node[1]))t++;nodes.add(new int[]{node[0],y[t]+1,node[1]});}
            r.roads.add(new RoadProfile(road,nodes,y[0],y[len-1]));
        }
        // Banks: the land beside a road eases back to the ground, one block per block.
        int[] nearest=new int[g.size()];Arrays.fill(nearest,-1);int[] dist=new int[g.size()];var queue=new java.util.ArrayDeque<Integer>();
        for(int i=0;i<g.size();i++)if(r.kind[i]==ROAD&&r.target[i]!=UNKNOWN){nearest[i]=i;queue.add(i);}
        while(!queue.isEmpty()){int i=queue.poll();if(dist[i]>=BANK)continue;int x=g.x(i),z=g.z(i);
            for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}}){int nx=x+d[0],nz=z+d[1];if(!g.in(nx,nz))continue;int j=g.index(nx,nz);
                if(nearest[j]>=0||!(r.kind[j]==OUTSIDE||r.kind[j]==NONE))continue;nearest[j]=nearest[i];dist[j]=dist[i]+1;r.kind[j]=SHOULDER;queue.add(j);}}
        for(int i=0;i<g.size();i++)if(r.kind[i]==SHOULDER){double s=at(g,smooth,g.x(i),g.z(i),r.target[nearest[i]]);int road=r.target[nearest[i]];
            r.target[i]=(int)Math.round(Math.max(road-dist[i]*GROUND_SLOPE,Math.min(road+dist[i]*GROUND_SLOPE,s)));}
    }

    // ---- outside the walls ---------------------------------------------------------------------------------------------
    private static void outside(TownPlan plan,Result r,double[] smooth){
        var g=r.grid;double[] fixed=nan(g.size());boolean[] active=new boolean[g.size()];
        for(int i=0;i<g.size();i++){int k=r.kind[i],x=g.x(i),z=g.z(i);
            if(k==OUTSIDE){active[i]=true;if(cheb(x,z)>=REACH-1&&r.natural[i]!=UNKNOWN)fixed[i]=r.natural[i];}
            else if(k==WALL||k==TOWER||k==GATE||k==ROAD||k==SHOULDER){if(r.target[i]!=UNKNOWN&&touches(r,x,z,OUTSIDE)){active[i]=true;fixed[i]=r.target[i];}}}
        double[] land=solve(g,smooth,active,fixed,GROUND_SLOPE,r.padSurface);
        for(int i=0;i<g.size();i++)if(r.kind[i]==OUTSIDE&&!Double.isNaN(land[i])&&!Double.isInfinite(land[i]))r.target[i]=(int)Math.round(land[i]);
    }
    private static boolean touches(Result r,int x,int z,byte kind){return r.kind(x+1,z)==kind||r.kind(x-1,z)==kind||r.kind(x,z+1)==kind||r.kind(x,z-1)==kind;}

    // ---- solving -------------------------------------------------------------------------------------------------------
    static double[] nan(int n){double[] a=new double[n];Arrays.fill(a,Double.NaN);return a;}
    static double at(Grid g,double[] values,int x,int z,double fallback){if(!g.in(x,z))return fallback;double v=values[g.index(x,z)];return Double.isNaN(v)||Double.isInfinite(v)?fallback:v;}

    /**
     * Over the active cells (4-neighbour steps), the highest heights no higher than `desired` that rise at most `slope`
     * per step, keep every fixed cell's value, and are no lower than the fixed cells allow: cut where the ground is too
     * high, fill where a fixed neighbour needs it. Inactive cells come back NaN; cells with no ground and nothing fixed
     * nearby come back as `fallback`.
     */
    static double[] solve(Grid g,double[] desired,boolean[] active,double[] fixed,double slope,double fallback){
        int n=g.size();double[] lower=new double[n];Arrays.fill(lower,Double.NEGATIVE_INFINITY);
        var queue=new PriorityQueue<double[]>((a,b)->Double.compare(b[0],a[0]));
        for(int i=0;i<n;i++)if(active[i]&&!Double.isNaN(fixed[i])){lower[i]=fixed[i];queue.add(new double[]{lower[i],i});}
        while(!queue.isEmpty()){var e=queue.poll();int i=(int)e[1];if(e[0]<lower[i])continue;
            for(int j:neighbours(g,i))if(active[j]&&lower[i]-slope>lower[j]){lower[j]=lower[i]-slope;queue.add(new double[]{lower[j],j});}}
        double[] v=new double[n];
        for(int i=0;i<n;i++){if(!active[i]){v[i]=Double.NaN;continue;}
            v[i]=!Double.isNaN(fixed[i])?fixed[i]:Math.max(Double.isNaN(desired[i])?Double.POSITIVE_INFINITY:desired[i],lower[i]);}
        var up=new PriorityQueue<double[]>((a,b)->Double.compare(a[0],b[0]));
        for(int i=0;i<n;i++)if(active[i]&&!Double.isInfinite(v[i]))up.add(new double[]{v[i],i});
        while(!up.isEmpty()){var e=up.poll();int i=(int)e[1];if(e[0]>v[i])continue;
            for(int j:neighbours(g,i))if(active[j]&&Double.isNaN(fixed[j])&&v[i]+slope<v[j]){v[j]=v[i]+slope;up.add(new double[]{v[j],j});}}
        for(int i=0;i<n;i++)if(active[i]&&Double.isInfinite(v[i]))v[i]=fallback;
        return v;
    }
    private static int[] neighbours(Grid g,int i){int x=g.x(i),z=g.z(i);int[] out=new int[4];int k=0;
        if(x>g.minX)out[k++]=i-1;if(x<g.minX+g.w-1)out[k++]=i+1;if(z>g.minZ)out[k++]=i-g.w;if(z<g.minZ+g.h-1)out[k++]=i+g.w;return Arrays.copyOf(out,k);}
    /** A box blur of the known heights (radius r); unknown columns stay unknown. */
    static double[] smooth(Grid g,int[] heights,int r){double[] d=new double[heights.length];for(int i=0;i<d.length;i++)d[i]=heights[i]==UNKNOWN?Double.NaN:heights[i];return smoothDoubles(g,d,r);}
    static double[] smoothDoubles(Grid g,double[] d,int r){
        int w=g.w,h=g.h;double[] sum=new double[(w+1)*(h+1)];int[] count=new int[(w+1)*(h+1)];
        for(int z=0;z<h;z++)for(int x=0;x<w;x++){double v=d[z*w+x];boolean known=!Double.isNaN(v);int k=(z+1)*(w+1)+x+1;
            sum[k]=(known?v:0)+sum[k-1]+sum[k-(w+1)]-sum[k-(w+1)-1];count[k]=(known?1:0)+count[k-1]+count[k-(w+1)]-count[k-(w+1)-1];}
        double[] out=new double[d.length];
        for(int z=0;z<h;z++)for(int x=0;x<w;x++){if(Double.isNaN(d[z*w+x])){out[z*w+x]=Double.NaN;continue;}
            int x0=Math.max(0,x-r),x1=Math.min(w,x+r+1),z0=Math.max(0,z-r),z1=Math.min(h,z+r+1);
            double s=sum[z1*(w+1)+x1]-sum[z0*(w+1)+x1]-sum[z1*(w+1)+x0]+sum[z0*(w+1)+x0];int c=count[z1*(w+1)+x1]-count[z0*(w+1)+x1]-count[z1*(w+1)+x0]+count[z0*(w+1)+x0];
            out[z*w+x]=c==0?Double.NaN:s/c;}
        return out;
    }
}
