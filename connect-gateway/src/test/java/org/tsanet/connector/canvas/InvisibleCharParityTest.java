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
 * the same invisible-character categories (Cntrl/Cf/Zl/Zp), so that a change to
 * a PROBED category in one copy fails here instead of drifting silently
 * (PR #8 QA round,
 * blast-radius item 7: duplication asserted by a check, not a comment).
 * Narrowing is caught by INVISIBLES (absence asserted, one representative per
 * stripped category); widening by MUST_SURVIVE (presence asserted, the
 * categories real partner prose is made of) — the round-2 mutation test showed
 * an absence fixture alone is blind to a class growing, which is the direction
 * where the canvas renders what the gateway rejects.
 *
 * <p>Both fixtures are REPRESENTATIVE, NOT EXHAUSTIVE: a finite set of probes
 * cannot prove a claim quantified over every category, so a widening into a
 * category no probe exercises still passes. That is not hypothetical — the #9
 * QA round found \p{Lu} slipping through because every probe string in this
 * file was lowercase. Adding a category to either sanitizer means adding a
 * probe here; keep the strings shaped like real notes.
 *
 * <p>The canvas side's documented deviation (\r\n\t handled by whitespace
 * normalization, \n reserved as the break sentinel) is outside both fixtures on
 * purpose. problemDetail is private; reflection is the price of asserting the
 * real method instead of a third copy of the class.
 *
 * <p>WHERE THIS RUNS: connect-gateway is outside the default reactor (D6), so
 * this class runs only under {@code -Psdk}, and the CI sdk job skips every real
 * step until the CONNECT_API_CODE_TOKEN repo secret exists. Until then this
 * guard is a local gate, not a CI one.
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

    // The classes real partner prose is made of. Every entry earns its place by
    // being a category a widening could plausibly swallow: keep this list
    // representative of actual notes, not minimal (#9 QA round — a \p{Lu}
    // widening passed clean because every probe string here was lowercase, while
    // rendering "TSANet Case 4471" as " et  ase 4471").
    private static final int[] MUST_SURVIVE = {
            0x00E9,  // é — Ll, lowercase letter
            0x00C9,  // É — Lu, uppercase letter (the #9 counterexample)
            0x0034,  // 4 — Nd, digit: case numbers and versions are made of these
            0x2192,  // right arrow — Sm, symbol, pinned in partner-note tests too
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
                    hex + " must survive the canvas converter (entity-minted); if this is"
                            + " the FIRST failure, check the change did not eat the &#N;"
                            + " syntax itself — it is built from # and ; (Po) and digits"
                            + " (Nd), so widening into either breaks the probe rather than"
                            + " the property it tests");

            String detail = problemDetail("a" + character + "b");
            assertNotNull(detail, hex + ": detail must survive as text, not be nulled");
            assertTrue(detail.contains(character),
                    hex + " must survive problemDetail");
        }
    }

    /** Real partner-note prose, not "ab": a control made only of lowercase
     * letters cannot notice a widening that eats capitals, digits or
     * punctuation (#9 QA round). */
    private static final String CLEAN_PROSE = "TSANet Case 4471: DB replica down at ACME.";

    @Test
    void theFixtureHarnessPassesCleanTextUntouched() throws Exception {
        // A harness that also mangled clean text would prove nothing about the
        // invisible class; pin the control case.
        assertEquals(List.of(CLEAN_PROSE), CanvasController.htmlNoteParagraphs(CLEAN_PROSE));
        assertEquals(CLEAN_PROSE, problemDetail(CLEAN_PROSE));
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
