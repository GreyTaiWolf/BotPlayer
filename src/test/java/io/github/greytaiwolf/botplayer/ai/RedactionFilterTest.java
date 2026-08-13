package io.github.greytaiwolf.botplayer.ai;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RedactionFilterTest {
    @Test
    void redactsHeadersAssignmentsQueryParametersAndStandaloneTokens() {
        String source = "Authorization: Bearer sk-live-1234567890; "
                + "api_key=another-secret&access_token=token-value; "
                + "jwt=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature";

        RedactionResult result = RedactionFilter.redact(source);

        Assertions.assertFalse(result.text().contains("1234567890"));
        Assertions.assertFalse(result.text().contains("another-secret"));
        Assertions.assertFalse(result.text().contains("token-value"));
        Assertions.assertFalse(result.text().contains("eyJhbGci"));
        Assertions.assertTrue(result.redactionCount() >= 4);
        Assertions.assertTrue(result.text().contains(RedactionFilter.REDACTED));
    }

    @Test
    void redactsOpaqueAuthorizationBasicAndSpacedSensitiveAssignments() {
        String source = "Authorization: Bearer opaque-bearer-value; "
                + "Proxy-Authorization: Basic opaque-basic-value; "
                + "api_key=opaque assigned value; "
                + "token: opaque-token-value";

        RedactionResult result = RedactionFilter.redact(source);

        Assertions.assertFalse(result.text().contains("opaque-bearer-value"));
        Assertions.assertFalse(result.text().contains("opaque-basic-value"));
        Assertions.assertFalse(result.text().contains("opaque assigned value"));
        Assertions.assertFalse(result.text().contains("opaque-token-value"));
        Assertions.assertTrue(result.redactionCount() >= 4);

        RedactionResult standaloneBasic = RedactionFilter.redact(
                "Basic opaque-basic-without-a-header");
        Assertions.assertFalse(standaloneBasic.text().contains(
                "opaque-basic-without-a-header"));
    }

    @Test
    void preservesSafeTextAndRedactsJsonFieldsWithoutRemovingStructure() {
        RedactionResult safe = RedactionFilter.redact(
                "玩家 Angelo 完成了安全任务。");
        Assertions.assertEquals("玩家 Angelo 完成了安全任务。", safe.text());
        Assertions.assertEquals(0, safe.redactionCount());

        RedactionResult json = RedactionFilter.redact(
                "{\"api_key\":\"sk-live-json-secret\",\"task\":\"mine\"}");
        Assertions.assertEquals(
                "{\"api_key\":\"[REDACTED]\",\"task\":\"mine\"}",
                json.text());
        Assertions.assertFalse(json.text().contains("json-secret"));
    }

    @Test
    void refusesOversizedLogTextAsOneRedactedValue() {
        RedactionResult result = RedactionFilter.redact(
                "x".repeat(RedactionFilter.MAX_INPUT_CHARACTERS + 1));

        Assertions.assertEquals(RedactionFilter.OVERSIZED, result.text());
        Assertions.assertEquals(1, result.redactionCount());
    }

    @Test
    void refusesExpansionThatWouldExceedTheSafeOutputBound() {
        String source = "key=x ".repeat(
                RedactionFilter.MAX_INPUT_CHARACTERS / 6);
        Assertions.assertTrue(
                source.length() <= RedactionFilter.MAX_INPUT_CHARACTERS);

        RedactionResult result = RedactionFilter.redact(source);

        Assertions.assertEquals(RedactionFilter.OVERSIZED, result.text());
        Assertions.assertTrue(result.redactionCount() >= 1);
    }
}
