package io.github.greytaiwolf.botplayer.skill.pack;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SkillPackJsonParserTest {
    private static final String PATH =
            "data/botplayer/botplayer/skills/wood_to_stone.json";

    @Test
    void parsesOnlyTheDeclaredSchemaAndScalarParameters() {
        SkillPackCandidate candidate = new SkillPackJsonParser().parse(
                source(validJson("1")));

        Assertions.assertEquals(
                new SkillPackId("botplayer", "wood_to_stone"),
                candidate.definition().id());
        Assertions.assertEquals(1, candidate.definition().schemaVersion());
        Assertions.assertEquals(1, candidate.definition().plan().nodes().size());
        Assertions.assertEquals(
                1,
                candidate.definition().plan().nodes().get(0).parameters()
                        .value("count").orElseThrow());
        Assertions.assertEquals(
                SkillPackHash.sha256(source(validJson("1")).bytes()),
                candidate.revision().contentHash());
    }

    @Test
    void rejectsDuplicateUnknownAndExecutableLookingFields() {
        SkillPackJsonParser parser = new SkillPackJsonParser();
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(source(validJson("1").replace(
                        "\"schemaVersion\":1,",
                        "\"schemaVersion\":1,\"schemaVersion\":1,"))));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(source(validJson("1").replace(
                        "\"skill\":\"botplayer:resource/collect\","
                                + "\"version\":\"1.0.0\",",
                        "\"skill\":\"botplayer:resource/collect\","
                                + "\"class\":\"java.lang.Runtime\","
                                + "\"version\":\"1.0.0\","))));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(source(validJson("1").replace(
                        "\"parameters\":{\"count\":1}",
                        "\"parameters\":{\"class\":\"java.lang.Runtime\"}"))));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(source(validJson("1").replace(
                        "\"parameters\":{\"count\":1}",
                        "\"parameters\":{\"count\":1,\"count\":2}"))));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(source(validJson("1").replace(
                        "\"parameters\":{\"count\":1}",
                        "\"parameters\":{\"command\":[\"op\"]}"))));
    }

    @Test
    void rejectsMalformedUtf8AndByteOrderMarkBeforeJsonParsing() {
        SkillPackJsonParser parser = new SkillPackJsonParser();
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(new SkillPackSource(
                        PATH, new byte[] {(byte) 0xc3, 0x28})));
        byte[] text = validJson("1").getBytes(StandardCharsets.UTF_8);
        byte[] bom = new byte[text.length + 3];
        bom[0] = (byte) 0xef;
        bom[1] = (byte) 0xbb;
        bom[2] = (byte) 0xbf;
        System.arraycopy(text, 0, bom, 3, text.length);
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(new SkillPackSource(PATH, bom)));
    }

    private static SkillPackSource source(String document) {
        return new SkillPackSource(
                PATH, document.getBytes(StandardCharsets.UTF_8));
    }

    static String validJson(String count) {
        return "{"
                + "\"schemaVersion\":1,"
                + "\"id\":\"botplayer:wood_to_stone\","
                + "\"version\":\"1.0.0\","
                + "\"descriptors\":[{"
                + "\"id\":\"botplayer:resource/collect\","
                + "\"version\":\"1.0.0\"}],"
                + "\"plan\":{"
                + "\"id\":\"00000000-0000-0000-0000-000000000001\","
                + "\"botId\":\"00000000-0000-0000-0000-000000000002\","
                + "\"revision\":1,"
                + "\"nodes\":[{"
                + "\"id\":\"00000000-0000-0000-0000-000000000003\","
                + "\"skill\":\"botplayer:resource/collect\","
                + "\"version\":\"1.0.0\","
                + "\"parameters\":{\"count\":" + count + "}}],"
                + "\"edges\":[]}}";
    }
}
