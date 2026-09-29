package com.hexvane.eterniamod.customization;

import com.hexvane.eterniamod.housing.PlotRect;
import java.util.*;
import java.util.function.Predicate;

/** A bounded four-neighbor route. Protected columns are excluded before entering the search queue. */
public final class PathPlanner {
    public record Cell(int x,int z){}
    public static List<Cell> route(PlotRect plot,Cell start,List<PlotRect> roads,Predicate<Cell> passable) {
        if(!plot.contains(start.x,start.z)||roads.isEmpty()||roads.stream().anyMatch(road->road.contains(start.x,start.z))||!passable.test(start))throw new IllegalArgumentException("Stand on clear ground outside your door, inside your plot.");
        ArrayDeque<Cell> queue=new ArrayDeque<>();Map<Cell,Cell> previous=new HashMap<>();queue.add(start);previous.put(start,null);
        Cell best=null;double bestGap=Double.POSITIVE_INFINITY;int bestDepth=Integer.MAX_VALUE;Map<Cell,Integer> depths=new HashMap<>();depths.put(start,0);
        while(!queue.isEmpty()&&previous.size()<=16_384) {
            Cell current=queue.remove();int depth=depths.get(current);double gap=roads.stream().mapToDouble(road->new PlotRect(current.x,current.z,1,1).gap(road)).min().orElse(Double.POSITIVE_INFINITY);
            if(depth>0&&(gap<bestGap||gap==bestGap&&depth<bestDepth)){best=current;bestGap=gap;bestDepth=depth;}
            if(depth>=127)continue;
            for(int[] delta:new int[][]{{1,0},{0,1},{-1,0},{0,-1}}) {
                Cell next=new Cell(current.x+delta[0],current.z+delta[1]);
                if(!plot.contains(next.x,next.z)||previous.containsKey(next)||roads.stream().anyMatch(road->road.contains(next.x,next.z))||!passable.test(next))continue;
                previous.put(next,current);depths.put(next,depth+1);queue.add(next);
            }
        }
        if(best==null||bestGap>5)throw new IllegalArgumentException("No clear route reaches within five blocks of a public road inside this plot.");
        List<Cell> path=new ArrayList<>();for(Cell cell=best;cell!=null;cell=previous.get(cell))path.add(cell);Collections.reverse(path);return List.copyOf(path);
    }
    private PathPlanner(){}
}
