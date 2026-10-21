package dev.fieldsync.sync

import android.Manifest.permission.*
import org.junit.Test
import kotlin.test.*

class NearbyPermissionsTest {
    @Test fun `older phones request location for discovery`() {
        val permissions = NearbyManager.permissions(30).toSet()
        assertTrue(ACCESS_FINE_LOCATION in permissions)
        assertFalse(BLUETOOTH_SCAN in permissions)
    }

    @Test fun `Android 12 needs location as well as Bluetooth permissions`() {
        for (sdk in listOf(31, 32)) {
            val permissions = NearbyManager.permissions(sdk).toSet()
            assertTrue(permissions.containsAll(setOf(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION,
                BLUETOOTH_SCAN, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE)))
            assertFalse(NEARBY_WIFI_DEVICES in permissions)
        }
    }

    @Test fun `modern Nearby uses nearby Wi-Fi without requiring GPS permission`() {
        val permissions = NearbyManager.permissions(33).toSet()
        assertTrue(permissions.containsAll(setOf(NEARBY_WIFI_DEVICES, BLUETOOTH_SCAN,
            BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE)))
        assertFalse(ACCESS_FINE_LOCATION in permissions)
    }
}
