package com.hexvane.eterniamod.pathtool;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hexvane.eterniamod.ui.ToolHudHotkeyRows;
import com.hexvane.eterniamod.ui.ToolKeybindSlot;
import com.hypixel.hytale.server.core.Message;

public final class SplineRoadHud extends CustomUIHud {
    private final PlayerRef viewer;
    SplineRoadHud(PlayerRef player){super(player,SplineRoadTool.HUD,0);viewer=player;}
    @Override protected void build(UICommandBuilder commands){commands.append("EterniaMod/SplineRoadHud.ui");}
    void refresh(SplineRoadTool.Session session){var commands=new UICommandBuilder();var styles=SplineRoadTool.service().styles();String style=styles.get(Math.floorMod(session.style,styles.size())).name();
        // Same dynamic CustomUIHud rows and resolver as Aetherhaven's PathToolStatusHud.
        controls(commands,viewer,session.review!=null||session.recovery!=null);
        commands.set("#Mode.Text",session.recovery!=null?"RECOVER ROAD":session.review!=null?"REVIEW · USE TO CONFIRM":session.editing!=null?"EDIT SAVED ROAD":"ROAD DESIGNER");
        commands.set("#Summary.Text",session.nodes.size()+" nodes  ·  "+session.width+" blocks wide\n"+style);
        String status=session.message;if(session.review==null&&session.recovery==null&&session.plan!=null&&!session.plan.valid())status=session.plan.invalid().size()+" blocked columns · "+session.plan.invalid().values().stream().findFirst().orElse("Adjust the road");
        commands.set("#Status.Text",status);commands.set("#Help.Text",session.review!=null?"Gold ground will change. Ivory junction cells belong to an existing road and stay unchanged. Edit a node to cancel.":"Green edges follow the ground; red marks blocked cells. Ivory endpoint cells join an existing road without changing it.");update(false,commands);
    }
    public static void controls(UICommandBuilder commands,PlayerRef player,boolean reviewing) {
        commands.clear("#ControlRows");
        var slots=new ToolKeybindSlot[]{ToolKeybindSlot.SECONDARY,ToolKeybindSlot.PRIMARY,ToolKeybindSlot.USE,ToolKeybindSlot.ABILITY1,ToolKeybindSlot.ABILITY2,ToolKeybindSlot.PICK};
        var descriptions=new String[]{"Add a ground node / remove aimed node","Select a node, then move it",reviewing?"Confirm reviewed road":"Review road before building","Undo last node edit","Road width and paving style","Edit or remove saved road"};
        for(int i=0;i<slots.length;i++)ToolHudHotkeyRows.appendRow(commands,"#ControlRows",i,slots[i],Message.raw(descriptions[i]),player);
    }
}
