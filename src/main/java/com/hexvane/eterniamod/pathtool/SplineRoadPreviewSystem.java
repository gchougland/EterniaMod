package com.hexvane.eterniamod.pathtool;

import com.hexvane.eterniamod.debug.DebugLineCylinderUtil;
import com.hexvane.eterniamod.setup.SetupAccess;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.matrix.Matrix4dUtil;
import com.hypixel.hytale.protocol.DebugShape;
import com.hypixel.hytale.protocol.packets.player.DisplayDebug;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.debug.DebugUtils;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;
import org.joml.Vector3f;

/** Throttled live preview; keyed HUD removal leaves every other mod's HUD intact. */
final class SplineRoadPreviewSystem extends EntityTickingSystem<EntityStore> {
    private static final Vector3f BLUE=new Vector3f(.35f,.65f,1f),GREEN=new Vector3f(.52f,.77f,.70f),GOLD=new Vector3f(.85f,.71f,.42f),RED=new Vector3f(.90f,.32f,.28f),IVORY=new Vector3f(.94f,.91f,.84f);
    @Override public Query<EntityStore> getQuery(){return Query.and(Player.getComponentType(),PlayerRef.getComponentType());}
    @Override public void tick(float dt,int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> commands){
        var ref=chunk.getReferenceTo(index);var player=chunk.getComponent(index,Player.getComponentType());var playerRef=chunk.getComponent(index,PlayerRef.getComponentType());
        boolean applies=SplineRoadTool.holding(ref,store)&&SetupAccess.allowed(playerRef.getUuid())&&player.getPageManager().getCustomPage()==null;
        if(!applies){if(player.getHudManager().getCustomHud(SplineRoadTool.HUD)!=null)player.getHudManager().removeCustomHud(playerRef,SplineRoadTool.HUD);var old=SplineRoadTool.existing(playerRef.getUuid());if(old!=null)old.shown=false;return;}
        var session=SplineRoadTool.session(playerRef,store.getExternalData().getWorld());long now=System.currentTimeMillis();if(session.shown&&now-session.lastPaint<200)return;session.lastPaint=now;
        nodes(playerRef,session);
        try{
            var transform=store.getComponent(ref,TransformComponent.getComponentType());
            var position=transform==null?null:transform.getPosition();
            var saved=position==null?List.<SplineRoadStore.Road>of():SavedRoadSelection.nearby(SplineRoadTool.service().roads(),session.world,position.x,position.z);
            var target=TargetUtil.getTargetBlock(ref,64,store);
            var aimed=target==null?null:SavedRoadSelection.aimed(saved,session.world,target.x,target.z);
            session.hovered=aimed==null?null:aimed.id();session.nearby=saved.size();
            if(session.nodes.size()>=2&&session.review==null&&session.recovery==null&&(session.plan==null||now-session.lastPlan>=1500)){try{session.plan=SplineRoadTool.service().preview(store.getExternalData().getWorld(),session.nodes,session.width,session.editing);}catch(IllegalArgumentException error){session.plan=null;session.message=error.getMessage();}session.lastPlan=now;}
            SplineRoadHud hud;if(player.getHudManager().getCustomHud(SplineRoadTool.HUD) instanceof SplineRoadHud h)hud=h;else{hud=new SplineRoadHud(playerRef);player.getHudManager().addCustomHud(playerRef,hud);}hud.refresh(session);boolean drawEdges=!session.shown||session.lastEdgeRevision!=session.revision||now-session.lastEdges>=600;session.shown=true;if(!drawEdges)return;session.lastEdges=now;session.lastEdgeRevision=session.revision;
            int savedEdges=0;
            for(var road:saved){if(road.id().equals(session.editing))continue;var color=road.id().equals(session.hovered)?GOLD:road.state()==SplineRoadStore.State.PENDING?RED:BLUE;
                for(var edge:SavedRoadSelection.edges(road,position.x,position.z)){if(savedEdges++>=1200)break;line(playerRef,edge.x(),edge.y(),edge.z(),edge.endX(),edge.y(),edge.endZ(),color);}if(savedEdges>=1200)break;}
            var cells=new ArrayList<>(session.review!=null?(session.review.pending().change().next()!=null?session.review.pending().change().next().cells():session.review.pending().current().cells()):session.plan!=null?session.plan.previewCells():List.<SplineGeometry.Cell>of());var connections=new HashSet<SplineGeometry.Column>();if(session.review!=null&&session.review.pending().change().next()!=null){var shared=session.review.pending().change().next().connections();cells.addAll(shared);shared.forEach(c->connections.add(c.column()));}else if(session.plan!=null)session.plan.connections().forEach(c->connections.add(c.column()));var invalid=session.review==null&&session.plan!=null?session.plan.invalid():Map.<SplineGeometry.Column,String>of();var columns=new HashSet<SplineGeometry.Column>();cells.forEach(c->columns.add(c.column()));int marked=0;
            for(var c:cells){var color=invalid.containsKey(c.column())?RED:connections.contains(c.column())?IVORY:session.review!=null?GOLD:GREEN;double y=c.y()+1.04;int x=c.x(),z=c.z();
                if(!columns.contains(new SplineGeometry.Column(x-1,z)))line(playerRef,x,y,z,x,y,z+1,color);
                if(!columns.contains(new SplineGeometry.Column(x+1,z)))line(playerRef,x+1,y,z,x+1,y,z+1,color);
                if(!columns.contains(new SplineGeometry.Column(x,z-1)))line(playerRef,x,y,z,x+1,y,z,color);
                if(!columns.contains(new SplineGeometry.Column(x,z+1)))line(playerRef,x,y,z+1,x+1,y,z+1,color);
                if(invalid.containsKey(c.column())&&marked++<128)line(playerRef,x+.25,y,z+.25,x+.75,y,z+.75,RED);
            }

        }catch(Exception error){session.message=error.getMessage()==null?"Adjust the nodes to make a supported road":error.getMessage();if(player.getHudManager().getCustomHud(SplineRoadTool.HUD) instanceof SplineRoadHud hud)hud.refresh(session);}
    }
    private static void nodes(PlayerRef playerRef,SplineRoadTool.Session session){
        for(int i=0;i<session.nodes.size();i++){var n=session.nodes.get(i);var color=i==session.selected?GOLD:IVORY;if(i==session.selected){nodeLine(playerRef,n.x(),n.y()+.1,n.z(),n.x(),n.y()+1.7,n.z(),GOLD);for(int edge=0;edge<4;edge++){double a=edge*Math.PI/2,b=(edge+1)*Math.PI/2;nodeLine(playerRef,n.x()+.8*Math.cos(a),n.y()+.08,n.z()+.8*Math.sin(a),n.x()+.8*Math.cos(b),n.y()+.08,n.z()+.8*Math.sin(b),GOLD);}}double y=n.y()+(i==session.selected?.65:.45);double radius=i==session.selected?.5:.3;nodeLine(playerRef,n.x()-radius,y,n.z(),n.x(),y+radius,n.z(),color);nodeLine(playerRef,n.x(),y+radius,n.z(),n.x()+radius,y,n.z(),color);nodeLine(playerRef,n.x()+radius,y,n.z(),n.x(),y-radius,n.z(),color);nodeLine(playerRef,n.x(),y-radius,n.z(),n.x()-radius,y,n.z(),color);nodeLine(playerRef,n.x(),y,n.z()-radius,n.x(),y+radius,n.z(),color);nodeLine(playerRef,n.x(),y+radius,n.z(),n.x(),y,n.z()+radius,color);nodeLine(playerRef,n.x(),y,n.z()+radius,n.x(),y-radius,n.z(),color);nodeLine(playerRef,n.x(),y-radius,n.z(),n.x(),y,n.z()-radius,color);}
    }
    private static void nodeLine(PlayerRef player,double x,double y,double z,double ex,double ey,double ez,Vector3f color){draw(player,x,y,z,ex,ey,ez,color,.45f,.045);}
    private static void line(PlayerRef player,double x,double y,double z,double ex,double ey,double ez,Vector3f color){draw(player,x,y,z,ex,ey,ez,color,1.1f,.024);}
    private static void draw(PlayerRef player,double x,double y,double z,double ex,double ey,double ez,Vector3f color,float duration,double radius){double length=Math.sqrt((ex-x)*(ex-x)+(ey-y)*(ey-y)+(ez-z)*(ez-z));var matrix=DebugLineCylinderUtil.segmentMatrix(x,y,z,ex,ey,ez,radius,length);if(matrix!=null)player.getPacketHandler().write(new DisplayDebug(DebugShape.Cylinder,Matrix4dUtil.asFloatData(matrix),color,duration,(byte)DebugUtils.FLAG_NO_WIREFRAME,null,1f));}
}
