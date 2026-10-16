package com.hexvane.eterniamod.pathtool;

import com.hexvane.eterniamod.setup.SetupAccess;
import com.hexvane.eterniamod.ui.*;
import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;

/** Server-owned material brushes never move real inventory stacks or trust client supplied block IDs. */
final class SplineRoadStylePage extends EterniaInteractiveCustomUIPage<SplineRoadStylePage.Data> {
    private record Material(String block,String item,String name){}
    private final SplineRoadTool.Session session;
    private final String id;private final long revision;private final List<List<String>> columns=new ArrayList<>();
    private final List<Material> materials;private List<Material> visible=List.of();
    private String name,search="",brush="",message="Choose a block on the right, then click a slot to paint it.";private int page;
    SplineRoadStylePage(PlayerRef player,SplineRoadTool.Session session,RoadStyle original,boolean copy){
        super(player,CustomPageLifetime.CanDismissOrCloseThroughInteraction,Data.CODEC);this.session=session;
        id=copy?"style_"+UUID.randomUUID().toString().replace("-",""):original.id();revision=copy?0:original.revision();name=copy?"New road style":original.name();
        original.columns().forEach(c->columns.add(new ArrayList<>(c)));brush=original.blockId();
        var byBlock=new TreeMap<String,Material>();
        for(var item:Item.getAssetMap().getAssetMap().values()){var block=item.getBlockId();if(block==null||!block.matches("[A-Za-z0-9_-]{1,160}"))continue;var type=BlockType.getAssetMap().getAsset(block);if(type==null||!type.isCubeDrawType()||type.getBlockEntity()!=null||type.getMaterial()!=com.hypixel.hytale.protocol.BlockMaterial.Solid)continue;var candidate=new Material(block,item.getId(),UiPresentation.itemName(item.getId()));var previous=byBlock.get(block);if(previous==null||candidate.item().compareTo(previous.item())<0)byBlock.put(block,candidate);}
        materials=byBlock.values().stream().sorted(Comparator.comparing(Material::name).thenComparing(Material::item)).toList();
    }
    private void bind(UIEventBuilder e,String selector,String action){e.addEventBinding(CustomUIEventBindingType.Activating,selector,new EventData().append("Action",action).append("@Name","#Name.Value").append("@Search","#Search.Value"),false);}
    @Override public void build(Ref<EntityStore> ref,UICommandBuilder c,UIEventBuilder e,Store<EntityStore> store){
        c.append("EterniaMod/SplineRoadStyle.ui");c.set("#Name.Value",name);c.set("#Search.Value",search);c.set("#Status.Text",message);
        c.set("#Brush.Text",brush.isEmpty()?"Eraser selected":materials.stream().filter(m->m.block().equals(brush)).map(Material::name).findFirst().orElse(UiPresentation.friendlyId(brush)));
        var itemByBlock=new HashMap<String,String>();materials.forEach(m->itemByBlock.put(m.block(),m.item()));
        for(int row=0;row<6;row++){c.append("#Palette","EterniaMod/RoadStylePaletteRow.ui");for(int col=0;col<9;col++){String selector="#Palette["+row+"] #Slot"+col,block=columns.get(col).get(row);icon(c,selector,itemByBlock.get(block));c.set(selector+".TooltipTextSpans",Message.raw(block.isEmpty()?"Empty slot":UiPresentation.itemName(itemByBlock.getOrDefault(block,block))));bind(e,selector,"Paint:"+col+":"+row);}}
        var matches=materials.stream().filter(m->(m.name()+" "+m.block()).toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT))).toList();page=Math.max(0,Math.min(page,Math.max(0,(matches.size()-1)/12)));visible=List.copyOf(matches.subList(page*12,Math.min(matches.size(),page*12+12)));
        for(int row=0;row<4;row++){c.append("#Materials","EterniaMod/RoadStyleMaterialRow.ui");for(int col=0;col<3;col++){int index=row*3+col;String selector="#Materials["+row+"] #Slot"+col;c.set(selector+".Visible",index<visible.size());if(index<visible.size()){var m=visible.get(index);icon(c,selector,m.item());c.set(selector+".TooltipTextSpans",Message.raw(m.name()));bind(e,selector,"Brush:"+index);}}}
        c.set("#Pages.Text",matches.isEmpty()?"No matching blocks":(page+1)+" / "+Math.max(1,(matches.size()+11)/12));c.set("#Previous.Disabled",page==0);c.set("#Next.Disabled",(page+1)*12>=matches.size());
        for(String action:new String[]{"SearchButton","Previous","Next","Erase","Fill","Save","Cancel"})bind(e,"#"+action,action);
    }
    private static void icon(UICommandBuilder c,String selector,String item){c.set(selector+" #Icon.Visible",item!=null);if(item!=null)c.set(selector+" #Icon.ItemId",item);}
    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,Data data){
        if(data.action==null||isDismissed())return;
        store.getExternalData().getWorld().execute(()->{if(!ref.isValid()||isDismissed())return;try{
            SetupAccess.require(playerRef.getUuid());if(!SplineRoadTool.holding(ref,store)||!session.world.equals(store.getExternalData().getWorld().getName())){close();return;}
            if(data.name!=null)name=data.name.length()>48?data.name.substring(0,48):data.name;
            String nextSearch=data.search==null?search:data.search.strip();if(nextSearch.length()>64)nextSearch=nextSearch.substring(0,64);
            String action=data.action;
            if(action.startsWith("Brush:")){int i=Integer.parseInt(action.substring(6));if(i<0||i>=visible.size())return;brush=visible.get(i).block();message="Click slots in the palette to add this material.";}
            else if(action.startsWith("Paint:")){var values=action.split(":");int col=Integer.parseInt(values[1]),row=Integer.parseInt(values[2]);if(col<0||col>=9||row<0||row>=6)return;columns.get(col).set(row,brush);message="Palette updated. Save when you are happy with the pattern.";}
            else switch(action){
                case "Erase"->{brush="";message="Click a slot to clear it. Empty columns borrow from their nearest filled column.";}
                case "Fill"->{if(brush.isEmpty())throw new IllegalArgumentException("Choose a paving block first");for(var column:columns){Collections.fill(column,"");column.set(0,brush);}message="All columns now use the selected block.";}
                case "SearchButton"->page=0;
                case "Previous"->page=Math.max(0,page-1);
                case "Next"->page++;
                case "Save"->{var style=new RoadStyle(id,name,revision+1,columns);SplineRoadTool.service().saveStyle(playerRef.getUuid(),style,revision);var styles=SplineRoadTool.service().styles();for(int i=0;i<styles.size();i++)if(styles.get(i).id().equals(id))session.style=i;session.invalidate();session.message="Style saved. Review the road to use it.";settings(ref,store);return;}
                case "Cancel"->{settings(ref,store);return;}
                default->{return;}
            }
            if(!search.equals(nextSearch)){search=nextSearch;page=0;}rebuild();
        }catch(Exception error){message=error.getMessage()==null?"The style could not be saved. Try again.":error.getMessage();rebuild();}});
    }
    private void settings(Ref<EntityStore> ref,Store<EntityStore> store){store.getComponent(ref,Player.getComponentType()).getPageManager().openCustomPage(ref,store,new SplineRoadSettingsPage(playerRef,session));}
    public static final class Data{
        public String action,name,search;
        public static final BuilderCodec<Data> CODEC=BuilderCodec.builder(Data.class,Data::new)
            .append(new KeyedCodec<>("Action",Codec.STRING),(d,v)->d.action=v,d->d.action).add()
            .append(new KeyedCodec<>("@Name",Codec.STRING),(d,v)->d.name=v,d->d.name).add()
            .append(new KeyedCodec<>("@Search",Codec.STRING),(d,v)->d.search=v,d->d.search).add().build();
    }
}
