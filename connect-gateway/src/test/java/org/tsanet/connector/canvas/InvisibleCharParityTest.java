package org.tsanet.connector.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientResponseException;
import org.tsanet.connector.sdk.SdkTsanetGateway;

/**
 * Drift guard for a security decision that lives in two places: the canvas
 * converter's FORMAT_CHARS strip (CanvasController) and the SDK gateway's
 * problem+json detail cleanup (SdkTsanetGateway.problemDetail). Both must strip
 * the same invisible-character categories (Cntrl/Cf/Zl/Zp); this fixture covers
 * one representative per category so widening or narrowing either side alone
 * fails here instead of drifting silently (PR #8 QA round, blast-radius item 7:
 * duplication asserted by a check, not a comment). Narrowing is caught by the
 * INVISIBLES fixture (absence asserted); widening by the MUST_SURVIVE fixture
 * (presence asserted) — QA's round-2 mutation test showed the absence fixture
 * alone is blind to a class growing, which is the direction where the canvas
 * renders what the gateway rejects. The canvas side's documented deviation
 * (\r\n\t handled by whitespace normalization, \n reserved as the break
 * sentinel) is outside both fixtures on purpose. problemDetail is private;
 * reflection is the price of asserting the real method instead of a third copy
 * of the class.
 */
class InvisibleCharParityTest {

    private static final int[] INVISIBLES = {
            0x0007,  // Cntrl, C0 (BEL)
            0x009D,  // Cntrl, C1 (needs (?U) — the ASCII-only regression case)
            0x202E,  // Cf (RTL override, the headline attack)
            0x200B,  // Cf (zero-width space)
            0xFEFF,  // Cf (BOM / zero-width no-break)
            0x2028,  // Zl (line separator)
            0x2029,  // Zp (paragraph separator)
    };

    @Test
    void bothSanitizersStripEveryFixtureCategory() throws Exception {
        for (int codePoint : INVISIBLES) {
            String character = new String(Character.toChars(codePoint));
            String hex = "U+%04X".formatted(codePoint);

            String canvasRaw = String.join(" ",
                    CanvasController.htmlNoteParagraphs("a" + character + "b"));
            assertFalse(canvasRaw.contains(character),
                    hex + " must not survive the canvas converter (raw)");

            String canvasEntity = String.join(" ",
                    CanvasController.htmlNoteParagraphs("a&#" + codePoint + ";b<br>"));
            assertFalse(canvasEntity.contains(character),
                    hex + " must not survive the canvas converter (entity-minted)");

            String detail = problemDetail("a" + character + "b");
            assertNotNull(detail, hex + ": detail must survive as text, not be nulled");
            assertFalse(detail.contains(character),
                    hex + " must not survive problemDetail");
        }
    }

    private static final int[] MUST_SURVIVE = {
            0x00E9,  // é — letter: any widening that eats letters mangles prose
            0x2192,  // right arrow — symbol, pinned in partner-note tests too
            0x00A0,  // no-break space — Zs, deliberately OUTSIDE both classes:
                     // adding \p{Zs} to either side is the canonical silent widening
    };

    @Test
    void bothSanitizersPreserveEveryMustSurviveCharacter() throws Exception {
        for (int codePoint : MUST_SURVIVE) {
            String character = new String(Character.toChars(codePoint));
            String hex = "U+%04X".formatted(codePoint);

            String canvasRaw = String.join(" ",
                    CanvasController.htmlNoteParagraphs("a" + character + "b"));
            assertTrue(canvasRaw.contains(character),
                    hex + " must survive the canvas converter (raw)");

            String canvasEntity = String.join(" ",
                    CanvasController.htmlNoteParagraphs("a&#" + codePoint + ";b<br>"));
            assertTrue(canvasEntity.contains(character),
                    hex + " must survive the canvas converter (entity-minted)");

            String detail = problemDetail("a" + character + "b");
            assertNotNull(detail, hex + ": detail must survive as text, not be nulled");
            assertTrue(detail.contains(character),
                    hex + " must survive problemDetail");
        }
    }

    @Test
    void theFixtureHarnessPassesCleanTextUntouched() throws Exception {
        // A harness that also mangled clean text would prove nothing about the
        // invisible class; pin the control case.
        assertEquals(List.of("ab"), CanvasController.htmlNoteParagraphs("ab"));
        assertEquals("ab", problemDetail("ab"));
    }

    private static String problemDetail(String detail) throws Exception {
        String body = new ObjectMapper().writeValueAsString(Map.of("detail", detail));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/problem+json"));
        RestClientResponseException http = new RestClientResponseException(
                "422", HttpStatus.UNPROCESSABLE_ENTITY, "Unprocessable Entity",
                headers, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        Method method = SdkTsanetGateway.class.getDeclaredMethod(
                "problemDetail", RestClientResponseException.class);
        method.setAccessible(true);
        return (String) method.invoke(null, http);
    }
}
