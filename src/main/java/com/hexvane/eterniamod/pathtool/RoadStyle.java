package com.hexvane.eterniamod.pathtool;

import java.util.*;

/** Nine cross-road columns with six equally weighted slots, like the Path Designer palette. */
record RoadStyle(String id,String name,long revision,List<List<String>> columns) {
    RoadStyle {
        if(id==null||!id.matches("[a-z0-9_-]{1,80}")||name==null||name.isBlank()||name.length()>48||name.chars().anyMatch(Character::isISOControl)||revision<0)throw new IllegalArgumentException("Give the style a name of up to 48 characters");
        name=name.strip();columns=validate(columns);
        if(columns.stream().flatMap(Collection::stream).allMatch(String::isEmpty))throw new IllegalArgumentException("Choose at least one paving block");
    }
    static List<List<String>> validate(List<List<String>> value){
        if(value==null||value.size()!=9)throw new IllegalArgumentException("A style needs nine columns");
        var result=new ArrayList<List<String>>();
        for(var column:value){if(column==null||column.size()!=6)throw new IllegalArgumentException("Each column has six material slots");for(var id:column)if(id==null||!id.isEmpty()&&!id.matches("[A-Za-z0-9_-]{1,160}"))throw new IllegalArgumentException("Choose a valid paving block");result.add(List.copyOf(column));}
        return List.copyOf(result);
    }
    static RoadStyle uniform(String id,String name,String block){var columns=new ArrayList<List<String>>();for(int i=0;i<9;i++)columns.add(List.of(block,"","","","",""));return new RoadStyle(id,name,0,columns);}
    String blockId(){return columns.stream().flatMap(Collection::stream).filter(s->!s.isEmpty()).findFirst().orElseThrow();}
    Set<String> blocks(){var result=new HashSet<String>();columns.forEach(c->c.stream().filter(s->!s.isEmpty()).forEach(result::add));return Set.copyOf(result);}
    /** Empty columns inherit their nearest populated neighbour. A stable hash prevents repaint flicker. */
    static String material(List<List<String>> columns,int column,int x,int z,String fallback){
        if(columns.isEmpty())return fallback;
        List<String> pool=List.of();
        for(int distance=0;distance<9&&pool.isEmpty();distance++)for(int candidate:new int[]{column-distance,column+distance})if(candidate>=0&&candidate<9){pool=columns.get(candidate).stream().filter(s->!s.isEmpty()).toList();if(!pool.isEmpty())break;}
        if(pool.isEmpty())return fallback;
        long hash=(x*0x9E3779B97F4A7C15L)^(z*0xC2B2AE3D27D4EB4FL);hash^=hash>>>30;hash*=0xBF58476D1CE4E5B9L;hash^=hash>>>27;
        return pool.get(Math.floorMod(hash,pool.size()));
    }
    static Map<SplineGeometry.Column,String> surface(List<SplineGeometry.Node> nodes,int width,List<SplineGeometry.Cell> cells,List<List<String>> columns,String fallback){
        var result=new HashMap<SplineGeometry.Column,String>();
        if(columns.isEmpty()){cells.forEach(c->result.put(c.column(),fallback));return result;}
        var samples=SplineGeometry.sample(nodes);
        for(var cell:cells){double best=Double.POSITIVE_INFINITY;int nearest=0;for(int i=0;i<samples.size();i++){var n=samples.get(i);double dx=cell.x()+.5-n.x(),dz=cell.z()+.5-n.z(),d=dx*dx+dz*dz;if(d<best){best=d;nearest=i;}}
            var n=samples.get(nearest);var a=samples.get(Math.max(0,nearest-1));var b=samples.get(Math.min(samples.size()-1,nearest+1));double dx=b.x()-a.x(),dz=b.z()-a.z(),length=Math.hypot(dx,dz);
            double side=length<1e-8?0:((cell.x()+.5-n.x())*dz-(cell.z()+.5-n.z())*dx)/length;
            // Narrow roads use the middle columns; even widths are centred between two columns.
            int first=(9-width)/2;int lane=Math.max(0,Math.min(width-1,(int)Math.floor(side+width/2.0)));
            result.put(cell.column(),material(columns,first+lane,cell.x(),cell.z(),fallback));
        }
        return Map.copyOf(result);
    }
}
