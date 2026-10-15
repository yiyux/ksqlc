package ksqlc.storage

import kotlin.test.*

class KeychainSmokeTest {
    @Test fun roundTripAgainstRealKeychain() {
        val s = KeyringSecretStore(service = "ksqlc-smoke-test")
        try {
            s.set("probe", "pässw0rd")
            assertEquals("pässw0rd", s.get("probe"))
        } finally {
            s.delete("probe")
        }
        assertNull(s.get("probe"))
    }
}
