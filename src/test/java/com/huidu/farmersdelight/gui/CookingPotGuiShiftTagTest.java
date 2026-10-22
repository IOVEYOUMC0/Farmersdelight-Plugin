package com.huidu.farmersdelight.gui;

import org.junit.jupiter.api.Test;

import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Locks the shift-tag handling of the pot GUI title against a value that cannot be laid out.
 */
class CookingPotGuiShiftTagTest {

    private static final IntFunction<String> STUB_RENDERER = offset -> "[" + offset + "]";

    private static Integer offset(String digits) {
        return CookingPotTitle.parseShiftOffset(digits);
    }

    private static String replace(String title) {
        return CookingPotTitle.replaceShiftTags(title, STUB_RENDERER);
    }

    @Test
    void representableOffsetsKeepTheirValue() {
        assertEquals(Integer.valueOf(0), offset("0"));
        assertEquals(Integer.valueOf(5), offset("5"));
        assertEquals(Integer.valueOf(7), offset("007"), "leading zeros never changed the parsed value");
        assertEquals(Integer.valueOf(5), offset("+5"), "an explicit plus sign still parses");
        assertEquals(Integer.valueOf(-5), offset("-5"), "a negative shift is a shift to the left");
        assertEquals(Integer.valueOf(Integer.MAX_VALUE), offset("2147483647"));
        assertEquals(Integer.valueOf(Integer.MIN_VALUE), offset("-2147483648"));
    }

    @Test
    void offsetsThatDoNotFitAnIntReportNoValueInsteadOfThrowing() {
        assertNull(offset("2147483648"), "one past Integer.MAX_VALUE");
        assertNull(offset("-2147483649"), "one past Integer.MIN_VALUE");
        assertNull(offset("+2147483648"), "an overflowing value with an explicit plus sign");
        assertNull(offset("99999999999"), "a value far past the int range");
    }

    @Test
    void aTitleWithRepresentableTagsOnlyIsFullyReplaced() {
        assertEquals("Pot [2] Title", replace("Pot <shift:2> Title"));
        assertEquals("plain title", replace("plain title"), "a title without tags is returned unchanged");
        assertEquals("[0]x[0]y", replace("<shift:0>x<shift:0000000000000000000000>y"),
                "zero padding never overflows, so these are ordinary values");
    }

    @Test
    void anUnrepresentableTagStaysInTheTitleWhileItsNeighboursAreReplaced() {
        assertEquals("a[1]b<shift:99999999999>c[3]d[-4]e",
                replace("a<shift:1>b<shift:99999999999>c<shift:+3>d<shift:-4>e"));
    }

    @Test
    void aTitleEndingInAnUnrepresentableTagKeepsIt() {
        assertEquals("Pot <shift:99999999999>", replace("Pot <shift:99999999999>"));
        assertEquals("<shift:2147483648>B", replace("<shift:2147483648>B"));
    }

    @Test
    void aTagWithoutDigitsIsNotATag() {
        assertEquals("<shift:+>", replace("<shift:+>"),
                "the pattern needs at least one digit, so this text is left alone");
    }
}
