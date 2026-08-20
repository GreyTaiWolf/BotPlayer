package io.github.greytaiwolf.botplayer.building.site.minecraft;

import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintCell;
import io.github.greytaiwolf.botplayer.building.site.ConstructionSiteBinding;
import io.github.greytaiwolf.botplayer.building.site.ConstructionSiteSurvey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Server-thread, loaded-world sampler for one exact candidate construction site.
 *
 * <p>This adapter reads only the canonical cells already present in a {@link ConstructionSiteBinding}.
 * It never requests chunks, creates tickets, queries nearby arbitrary positions, writes a block, creates a
 * lease, or schedules construction. An unloaded target, a target outside this level's build height, or an
 * unencodable third-party state becomes {@link ConstructionSiteSurvey.TargetState#UNKNOWN}.
 *
 * <p>The returned survey is only a point-in-time immutable observation. It is not site approval, material
 * availability, ownership proof, placement permission, a Technique, an Action, or a lifecycle integration.
 */
public final class MinecraftConstructionSiteSurveySampler {
    private MinecraftConstructionSiteSurveySampler() {}

    /**
     * Samples every and only canonical blueprint target in {@code binding} from this already-loaded level.
     *
     * <p>The call must occur on {@code level}'s authoritative server thread. The binding's dimension must
     * exactly equal the supplied level; a mismatch or off-thread invocation fails closed before a world-state
     * read occurs.
     *
     * @param level the authoritative server level to read without loading chunks
     * @param binding the immutable candidate binding whose exact blueprint targets are sampled
     * @return a complete immutable survey containing {@code UNKNOWN}, {@code EMPTY}, or full-state
     *         {@code OCCUPIED} evidence for every canonical target
     * @throws IllegalArgumentException if the binding belongs to a different dimension
     * @throws IllegalStateException if no authoritative server thread is available or its tick is invalid
     */
    public static ConstructionSiteSurvey sample(
            ServerLevel level, ConstructionSiteBinding binding) {
        ServerLevel checkedLevel = Objects.requireNonNull(level, "level");
        ConstructionSiteBinding checkedBinding = Objects.requireNonNull(binding, "binding");
        MinecraftServer server = requireServerThread(checkedLevel);
        ResourceId levelDimension = new ResourceId(
                checkedLevel.dimension().location().toString());
        if (!checkedBinding.anchor().dimension().equals(levelDimension)) {
            throw new IllegalArgumentException(
                    "construction site binding dimension must equal the sampled server level");
        }

        long observedTick = server.getTickCount();
        if (observedTick < 0L) {
            throw new IllegalStateException("server tick must not be negative");
        }
        List<BlueprintCell> cells = checkedBinding.workPlan().blueprint().cells();
        List<ConstructionSiteSurvey.TargetObservation> observations = new ArrayList<>(cells.size());
        for (BlueprintCell cell : cells) {
            observations.add(observe(checkedLevel, checkedBinding, cell));
        }
        return new ConstructionSiteSurvey(checkedBinding, observedTick, observations);
    }

    private static ConstructionSiteSurvey.TargetObservation observe(
            ServerLevel level,
            ConstructionSiteBinding binding,
            BlueprintCell cell) {
        BlockCoordinates coordinates = binding.targetPosition(cell.offset());
        BlockPos position = new BlockPos(
                coordinates.x(), coordinates.y(), coordinates.z());
        if (!isWithinBuildHeight(level, position) || !level.isLoaded(position)) {
            return ConstructionSiteSurvey.TargetObservation.unknown(cell.offset());
        }

        try {
            BlockState state = level.getBlockState(position);
            if (state.isAir()) {
                return ConstructionSiteSurvey.TargetObservation.empty(cell.offset());
            }
            return ConstructionSiteSurvey.TargetObservation.occupied(
                    cell.offset(), fingerprint(state));
        } catch (RuntimeException exception) {
            // A malformed mod state must never be promoted to a trusted occupied observation.
            return ConstructionSiteSurvey.TargetObservation.unknown(cell.offset());
        }
    }

    private static boolean isWithinBuildHeight(ServerLevel level, BlockPos position) {
        return position.getY() >= level.getMinBuildHeight()
                && position.getY() < level.getMaxBuildHeight();
    }

    private static BlockStateFingerprint fingerprint(BlockState state) {
        ResourceId blockId = new ResourceId(BuiltInRegistries.BLOCK
                .getKey(state.getBlock()).toString());
        Map<String, String> properties = new TreeMap<>();
        state.getValues().forEach((property, value) -> properties.put(
                property.getName(), propertyValueName(property, value)));
        return new BlockStateFingerprint(blockId, properties);
    }

    /**
     * Uses the property serializer rather than {@link Comparable#toString()}, which is not the canonical
     * Minecraft block-state representation for every enum-backed property.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyValueName(Property property, Comparable value) {
        return property.getName(value);
    }

    private static MinecraftServer requireServerThread(ServerLevel level) {
        MinecraftServer server = level.getServer();
        if (server == null || !server.isSameThread()) {
            throw new IllegalStateException(
                    "construction site sampling requires the authoritative server thread");
        }
        return server;
    }
}
