package com.hexvane.eterniamod.prop;

import com.hexvane.eterniamod.EterniaModConstants;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.hub.HubPlotFootprint;
import com.hexvane.eterniamod.hub.HubPlotProp;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hexvane.eterniamod.placement.PlotFootprintUtil;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferUtil;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import java.nio.file.Path;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3i;

/** Padded axis-aligned bounds for prop highlight and packaging detection. */
public final class PropBoundsUtil {
    private PropBoundsUtil() {}

    /** The aimed object selects its plot, even when the player stands across a property line. */
    @Nullable
    public static HubPlotRecord findAimedPlot(java.util.List<HubPlotRecord> plots,Ref<EntityStore> ref,Store<EntityStore> store,EterniaModPlugin plugin,double maxDistance) {
        var look=TargetUtil.getLook(ref,store);var start=look.getPosition();var end=new Vector3d(start).fma(maxDistance,look.getDirection());
        HubPlotRecord closest=null;double distance=Double.MAX_VALUE;
        for(var plot:plots) {
            var fp=plot.getFootprint();
            // Cull whole properties before resolving any prefab assets for the HUD's repeated query.
            double plotEntry=rayEntryDistance(start,end,new Box(fp.getMinX()-.5,-4096,fp.getMinZ()-.5,fp.getMaxX()+1.5,4096,fp.getMaxZ()+1.5));
            if(plotEntry<0||plotEntry>distance)continue;
            for(var prop:plot.getProps()) {
            var def=plugin.getPropCatalog().get(prop.getPropId());if(def==null)continue;
            var path=PrefabResolveUtil.resolvePrefabPath(def.getPrefabPath());if(path==null)continue;
            double entry=rayEntryDistance(start,end,toPaddedBox(new Vector3i(prop.getAnchorX(),prop.getAnchorY(),prop.getAnchorZ()),prop.resolveRotationYaw(),PrefabBufferUtil.getCached(path)));
            if(entry>=0&&entry<distance){closest=plot;distance=entry;}
            }
        }
        return closest;
    }

    @Nonnull
    public static Box toPaddedBox(@Nonnull HubPlotFootprint footprint) {
        double pad = EterniaModConstants.PROP_BOUNDS_PADDING;
        return new Box(
            footprint.getMinX() - pad,
            footprint.getMinY() - pad,
            footprint.getMinZ() - pad,
            footprint.getMaxX() + 1.0 + pad,
            footprint.getMaxY() + 1.0 + pad,
            footprint.getMaxZ() + 1.0 + pad
        );
    }

    @Nonnull
    public static Box toPaddedBox(
        @Nonnull Vector3i origin,
        @Nonnull Rotation yaw,
        @Nonnull IPrefabBuffer buffer
    ) {
        return toPaddedBox(PlotFootprintUtil.computeFootprint(origin, yaw, buffer));
    }

    @Nullable
    public static PropLookupUtil.PropMatch findPropAlongLookRay(
        @Nonnull HubPlotRecord plot,
        @Nonnull Ref<EntityStore> ref,
        @Nonnull Store<EntityStore> store,
        @Nonnull EterniaModPlugin plugin,
        double maxDistance
    ) {
        var look = TargetUtil.getLook(ref, store);
        Vector3d start = look.getPosition();
        Vector3d direction = look.getDirection();
        Vector3d end = new Vector3d(start).fma(maxDistance, direction);

        PropLookupUtil.PropMatch closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (HubPlotProp prop : plot.getProps()) {
            PropDefinition def = plugin.getPropCatalog().get(prop.getPropId());
            if (def == null) {
                continue;
            }
            Path prefabPath = PrefabResolveUtil.resolvePrefabPath(def.getPrefabPath());
            if (prefabPath == null) {
                continue;
            }
            IPrefabBuffer buffer = PrefabBufferUtil.getCached(prefabPath);
            Vector3i origin = new Vector3i(prop.getAnchorX(), prop.getAnchorY(), prop.getAnchorZ());
            Box box = toPaddedBox(origin, prop.resolveRotationYaw(), buffer);
            double entryDistance = rayEntryDistance(start, end, box);
            if (entryDistance >= 0.0 && entryDistance < closestDistance) {
                closestDistance = entryDistance;
                closest = new PropLookupUtil.PropMatch(prop);
            }
        }
        return closest;
    }

    /** Returns normalized ray parameter t in [0, 1] for the near intersection, or -1 when there is none. */
    private static double rayEntryDistance(@Nonnull Vector3d start, @Nonnull Vector3d end, @Nonnull Box box) {
        double ox = start.x;
        double oy = start.y;
        double oz = start.z;
        double dx = end.x - ox;
        double dy = end.y - oy;
        double dz = end.z - oz;

        double tmin = 0.0;
        double tmax = 1.0;

        if (Math.abs(dx) < 1e-10) {
            if (ox < box.min.x || ox > box.max.x) {
                return -1.0;
            }
        } else {
            double t1 = (box.min.x - ox) / dx;
            double t2 = (box.max.x - ox) / dx;
            if (t1 > t2) {
                double temp = t1;
                t1 = t2;
                t2 = temp;
            }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) {
                return -1.0;
            }
        }

        if (Math.abs(dy) < 1e-10) {
            if (oy < box.min.y || oy > box.max.y) {
                return -1.0;
            }
        } else {
            double t1 = (box.min.y - oy) / dy;
            double t2 = (box.max.y - oy) / dy;
            if (t1 > t2) {
                double temp = t1;
                t1 = t2;
                t2 = temp;
            }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) {
                return -1.0;
            }
        }

        if (Math.abs(dz) < 1e-10) {
            if (oz < box.min.z || oz > box.max.z) {
                return -1.0;
            }
        } else {
            double t1 = (box.min.z - oz) / dz;
            double t2 = (box.max.z - oz) / dz;
            if (t1 > t2) {
                double temp = t1;
                t1 = t2;
                t2 = temp;
            }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) {
                return -1.0;
            }
        }

        return tmin;
    }
}
