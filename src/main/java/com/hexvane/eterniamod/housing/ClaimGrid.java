package com.hexvane.eterniamod.housing;

import com.hexvane.eterniamod.debug.DebugLineCylinderUtil;
import com.hypixel.hytale.math.matrix.Matrix4dUtil;
import com.hypixel.hytale.protocol.DebugShape;
import com.hypixel.hytale.protocol.packets.player.DisplayDebug;
import com.hypixel.hytale.server.core.modules.debug.DebugUtils;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import org.joml.Vector3f;

/** Opaque per-viewer claim aid, refreshed while its page owns the debug surface. */
final class ClaimGrid {
    static void draw(PlayerRef player,PlotRect rect,double groundY,boolean valid,boolean bird) {
        player.getPacketHandler().write(new com.hypixel.hytale.protocol.packets.player.ClearDebugShapes());
        Vector3f color=valid?new Vector3f(.57f,.79f,.55f):new Vector3f(.9f,.4f,.35f);
        int step=Math.max(1,(int)Math.ceil(Math.max(rect.width(),rect.depth())/32.0));
        // At the guild camera's height the old 0.018-block strokes were subpixel.
        double thickness=bird?Math.max(.07,Math.max(rect.width(),rect.depth())*1.25*.0028):.035;
        double y=groundY+Math.max(.12,thickness);
        for(int x=0;x<rect.width();x+=step)line(player,rect.x()+x,y,rect.z(),rect.x()+x,y,rect.endZ(),color,thickness);
        for(int z=0;z<rect.depth();z+=step)line(player,rect.x(),y,rect.z()+z,rect.endX(),y,rect.z()+z,color,thickness);
        line(player,rect.endX(),y,rect.z(),rect.endX(),y,rect.endZ(),color,thickness);
        line(player,rect.x(),y,rect.endZ(),rect.endX(),y,rect.endZ(),color,thickness);
    }
    private static void line(PlayerRef p,double x,double y,double z,double ex,double ey,double ez,Vector3f color,double thickness) {
        var matrix=DebugLineCylinderUtil.segmentMatrix(x,y,z,ex,ey,ez,thickness,Math.hypot(ex-x,ez-z));
        if(matrix!=null)p.getPacketHandler().write(new DisplayDebug(DebugShape.Cylinder,Matrix4dUtil.asFloatData(matrix),color,3f,(byte)DebugUtils.FLAG_NO_WIREFRAME,null,1f));
    }
}
