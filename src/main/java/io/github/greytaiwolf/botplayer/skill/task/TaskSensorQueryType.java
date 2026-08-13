package io.github.greytaiwolf.botplayer.skill.task;

/**
 * P5A 允许的任务私有感知种类；新增种类必须经过独立的预算和安全审查。
 */
public enum TaskSensorQueryType {
    SELF_INVENTORY(false, true, false, false),
    TARGET_BLOCK(false, false, true, true),
    RESOURCE_CANDIDATES(true, false, true, true),
    DROPPED_ITEMS(true, false, false, true),
    THREATS(true, false, false, true),
    OPEN_MENU(false, true, false, false),
    WORKSTATION_STATE(false, true, true, false),
    RECIPE_FEASIBILITY(false, true, false, false);

    private final boolean permitsCandidates;
    private final boolean permitsSlots;
    private final boolean permitsBlocks;
    private final boolean permitsRays;

    TaskSensorQueryType(
            boolean permitsCandidates,
            boolean permitsSlots,
            boolean permitsBlocks,
            boolean permitsRays) {
        this.permitsCandidates = permitsCandidates;
        this.permitsSlots = permitsSlots;
        this.permitsBlocks = permitsBlocks;
        this.permitsRays = permitsRays;
    }

    boolean permitsCandidates() {
        return permitsCandidates;
    }

    boolean permitsSlots() {
        return permitsSlots;
    }

    boolean permitsBlocks() {
        return permitsBlocks;
    }

    boolean permitsRays() {
        return permitsRays;
    }
}
