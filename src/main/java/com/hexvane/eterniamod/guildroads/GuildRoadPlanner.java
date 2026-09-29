package com.hexvane.eterniamod.guildroads;

import com.hexvane.eterniamod.housing.PlotRect;
import java.util.*;

/** Straight routes have an exact rectangular edit mask and bounded gaps between member plots. */
public final class GuildRoadPlanner {
    private GuildRoadPlanner(){}
    public record Cell(int x,int z){}
    public static List<Cell> route(Cell start,Cell end,List<PlotRect> memberZone,List<PlotRect> forbidden,List<PlotRect> structures){
        if(start.x()!=end.x()&&start.z()!=end.z())throw new IllegalArgumentException("Road endpoints must share an X or Z coordinate");
        long length=Math.abs((long)end.x()-start.x())+Math.abs((long)end.z()-start.z())+1;
        if(length<2||length>128)throw new IllegalArgumentException("Choose a straight segment between 2 and 128 blocks long");
        if(memberZone.stream().noneMatch(r->r.contains(start.x(),start.z()))||memberZone.stream().noneMatch(r->r.contains(end.x(),end.z())))throw new IllegalArgumentException("Both endpoints must be inside active plots in your guild community");
        int dx=Integer.compare(end.x(),start.x()),dz=Integer.compare(end.z(),start.z()),gap=0;
        var result=new ArrayList<Cell>();
        for(int i=0;i<length;i++){
            int x=start.x()+dx*i,z=start.z()+dz*i;var cell=new PlotRect(x,z,1,1);
            if(forbidden.stream().anyMatch(r->r.contains(x,z)))throw new IllegalArgumentException("The route crosses public infrastructure, another road or an unrelated plot");
            if(structures.stream().anyMatch(r->r.gap(cell)<5))throw new IllegalArgumentException("Roads must remain five blocks from houses and additions");
            gap=memberZone.stream().anyMatch(r->r.contains(x,z))?0:gap+1;
            if(gap>5)throw new IllegalArgumentException("Roads may bridge at most five unclaimed blocks between guild member plots");
            result.add(new Cell(x,z));
        }
        return List.copyOf(result);
    }
    public static PlotRect rectangle(List<Cell> cells){int minX=cells.stream().mapToInt(Cell::x).min().orElseThrow(),minZ=cells.stream().mapToInt(Cell::z).min().orElseThrow();return new PlotRect(minX,minZ,cells.stream().mapToInt(Cell::x).max().orElseThrow()-minX+1,cells.stream().mapToInt(Cell::z).max().orElseThrow()-minZ+1);}
}
