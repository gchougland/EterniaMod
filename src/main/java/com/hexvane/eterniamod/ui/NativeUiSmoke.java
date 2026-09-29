package com.hexvane.eterniamod.ui;

import com.hypixel.hytale.protocol.packets.interface_.CustomUICommand;
import com.hypixel.hytale.protocol.packets.interface_.CustomUICommandType;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.util.*;
import org.bson.BsonDocument;

/** Native command serialization requires Hytale's initialized logging manager. Run only in the isolated smoke server. */
public final class NativeUiSmoke {
    private NativeUiSmoke(){}
    public static void validate() {
        var roadCommands=new UICommandBuilder();
        com.hexvane.eterniamod.pathtool.SplineRoadHud.controls(roadCommands,null,false);
        for(int i=0;i<6;i++){
            String selector="#ControlRows["+i+"] #KeyLabel.TextSpans";
            require(Arrays.stream(roadCommands.getCommands()).anyMatch(c->selector.equals(c.selector)&&c.data!=null&&c.data.length()>10),"Missing custom road HUD key label: "+i);
        }
        var choices=new ArrayList<ChoicePage.Choice>();
        for(int i=0;i<9;i++)choices.add(new ChoicePage.Choice("A furnished house and the decorations around it",i==0?"Transfer leadership":"Preview",(r,s)->{}));
        // This page's build is deliberately independent of entity reads; no player is attached or mutated.
        var page=new ChoicePage(null,"Housing","Select a house to preview.",choices);
        var commands=new UICommandBuilder();var events=new UIEventBuilder();page.build(null,commands,events,null);
        require(Arrays.stream(events.getEvents()).anyMatch(event -> "#MenuHome".equals(event.selector)), "Choice menus need a home shortcut");
        page.handleDataEvent(null,null,"{\"EterniaHome\":\"stale-page-event\"}"); // Ignored before entity access.
        var all=Arrays.asList(commands.getCommands());
        require(all.stream().filter(command->command.type==CustomUICommandType.Append&&"EterniaMod/ServiceRow.ui".equals(command.text)).count()==6,"Choice pagination must expose exactly six first-page entries");
        var actionAnchor=value(all,"#Rows[0] #RowAction.Anchor").getDocument("0");
        int width=actionAnchor.getInt32("Width").getValue();
        require(width>=UiPresentation.textWidth("Transfer leadership")+32,"Long choice actions need their full text width and padding");
        require(actionAnchor.getInt32("Height").getValue()==44&&actionAnchor.getInt32("Left").getValue()==12&&actionAnchor.getInt32("Top").getValue()==16,"Replacing an action anchor must retain ServiceRow height and spacing");
        var rowAnchor=value(all,"#Rows[0].Anchor").getDocument("0");
        require(rowAnchor.getInt32("Height").getValue()>=76&&rowAnchor.getInt32("Bottom").getValue()==8,"Replacing a row anchor must retain the gap between rows");
        require(all.stream().noneMatch(command->command.selector!=null&&command.selector.contains(".Anchor.")),"Anchor dimensions must be sent as a complete native Anchor value");
        require(value(all,"#Previous.Disabled").getBoolean("0").getValue(),"Previous must be disabled on the first page");
        require(!value(all,"#Next.Disabled").getBoolean("0").getValue(),"Next must be available for remaining choices");
        require(value(all,"#Next.Visible").getBoolean("0").getValue(),"Multi-page choices must retain pagination");
        for (int count : new int[]{0,1,6}) {
            var singleCommands=new UICommandBuilder();
            new ChoicePage(null,"One page","Choose an entry.",choices.subList(0,count)).build(null,singleCommands,new UIEventBuilder(),null);
            var single=Arrays.asList(singleCommands.getCommands());
            for (String selector : List.of("#Previous.Visible","#Next.Visible","#Pagination.Visible"))
                require(!value(single,selector).getBoolean("0").getValue(),"Single-page menu leaked pagination: "+selector);
        }
        for(var command:all)if(command.type==CustomUICommandType.Append)require(NativeUiSmoke.class.getClassLoader().getResource("Common/UI/Custom/"+command.text)!=null,"Missing native choice asset: "+command.text);
    }
    private static BsonDocument value(List<CustomUICommand> commands,String selector){return BsonDocument.parse(commands.stream().filter(command->selector.equals(command.selector)).findFirst().orElseThrow(()->new IllegalStateException("Missing UI command: "+selector)).data);}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
