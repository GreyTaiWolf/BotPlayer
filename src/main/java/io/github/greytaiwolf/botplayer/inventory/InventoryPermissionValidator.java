package io.github.greytaiwolf.botplayer.inventory;

import java.util.UUID;

@FunctionalInterface
public interface InventoryPermissionValidator {
   boolean canWriteInventory(UUID var1, UUID var2);
}
