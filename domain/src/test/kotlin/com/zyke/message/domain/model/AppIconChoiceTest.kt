package com.zyke.message.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIconChoiceTest {
    @Test fun idsAliasesAndSwatchesAreUnique() {
        val all = AppIconChoice.entries
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(all.size, all.map { it.aliasName }.toSet().size)
        assertEquals(all.size, all.map { it.swatch }.toSet().size)
        assertTrue(all.all { it.aliasName.startsWith("com.zyke.message.Icon") && it.swatch ushr 24 == 0xFFL })
    }

    @Test fun exactlyOneIsTheDefault_andItIsTheFirst() {
        assertEquals(AppIconChoice.DEFAULT, AppIconChoice.entries.first())
        assertEquals(1, AppIconChoice.entries.count { it.id == "default" })
    }

    @Test fun fromStoredFallsBackToTheDefault() {
        assertEquals(AppIconChoice.ROSE, AppIconChoice.fromStored("rose"))
        assertEquals(AppIconChoice.DEFAULT, AppIconChoice.fromStored(null))
        assertEquals(AppIconChoice.DEFAULT, AppIconChoice.fromStored("magenta"))
        AppIconChoice.entries.forEach { assertEquals(it, AppIconChoice.fromStored(it.id)) }
    }
}
