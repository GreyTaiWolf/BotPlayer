package io.github.greytaiwolf.botplayer.ai.plan;

/**
 * P6's sole view of the P5 skill catalog.
 *
 * <p>The implementation must derive visibility from the current server authority, capabilities and
 * request policy. It must return at most {@link AiSkillCatalogQuery#maximumSkills()} static descriptors;
 * it must not expose handlers, live Minecraft objects, secret policy data or direct execution methods.
 */
@FunctionalInterface
public interface AiSkillCatalogPort {
    AiSkillCatalog visibleSkills(AiSkillCatalogQuery query);
}
