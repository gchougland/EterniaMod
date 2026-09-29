package com.hexvane.eterniamod.ui;

import com.hexvane.eterniamod.domain.SeasonService;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CitadelPresentationTest {
    private static final Path UI=Path.of("src/main/resources/Common/UI/Custom/EterniaMod");

    @Test void markupStringsOnlyUseClientSupportedEscapes() throws Exception {
        // A single malformed document can prevent other received UI documents from loading.
        // Unlike Java strings, native .ui strings do not support backslash-n/t/r escapes.
        var tokens=Pattern.compile("//[^\\r\\n]*|/\\*.*?\\*/|\"(?:\\\\.|[^\"\\\\])*\"",Pattern.DOTALL);
        try(var files=Files.walk(UI)) {
            for(var file:files.filter(path->path.toString().endsWith(".ui")).toList()) {
                var source=Files.readString(file);var matcher=tokens.matcher(source);
                while(matcher.find()) {
                    String token=matcher.group();if(!token.startsWith("\""))continue;
                    for(int i=1;i<token.length()-1;i++)if(token.charAt(i)=='\\') {
                        char escaped=token.charAt(++i);
                        assertTrue(escaped=='\\'||escaped=='"',file+":"+(source.substring(0,matcher.start()+i).lines().count())+": unsupported UI escape \\"+escaped);
                    }
                }
            }
        }
    }

    @Test void appendedDocumentsExistInProcessedResources() throws Exception {
        var append=Pattern.compile("\\bappend\\(\"(EterniaMod/[^\"]+\\.ui)\"\\)");int checked=0;
        try(var files=Files.walk(Path.of("src/main/java"))) {
            for(var file:files.filter(path->path.toString().endsWith(".java")).toList()) {
                var matcher=append.matcher(Files.readString(file));
                while(matcher.find()) {
                    String document=matcher.group(1);
                    try(var stream=getClass().getClassLoader().getResourceAsStream("Common/UI/Custom/"+document)) {
                        assertNotNull(stream,file+": missing packaged document "+document);
                        assertArrayEquals(Files.readAllBytes(UI.getParent().resolve(document)),stream.readAllBytes(),document+": stale processed resource");
                    }
                    checked++;
                }
            }
        }
        assertTrue(checked>=10,"Inspect the actual append paths, including the Crown Store and road HUD");
    }

    @Test void dynamicLayoutNeverTargetsUnsupportedNestedAnchorProperties() throws Exception {
        // The server accepts these strings, but the client disconnects on Anchor.Height/Width.
        // Anchor is one typed UI property; its complete value must be sent with setObject.
        var nestedAnchor=Pattern.compile("\"[^\"\\r\\n]*\\.Anchor\\.[A-Za-z]+");
        try(var files=Files.walk(Path.of("src/main/java"))) {
            for(var file:files.filter(path->path.toString().endsWith(".java")).toList()) {
                assertFalse(nestedAnchor.matcher(Files.readString(file)).find(),file+": unsupported nested anchor selector");
            }
        }
    }

    @Test void everyLiteralLabelAlignmentUsesHytalesMarkupValues() throws Exception {
        // Label alignment uses Start/Center/End, independently of LayoutMode's Left/Right.
        // A native server can serialize UI commands without parsing the client markup.
        var alignment=Pattern.compile("\\b(?:HorizontalAlignment|VerticalAlignment)\\s*:\\s*([A-Za-z]+)\\b");
        var allowed=Set.of("Start","Center","End");int checked=0;
        try(var files=Files.walk(UI)) {
            for(var file:files.filter(path->path.toString().endsWith(".ui")).toList()) {
                var values=alignment.matcher(Files.readString(file));
                while(values.find()) {
                    assertTrue(allowed.contains(values.group(1)),file+": unsupported label alignment "+values.group(1));
                    checked++;
                }
            }
        }
        assertTrue(checked>0,"The markup audit must inspect label alignment declarations");
    }

    @Test void questCopyDescribesTheCountAndDistinctResourceGoal() {
        var hunt=new SeasonService.Quest("eternia:hunt",SeasonService.ActivityKind.KILL,SeasonService.ObjectiveKind.COUNT,Set.of(),50,500);
        var gather=new SeasonService.Quest("eternia:gather",SeasonService.ActivityKind.ACQUIRE,SeasonService.ObjectiveKind.DISTINCT,Set.of(),8,400);
        assertEquals("Defeat 50 creatures",UiPresentation.questGoal(hunt));
        assertEquals("Gather 8 different types of resources",UiPresentation.questGoal(gather));
        assertEquals("On patrol",UiPresentation.questTitle(hunt));
        assertFalse(UiPresentation.questGoal(hunt).contains(hunt.id()));
    }

    @Test void fallbackNamesHideStorageSyntaxAndHandleEmptySegments() {
        assertEquals("Royal Oak Chair",UiPresentation.friendlyId("eternia:prop/royal_oak_chair"));
        assertEquals("Plot Deed",UiPresentation.friendlyId("Eternia_PlotDeed"));
        assertEquals("Unknown item",UiPresentation.friendlyId("eternia:..."));
        assertEquals("Remove estate blocks",UiPresentation.capability("housing.block.break"));
    }

    @Test void everyServiceBodyProvidesTheSharedBindingsAndItsPurposeSpecificSurface() throws Exception {
        var common=List.of("SectionTitle","Description","Tabs","Rows","Status","Actions","Primary","Secondary","Tertiary","Fields","FieldOne","FieldTwo","FieldThree","FieldOneGroup","FieldTwoGroup","FieldThreeGroup");
        for(String layout:List.of("Directory","Overview","Roster","Mail","Season","Trade","Collection")) {
            String source=Files.readString(UI.resolve("Services"+layout+".ui"));
            for(String id:common)assertTrue(Pattern.compile("#"+id+"\\s*\\{").matcher(source).find(),layout+" is missing "+id);
            assertTrue(source.contains("TopScrolling"),layout+" must scroll overflowing content");
        }
        assertTrue(Files.readString(UI.resolve("ServicesMail.ui")).contains("MultilineTextField #FieldThree"));
        assertTrue(Files.readString(UI.resolve("ServicesTrade.ui")).contains("#TheirOffer"));
        assertTrue(Files.readString(UI.resolve("RewardLevel.ui")).contains("FREE TRACK"));
        assertTrue(Files.readString(UI.resolve("RewardLevel.ui")).contains("PAID TRACK"));
        assertTrue(Files.readString(UI.resolve("QuestRow.ui")).contains("#QuestGoal"));
        for(String name:List.of("housing","guild","mail","season","collection","worlds","crown")) {
            byte[] png=Files.readAllBytes(UI.resolve("Icons/"+name+".png"));
            assertEquals(137,Byte.toUnsignedInt(png[0]));
            assertTrue(Files.readString(UI.resolve("Icons/"+name+".svg")).contains("viewBox=\"0 0 128 128\""));
        }
    }

    @Test void homeShortcutsHaveUniqueSelectorsAndPackagedIconStates() throws Exception {
        for (String page : List.of("ChoicePage", "ServicesPage", "PremiumShopPage", "GuildBoardPage", "PlotClaimPage",
            "BuildingPlacementPage", "PropPlacementPage", "BuildingPickupPage", "PrefabBrowserPage", "CustomizationPreview", "SplineRoadSettings")) {
            String source = Files.readString(UI.resolve(page + ".ui"));
            assertEquals(1, source.split("#MenuHome", -1).length - 1, page + " needs exactly one home target");
            assertTrue(source.contains("$T.@HomeButton #MenuHome"), page);
        }
        for (String state : List.of("", "Hover", "Pressed")) {
            try (var input = getClass().getClassLoader().getResourceAsStream("Common/UI/Custom/EterniaMod/Theme/Surfaces/Home" + state + ".png")) {
                assertNotNull(input);
                var image = javax.imageio.ImageIO.read(input);
                assertEquals(44, image.getWidth()); assertEquals(44, image.getHeight());
            }
        }
    }

    @Test void crownStoreKeepsLongCatalogsAndConfirmationDetailsScrollable() throws Exception {
        String page=Files.readString(UI.resolve("PremiumShopPage.ui"));
        for(String id:List.of("Cards","Categories","PurchaseDetails"))assertTrue(Pattern.compile("Group #"+id+"\\s*\\{[^}]*LayoutMode: TopScrolling",Pattern.DOTALL).matcher(page).find(),id+" must scroll");
        for(String id:List.of("Balance","Notice","Catalog","ConfirmView","SelectedName","SelectedDescription","SelectedPrice","Back","Purchase","GetCrowns","Close"))assertTrue(page.contains("#"+id+" "),id);
        String card=Files.readString(UI.resolve("PremiumCard.ui"));
        for(String id:List.of("ItemCategory","ItemName","ItemDescription","Price","Review"))assertTrue(card.contains("#"+id+" "),id);
        assertTrue(220>=UiPresentation.textWidth("Confirm purchase")+32);
        assertTrue(200>=UiPresentation.textWidth("Keep browsing")+32);
        assertTrue(Files.readString(UI.resolve("PremiumCategory.ui")).contains("Width: 184"));
        assertTrue(page.contains("Icons/crown.png"));
    }

    @Test void fixedButtonsAndTheOldPlacementPanelsHaveRoomForTheirCaptions() throws Exception {
        var button=Pattern.compile("(?:\\$T\\.@\\w*TextButton|\\bTextButton)\\s*(?:#\\w+)?\\s*\\{([^{}]*)}",Pattern.DOTALL);
        var width=Pattern.compile("Width:\\s*(\\d+)");var label=Pattern.compile("\\bText:\\s*\"([^\"]+)\"");int checked=0;
        try(var files=Files.walk(UI)) {
            for(var file:files.filter(path->path.toString().endsWith(".ui")).toList()) {
                var buttons=button.matcher(Files.readString(file));while(buttons.find()) {
                    var w=width.matcher(buttons.group(1));var text=label.matcher(buttons.group(1));
                    if(w.find()&&text.find()&&!text.group(1).equals(".")) {
                        assertTrue(Integer.parseInt(w.group(1))>=UiPresentation.textWidth(text.group(1))+24,file.getFileName()+": "+text.group(1));checked++;
                    }
                }
            }
        }
        assertTrue(checked>=15,"The fixed-width audit must cover the packaged buttons");
        for(String caption:List.of("Confirm claim","Review claim","Return to player","Bird’s-eye view","Change shape","Larger size","At my feet"))assertTrue(UiPresentation.textWidth(caption)+32<=(480-44-8)/2,caption);
        for(String caption:List.of("West","North","South","East"))assertTrue(UiPresentation.textWidth(caption)+32<=(480-44-24)/4,caption);
        for(String caption:List.of("Place","Cancel"))assertTrue(UiPresentation.textWidth(caption)+32<=200,caption);
        assertTrue(UiPresentation.textWidth("Snap to me")+32<=448);
        assertTrue(UiPresentation.textWidth("Create Building")+32<=280);
        assertTrue(UiPresentation.textWidth("Edit own notice")+32<=340);
        assertTrue(UiPresentation.textWidth("Collect attachments")+32<=278);
        String claim=Files.readString(UI.resolve("PlotClaimPage.ui"));
        assertTrue(claim.contains("Height: 720"));assertTrue(28+54+30+56+56+26+52+60+98+54+44<=720-44,"All plot controls must fit inside the padded panel");
        assertTrue(Files.readString(UI.resolve("GuildBoardPage.ui")).contains("#PostList { LayoutMode: TopScrolling"));
        assertTrue(Files.readString(UI.resolve("GuildPostNav.ui")).contains("WrappedSecondaryButtonStyle"));
        assertTrue(Files.readString(UI.resolve("PrefabFileButton.ui")).contains("WrappedSecondaryButtonStyle"));
        assertTrue(Files.readString(UI.resolve("PremiumCategory.ui")).contains("WrappedSecondaryButtonStyle"));
    }
}
