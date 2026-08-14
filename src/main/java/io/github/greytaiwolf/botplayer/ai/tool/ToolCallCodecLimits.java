package io.github.greytaiwolf.botplayer.ai.tool;

/** ToolCallCodec 的调用方可收紧、不能放宽到硬上限之外的解析预算。 */
public record ToolCallCodecLimits(
        int maximumPlanBytes,
        int maximumArgumentBytes,
        int maximumCalls,
        int maximumDepth,
        int maximumObjectMembers,
        int maximumArrayEntries,
        int maximumParameterNameCharacters,
        int maximumStringCharacters) {
    public static final int HARD_MAX_PLAN_BYTES = 262_144;
    public static final int HARD_MAX_ARGUMENT_BYTES = 65_536;
    public static final int HARD_MAX_CALLS = ProposedSkillPlan.MAX_TOOL_CALLS;
    public static final int HARD_MAX_DEPTH = ToolValue.MAX_TREE_DEPTH;
    public static final int HARD_MAX_OBJECT_MEMBERS = ProposedToolCall.MAX_ARGUMENTS;
    public static final int HARD_MAX_ARRAY_ENTRIES = ToolValue.MAX_COLLECTION_ENTRIES;
    public static final int HARD_MAX_PARAMETER_NAME_CHARACTERS = 64;
    public static final int HARD_MAX_STRING_CHARACTERS = ToolValue.MAX_STRING_CHARACTERS;

    public ToolCallCodecLimits {
        requireRange(maximumPlanBytes, 1, HARD_MAX_PLAN_BYTES, "maximumPlanBytes");
        requireRange(maximumArgumentBytes, 1, HARD_MAX_ARGUMENT_BYTES,
                "maximumArgumentBytes");
        if (maximumArgumentBytes > maximumPlanBytes) {
            throw new IllegalArgumentException(
                    "maximumArgumentBytes must not exceed maximumPlanBytes");
        }
        requireRange(maximumCalls, 1, HARD_MAX_CALLS, "maximumCalls");
        requireRange(maximumDepth, 1, HARD_MAX_DEPTH, "maximumDepth");
        requireRange(maximumObjectMembers, 1, HARD_MAX_OBJECT_MEMBERS,
                "maximumObjectMembers");
        requireRange(maximumArrayEntries, 1, HARD_MAX_ARRAY_ENTRIES,
                "maximumArrayEntries");
        requireRange(maximumParameterNameCharacters, 1,
                HARD_MAX_PARAMETER_NAME_CHARACTERS,
                "maximumParameterNameCharacters");
        requireRange(maximumStringCharacters, 1,
                HARD_MAX_STRING_CHARACTERS,
                "maximumStringCharacters");
    }

    public static ToolCallCodecLimits defaults() {
        return new ToolCallCodecLimits(
                131_072,
                16_384,
                16,
                12,
                32,
                32,
                64,
                8_192);
    }

    private static void requireRange(
            int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum);
        }
    }
}
