package com.tomtom.openlr.tool.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Pins the interpretation of the `flowdir` column of `local.roads`.
 *
 * Exactly three values are defined. Everything else is reserved rather than being a
 * synonym for two-way, so that giving a value a meaning later cannot silently change
 * how already-loaded data is read.
 */
class FlowDirectionTest {

    @Test
    fun `flowdir 1 is two-way`() {
        assertEquals(FlowDirection.BOTH_WAYS, FlowDirection.fromDbValue(1))
    }

    @Test
    fun `flowdir 2 is one-way against the digitised direction`() {
        assertEquals(FlowDirection.END_TO_START, FlowDirection.fromDbValue(2))
    }

    @Test
    fun `flowdir 3 is one-way with the digitised direction`() {
        assertEquals(FlowDirection.START_TO_END, FlowDirection.fromDbValue(3))
    }

    @Test
    fun `undefined flowdir values are rejected rather than read as two-way`() {
        for (value in listOf(0, 4, 7, -1, Int.MAX_VALUE)) {
            assertNull(
                FlowDirection.fromDbValue(value),
                "flowdir $value is undefined and must not map to a direction"
            )
        }
    }
}
