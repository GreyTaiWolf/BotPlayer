package io.github.greytaiwolf.botplayer.ai.tool;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.github.greytaiwolf.botplayer.ai.AiFinishReason;
import io.github.greytaiwolf.botplayer.ai.AiRawToolCall;
import io.github.greytaiwolf.botplayer.ai.AiResponse;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 将 Provider 返回的 JSON 解析为不可执行的工具提案。
 *
 * <p>Codec 只处理语法、边界和 DTO 不变量。它不会读取世界、不会访问凭据，也不会把提案提交给
 * P5 Skill runtime；调用方仍必须经过 {@link ToolFirewall} 以及未来的会话、ACL、revision 和
 * 世界状态校验。
 */
public final class ToolCallCodec {
    private static final Set<String> PLAN_FIELDS = Set.of("toolCalls");
    private static final Set<String> CALL_FIELDS =
            Set.of("callId", "name", "arguments");
    private static final Pattern INTEGER =
            Pattern.compile("-?(?:0|[1-9][0-9]*)");

    private final ToolCallCodecLimits limits;

    public ToolCallCodec(ToolCallCodecLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public static ToolCallCodec strictDefaults() {
        return new ToolCallCodec(ToolCallCodecLimits.defaults());
    }

    public ToolCallCodecLimits limits() {
        return limits;
    }

    /**
     * 解码 Provider 已分离出的原始工具调用。
     *
     * <p>每个 {@link AiRawToolCall#argumentsJson()} 必须是一个受限 JSON 对象。Provider 的
     * 工具名称和调用 ID 依旧只是不可信文本。
     */
    public ProposedSkillPlan decode(AiResponse response)
            throws ToolCallCodecException {
        Objects.requireNonNull(response, "response");
        if (response.finishReason() != AiFinishReason.TOOL_CALLS) {
            throw new ToolCallCodecException(
                    ToolCallCodecFailure.INVALID_RESPONSE);
        }
        return decode(response.requestId(), response.toolCalls());
    }

    /**
     * 解码一个已经与请求关联、但尚未获授权的原始调用批次。
     */
    public ProposedSkillPlan decode(
            UUID requestId, List<AiRawToolCall> rawToolCalls)
            throws ToolCallCodecException {
        ToolChecks.requireNonZero(requestId, "requestId");
        Objects.requireNonNull(rawToolCalls, "rawToolCalls");
        if (rawToolCalls.isEmpty()) {
            throw new ToolCallCodecException(
                    ToolCallCodecFailure.MISSING_REQUIRED_FIELD);
        }
        if (rawToolCalls.size() > limits.maximumCalls()) {
            throw new ToolCallCodecException(
                    ToolCallCodecFailure.LIMIT_EXCEEDED);
        }

        List<ProposedToolCall> decoded = new ArrayList<>(rawToolCalls.size());
        Set<String> callIds = new HashSet<>();
        int totalBytes = 0;
        for (AiRawToolCall rawToolCall : rawToolCalls) {
            AiRawToolCall raw = Objects.requireNonNull(
                    rawToolCall, "rawToolCall");
            totalBytes = addBoundedBytes(totalBytes,
                    ToolChecks.utf8LengthAtMost(
                            raw.callId(), "callId", limits.maximumPlanBytes()),
                    limits.maximumPlanBytes());
            totalBytes = addBoundedBytes(totalBytes,
                    ToolChecks.utf8LengthAtMost(
                            raw.name(), "name", limits.maximumPlanBytes()),
                    limits.maximumPlanBytes());
            int argumentBytes = ToolChecks.utf8LengthAtMost(
                    raw.argumentsJson(), "argumentsJson",
                    limits.maximumArgumentBytes());
            if (argumentBytes > limits.maximumArgumentBytes()) {
                throw new ToolCallCodecException(
                        ToolCallCodecFailure.LIMIT_EXCEEDED);
            }
            totalBytes = addBoundedBytes(totalBytes, argumentBytes,
                    limits.maximumPlanBytes());
            if (!callIds.add(raw.callId())) {
                throw new ToolCallCodecException(
                        ToolCallCodecFailure.DUPLICATE_FIELD);
            }
            Map<String, ToolValue> arguments = parseArgumentObject(
                    raw.argumentsJson());
            try {
                decoded.add(new ProposedToolCall(
                        raw.callId(), raw.name(), arguments));
            } catch (IllegalArgumentException exception) {
                /* AiRawToolCall 的 callId 是 opaque text；跨过可信边界时必须稳定拒绝。 */
                throw new ToolCallCodecException(
                        ToolCallCodecFailure.INVALID_FIELD_NAME);
            }
        }
        return new ProposedSkillPlan(requestId, decoded);
    }

    /**
     * 解码严格的独立提案 JSON fixture。
     *
     * <p>唯一允许的形状为：
     *
     * <pre>
     * {"toolCalls":[{"callId":"...","name":"...","arguments":{...}}]}
     * </pre>
     *
     * <p>该入口用于固定 parser fixture；真实 Provider 接线可以使用上面的
     * {@link #decode(AiResponse)}，但两条路径共享同一值和预算规则。
     */
    public ProposedSkillPlan decodePlanJson(UUID requestId, String planJson)
            throws ToolCallCodecException {
        ToolChecks.requireNonZero(requestId, "requestId");
        requirePlanBytes(planJson);
        new PlanArgumentByteScanner(planJson, limits.maximumArgumentBytes())
                .verify();
        Parser parser = new Parser(planJson, limits);
        try {
            ProposedSkillPlan plan = parser.parsePlan(requestId);
            parser.requireEndDocument();
            return plan;
        } catch (IOException | IllegalStateException exception) {
            throw new ToolCallCodecException(
                    ToolCallCodecFailure.MALFORMED_JSON);
        }
    }

    private Map<String, ToolValue> parseArgumentObject(String argumentsJson)
            throws ToolCallCodecException {
        Parser parser = new Parser(argumentsJson, limits);
        try {
            Map<String, ToolValue> arguments = parser.parseArgumentRoot();
            parser.requireEndDocument();
            return arguments;
        } catch (IOException | IllegalStateException exception) {
            throw new ToolCallCodecException(
                    ToolCallCodecFailure.MALFORMED_JSON);
        }
    }

    private void requirePlanBytes(String planJson) throws ToolCallCodecException {
        int bytes = ToolChecks.utf8LengthAtMost(
                planJson, "planJson", limits.maximumPlanBytes());
        if (bytes > limits.maximumPlanBytes()) {
            throw new ToolCallCodecException(
                    ToolCallCodecFailure.LIMIT_EXCEEDED);
        }
    }

    private static int addBoundedBytes(int current, int addition, int maximum)
            throws ToolCallCodecException {
        if (addition > maximum - current) {
            throw new ToolCallCodecException(
                    ToolCallCodecFailure.LIMIT_EXCEEDED);
        }
        return current + addition;
    }

    /**
     * 为 fixture JSON 中每个 {@code arguments} 值保留与 Provider 原始参数相同的字节预算。
     *
     * <p>{@link JsonReader} 正确地把 JSON 解析成值，却不公开某个子树在原文中的边界；不能
     * 因为使用独立 fixture 入口就放松 {@code maximumArgumentBytes}。这个轻量扫描器只在主
     * parser 前记录顶层 {@code toolCalls[*].arguments} 的精确源码区间，仍由下方严格
     * JsonReader 负责字段、类型和 DTO 语义。扫描深度同样受硬上限约束，避免畸形包络在
     * 预算检查阶段耗尽栈。</p>
     */
    private static final class PlanArgumentByteScanner {
        /* 每层数组/对象会在 scanner 中多经过一次 value 分派。 */
        private static final int MAX_ENVELOPE_DEPTH =
                ToolValue.MAX_TREE_DEPTH * 2 + 8;

        private final String source;
        private final int maximumArgumentBytes;
        private int index;

        private PlanArgumentByteScanner(String source, int maximumArgumentBytes) {
            this.source = Objects.requireNonNull(source, "source");
            this.maximumArgumentBytes = maximumArgumentBytes;
        }

        private void verify() throws ToolCallCodecException {
            skipWhitespace();
            if (atEnd()) {
                throw malformed();
            }
            if (current() == '{') {
                scanPlanObject(1);
            } else {
                scanValue(1);
            }
            skipWhitespace();
            if (!atEnd()) {
                throw malformed();
            }
        }

        private void scanPlanObject(int depth) throws ToolCallCodecException {
            requireDepth(depth);
            expect('{');
            skipWhitespace();
            if (consume('}')) {
                return;
            }
            while (true) {
                String name = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                if ("toolCalls".equals(name) && !atEnd()
                        && current() == '[') {
                    scanToolCalls(depth + 1);
                } else {
                    scanValue(depth + 1);
                }
                skipWhitespace();
                if (consume('}')) {
                    return;
                }
                expect(',');
                skipWhitespace();
            }
        }

        private void scanToolCalls(int depth) throws ToolCallCodecException {
            requireDepth(depth);
            expect('[');
            skipWhitespace();
            if (consume(']')) {
                return;
            }
            while (true) {
                if (!atEnd() && current() == '{') {
                    scanToolCall(depth + 1);
                } else {
                    scanValue(depth + 1);
                }
                skipWhitespace();
                if (consume(']')) {
                    return;
                }
                expect(',');
                skipWhitespace();
            }
        }

        private void scanToolCall(int depth) throws ToolCallCodecException {
            requireDepth(depth);
            expect('{');
            skipWhitespace();
            if (consume('}')) {
                return;
            }
            while (true) {
                String name = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                int valueStart = index;
                scanValue(depth + 1);
                if ("arguments".equals(name)) {
                    int bytes = ToolChecks.utf8LengthRangeAtMost(
                            source, valueStart, index, maximumArgumentBytes);
                    if (bytes > maximumArgumentBytes) {
                        throw new ToolCallCodecException(
                                ToolCallCodecFailure.LIMIT_EXCEEDED);
                    }
                }
                skipWhitespace();
                if (consume('}')) {
                    return;
                }
                expect(',');
                skipWhitespace();
            }
        }

        private void scanValue(int depth) throws ToolCallCodecException {
            requireDepth(depth);
            skipWhitespace();
            if (atEnd()) {
                throw malformed();
            }
            switch (current()) {
                case '{' -> scanObject(depth + 1);
                case '[' -> scanArray(depth + 1);
                case '"' -> readString();
                case 't' -> expectLiteral("true");
                case 'f' -> expectLiteral("false");
                case 'n' -> expectLiteral("null");
                default -> scanNumber();
            }
        }

        private void scanObject(int depth) throws ToolCallCodecException {
            requireDepth(depth);
            expect('{');
            skipWhitespace();
            if (consume('}')) {
                return;
            }
            while (true) {
                readString();
                skipWhitespace();
                expect(':');
                scanValue(depth + 1);
                skipWhitespace();
                if (consume('}')) {
                    return;
                }
                expect(',');
                skipWhitespace();
            }
        }

        private void scanArray(int depth) throws ToolCallCodecException {
            requireDepth(depth);
            expect('[');
            skipWhitespace();
            if (consume(']')) {
                return;
            }
            while (true) {
                scanValue(depth + 1);
                skipWhitespace();
                if (consume(']')) {
                    return;
                }
                expect(',');
                skipWhitespace();
            }
        }

        private String readString() throws ToolCallCodecException {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (!atEnd()) {
                char character = source.charAt(index++);
                if (character == '"') {
                    return value.toString();
                }
                if (character < 0x20) {
                    throw malformed();
                }
                if (character != '\\') {
                    value.append(character);
                    continue;
                }
                if (atEnd()) {
                    throw malformed();
                }
                char escaped = source.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> value.append(escaped);
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> value.append(readUnicodeEscape());
                    default -> throw malformed();
                }
            }
            throw malformed();
        }

        private char readUnicodeEscape() throws ToolCallCodecException {
            if (index + 4 > source.length()) {
                throw malformed();
            }
            int codePoint = 0;
            for (int offset = 0; offset < 4; offset++) {
                int digit = Character.digit(source.charAt(index++), 16);
                if (digit < 0) {
                    throw malformed();
                }
                codePoint = (codePoint << 4) | digit;
            }
            return (char) codePoint;
        }

        private void scanNumber() throws ToolCallCodecException {
            consume('-');
            if (atEnd()) {
                throw malformed();
            }
            if (consume('0')) {
                // JSON forbids an extra digit after a leading zero; the enclosing delimiter check
                // below turns one into a malformed value.
            } else {
                requireDigitBetween('1', '9');
                while (!atEnd() && isDigit(current())) {
                    index++;
                }
            }
            if (consume('.')) {
                requireDigitBetween('0', '9');
                while (!atEnd() && isDigit(current())) {
                    index++;
                }
            }
            if (!atEnd() && (current() == 'e' || current() == 'E')) {
                index++;
                if (!atEnd() && (current() == '+' || current() == '-')) {
                    index++;
                }
                requireDigitBetween('0', '9');
                while (!atEnd() && isDigit(current())) {
                    index++;
                }
            }
        }

        private void expectLiteral(String literal) throws ToolCallCodecException {
            if (!source.startsWith(literal, index)) {
                throw malformed();
            }
            index += literal.length();
        }

        private void requireDigitBetween(char minimum, char maximum)
                throws ToolCallCodecException {
            if (atEnd() || current() < minimum || current() > maximum) {
                throw malformed();
            }
            index++;
        }

        private void requireDepth(int depth) throws ToolCallCodecException {
            if (depth > MAX_ENVELOPE_DEPTH) {
                throw new ToolCallCodecException(
                        ToolCallCodecFailure.LIMIT_EXCEEDED);
            }
        }

        private void expect(char expected) throws ToolCallCodecException {
            if (atEnd() || source.charAt(index) != expected) {
                throw malformed();
            }
            index++;
        }

        private boolean consume(char expected) {
            if (!atEnd() && source.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (!atEnd()) {
                char character = source.charAt(index);
                if (character != ' ' && character != '\n'
                        && character != '\r' && character != '\t') {
                    return;
                }
                index++;
            }
        }

        private boolean atEnd() {
            return index >= source.length();
        }

        private char current() {
            return source.charAt(index);
        }

        private static boolean isDigit(char character) {
            return character >= '0' && character <= '9';
        }

        private static ToolCallCodecException malformed() {
            return new ToolCallCodecException(
                    ToolCallCodecFailure.MALFORMED_JSON);
        }
    }

    /** 保持 JSON 读取状态与递归预算绑定的私有解析器。 */
    private static final class Parser {
        private final JsonReader reader;
        private final ToolCallCodecLimits limits;

        private Parser(String json, ToolCallCodecLimits limits) {
            this.reader = new JsonReader(new StringReader(
                    Objects.requireNonNull(json, "json")));
            this.limits = limits;
        }

        private ProposedSkillPlan parsePlan(UUID requestId)
                throws IOException, ToolCallCodecException {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw new ToolCallCodecException(
                        ToolCallCodecFailure.ROOT_NOT_OBJECT);
            }
            reader.beginObject();
            Set<String> seen = new HashSet<>();
            List<ProposedToolCall> calls = null;
            while (reader.hasNext()) {
                String field = reader.nextName();
                requireKnownUniqueField(field, PLAN_FIELDS, seen);
                if ("toolCalls".equals(field)) {
                    calls = parseCalls();
                }
            }
            reader.endObject();
            if (calls == null) {
                throw new ToolCallCodecException(
                        ToolCallCodecFailure.MISSING_REQUIRED_FIELD);
            }
            try {
                return new ProposedSkillPlan(requestId, calls);
            } catch (IllegalArgumentException exception) {
                throw new ToolCallCodecException(
                        ToolCallCodecFailure.DUPLICATE_FIELD);
            }
        }

        private List<ProposedToolCall> parseCalls()
                throws IOException, ToolCallCodecException {
            if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                throw new ToolCallCodecException(
                        ToolCallCodecFailure.TYPE_NOT_SUPPORTED);
            }
            reader.beginArray();
            List<ProposedToolCall> calls = new ArrayList<>();
            while (reader.hasNext()) {
                if (calls.size() >= limits.maximumCalls()) {
                    throw new ToolCallCodecException(
                            ToolCallCodecFailure.LIMIT_EXCEEDED);
                }
                calls.add(parseCall());
            }
            reader.endArray();
            if (calls.isEmpty()) {
                throw new ToolCallCodecException(
                        ToolCallCodecFailure.MISSING_REQUIRED_FIELD);
            }
            return List.copyOf(calls);
        }

        private ProposedToolCall parseCall()
                throws IOException, ToolCallCodecException {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw new ToolCallCodecException(
                        ToolCallCodecFailure.TYPE_NOT_SUPPORTED);
            }
            reader.beginObject();
            Set<String> seen = new HashSet<>();
            String callId = null;
            String name = null;
            Map<String, ToolValue> arguments = null;
            while (reader.hasNext()) {
                String field = reader.nextName();
                requireKnownUniqueField(field, CALL_FIELDS, seen);
                switch (field) {
                    case "callId" -> callId = readBoundedString();
                    case "name" -> name = readBoundedString();
                    case "arguments" -> arguments = parseObject(1);
                    default -> throw new ToolCallCodecException(
                            ToolCallCodecFailure.UNKNOWN_FIELD);
                }
            }
            reader.endObject();
            if (callId == null || name == null || arguments == null) {
                throw new ToolCallCodecException(
                        ToolCallCodecFailure.MISSING_REQUIRED_FIELD);
            }
            try {
                return new ProposedToolCall(callId, name, arguments);
            } catch (IllegalArgumentException exception) {
                throw new ToolCallCodecException(
                        ToolCallCodecFailure.INVALID_FIELD_NAME);
            }
        }

        private Map<String, ToolValue> parseArgumentRoot()
                throws IOException, ToolCallCodecException {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw new ToolCallCodecException(
                        ToolCallCodecFailure.ROOT_NOT_OBJECT);
            }
            return parseObject(1);
        }

        private Map<String, ToolValue> parseObject(int depth)
                throws IOException, ToolCallCodecException {
            requireDepth(depth);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw new ToolCallCodecException(
                        ToolCallCodecFailure.TYPE_NOT_SUPPORTED);
            }
            reader.beginObject();
            Map<String, ToolValue> values = new LinkedHashMap<>();
            while (reader.hasNext()) {
                if (values.size() >= limits.maximumObjectMembers()) {
                    throw new ToolCallCodecException(
                            ToolCallCodecFailure.LIMIT_EXCEEDED);
                }
                String name = reader.nextName();
                requireParameterName(name);
                if (values.containsKey(name)) {
                    throw new ToolCallCodecException(
                            ToolCallCodecFailure.DUPLICATE_FIELD);
                }
                values.put(name, parseValue(depth + 1));
            }
            reader.endObject();
            try {
                return new ToolValue.ObjectValue(values).values();
            } catch (IllegalArgumentException exception) {
                throw new ToolCallCodecException(
                        ToolCallCodecFailure.LIMIT_EXCEEDED);
            }
        }

        private ToolValue parseValue(int depth)
                throws IOException, ToolCallCodecException {
            requireDepth(depth);
            return switch (reader.peek()) {
                case NULL -> {
                    reader.nextNull();
                    yield new ToolValue.NullValue();
                }
                case BOOLEAN -> new ToolValue.BooleanValue(reader.nextBoolean());
                case STRING -> new ToolValue.StringValue(readBoundedString());
                case NUMBER -> parseInteger();
                case BEGIN_ARRAY -> parseArray(depth);
                case BEGIN_OBJECT -> new ToolValue.ObjectValue(parseObject(depth));
                default -> throw new ToolCallCodecException(
                        ToolCallCodecFailure.TYPE_NOT_SUPPORTED);
            };
        }

        private ToolValue parseInteger()
                throws IOException, ToolCallCodecException {
            String literal = reader.nextString();
            if (!INTEGER.matcher(literal).matches()) {
                throw new ToolCallCodecException(
                        ToolCallCodecFailure.TYPE_NOT_SUPPORTED);
            }
            try {
                return new ToolValue.IntegerValue(Long.parseLong(literal));
            } catch (NumberFormatException exception) {
                throw new ToolCallCodecException(
                        ToolCallCodecFailure.INVALID_INTEGER);
            }
        }

        private ToolValue.ArrayValue parseArray(int depth)
                throws IOException, ToolCallCodecException {
            requireDepth(depth);
            reader.beginArray();
            List<ToolValue> values = new ArrayList<>();
            while (reader.hasNext()) {
                if (values.size() >= limits.maximumArrayEntries()) {
                    throw new ToolCallCodecException(
                            ToolCallCodecFailure.LIMIT_EXCEEDED);
                }
                values.add(parseValue(depth + 1));
            }
            reader.endArray();
            try {
                return new ToolValue.ArrayValue(values);
            } catch (IllegalArgumentException exception) {
                throw new ToolCallCodecException(
                        ToolCallCodecFailure.LIMIT_EXCEEDED);
            }
        }

        private String readBoundedString()
                throws IOException, ToolCallCodecException {
            if (reader.peek() != JsonToken.STRING) {
                throw new ToolCallCodecException(
                        ToolCallCodecFailure.TYPE_NOT_SUPPORTED);
            }
            String value = reader.nextString();
            if (value.length() > limits.maximumStringCharacters()) {
                throw new ToolCallCodecException(
                        ToolCallCodecFailure.LIMIT_EXCEEDED);
            }
            if (!ToolChecks.hasOnlyUnicodeScalars(value)) {
                throw new ToolCallCodecException(
                        ToolCallCodecFailure.INVALID_STRING);
            }
            return value;
        }

        private void requireKnownUniqueField(
                String field, Set<String> allowed, Set<String> seen)
                throws ToolCallCodecException {
            if (!allowed.contains(field)) {
                throw new ToolCallCodecException(
                        ToolCallCodecFailure.UNKNOWN_FIELD);
            }
            if (!seen.add(field)) {
                throw new ToolCallCodecException(
                        ToolCallCodecFailure.DUPLICATE_FIELD);
            }
        }

        private void requireParameterName(String name)
                throws ToolCallCodecException {
            if (name.length() > limits.maximumParameterNameCharacters()) {
                throw new ToolCallCodecException(
                        ToolCallCodecFailure.LIMIT_EXCEEDED);
            }
            if (!ToolChecks.PARAMETER_NAME.matcher(name).matches()) {
                throw new ToolCallCodecException(
                        ToolCallCodecFailure.INVALID_FIELD_NAME);
            }
        }

        private void requireDepth(int depth) throws ToolCallCodecException {
            if (depth > limits.maximumDepth()) {
                throw new ToolCallCodecException(
                        ToolCallCodecFailure.LIMIT_EXCEEDED);
            }
        }

        private void requireEndDocument()
                throws IOException, ToolCallCodecException {
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new ToolCallCodecException(
                        ToolCallCodecFailure.MALFORMED_JSON);
            }
        }
    }
}
