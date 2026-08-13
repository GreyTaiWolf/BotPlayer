package io.github.greytaiwolf.botplayer.skill.pack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 仅供声明式 Skill Pack 使用的严格 JSON 子集解析器。
 *
 * <p>它拒绝重复键、注释、尾随内容、无效 Unicode、过深嵌套和过大容器；不会把 JSON 映射为
 * 任意 Java 类或调用反射。
 */
final class StrictJsonParser {
    static final int MAX_NESTING = 64;
    static final int MAX_OBJECT_MEMBERS = 4_096;
    static final int MAX_ARRAY_ITEMS = 16_384;

    private StrictJsonParser() {}

    static Value parse(String document) {
        return new Reader(Objects.requireNonNull(document, "document"))
                .parseDocument();
    }

    sealed interface Value permits ObjectValue, ArrayValue, StringValue,
            NumberValue, BooleanValue, NullValue {}

    record ObjectValue(Map<String, Value> fields) implements Value {
        ObjectValue {
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(fields, "fields")));
        }
    }

    record ArrayValue(List<Value> items) implements Value {
        ArrayValue {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }
    }

    record StringValue(String value) implements Value {
        StringValue {
            Objects.requireNonNull(value, "value");
        }
    }

    record NumberValue(String lexical) implements Value {
        NumberValue {
            Objects.requireNonNull(lexical, "lexical");
        }
    }

    record BooleanValue(boolean value) implements Value {}

    enum NullValue implements Value {
        INSTANCE
    }

    private static final class Reader {
        private final String document;
        private int index;

        private Reader(String document) {
            this.document = document;
        }

        private Value parseDocument() {
            skipWhitespace();
            Value value = readValue(0);
            skipWhitespace();
            if (!atEnd()) {
                throw invalid("document has trailing content");
            }
            return value;
        }

        private Value readValue(int depth) {
            if (depth > MAX_NESTING) {
                throw invalid("document nesting exceeds maximum "
                        + MAX_NESTING);
            }
            skipWhitespace();
            if (atEnd()) {
                throw invalid("document ends before a value");
            }
            return switch (current()) {
                case '{' -> readObject(depth + 1);
                case '[' -> readArray(depth + 1);
                case '"' -> new StringValue(readString());
                case 't' -> readLiteral("true", new BooleanValue(true));
                case 'f' -> readLiteral("false", new BooleanValue(false));
                case 'n' -> readLiteral("null", NullValue.INSTANCE);
                case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' ->
                        new NumberValue(readNumber());
                default -> throw invalid("unexpected value token");
            };
        }

        private ObjectValue readObject(int depth) {
            expect('{');
            skipWhitespace();
            Map<String, Value> fields = new LinkedHashMap<>();
            if (consume('}')) {
                return new ObjectValue(fields);
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || current() != '"') {
                    throw invalid("object field name must be a string");
                }
                String name = readString();
                skipWhitespace();
                expect(':');
                Value previous = fields.putIfAbsent(name, readValue(depth));
                if (previous != null) {
                    throw invalid("object contains a duplicate field");
                }
                if (fields.size() > MAX_OBJECT_MEMBERS) {
                    throw invalid("object member count exceeds maximum "
                            + MAX_OBJECT_MEMBERS);
                }
                skipWhitespace();
                if (consume('}')) {
                    return new ObjectValue(fields);
                }
                expect(',');
            }
        }

        private ArrayValue readArray(int depth) {
            expect('[');
            skipWhitespace();
            List<Value> items = new ArrayList<>();
            if (consume(']')) {
                return new ArrayValue(items);
            }
            while (true) {
                items.add(readValue(depth));
                if (items.size() > MAX_ARRAY_ITEMS) {
                    throw invalid("array item count exceeds maximum "
                            + MAX_ARRAY_ITEMS);
                }
                skipWhitespace();
                if (consume(']')) {
                    return new ArrayValue(items);
                }
                expect(',');
            }
        }

        private String readString() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (!atEnd()) {
                char character = document.charAt(index++);
                if (character == '"') {
                    return value.toString();
                }
                if (character == '\\') {
                    appendEscape(value);
                    continue;
                }
                if (character < 0x20) {
                    throw invalid("string contains a control character");
                }
                value.append(character);
            }
            throw invalid("string is not terminated");
        }

        private void appendEscape(StringBuilder value) {
            if (atEnd()) {
                throw invalid("string escape is not terminated");
            }
            char escaped = document.charAt(index++);
            switch (escaped) {
                case '"' -> value.append('"');
                case '\\' -> value.append('\\');
                case '/' -> value.append('/');
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> appendUnicode(value);
                default -> throw invalid("string has an invalid escape");
            }
        }

        private void appendUnicode(StringBuilder value) {
            char first = (char) readHexCodeUnit();
            if (Character.isLowSurrogate(first)) {
                throw invalid("string contains an unpaired low surrogate");
            }
            if (!Character.isHighSurrogate(first)) {
                value.append(first);
                return;
            }
            if (atEnd() || document.charAt(index++) != '\\'
                    || atEnd() || document.charAt(index++) != 'u') {
                throw invalid("string contains an unpaired high surrogate");
            }
            char second = (char) readHexCodeUnit();
            if (!Character.isLowSurrogate(second)) {
                throw invalid("string contains an invalid surrogate pair");
            }
            value.append(first).append(second);
        }

        private int readHexCodeUnit() {
            if (document.length() - index < 4) {
                throw invalid("unicode escape is incomplete");
            }
            int result = 0;
            for (int count = 0; count < 4; count++) {
                int digit = Character.digit(document.charAt(index++), 16);
                if (digit < 0) {
                    throw invalid("unicode escape is not hexadecimal");
                }
                result = (result << 4) | digit;
            }
            return result;
        }

        private String readNumber() {
            int begin = index;
            consume('-');
            if (atEnd()) {
                throw invalid("number is incomplete");
            }
            if (consume('0')) {
                if (!atEnd() && isDigit(current())) {
                    throw invalid("number must not have a leading zero");
                }
            } else if (!atEnd() && isNonZeroDigit(current())) {
                do {
                    index++;
                } while (!atEnd() && isDigit(current()));
            } else {
                throw invalid("number is missing its integer part");
            }
            if (consume('.')) {
                requireDigits("number fraction is incomplete");
            }
            if (consume('e') || consume('E')) {
                if (!atEnd() && (current() == '+' || current() == '-')) {
                    index++;
                }
                requireDigits("number exponent is incomplete");
            }
            return document.substring(begin, index);
        }

        private void requireDigits(String message) {
            if (atEnd() || !isDigit(current())) {
                throw invalid(message);
            }
            do {
                index++;
            } while (!atEnd() && isDigit(current()));
        }

        private Value readLiteral(String literal, Value value) {
            if (!document.startsWith(literal, index)) {
                throw invalid("invalid literal");
            }
            index += literal.length();
            return value;
        }

        private void skipWhitespace() {
            while (!atEnd()) {
                char character = current();
                if (character != ' ' && character != '\t'
                        && character != '\n' && character != '\r') {
                    return;
                }
                index++;
            }
        }

        private void expect(char expected) {
            skipWhitespace();
            if (!consume(expected)) {
                throw invalid("expected '" + expected + "'");
            }
        }

        private boolean consume(char expected) {
            if (atEnd() || current() != expected) {
                return false;
            }
            index++;
            return true;
        }

        private boolean atEnd() {
            return index >= document.length();
        }

        private char current() {
            return document.charAt(index);
        }

        private static boolean isDigit(char value) {
            return value >= '0' && value <= '9';
        }

        private static boolean isNonZeroDigit(char value) {
            return value >= '1' && value <= '9';
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid strict JSON: " + message);
    }
}
