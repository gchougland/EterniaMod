package com.hexvane.eterniamod.pathtool;

import com.hexvane.eterniamod.housing.PlotRect;
import java.util.*;

/** Smooth Catmull–Rom centerline and exact swept-width columns. Shared by all road scopes. */
public final class SplineGeometry {
    public static final int MAX_NODES=32,MAX_CELLS=4096,MAX_WIDTH=9;
    public record Node(double x,double y,double z){public Node{if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||Math.abs(x)>30_000_000||Math.abs(z)>30_000_000||y<0||y>319)throw new IllegalArgumentException("Node is outside the supported world");}}
    public record Column(int x,int z){}
    public record Hint(Column column,double y){}
    public record Cell(int x,int y,int z){public Column column(){return new Column(x,z);}}
    private SplineGeometry(){}
    /** Only short endpoint junctions may borrow another road's surface without taking custody of it. */
    public static boolean endpointConnection(Column cell,List<Node> nodes,int width){if(nodes.size()<2)return false;double limit=width/2.0+5;return List.of(nodes.getFirst(),nodes.getLast()).stream().anyMatch(n->Math.hypot(cell.x+.5-n.x,cell.z+.5-n.z)<=limit);}
    public static List<Node> sample(List<Node> nodes){
        if(nodes.size()<2||nodes.size()>MAX_NODES)throw new IllegalArgumentException("A road needs 2–32 nodes");
        var samples=new ArrayList<Node>();double total=0;
        for(int i=0;i<nodes.size()-1;i++){
            Node a=nodes.get(Math.max(0,i-1)),b=nodes.get(i),c=nodes.get(i+1),d=nodes.get(Math.min(nodes.size()-1,i+2));
            double length=Math.hypot(c.x-b.x,c.z-b.z);total+=length;
            if(length<.5||length>96||total>512)throw new IllegalArgumentException("Keep nodes 1–96 blocks apart and the road under 512 blocks");
            int steps=Math.max(8,(int)Math.ceil(length*8));
            for(int n=i==0?0:1;n<=steps;n++){double t=n/(double)steps;samples.add(new Node(curve(a.x,b.x,c.x,d.x,t),Math.max(0,Math.min(319,curve(a.y,b.y,c.y,d.y,t))),curve(a.z,b.z,c.z,d.z,t)));}
        }
        return List.copyOf(samples);
    }
    private static double curve(double a,double b,double c,double d,double t){return .5*((2*b)+(-a+c)*t+(2*a-5*b+4*c-d)*t*t+(-a+3*b-3*c+d)*t*t*t);}
    public static List<Hint> footprint(List<Node> nodes,int width){
        if(width<1||width>MAX_WIDTH)throw new IllegalArgumentException("Road width must be 1–9 blocks");
        var samples=sample(nodes);var distance=new HashMap<Column,Double>();var height=new HashMap<Column,Double>();double radius=width/2.0;
        for(var p:samples){int minX=(int)Math.floor(p.x-radius),maxX=(int)Math.floor(p.x+radius),minZ=(int)Math.floor(p.z-radius),maxZ=(int)Math.floor(p.z+radius);
            for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++){double dist=Math.hypot(x+.5-p.x,z+.5-p.z);if(dist>radius+.015)continue;var key=new Column(x,z);
                if(dist<distance.getOrDefault(key,Double.POSITIVE_INFINITY)){distance.put(key,dist);height.put(key,p.y);}
                if(height.size()>MAX_CELLS)throw new IllegalArgumentException("This road exceeds 4,096 ground cells; use shorter sections");
            }
        }
        return height.entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<Column,Double> e)->e.getKey().z).thenComparingInt(e->e.getKey().x)).map(e->new Hint(e.getKey(),e.getValue())).toList();
    }
    /** Merge only identical adjacent horizontal runs; curved interiors and holes stay unprotected. */
    public static List<PlotRect> rectangles(Collection<Column> columns){
        var rows=new TreeMap<Integer,SortedSet<Integer>>();for(var c:columns)rows.computeIfAbsent(c.z,k->new TreeSet<>()).add(c.x);
        var result=new ArrayList<PlotRect>();var active=new HashMap<String,PlotRect>();int priorZ=Integer.MIN_VALUE;
        for(var row:rows.entrySet()){
            var runs=new ArrayList<PlotRect>();Integer start=null,last=null;
            for(int x:row.getValue()){if(last!=null&&x!=last+1){runs.add(new PlotRect(start,row.getKey(),last-start+1,1));start=null;}if(start==null)start=x;last=x;}
            if(start!=null)runs.add(new PlotRect(start,row.getKey(),last-start+1,1));
            var next=new HashMap<String,PlotRect>();for(var run:runs){String key=run.x()+":"+run.width();var previous=priorZ==row.getKey()-1?active.remove(key):null;next.put(key,previous==null?run:new PlotRect(previous.x(),previous.z(),previous.width(),previous.depth()+1));}
            result.addAll(active.values());active=next;priorZ=row.getKey();
        }
        result.addAll(active.values());result.sort(Comparator.comparingInt(PlotRect::z).thenComparingInt(PlotRect::x));return List.copyOf(result);
    }
    public static int pick(List<Node> nodes,double ox,double oy,double oz,double dx,double dy,double dz){
        double length=Math.sqrt(dx*dx+dy*dy+dz*dz);if(length<1e-8)return -1;dx/=length;dy/=length;dz/=length;int selected=-1;double nearest=128;
        for(int i=0;i<nodes.size();i++){var n=nodes.get(i);double vx=n.x-ox,vy=n.y+.4-oy,vz=n.z-oz,t=vx*dx+vy*dy+vz*dz;if(t<=0||t>=nearest)continue;double distance=Math.pow(vx-dx*t,2)+Math.pow(vy-dy*t,2)+Math.pow(vz-dz*t,2);if(distance<=.55*.55){selected=i;nearest=t;}}
        return selected;
    }
}
