package lk.cf.fr.monolith.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SriLankanNicFormatTest {

    @Nested
    @DisplayName("compact")
    class Compact {

        @Test
        void uppercasesAndStripsWhitespace() {
            assertEquals("935560233V", SriLankanNicFormat.compact("935560233 v"));
            assertEquals("935560233V", SriLankanNicFormat.compact("  935560233\tV  "));
        }

        @Test
        void handlesNull() {
            assertNull(SriLankanNicFormat.compact(null));
        }
    }

    @Nested
    @DisplayName("correctDigitConfusions")
    class DigitConfusions {

        @Test
        void undoesTheSixKnownOcrMisreads() {
            // O->0, I->1, L->1, S->5, B->8, Z->2
            assertEquals("011582", SriLankanNicFormat.correctDigitConfusions("OILSBZ"));
        }

        @Test
        void leavesTheVxCheckLetterAlone() {
            assertEquals("751932523V", SriLankanNicFormat.correctDigitConfusions("751932523V"));
            assertEquals("751932523X", SriLankanNicFormat.correctDigitConfusions("751932523X"));
        }

        @Test
        void isAnIdentityOnACleanNumber() {
            assertEquals("199012345678", SriLankanNicFormat.correctDigitConfusions("199012345678"));
        }
    }

    @Nested
    @DisplayName("format detection")
    class FormatDetection {

        @Test
        void recognisesBothFormats() {
            assertTrue(SriLankanNicFormat.isOldFormat("751932523V"));
            assertTrue(SriLankanNicFormat.isOldFormat("751932523X"));
            assertTrue(SriLankanNicFormat.isNewFormat("197519302523"));
        }

        @Test
        void rejectsNearMisses() {
            assertFalse(SriLankanNicFormat.isOldFormat("75193252V"));      // 8 digits
            assertFalse(SriLankanNicFormat.isOldFormat("751932523A"));     // wrong check letter
            assertFalse(SriLankanNicFormat.isNewFormat("19751930252"));    // 11 digits
            assertFalse(SriLankanNicFormat.isNewFormat("1975193025234"));  // 13 digits
            assertFalse(SriLankanNicFormat.isOldFormat(null));
        }
    }

    @Nested
    @DisplayName("oldToNew")
    class OldToNew {

        @Test
        @DisplayName("YY DDD SSSS + V/X  ->  19YY DDD 0SSSS")
        void convertsUsingTheDocumentedRule() {
            // 75 | 193 | 2523 | V  ->  1975 | 193 | 02523
            assertEquals("197519302523", SriLankanNicFormat.oldToNew("751932523V"));
        }

        @Test
        void alwaysProducesTwelveDigits() {
            assertEquals(12, SriLankanNicFormat.oldToNew("935560233V").length());
            assertEquals(12, SriLankanNicFormat.oldToNew("000000000X").length());
        }

        @Test
        void preservesTheDayOfYearIncludingTheFemale500Offset() {
            // day-of-year 561 = female, born on day 61. The offset must survive conversion.
            assertTrue(SriLankanNicFormat.oldToNew("895614102V").startsWith("1989561"));
        }

        @Test
        void checkLetterDoesNotAlterTheDigits() {
            assertEquals(SriLankanNicFormat.oldToNew("751932523V"),
                    SriLankanNicFormat.oldToNew("751932523X"));
        }

        @Test
        void returnsNullForAnythingNotOldFormat() {
            assertNull(SriLankanNicFormat.oldToNew("199012345678"));
            assertNull(SriLankanNicFormat.oldToNew("garbage"));
            assertNull(SriLankanNicFormat.oldToNew(null));
        }
    }

    @Nested
    @DisplayName("canonicalise")
    class Canonicalise {

        @Test
        void newFormatIsAlreadyCanonical() {
            assertEquals("199012345678", SriLankanNicFormat.canonicalise("199012345678"));
        }

        @Test
        void oldFormatIsConverted() {
            assertEquals("197519302523", SriLankanNicFormat.canonicalise("751932523V"));
        }

        @Test
        void bothFormsOfTheSamePersonCollapseToOneValue() {
            assertEquals(SriLankanNicFormat.canonicalise("751932523V"),
                    SriLankanNicFormat.canonicalise("197519302523"));
        }

        @Test
        void returnsNullWhenNeitherFormatMatches() {
            assertNull(SriLankanNicFormat.canonicalise("NOTANIC"));
        }
    }

    @Nested
    @DisplayName("levenshtein / normalisedSimilarity")
    class Distance {

        @Test
        void identicalStringsAreDistanceZero() {
            assertEquals(0, SriLankanNicFormat.levenshtein("199012345678", "199012345678"));
            assertEquals(1.0, SriLankanNicFormat.normalisedSimilarity("199012345678", "199012345678"));
        }

        @Test
        void singleCharacterDifference() {
            assertEquals(1, SriLankanNicFormat.levenshtein("199012345678", "199012345679"));
        }

        @Test
        void similarityIsBoundedToZeroOne() {
            double s = SriLankanNicFormat.normalisedSimilarity("199012345678", "888888888888");
            assertTrue(s >= 0.0 && s <= 1.0, "similarity out of range: " + s);
        }

        @Test
        void nullsAreHandled() {
            assertEquals(0.0, SriLankanNicFormat.normalisedSimilarity(null, "199012345678"));
            assertEquals(0.0, SriLankanNicFormat.normalisedSimilarity("199012345678", null));
        }
    }
}
