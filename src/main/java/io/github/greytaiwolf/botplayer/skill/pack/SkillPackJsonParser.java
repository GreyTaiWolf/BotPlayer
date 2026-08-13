package io.github.greytaiwolf.botplayer.skill.pack;

import io.github.greytaiwolf.botplayer.skill.core.SkillId;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.core.SkillVersion;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanEdge;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanNode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Skill Pack 唯一允许的 JSON 解码器。它只产生纯 descriptor/plan DTO，拒绝重复、未知
 * 或非标量字段，因此 JSON 永远不能携带类名、命令、动作或反射配置。
 */
public final class SkillPackJsonParser {
    private static final Set<String> FORBIDDEN_PARAMETER_NAMES = Set.of(
            "action",
            "class",
            "code",
            "command",
            "method",
            "reflection",
            "script");

    public SkillPackCandidate parse(SkillPackSource source) {
        Objects.requireNonNull(source, "source");
        try {
            StrictJsonParser.Value root = StrictJsonParser.parse(
                    decodeUtf8(source.bytes()));
            return new SkillPackCandidate(
                    readDefinition(object(root, "skill pack")), source);
        } catch (IllegalArgumentException exception) {
            throw invalid("schema or field validation failed", exception);
        }
    }

    private static SkillPackDefinition readDefinition(
            StrictJsonParser.ObjectValue object) {
        requireFields(
                object,
                Set.of("schemaVersion", "id", "version", "descriptors", "plan"),
                "skill pack");
        return new SkillPackDefinition(
                readInteger(field(object, "schemaVersion"), "schemaVersion"),
                SkillPackId.parse(readString(field(object, "id"), "id")),
                readVersion(readString(field(object, "version"), "version")),
                readDescriptors(array(field(object, "descriptors"), "descriptors")),
                readPlan(object(field(object, "plan"), "plan")));
    }

    private static Set<SkillPackDescriptorReference> readDescriptors(
            StrictJsonParser.ArrayValue array) {
        Set<SkillPackDescriptorReference> result = new LinkedHashSet<>();
        for (StrictJsonParser.Value value : array.items()) {
            StrictJsonParser.ObjectValue object = object(value, "descriptor");
            requireFields(object, Set.of("id", "version"), "descriptor");
            SkillPackDescriptorReference reference =
                    new SkillPackDescriptorReference(
                            SkillId.parse(readString(
                                    field(object, "id"), "descriptor id")),
                            readVersion(readString(
                                    field(object, "version"),
                                    "descriptor version")));
            if (!result.add(reference)) {
                throw invalid("descriptor declaration is duplicated");
            }
        }
        return Set.copyOf(result);
    }

    private static SkillPlan readPlan(StrictJsonParser.ObjectValue object) {
        requireFields(
                object,
                Set.of("id", "botId", "revision", "nodes", "edges"),
                "plan");
        return new SkillPlan(
                readUuid(field(object, "id"), "plan id"),
                readUuid(field(object, "botId"), "plan bot id"),
                readLong(field(object, "revision"), "plan revision"),
                readNodes(array(field(object, "nodes"), "plan nodes")),
                readEdges(array(field(object, "edges"), "plan edges")));
    }

    private static List<SkillPlanNode> readNodes(
            StrictJsonParser.ArrayValue array) {
        List<SkillPlanNode> result = new ArrayList<>();
        for (StrictJsonParser.Value value : array.items()) {
            StrictJsonParser.ObjectValue object = object(value, "plan node");
            requireFields(
                    object,
                    Set.of("id", "skill", "version", "parameters"),
                    "plan node");
            result.add(new SkillPlanNode(
                    readUuid(field(object, "id"), "node id"),
                    SkillId.parse(readString(
                            field(object, "skill"), "node skill")),
                    readVersion(readString(
                            field(object, "version"), "node version")),
                    readParameters(object(
                            field(object, "parameters"), "node parameters"))));
        }
        return List.copyOf(result);
    }

    private static List<SkillPlanEdge> readEdges(
            StrictJsonParser.ArrayValue array) {
        List<SkillPlanEdge> result = new ArrayList<>();
        for (StrictJsonParser.Value value : array.items()) {
            StrictJsonParser.ObjectValue object = object(value, "plan edge");
            requireFields(object, Set.of("prerequisite", "dependent"),
                    "plan edge");
            result.add(new SkillPlanEdge(
                    readUuid(field(object, "prerequisite"),
                            "edge prerequisite"),
                    readUuid(field(object, "dependent"),
                            "edge dependent")));
        }
        return List.copyOf(result);
    }

    private static SkillParameters readParameters(
            StrictJsonParser.ObjectValue object) {
        Map<String, Object> values = new LinkedHashMap<>();
        object.fields().forEach((name, value) -> {
            if (FORBIDDEN_PARAMETER_NAMES.contains(name)) {
                throw invalid("parameters contain a forbidden executable field");
            }
            values.put(name, readScalar(value));
        });
        return new SkillParameters(values);
    }

    private static Object readScalar(StrictJsonParser.Value value) {
        if (value instanceof StrictJsonParser.StringValue string) {
            return string.value();
        }
        if (value instanceof StrictJsonParser.BooleanValue bool) {
            return bool.value();
        }
        if (value instanceof StrictJsonParser.NumberValue number) {
            return number(number.lexical());
        }
        throw invalid(
                "parameters only allow scalar string/boolean/number values");
    }

    private static Object number(String value) {
        try {
            if (value.indexOf('.') < 0
                    && value.indexOf('e') < 0
                    && value.indexOf('E') < 0) {
                long integer = Long.parseLong(value);
                if (integer >= Integer.MIN_VALUE
                        && integer <= Integer.MAX_VALUE) {
                    return Integer.valueOf((int) integer);
                }
                return Long.valueOf(integer);
            }
            double decimal = Double.parseDouble(value);
            if (!Double.isFinite(decimal)) {
                throw invalid("number must be finite");
            }
            return decimal;
        } catch (NumberFormatException exception) {
            throw invalid("number is outside supported range", exception);
        }
    }

    private static String readString(
            StrictJsonParser.Value value, String subject) {
        if (value instanceof StrictJsonParser.StringValue string) {
            return string.value();
        }
        throw invalid(subject + " must be a JSON string");
    }

    private static int readInteger(
            StrictJsonParser.Value value, String subject) {
        long parsed = readLong(value, subject);
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
            throw invalid(subject + " is outside 32-bit range");
        }
        return (int) parsed;
    }

    private static long readLong(
            StrictJsonParser.Value value, String subject) {
        if (!(value instanceof StrictJsonParser.NumberValue number)) {
            throw invalid(subject + " must be a JSON integer");
        }
        String lexical = number.lexical();
        if (lexical.indexOf('.') >= 0
                || lexical.indexOf('e') >= 0
                || lexical.indexOf('E') >= 0) {
            throw invalid(subject + " must not use a decimal or exponent");
        }
        try {
            return Long.parseLong(lexical);
        } catch (NumberFormatException exception) {
            throw invalid(subject + " is outside 64-bit range", exception);
        }
    }

    private static UUID readUuid(
            StrictJsonParser.Value value, String subject) {
        try {
            UUID parsed = UUID.fromString(readString(value, subject));
            if (parsed.getMostSignificantBits() == 0L
                    && parsed.getLeastSignificantBits() == 0L) {
                throw invalid(subject + " must not be zero UUID");
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw invalid(subject + " is not a valid UUID", exception);
        }
    }

    private static SkillVersion readVersion(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 3) {
            throw invalid("version must use major.minor.patch");
        }
        try {
            return new SkillVersion(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]));
        } catch (IllegalArgumentException exception) {
            throw invalid("version components are invalid", exception);
        }
    }

    private static StrictJsonParser.ObjectValue object(
            StrictJsonParser.Value value, String subject) {
        if (value instanceof StrictJsonParser.ObjectValue object) {
            return object;
        }
        throw invalid(subject + " must be a JSON object");
    }

    private static StrictJsonParser.ArrayValue array(
            StrictJsonParser.Value value, String subject) {
        if (value instanceof StrictJsonParser.ArrayValue array) {
            return array;
        }
        throw invalid(subject + " must be a JSON array");
    }

    private static StrictJsonParser.Value field(
            StrictJsonParser.ObjectValue object, String name) {
        StrictJsonParser.Value value = object.fields().get(name);
        if (value == null) {
            throw invalid("required field is missing");
        }
        return value;
    }

    private static void requireFields(
            StrictJsonParser.ObjectValue object,
            Set<String> required,
            String subject) {
        if (!object.fields().keySet().equals(required)) {
            throw invalid(subject + " has an unknown or missing field");
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            if (value.startsWith("\ufeff")) {
                throw invalid("UTF-8 byte-order mark is not allowed");
            }
            return value;
        } catch (CharacterCodingException exception) {
            throw invalid("source is not valid UTF-8", exception);
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(
                "Invalid skill pack JSON: " + message);
    }

    private static IllegalArgumentException invalid(
            String message, Throwable cause) {
        return new IllegalArgumentException(
                "Invalid skill pack JSON: " + message, cause);
    }
}
