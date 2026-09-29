package com.hexvane.eterniamod.customization;

import org.bson.*;
import java.util.*;

/** Sparse snapshot operations retain fluids and coordinates outside the edit mask unchanged. */
final class CellEdits {
    static String key(BsonDocument cell){return cell.getInt32("x").getValue()+","+cell.getInt32("y").getValue()+","+cell.getInt32("z").getValue();}
    static Map<String,BsonDocument> index(BsonDocument document,String group){Map<String,BsonDocument> result=new TreeMap<>();for(var cell:document.getArray(group,new BsonArray()))result.put(key(cell.asDocument()),cell.asDocument());return result;}
    static BsonDocument select(BsonDocument document,Set<String> keys){var result=document.clone();for(String group:List.of("blocks","fluids")){var cells=new BsonArray();for(var value:document.getArray(group,new BsonArray()))if(keys.contains(key(value.asDocument())))cells.add(value);result.put(group,cells);}result.remove("entities");return result;}
    static void overlay(BsonDocument destination,BsonDocument changes){var blocks=index(destination,"blocks");var fluids=index(destination,"fluids");for(var value:changes.getArray("blocks")){var cell=value.asDocument();blocks.put(key(cell),cell);fluids.remove(key(cell));}fluids.putAll(index(changes,"fluids"));destination.put("blocks",new BsonArray(new ArrayList<>(blocks.values())));destination.put("fluids",new BsonArray(new ArrayList<>(fluids.values())));}
    static boolean sameCell(BsonDocument a,BsonDocument b){if(a==null||b==null)return false;a=a.clone();b=b.clone();a.remove("support");b.remove("support");return a.equals(b);}
    static void requireSame(BsonDocument actual,BsonDocument expected){var blocks=index(actual,"blocks");for(var cell:expected.getArray("blocks"))if(!sameCell(blocks.get(key(cell.asDocument())),cell.asDocument()))throw new IllegalStateException("Previewed blocks changed. Make a new preview.");var currentFluids=index(actual,"fluids");var expectedFluids=index(expected,"fluids");for(String key:index(expected,"blocks").keySet())if(!Objects.equals(currentFluids.get(key),expectedFluids.get(key)))throw new IllegalStateException("Previewed fluids changed. Make a new preview.");}
    private CellEdits(){}
}
