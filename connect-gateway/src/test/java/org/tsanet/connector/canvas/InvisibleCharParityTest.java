package org.tsanet.connector.canvas;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
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

    /**
     * What every INVISIBLES probe must render as, on all three paths: the
     * character becomes a space (measured against the real sanitizers, not
     * assumed). Asserting this EXACT string rather than mere absence is half of
     * the #11 fix — "the character is absent" is also what a probe that never
     * minted produces, but "a b" is not, so equality tells the two apart at
     * every entity width including the ones no canary can cover.
     */
    private static final String STRIPPED = "a b";

    /**
     * Visible characters minted through the identical a&#N;b path, one per
     * decimal width the INVISIBLES probes use. They prove the probe still works
     * before any absence assertion is trusted: see #11, where a widening that
     * ate the probe alphabet made this whole test pass while the sanitizer was
     * broken.
     *
     * <p>One canary per decimal width the probes use, because a decode cap is
     * width-selective: with decoding capped at 4 digits, the 5-digit canary is
     * the only assertion in this class that names the actual cause — the
     * sibling tests pass, and STRIPPED equality does fail but later and far
     * less specifically. Honest limit, measured rather than assumed: no fault
     * was found that a wider canary catches while narrower ones pass outright,
     * because anything that makes a wider literal collapse to STRIPPED also
     * eats the shorter canaries' entities. Width parity buys attribution, not
     * extra coverage. No 1-digit canary can exist (codepoints 0-9 are all C0
     * controls), so width 1 rests on equality alone. Each entry was measured
     * surviving this path against the real sanitizers before being added.
     */
    private static final int[] PROBE_CANARIES = {
            0x00E9,  // 233,   3 digits — matches U+009D (157)
            0x2192,  // 8594,  4 digits — matches U+202E, U+200B, U+2028, U+2029
            0x4E2D,  // 20013, 5 digits — matches U+FEFF (65279)
            // U+0007 (7) is 1 digit and uncoverable; equality carries that width.
    };

    @Test
    void bothSanitizersStripEveryFixtureCategory() throws Exception {
        assertEntityProbeStillMints();
        for (int codePoint : INVISIBLES) {
            String character = new String(Character.toChars(codePoint));
            String hex = "U+%04X".formatted(codePoint);

            String canvasRaw = String.join(" ",
                    CanvasController.htmlNoteParagraphs("a" + character + "b"));
            assertEquals(STRIPPED, canvasRaw,
                    hex + " must not survive the canvas converter (raw)");

            String canvasEntity = String.join(" ",
                    CanvasController.htmlNoteParagraphs("a&#" + codePoint + ";b<br>"));
            assertEquals(STRIPPED, canvasEntity,
                    hex + " must not survive the canvas converter (entity-minted)");

            String detail = problemDetail("a" + character + "b");
            assertNotNull(detail, hex + ": detail must survive as text, not be nulled");
            assertEquals(STRIPPED, detail, hex + " must not survive problemDetail");
        }
    }

    /**
     * Probe-integrity gate (#11). An absence assertion cannot distinguish "the
     * sanitizer removed it" from "the probe never minted it", so anything that
     * eats the probe alphabet — &, # and ; (Po) or the digits (Nd) — silently
     * turns every entity-minted assertion in this test into a tautology.
     * Demonstrated before the fix: with the entity invisible-refusal removed
     * AND FORMAT_CHARS widened by \p{Po}, this test passed while the sanitizer
     * was broken.
     *
     * <p>A hard assertion, never a JUnit assumption: an assumption SKIPS, and
     * silently not running is the very failure mode being closed here.
     */
    private static void assertEntityProbeStillMints() {
        for (int codePoint : PROBE_CANARIES) {
            String decimal = Integer.toString(codePoint);
            String actual = String.join(" ",
                    CanvasController.htmlNoteParagraphs("a&#" + decimal + ";b<br>"));
            assertEquals("a" + new String(Character.toChars(codePoint)) + "b", actual,
                    "entity probe canary U+%04X (&#%s;, %d decimal digits) did not survive the"
                            .formatted(codePoint, decimal, decimal.length())
                            + " a&#N;b path. Either the probe alphabet (&, #, ; or the digits) is"
                            + " being eaten, or entity decoding is now limited by codepoint value"
                            + " or digit count — if the shorter canary passed and this one did"
                            + " not, suspect the latter. Until this passes, every entity-minted"
                            + " absence assertion in this test proves nothing (#11).");
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

    /**
     * Three sequential passes rather than one loop per character, and the order
     * is the point: the entity probe is BUILT FROM the characters it tests
     * around (&#N; is # and ; (Po) plus digits (Nd)), so a widening into those
     * categories breaks the probe and the property at the same time. Running
     * every bare-character assertion first means the honest failure is always
     * the one reported: if the entity pass fails, the raw pass has already
     * passed, so the syntax really was eaten and the property really does hold.
     * That makes the advisory below true by construction instead of by comment
     * — before this, a \p{Nd} widening reported "the probe broke" while
     * silently also eating every case number (#9 QA round 2). assertAll within
     * each pass so one character's failure does not hide its siblings.
     */
    @Test
    void bothSanitizersPreserveEveryMustSurviveCharacter() {
        assertAll("canvas converter, bare characters", overFixture(codePoint -> () -> {
            String character = character(codePoint);
            String out = String.join(" ",
                    CanvasController.htmlNoteParagraphs("a" + character + "b"));
            assertTrue(out.contains(character),
                    hex(codePoint) + " must survive the canvas converter (raw)");
        }));
        assertAll("canvas converter, entity-minted", overFixture(codePoint -> () -> {
            String character = character(codePoint);
            String out = String.join(" ",
                    CanvasController.htmlNoteParagraphs("a&#" + codePoint + ";b<br>"));
            assertTrue(out.contains(character),
                    hex(codePoint) + " must survive the canvas converter (entity-minted);"
                            + " the bare-character pass above already passed, so this means"
                            + " the &#N; syntax itself was eaten — repair the probe, the"
                            + " property it tests still holds");
        }));
        assertAll("problemDetail", overFixture(codePoint -> () -> {
            String character = character(codePoint);
            String detail = problemDetail("a" + character + "b");
            assertNotNull(detail, hex(codePoint) + ": detail must survive as text, not be nulled");
            assertTrue(detail.contains(character),
                    hex(codePoint) + " must survive problemDetail");
        }));
    }

    /** One probe applied across the whole fixture, as an assertAll pass. */
    private static Stream<Executable> overFixture(IntFunction<Executable> probe) {
        return IntStream.of(MUST_SURVIVE).mapToObj(probe);
    }

    private static String character(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    private static String hex(int codePoint) {
        return "U+%04X".formatted(codePoint);
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
