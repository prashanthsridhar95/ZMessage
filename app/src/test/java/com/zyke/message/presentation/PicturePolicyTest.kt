package com.zyke.message.presentation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A picture the person picks is saved whatever happens to the screen that asked for it. */
class PicturePolicyTest {
    private val picker = File("src/main/java/com/zyke/message/presentation/common/PicturePicker.kt").readText()
    private val store = File("src/main/java/com/zyke/message/data/images/ImageStore.kt").readText()

    @Test
    fun thePickerSavesThroughTheStoreNotOnTheScreensOwnScope() {
        assertFalse("a save on a screen's scope is dropped silently once the screen has left", "rememberCoroutineScope" in picker)
        assertTrue("saveChosenDetached(key, uri)" in picker)
        assertTrue("saveChosenBitmapDetached(key, bitmap)" in picker)
    }

    @Test
    fun theStoresSaveScopeOutlivesScreens() {
        assertTrue("SupervisorJob()" in store && "saveScope" in store)
    }

    @Test
    fun aSavedPictureIsInMemoryBeforeScreensAreTold() {
        val keep = store.substringAfter("private fun keep(").substringBefore("/** Gives [toKey]")
        assertTrue(keep.indexOf("cache.put(CHOSEN + key, bitmap)") in 0 until keep.indexOf("updateKeys(_keys.value + key)"))
    }
}
