package com.hexvane.eterniamod.customization;

import com.hexvane.eterniamod.housing.PlotRect;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** A bounded four-neighbor route. Protected columns are excluded before entering the search queue. */
public final class PathPlanner {
    public record Cell(int x,int z){}
    /** The land a path may use: a plot rectangle, or the cells of a hex plot ({@code HubPlotRecord::containsColumn}). */
    @FunctionalInterface public interface Area { boolean contains(int x,int z); }
    public static List<Cell> route(PlotRect plot,Cell start,List<PlotRect> roads,Predicate<Cell> passable) {
        return route(plot::contains,start,roads,passable,5);
    }
    public static List<Cell> route(Area plot,Cell start,List<PlotRect> roads,Predicate<Cell> passable) {
        return route(plot,start,roads,passable,5);
    }
    private static List<Cell> route(Area plot,Cell start,List<PlotRect> roads,Predicate<Cell> passable,int reach) {return route(plot,start,roads,passable,reach,(a,b)->true);}
    /** Searched blocks before giving up; a seven hexagon plot and the road hexagon beside it fit many times over. */
    static final int SEARCH=400_000;
    private static List<Cell> route(Area plot,Cell start,List<PlotRect> roads,Predicate<Cell> passable,int reach,BiPredicate<Cell,Cell> step) {
        if(!plot.contains(start.x,start.z)||roads.isEmpty()||roads.stream().anyMatch(road->road.contains(start.x,start.z))||!passable.test(start))throw new IllegalArgumentException("Stand on clear ground outside your door, inside your plot.");
        ArrayDeque<Cell> queue=new ArrayDeque<>();Map<Cell,Cell> previous=new HashMap<>();queue.add(start);previous.put(start,null);
        Cell best=null;double bestGap=Double.POSITIVE_INFINITY;int bestDepth=Integer.MAX_VALUE;Map<Cell,Integer> depths=new HashMap<>();depths.put(start,0);
        while(!queue.isEmpty()&&previous.size()<=SEARCH) {
            Cell current=queue.remove();int depth=depths.get(current);double gap=roads.stream().mapToDouble(road->new PlotRect(current.x,current.z,1,1).gap(road)).min().orElse(Double.POSITIVE_INFINITY);
            if(depth>0&&(gap<bestGap||gap==bestGap&&depth<bestDepth)){best=current;bestGap=gap;bestDepth=depth;}
            for(int[] delta:new int[][]{{1,0},{0,1},{-1,0},{0,-1}}) {
                Cell next=new Cell(current.x+delta[0],current.z+delta[1]);
                if(!plot.contains(next.x,next.z)||previous.containsKey(next)||roads.stream().anyMatch(road->road.contains(next.x,next.z))||!passable.test(next)||!step.test(current,next))continue;
                previous.put(next,current);depths.put(next,depth+1);queue.add(next);
            }
        }
        if(best==null||bestGap>reach)throw new IllegalArgumentException("No clear route reaches the road. Try a narrower path or a different starting point.");
        List<Cell> path=new ArrayList<>();for(Cell cell=best;cell!=null;cell=previous.get(cell))path.add(cell);Collections.reverse(path);return List.copyOf(path);
    }
    public record Plan(List<Cell> centerline,List<Cell> blocks,int width){}
    /** Width and obstacle clearance are checked for every paved cell, including rounded corners. */
    public static Plan plan(PlotRect plot,Cell start,List<PlotRect> roads,int width,Predicate<Cell> ground,List<PlotRect> obstacles){
        return plan((Area)plot::contains,start,roads,width,ground,obstacles);
    }
    public static Plan plan(Area plot,Cell start,List<PlotRect> roads,int width,Predicate<Cell> ground,List<PlotRect> obstacles){
        return planOnGround(plot,start,roads,width,c->ground.test(c)?0:NO_GROUND,obstacles);
    }
    /** A cell the path cannot stand on. */
    public static final int NO_GROUND=Integer.MIN_VALUE;
    /**
     * Like {@link #plan(Area,Cell,List,int,Predicate,List)}, but each cell has its own ground height ({@link #NO_GROUND} when
     * unusable). The path follows the ground: neighbouring cells and the cells across its width differ by one block at most.
     */
    public static Plan planOnGround(Area plot,Cell start,List<PlotRect> roads,int width,ToIntFunction<Cell> height,List<PlotRect> obstacles){
        Map<Cell,Integer> heights=new HashMap<>();ToIntFunction<Cell> h=c->heights.computeIfAbsent(c,height::applyAsInt);
        Predicate<Cell> ground=c->h.applyAsInt(c)!=NO_GROUND;
        BiPredicate<Cell,Cell> step=(a,b)->Math.abs(h.applyAsInt(a)-h.applyAsInt(b))<=1;
        if(width<1||width>3)throw new IllegalArgumentException("Choose a path width from one to three blocks.");
        Map<Cell,Boolean> checked=new HashMap<>();
        Predicate<Cell> valid=cell->checked.computeIfAbsent(cell,c->plot.contains(c.x,c.z)&&roads.stream().noneMatch(r->r.contains(c.x,c.z))
            &&obstacles.stream().noneMatch(o->c.x>=o.x()-1&&c.x<o.x()+o.width()+1&&c.z>=o.z()-1&&c.z<o.z()+o.depth()+1)&&ground.test(c));
        Predicate<Cell> center=cell->stamp(cell,width).stream().allMatch(t->valid.test(t)&&step.test(cell,t));
        if(!center.test(start))throw new IllegalArgumentException("Stand on clear ground with room for this width and one empty block beside your house or decorations.");
        // Reserve another cell around obstacles when space allows, so a bend has room to round off.
        // Near the starting point retain the requested clearance, allowing a path to leave a doorway.
        Predicate<Cell> roomy=c->center.test(c)&&(Math.hypot(c.x-start.x,c.z-start.z)<4||stamp(c,width).stream().noneMatch(tile->obstacles.stream().anyMatch(o->tile.x>=o.x()-2&&tile.x<o.x()+o.width()+2&&tile.z>=o.z()-2&&tile.z<o.z()+o.depth()+2)));
        List<Cell> original;
        try{original=route(plot,start,roads,roomy,5+width/2,step);}catch(IllegalArgumentException tight){original=route(plot,start,roads,center,5+width/2,step);}
        List<Point> bends=new ArrayList<>();bends.add(point(original.getFirst()));
        for(int i=1;i<original.size()-1;i++){Cell a=original.get(i-1),b=original.get(i),c=original.get(i+1);if(b.x-a.x!=c.x-b.x||b.z-a.z!=c.z-b.z)bends.add(point(b));}
        bends.add(point(original.getLast()));List<Point> curve=new ArrayList<>();curve.add(bends.getFirst());
        for(int i=1;i<bends.size()-1;i++){
            Point a=bends.get(i-1),b=bends.get(i),c=bends.get(i+1);double ab=a.distance(b),bc=b.distance(c),radius=Math.min(3,Math.min(ab,bc)*.45);
            Point enter=b.toward(a,radius/ab),exit=b.toward(c,radius/bc);curve.add(enter);
            for(int k=1;k<=16;k++){double t=k/16.0,u=1-t;curve.add(new Point(u*u*enter.x+2*u*t*b.x+t*t*exit.x,u*u*enter.z+2*u*t*b.z+t*t*exit.z));}
        }
        curve.add(bends.getLast());List<Cell> rounded=raster(curve,center,step);List<Cell> line=rounded==null?original:rounded;
        Set<Cell> blocks=new LinkedHashSet<>();line.forEach(c->blocks.addAll(stamp(c,width)));
        if(blocks.stream().mapToDouble(c->roads.stream().mapToDouble(r->new PlotRect(c.x,c.z,1,1).gap(r)).min().orElse(99)).min().orElse(99)>5)
            throw new IllegalArgumentException("This path cannot reach close enough to the road.");
        return new Plan(List.copyOf(line),List.copyOf(blocks),width);
    }
    private record Point(double x,double z){double distance(Point p){return Math.hypot(x-p.x,z-p.z);}Point toward(Point p,double t){return new Point(x+(p.x-x)*t,z+(p.z-z)*t);}}
    private static Point point(Cell c){return new Point(c.x,c.z);}
    private static List<Cell> stamp(Cell c,int width){List<Cell> cells=new ArrayList<>();int low=-(width-1)/2;for(int x=low;x<low+width;x++)for(int z=low;z<low+width;z++)cells.add(new Cell(c.x+x,c.z+z));return cells;}
    private static List<Cell> raster(List<Point> points,Predicate<Cell> valid,BiPredicate<Cell,Cell> step){
        List<Cell> cells=new ArrayList<>();Cell last=null;
        for(int i=1;i<points.size();i++){Point a=points.get(i-1),b=points.get(i);int steps=Math.max(1,(int)Math.ceil(a.distance(b)*5));
            for(int n=0;n<=steps;n++){Point p=a.toward(b,(double)n/steps);Cell c=new Cell((int)Math.round(p.x),(int)Math.round(p.z));if(!valid.test(c))return null;
                if(last!=null&&last.x!=c.x&&last.z!=c.z){Cell join=new Cell(c.x,last.z);if(!valid.test(join)||!step.test(last,join)||!step.test(join,c))join=new Cell(last.x,c.z);if(!valid.test(join)||!step.test(last,join)||!step.test(join,c))return null;cells.add(join);}
                else if(last!=null&&!c.equals(last)&&!step.test(last,c))return null;
                if(!c.equals(last))cells.add(c);last=c;
            }
        }return cells;
    }
    private PathPlanner(){}
}
