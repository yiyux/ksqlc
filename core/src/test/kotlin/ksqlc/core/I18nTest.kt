package ksqlc.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class I18nTest {
    @AfterTest fun reset() { I18n.language = Language.ES }

    @Test fun `spanish returns the key and formats args`() {
        I18n.language = Language.ES
        assertEquals("Ajustes", tr("Ajustes"))
        assertEquals("fila 3", tr("fila %s", 3))
    }

    @Test fun `english translates and unknown keys fall back to the key`() {
        I18n.language = Language.EN
        assertEquals("Settings", tr("Ajustes"))
        assertEquals("sin traducir 100%", tr("sin traducir 100%"))
    }
}
