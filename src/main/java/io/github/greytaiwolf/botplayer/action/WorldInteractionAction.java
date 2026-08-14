package io.github.greytaiwolf.botplayer.action;

import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import java.util.Objects;
import java.util.Set;

public record WorldInteractionAction(WorldInteractionActionSpec spec) implements ActionRequest {
   public WorldInteractionAction(WorldInteractionActionSpec spec) {
      Objects.requireNonNull(spec, "spec");
      this.spec = spec;
   }

   @Override
   public ActionKind kind() {
      return switch (this.spec.kind()) {
         case SELECT_HOTBAR -> ActionKind.SELECT_HOTBAR;
         case SWAP_INVENTORY_HOTBAR -> ActionKind.SWAP_INVENTORY_HOTBAR;
         case INVENTORY_MENU_SWAP -> ActionKind.INVENTORY_MENU_SWAP;
         case WORLD_MENU_TRANSACTION -> ActionKind.WORLD_MENU_TRANSACTION;
         case WORLD_MENU_TRANSFER -> ActionKind.WORLD_MENU_TRANSFER;
         case WORLD_MENU_RECIPE -> ActionKind.WORLD_MENU_RECIPE;
         case WORLD_VILLAGER_TRADE -> ActionKind.WORLD_VILLAGER_TRADE;
         case USE_ITEM -> ActionKind.USE_ITEM;
         case RELEASE_USE -> ActionKind.RELEASE_USE;
         case USE_ON_BLOCK -> ActionKind.USE_ON_BLOCK;
         case PLACE_BLOCK -> ActionKind.PLACE_BLOCK;
         case BREAK_BLOCK -> ActionKind.BREAK_BLOCK;
         case ATTACK_ENTITY -> ActionKind.ATTACK_ENTITY;
         case INTERACT_ENTITY -> ActionKind.INTERACT_ENTITY;
         case DROP_SELECTED -> ActionKind.DROP_SELECTED;
         case PICKUP_WAIT -> ActionKind.PICKUP_WAIT;
         case AIM_AND_PLACE_BLOCK -> ActionKind.AIM_AND_PLACE_BLOCK;
      };
   }

   @Override
   public Set<ActionChannel> channels() {
      return this.spec.channels();
   }
}
