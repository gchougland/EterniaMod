package com.hexvane.eterniamod.setup;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.joml.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HubNpcIdentityTest {
    private static final Path RES=Path.of("src/main/resources");
    private static JsonObject read(Path path)throws Exception{return JsonParser.parseString(Files.readString(path)).getAsJsonObject();}
    @Test void sixCharactersHaveDistinctModelsAndNativeUseHints()throws Exception{
        assertEquals(6,HubNpcIdentity.values().length);var models=new HashSet<String>();var names=new HashSet<String>();
        for(var identity:HubNpcIdentity.values()){
            assertTrue(models.add(identity.model()));assertTrue(names.add(identity.characterName()));
            var role=read(RES.resolve("Server/NPC/Roles/Eternia/"+identity.role()+".json"));
            assertEquals(identity.model(),role.get("Appearance").getAsString());assertEquals(identity.nameplate(),role.getAsJsonArray("DisplayNames").get(0).getAsString());
            var action=role.getAsJsonObject("InteractionInstruction").getAsJsonArray("Instructions").get(1).getAsJsonObject().getAsJsonArray("Actions").get(0).getAsJsonObject();
            assertTrue(action.get("ShowPrompt").getAsBoolean());assertEquals(identity.hint(),action.get("Hint").getAsString());
            assertTrue(Files.exists(RES.resolve("Server/Models/Eternia/"+identity.model()+".json")));
        }
        assertEquals("Eternia_Prowl",HubNpcIdentity.GREETER.model());
        assertTrue(HubNpcIdentity.forRole("foreign").isEmpty());
    }
    private record Node(JsonObject value,String parent,Matrix4d world){}
    private static Map<String,Node> nodes(JsonObject model){var result=new LinkedHashMap<String,Node>();for(var node:model.getAsJsonArray("nodes"))visit(node.getAsJsonObject(),null,new Matrix4d(),result);return result;}
    private static double component(JsonObject node,String field,String axis,double fallback){var value=node.getAsJsonObject(field);return value==null||!value.has(axis)?fallback:value.get(axis).getAsDouble();}
    private static void visit(JsonObject node,String parent,Matrix4d world,Map<String,Node> result){
        var q=new Quaterniond(component(node,"orientation","x",0),component(node,"orientation","y",0),component(node,"orientation","z",0),component(node,"orientation","w",1)).normalize();
        var absolute=new Matrix4d(world).translate(component(node,"position","x",0),component(node,"position","y",0),component(node,"position","z",0)).rotate(q);
        // The client passes the shape-center frame to children, including for
        // shapeless grouping nodes. This matches native BlockyModelBoundsParser.
        var shape=node.getAsJsonObject("shape");
        if(shape!=null)absolute.translate(component(shape,"offset","x",0),component(shape,"offset","y",0),component(shape,"offset","z",0));
        assertNull(result.put(node.get("id").getAsString(),new Node(node,parent,absolute)),"duplicate node id");
        if(node.has("children"))for(var child:node.getAsJsonArray("children"))visit(child.getAsJsonObject(),node.get("name").getAsString(),absolute,result);
    }
    private static Map<String,Node> byName(Map<String,Node> nodes){
        var result=new LinkedHashMap<String,Node>();
        for(var node:nodes.values())assertNull(result.put(node.value.get("name").getAsString(),node),"ambiguous animated name");
        return result;
    }
    @Test void playerRigPreservesApprovedAppearanceAndAnimationHierarchy()throws Exception{
        // Approved 2026-09-29: retain the edited belly/hair and editor-rounded transforms.
        // Numeric IDs are editor bookkeeping; names are animation targets.
        var approved=byName(nodes(read(Path.of("src/test/resources/prowl/approved-player-rig.blockymodel"))));
        var byName=byName(nodes(read(RES.resolve("Common/NPC/Eternia/Prowl/Prowl_PlayerRig.blockymodel"))));
        assertEquals(approved.keySet(),byName.keySet(),"missing or renamed animation/mesh node");
        for(var entry:approved.entrySet()){
            var before=entry.getValue();var after=byName.get(entry.getKey());
            assertEquals(before.parent,after.parent,"animation hierarchy: "+entry.getKey());
            assertEquals(before.value.get("shape"),after.value.get("shape"),"geometry/UV/visibility: "+entry.getKey());
            // Independent JOML transforms cover all vertices and shapeless animation pivots.
            double[] a=new double[16],b=new double[16];before.world.get(a);after.world.get(b);
            assertArrayEquals(a,b,1e-9,entry.getKey());
        }
        assertEquals(40,byName.values().stream().filter(n->!n.value.getAsJsonObject("shape").get("type").getAsString().equals("none")).count());
        assertEquals("Chest",byName.get("Head").parent);assertEquals("Head",byName.get("Neck").parent);
        for(String side:List.of("L-","R-")){
            assertEquals("Head",byName.get(side+"Eye-Attachment").parent);assertEquals(side+"Eye-Attachment",byName.get(side+"Eye").parent);
            assertEquals("Head",byName.get(side+"Eyelid").parent);assertEquals("quad",byName.get(side+"Eyelid").value.getAsJsonObject("shape").get("type").getAsString());
            assertEquals(side+"Arm",byName.get(side+"Forearm").parent);assertEquals(side+"Thigh",byName.get(side+"Calf").parent);
        }
        assertEquals("Mouth-Attachment",byName.get("Mouth").parent);
        // Fixed anatomical references catch a shared transform-convention error
        // in generator and proof, which previously allowed the collapsed rig.
        assertEquals(50,byName.get("Pelvis").world.m31(),1e-9);
        assertEquals(88.5,byName.get("Head").world.m31(),1e-9);
        assertEquals(48.5,byName.get("L-Thigh").world.m31(),1e-9);
        assertEquals(28.48914,byName.get("L-Calf").world.m31(),1e-9);
        assertEquals(7.55741,byName.get("L-Foot").world.m31(),1e-9);
    }

    @Test void shapelessParentsKeepOffsetsInChildFrame(){
        var model=JsonParser.parseString("""
                {"nodes":[{"id":"parent","name":"Parent","position":{"y":10},"shape":{"type":"none","offset":{"y":25}},
                "children":[{"id":"child","name":"Child","position":{"y":5},"shape":{"type":"box","offset":{"y":2}}}]}]}
                """).getAsJsonObject();
        var frames=nodes(model);
        assertEquals(35,frames.get("parent").world.m31(),1e-9);
        assertEquals(42,frames.get("child").world.m31(),1e-9);
    }

    @Test void offsetsRotateWithTheirNodeButShapeStretchDoesNotScaleChildren(){
        var model=JsonParser.parseString("""
                {"nodes":[{"id":"parent","name":"Parent","position":{"x":10},"orientation":{"z":0.7071067811865476,"w":0.7071067811865476},
                "shape":{"type":"box","offset":{"x":2},"stretch":{"x":5,"y":5,"z":5}},
                "children":[{"id":"child","name":"Child","position":{"x":3},"shape":{"type":"box","offset":{"x":1}}}]}]}
                """).getAsJsonObject();
        var frames=nodes(model);
        assertEquals(10,frames.get("parent").world.m30(),1e-9);
        assertEquals(2,frames.get("parent").world.m31(),1e-9);
        assertEquals(10,frames.get("child").world.m30(),1e-9);
        assertEquals(6,frames.get("child").world.m31(),1e-9);
    }
}
