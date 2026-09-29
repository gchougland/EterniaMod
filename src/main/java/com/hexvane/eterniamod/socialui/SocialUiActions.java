package com.hexvane.eterniamod.socialui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** World-affecting transitions supplied by the root runtime; invoked only on the player's world thread. */
@FunctionalInterface
public interface SocialUiActions {
    enum Action { HOUSING_CLAIM, HOUSING_MANAGE, GUILD_HOUSING, WORLD_SELECT, HUB_TRAVEL, STORE_OPEN, SHOP_BROWSE, SHOP_MANAGE, ITEM_DESK, TRADE_DESK }
    record Result(boolean opened, String message) {
        public static Result openedPage() { return new Result(true, ""); }
        public static Result notice(String message) { return new Result(false, message); }
    }
    Result perform(Action action, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player);

    /** Must re-resolve the seller's active plot and validated visitor entrance before teleporting. */
    default Result visitShop(String listingId, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player) {
        return Result.notice("Shop visits are not available until a safe visitor entrance is configured.");
    }
    default Result composeMail(String recipient,String subject,String body,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player){return Result.notice("Parcel delivery is not available yet.");}
}
