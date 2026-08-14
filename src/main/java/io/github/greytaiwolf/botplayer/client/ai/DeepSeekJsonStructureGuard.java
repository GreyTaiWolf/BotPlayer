package io.github.greytaiwolf.botplayer.client.ai;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 在交给 Gson 前严格限制不可信 JSON 的语法和结构复杂度。
 *
 * <p>HTTP 字节上限不能阻止很小的深嵌 JSON 触发递归解析或大量 DOM 节点。此检查器拒绝宽松
 * 语法、重复键、孤立 surrogate、过深嵌套和过大容器；Gson 随后只负责构造已受限的 DOM 与字段
 * 语义。</p>
 */
final class DeepSeekJsonStructureGuard {
    static final int MAX_NESTING_DEPTH = 64;
    static final int MAX_STRUCTURAL_TOKENS = 8_192;
    private static final int MAX_CONTAINER_ENTRIES = 4_096;

    private DeepSeekJsonStructureGuard() {
    }

    static void requireDocument(String document) {
        new Reader(Objects.requireNonNull(document, "document")).parseDocument();
    }

    private static final class Reader {
        private final String document;
        private int index;
        private int structuralTokens;

        private Reader(String document) {
            this.document = document;
        }

        private void parseDocument() {
            skipWhitespace();
            readValue(0);
            skipWhitespace();
            if (!atEnd()) {
                throw invalid();
            }
        }

        private void readValue(int depth) {
            if (depth > MAX_NESTING_DEPTH) {
                throw invalid();
            }
            skipWhitespace();
            if (atEnd()) {
                throw invalid();
            }
            switch (current()) {
                case '{' -> readObject(depth + 1);
                case '[' -> readArray(depth + 1);
                case '"' -> readString(false);
                case 't' -> readLiteral("true");
                case 'f' -> readLiteral("false");
                case 'n' -> readLiteral("null");
                case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' ->
                        readNumber();
                default -> throw invalid();
            }
        }

        private void readObject(int depth) {
            if (depth > MAX_NESTING_DEPTH) {
                throw invalid();
            }
            expect('{');
            incrementStructuralTokens();
            skipWhitespace();
            if (consume('}')) {
                incrementStructuralTokens();
                return;
            }
            Set<String> names = new HashSet<>();
            while (true) {
                skipWhitespace();
                if (atEnd() || current() != '"') {
                    throw invalid();
                }
                String name = readString(true);
                if (!names.add(name) || names.size() > MAX_CONTAINER_ENTRIES) {
                    throw invalid();
                }
                expect(':');
                readValue(depth);
                skipWhitespace();
                if (consume('}')) {
                    incrementStructuralTokens();
                    return;
                }
                expect(',');
                incrementStructuralTokens();
            }
        }

        private void readArray(int depth) {
            if (depth > MAX_NESTING_DEPTH) {
                throw invalid();
            }
            expect('[');
            incrementStructuralTokens();
            skipWhitespace();
            if (consume(']')) {
                incrementStructuralTokens();
                return;
            }
            int entries = 0;
            while (true) {
                readValue(depth);
                entries++;
                if (entries > MAX_CONTAINER_ENTRIES) {
                    throw invalid();
                }
                skipWhitespace();
                if (consume(']')) {
                    incrementStructuralTokens();
                    return;
                }
                expect(',');
                incrementStructuralTokens();
            }
        }

        private String readString(boolean retain) {
            if (!consume('"')) {
                throw invalid();
            }
            StringBuilder value = retain ? new StringBuilder() : null;
            while (!atEnd()) {
                char character = document.charAt(index++);
                if (character == '"') {
                    return retain ? value.toString() : "";
                }
                if (character == '\\') {
                    appendEscape(value);
                    continue;
                }
                if (character < 0x20) {
                    throw invalid();
                }
                if (Character.isHighSurrogate(character)) {
                    if (atEnd() || !Character.isLowSurrogate(current())) {
                        throw invalid();
                    }
                    append(value, character);
                    append(value, document.charAt(index++));
                    continue;
                }
                if (Character.isLowSurrogate(character)) {
                    throw invalid();
                }
                append(value, character);
            }
            throw invalid();
        }

        private void appendEscape(StringBuilder value) {
            if (atEnd()) {
                throw invalid();
            }
            char escaped = document.charAt(index++);
            switch (escaped) {
                case '"' -> append(value, '"');
                case '\\' -> append(value, '\\');
                case '/' -> append(value, '/');
                case 'b' -> append(value, '\b');
                case 'f' -> append(value, '\f');
                case 'n' -> append(value, '\n');
                case 'r' -> append(value, '\r');
                case 't' -> append(value, '\t');
                case 'u' -> appendUnicode(value);
                default -> throw invalid();
            }
        }

        private void appendUnicode(StringBuilder value) {
            char first = (char) readHexCodeUnit();
            if (Character.isLowSurrogate(first)) {
                throw invalid();
            }
            if (!Character.isHighSurrogate(first)) {
                append(value, first);
                return;
            }
            if (!consume('\\') || !consume('u')) {
                throw invalid();
            }
            char second = (char) readHexCodeUnit();
            if (!Character.isLowSurrogate(second)) {
                throw invalid();
            }
            append(value, first);
            append(value, second);
        }

        private int readHexCodeUnit() {
            if (document.length() - index < 4) {
                throw invalid();
            }
            int result = 0;
            for (int count = 0; count < 4; count++) {
                int digit = Character.digit(document.charAt(index++), 16);
                if (digit < 0) {
                    throw invalid();
                }
                result = (result << 4) | digit;
            }
            return result;
        }

        private void readNumber() {
            consume('-');
            if (atEnd()) {
                throw invalid();
            }
            if (consume('0')) {
                if (!atEnd() && isDigit(current())) {
                    throw invalid();
                }
            } else if (!atEnd() && isNonZeroDigit(current())) {
                do {
                    index++;
                } while (!atEnd() && isDigit(current()));
            } else {
                throw invalid();
            }
            if (consume('.')) {
                requireDigits();
            }
            if (consume('e') || consume('E')) {
                if (!atEnd() && (current() == '+' || current() == '-')) {
                    index++;
                }
                requireDigits();
            }
        }

        private void requireDigits() {
            if (atEnd() || !isDigit(current())) {
                throw invalid();
            }
            do {
                index++;
            } while (!atEnd() && isDigit(current()));
        }

        private void readLiteral(String literal) {
            if (!document.startsWith(literal, index)) {
                throw invalid();
            }
            index += literal.length();
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
                throw invalid();
            }
        }

        private boolean consume(char expected) {
            if (atEnd() || current() != expected) {
                return false;
            }
            index++;
            return true;
        }

        private void incrementStructuralTokens() {
            if (structuralTokens >= MAX_STRUCTURAL_TOKENS) {
                throw invalid();
            }
            structuralTokens++;
        }

        private boolean atEnd() {
            return index >= document.length();
        }

        private char current() {
            return document.charAt(index);
        }

        private static void append(StringBuilder target, char value) {
            if (target != null) {
                target.append(value);
            }
        }

        private static boolean isDigit(char value) {
            return value >= '0' && value <= '9';
        }

        private static boolean isNonZeroDigit(char value) {
            return value >= '1' && value <= '9';
        }
    }

    private static DeepSeekProviderException invalid() {
        return new DeepSeekProviderException(
                DeepSeekFailureCode.INVALID_PROVIDER_RESPONSE);
    }
}
