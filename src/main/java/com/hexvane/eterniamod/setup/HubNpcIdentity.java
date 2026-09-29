package com.hexvane.eterniamod.setup;

import java.util.*;

/** Stable service roles and distinct authored characters; visual identity never changes service permissions. */
public enum HubNpcIdentity {
    GREETER("Eternia_Greeter","Prowl","Eternia Guide","Eternia_Prowl","greeter"),
    HOUSING("Eternia_Housing","Bramble Hearthleaf","Housing Steward","Eternia_Housing_Steward","housing"),
    GUILD("Eternia_Guild","Torren Ironbough","Guild Marshal","Eternia_Guild_Marshal","guild"),
    SHOP("Eternia_Shop","Nima Quicktail","Market Broker","Eternia_Market_Broker","shop"),
    STORE("Eternia_Store","Lyra Starweave","Royal Quartermaster","Eternia_Royal_Quartermaster","store"),
    MINIGAME("Eternia_Minigame","Pip Coppercap","Games Master","Eternia_Games_Master","minigame");
    public static final int REVISION=2;
    private final String role,name,profession,model,key;
    HubNpcIdentity(String role,String name,String profession,String model,String key){this.role=role;this.name=name;this.profession=profession;this.model=model;this.key=key;}
    public String role(){return role;} public String characterName(){return name;} public String profession(){return profession;} public String model(){return model;}
    public String nameplate(){return name+" ["+profession+"]";} public String label(){return name+" · "+profession;} public String hint(){return "eternia_services.interact."+key;}
    public static Optional<HubNpcIdentity> forRole(String role){return Arrays.stream(values()).filter(identity->identity.role.equals(role)).findFirst();}
}
