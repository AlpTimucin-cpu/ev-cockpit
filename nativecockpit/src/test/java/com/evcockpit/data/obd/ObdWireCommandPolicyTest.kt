package com.evcockpit.data.obd

import org.junit.Assert.*
import org.junit.Test

class ObdWireCommandPolicyTest {
    @Test fun fixedReadOnlyCommandsAndNecessaryAdapterSettingsOnly() {
        for (cmd in listOf("010D", "22028C", "221E3D", "ATH0", "ATFCSM0",
                           "ATCRA", "ATSP0", "ATSHFC007B")) {
            assertTrue(cmd, ObdWireCommandPolicy.allowed(cmd))
        }
        for (cmd in listOf("01FF", "22F190", "2E028C0001", "31010000",
                           "ATSH7E0\r2E028C", "ATD", "ATMA", "ATSP8",
                           "ATSH123", "010D\rATH1", "ATZ\n", "010D\r", "")) {
            assertFalse(cmd, ObdWireCommandPolicy.allowed(cmd))
        }
    }

    @Test fun protocolRestoreNeedsPositiveAdapterAcknowledgement() {
        assertTrue(ObdWireCommandPolicy.acknowledged("ATSP0", "ATSP0\rOK\r>"))
        assertTrue(ObdWireCommandPolicy.acknowledged("ATCRA", "OK\r>"))
        assertFalse(ObdWireCommandPolicy.acknowledged("ATSP0", "NO DATA\r>"))
        assertFalse(ObdWireCommandPolicy.acknowledged("ATSP0", "?\r>"))
        assertFalse(ObdWireCommandPolicy.acknowledged("ATSP0", "ATSP0\rERROR\r>"))
        assertFalse(ObdWireCommandPolicy.acknowledged("ATSP0", "OK\rOK\r>"))
        assertFalse(ObdWireCommandPolicy.acknowledged("221E3D", "OK\r>"))
    }
}
