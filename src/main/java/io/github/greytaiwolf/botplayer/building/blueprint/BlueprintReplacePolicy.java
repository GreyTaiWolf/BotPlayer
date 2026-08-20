package io.github.greytaiwolf.botplayer.building.blueprint;

/**
 * Future replacement intent for a cell.
 *
 * <p>P5D-A0 only records this metadata. No current code performs a replacement, removes a block,
 * or treats any value as authority to alter a world.
 */
public enum BlueprintReplacePolicy {
    /** Existing world content must be preserved. */
    PRESERVE_EXISTING,
    /** A future route may replace only a temporary block whose ownership it can prove. */
    REPLACE_OWNED_TEMPORARY,
    /** A future route must obtain an explicit human confirmation before replacing anything. */
    REQUIRE_HUMAN_CONFIRMATION
}
