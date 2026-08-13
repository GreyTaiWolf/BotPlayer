package io.github.greytaiwolf.botplayer.skill.ai;

import io.github.greytaiwolf.botplayer.skill.core.SkillId;
import io.github.greytaiwolf.botplayer.skill.core.SkillVersion;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Trusted one-to-one mapping from a model-visible tool to one registered P5 skill version.
 *
 * <p>Arguments deliberately retain their names.  This first production bridge accepts only scalar
 * values whose explicitly mapped names and types are accepted by the target
 * {@code SkillParameterSchema}; it does not offer a generic object mapper, expression language,
 * script hook, or model-chosen skill id.
 */
public record P5AiToolSkillBinding(
        String toolName,
        SkillId skillId,
        SkillVersion skillVersion,
        Map<String, String> parameterMappings,
        String summary) {
    public static final int MAX_SUMMARY_LENGTH = 512;

    private static final Pattern TOOL_NAME = Pattern.compile(
            "[A-Za-z0-9_.:/-]{1,128}");
    private static final Pattern TOOL_PARAMETER_NAME = Pattern.compile(
            "[A-Za-z][A-Za-z0-9_]{0,63}");
    private static final Pattern SKILL_PARAMETER_NAME = Pattern.compile(
            "[a-z][a-z0-9_.-]{0,63}");

    public P5AiToolSkillBinding {
        Objects.requireNonNull(toolName, "toolName");
        if (!TOOL_NAME.matcher(toolName).matches()) {
            throw new IllegalArgumentException("toolName is invalid");
        }
        skillId = Objects.requireNonNull(skillId, "skillId");
        skillVersion = Objects.requireNonNull(skillVersion, "skillVersion");
        Objects.requireNonNull(parameterMappings, "parameterMappings");
        Map<String, String> copiedMappings = new LinkedHashMap<>();
        java.util.HashSet<String> mappedTargets = new java.util.HashSet<>();
        for (Map.Entry<String, String> entry : parameterMappings.entrySet()) {
            String source = Objects.requireNonNull(entry.getKey(), "tool parameter name");
            String target = Objects.requireNonNull(entry.getValue(), "skill parameter name");
            if (!TOOL_PARAMETER_NAME.matcher(source).matches()
                    || !SKILL_PARAMETER_NAME.matcher(target).matches()
                    || !mappedTargets.add(target)
                    || copiedMappings.put(source, target) != null) {
                throw new IllegalArgumentException("parameter mapping is invalid");
            }
        }
        parameterMappings = Map.copyOf(copiedMappings);
        summary = requirePlainText(summary, "summary", MAX_SUMMARY_LENGTH);
    }

    /** Convenient exact-name mapping constructor for already lower-case compatible schemas. */
    public P5AiToolSkillBinding(
            String toolName,
            SkillId skillId,
            SkillVersion skillVersion,
            String summary) {
        this(toolName, skillId, skillVersion, Map.of(), summary);
    }

    private static String requirePlainText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is outside its allowed length");
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isISOControl(current)) {
                throw new IllegalArgumentException(name + " must not contain controls");
            }
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(name + " has an unpaired surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(name + " has an unpaired surrogate");
            }
        }
        return value;
    }

    /** Never include a prompt-facing summary in a diagnostic string. */
    @Override
    public String toString() {
        return "P5AiToolSkillBinding[toolName=" + toolName
                + ", skillId=" + skillId
                + ", skillVersion=" + skillVersion
                + ", parameterMappingCount=" + parameterMappings.size()
                + ", summaryLength=" + summary.length() + "]";
    }
}
