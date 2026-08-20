package io.github.greytaiwolf.botplayer.building.blueprint;

/** Structural intent attached to one desired block state; it does not authorize placement. */
public enum BlueprintPlacementRole {
    FOUNDATION,
    STRUCTURE,
    ENCLOSURE,
    OPENING,
    ROOF,
    UTILITY,
    TEMPORARY_SUPPORT
}
