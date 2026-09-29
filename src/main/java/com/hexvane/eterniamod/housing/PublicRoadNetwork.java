package com.hexvane.eterniamod.housing;

import java.util.*;

/** Road surfaces determine connectivity; centerlines determine the five-block claim distance. */
public final class PublicRoadNetwork {
    public record Point(double x,double z) {}
    public record Route(List<PlotRect> surface,List<Point> centerline) {
        public Route {surface=List.copyOf(surface);centerline=List.copyOf(centerline);}
    }
    private final List<Route> routes;
    public PublicRoadNetwork(List<Route> routes){this.routes=List.copyOf(routes);}
    public static PublicRoadNetwork rectangles(List<PlotRect> roads){return new PublicRoadNetwork(roads.stream().map(PublicRoadNetwork::rectangle).toList());}
    public static Route rectangle(PlotRect r){
        double x=r.x()+r.width()/2.0,z=r.z()+r.depth()/2.0;
        return new Route(List.of(r),r.width()>r.depth()?List.of(new Point(r.x(),z),new Point(r.endX(),z)):List.of(new Point(x,r.z()),new Point(x,r.endZ())));
    }
    public boolean near(PlotRect plot){return routes.stream().anyMatch(r->distance(plot,r.centerline())<=5);}
    public boolean anchored(PlotRect plot,List<PlotRect> portals){
        var reached=new HashSet<Integer>();var queue=new ArrayDeque<Integer>();
        for(int i=0;i<routes.size();i++)if(routes.get(i).surface().stream().anyMatch(s->portals.stream().anyMatch(p->p.gap(s)<=5))){reached.add(i);queue.add(i);}
        while(!queue.isEmpty()){
            int i=queue.remove();var route=routes.get(i);if(distance(plot,route.centerline())<=5)return true;
            for(int j=0;j<routes.size();j++)if(!reached.contains(j)){var other=routes.get(j);if(route.surface().stream().anyMatch(a->other.surface().stream().anyMatch(b->a.gap(b)==0))){reached.add(j);queue.add(j);}}
        }
        return false;
    }
    public static double distance(PlotRect r,List<Point> line){
        double best=Double.POSITIVE_INFINITY;
        for(int i=1;i<line.size();i++){
            var a=line.get(i-1);var b=line.get(i);
            // Distance from a segment to a rectangle: intersect, endpoint-to-box, or corner-to-segment.
            double lo=0,hi=1;double[] start={a.x(),a.z()},delta={b.x()-a.x(),b.z()-a.z()},min={r.x(),r.z()},max={r.endX(),r.endZ()};boolean hit=true;
            for(int k=0;k<2;k++){if(Math.abs(delta[k])<1e-12){if(start[k]<min[k]||start[k]>max[k])hit=false;}else{double t1=(min[k]-start[k])/delta[k],t2=(max[k]-start[k])/delta[k];lo=Math.max(lo,Math.min(t1,t2));hi=Math.min(hi,Math.max(t1,t2));}}
            if(hit&&lo<=hi)return 0;
            for(var p:List.of(a,b))best=Math.min(best,Math.hypot(Math.max(0,Math.max(r.x()-p.x(),p.x()-r.endX())),Math.max(0,Math.max(r.z()-p.z(),p.z()-r.endZ()))));
            double length=delta[0]*delta[0]+delta[1]*delta[1];
            for(double x:minMax(r.x(),r.endX()))for(double z:minMax(r.z(),r.endZ())){double t=length==0?0:Math.max(0,Math.min(1,((x-a.x())*delta[0]+(z-a.z())*delta[1])/length));best=Math.min(best,Math.hypot(x-a.x()-t*delta[0],z-a.z()-t*delta[1]));}
        }
        return best;
    }
    private static double[] minMax(double a,double b){return new double[]{a,b};}
}
