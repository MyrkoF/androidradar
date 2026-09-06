package ch.lab77.radar.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OuiTest {
    @Test fun `nom d affichage lisible`() {
        assertEquals("Routerboard", Oui.displayName("Routerboard.com"))
        assertEquals("Huawei", Oui.displayName("Huawei Technologies Co.,Ltd"))
        assertEquals("Huawei Device", Oui.displayName("Huawei Device Co., Ltd."))
        assertEquals("Milesight IoT", Oui.displayName("Xiamen Milesight IoT Co., Ltd."))
        assertEquals("Teltonika Telematics", Oui.displayName("UAB \"Teltonika Telematics\""))
        assertEquals("Apple", Oui.displayName("Apple, Inc."))
        assertEquals("Espressif", Oui.displayName("Espressif Inc."))
        assertEquals("Dji", Oui.displayName("Sz Dji Technology Co.,Ltd"))
        assertEquals("Cisco", Oui.displayName("Cisco Systems, Inc"))
        assertEquals("Samsung", Oui.displayName("Samsung Electronics Co.,Ltd"))
        assertEquals("Silicon Laboratories", Oui.displayName("Silicon Laboratories"))
        assertEquals("Mitsubishi Electric", Oui.displayName("Mitsubishi Electric Corporation"))
        assertEquals("Hon Hai", Oui.displayName("Hon Hai Precision Ind. Co.,Ltd."))
        assertEquals("Xerox", Oui.displayName("Xerox Corporation"))
        assertEquals("Court", Oui.displayName("Co., Ltd.", "Court"))
    }

    @Test fun `bits d adresse`() {
        assertEquals(true, Oui.isLocallyAdministered("02:00:00:00:00:00"))
        assertEquals(false, Oui.isLocallyAdministered("00:00:00:00:00:00"))
        assertEquals("aléatoire statique", Oui.bleAddressType("C0:00:00:00:00:00"))
        assertEquals("privée résolvable", Oui.bleAddressType("40:00:00:00:00:00"))
        assertEquals("publique/non-résolvable", Oui.bleAddressType("00:00:00:00:00:00"))
    }
}
