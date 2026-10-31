package com.hexvane.eterniamod.pathtool;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hexvane.eterniamod.ui.ToolHudHotkeyRows;
import com.hexvane.eterniamod.ui.ToolKeybindSlot;
import com.hypixel.hytale.server.core.Message;

public final class SplineRoadHud extends CustomUIHud {
    private final PlayerRef viewer;private String lastState="";private Boolean lastReview;private long lastKeys;
    SplineRoadHud(PlayerRef player){super(player,SplineRoadTool.HUD,0);viewer=player;}
    @Override protected void build(UICommandBuilder commands){commands.append("EterniaMod/SplineRoadHud.ui");}
    void refresh(SplineRoadTool.Session session){var commands=new UICommandBuilder();var styles=SplineRoadTool.service().styles();String style=styles.get(Math.floorMod(session.style,styles.size())).name();
        // Same dynamic CustomUIHud rows and resolver as Aetherhaven's PathToolStatusHud.
        boolean reviewing=session.review!=null||session.recovery!=null;String state=session.revision+":"+session.hovered+":"+session.nearby+":"+session.message+":"+session.selected+":"+style+":"+reviewing+":"+(session.plan==null?"":session.plan.invalid());long now=System.currentTimeMillis();boolean refreshKeys=now-lastKeys>=2000;if(state.equals(lastState)&&!refreshKeys)return;lastState=state;if(lastReview==null||lastReview!=reviewing||refreshKeys){controls(commands,viewer,reviewing);lastReview=reviewing;lastKeys=now;}
        commands.set("#Mode.Text",session.recovery!=null?"RECOVER ROAD":session.review!=null?"REVIEW · USE TO CONFIRM":session.editing!=null?"EDIT SAVED ROAD":"ROAD DESIGNER");
        commands.set("#Summary.Text",session.nodes.size()+" nodes  ·  "+session.width+" blocks wide\n"+style+"\n"+(session.selected>=0?"Selected node "+(session.selected+1)+" of "+session.nodes.size():session.hovered!=null?"Saved road under crosshair":session.nearby+" saved roads nearby"));
        String status=session.message;if(session.review==null&&session.recovery==null&&session.plan!=null&&!session.plan.valid())status=session.plan.invalid().size()+" blocked columns · "+session.plan.invalid().values().stream().findFirst().orElse("Adjust the road");
        commands.set("#Status.Text",status);commands.set("#Help.Text",session.review!=null?"Gold ground will change. Ivory junction cells belong to a road or plaza and stay unchanged. Edit a node to cancel.":"Blue outlines show saved roads. Aim at one to highlight it in gold. Use Manage to edit or delete it. Green is your new road; ivory overlaps stay unchanged.");update(false,commands);
    }
    public static void controls(UICommandBuilder commands,PlayerRef player,boolean reviewing) {
        commands.clear("#ControlRows");
        var slots=new ToolKeybindSlot[]{ToolKeybindSlot.SECONDARY,ToolKeybindSlot.PRIMARY,ToolKeybindSlot.USE,ToolKeybindSlot.ABILITY1,ToolKeybindSlot.ABILITY4,ToolKeybindSlot.PICK};
        var descriptions=new String[]{"Add a ground node / remove aimed node","Select road / select or move node",reviewing?"Confirm reviewed road":"Review road before building","Undo last node edit","Road width and paving style","Edit or remove saved road"};
        for(int i=0;i<slots.length;i++)ToolHudHotkeyRows.appendRow(commands,"#ControlRows",i,slots[i],Message.raw(descriptions[i]),player);
    }
}
