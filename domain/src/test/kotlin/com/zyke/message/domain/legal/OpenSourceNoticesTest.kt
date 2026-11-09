package com.zyke.message.domain.legal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSourceNoticesTest {
    @Test fun everyNoticeHasALicenceAndAnHttpsAddress() {
        OpenSourceNotices.all.forEach {
            assertTrue("${it.name} licence", it.licence.isNotBlank())
            assertTrue("${it.name} url", it.url.startsWith("https://"))
        }
        assertEquals(OpenSourceNotices.all.size, OpenSourceNotices.all.map { it.name }.toSet().size)
    }

    @Test fun theFontAndTheLogosAreCredited() {
        val names = OpenSourceNotices.all.associateBy { it.name }
        assertEquals("SIL Open Font License 1.1", names.getValue("Plus Jakarta Sans").licence)
        assertTrue(names.containsKey("Brand logos"))
    }

    @Test fun theNoticeFileListsEveryItem() {
        val file = OpenSourceNotices.noticeFileText()
        OpenSourceNotices.all.forEach { assertTrue(it.name in file && it.url in file) }
    }
}
