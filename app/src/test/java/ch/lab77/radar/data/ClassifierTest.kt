package ch.lab77.radar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassifierTest {
    private fun wifi(vendorLong: String) = Classifier.classify(vendorLong, Kind.WIFI, "00:11:22:33:44:55")

    @Test fun `aucun recoupement entre les regles`() {
        val problems = Classifier.ruleProblems()
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun `un fabricant une categorie`() {
        assertEquals(Category.FLEET, wifi("UAB \"Teltonika Telematics\""))
        assertEquals(Category.CELLULAR_ROUTER, wifi("Teltonika Networks Uab"))
        assertEquals(Category.CELLULAR_ROUTER, wifi("Teltonika"))
        assertEquals(Category.FLEET, wifi("Calamp Wireless Networks Inc"))
        assertEquals(Category.CAMERA, wifi("Milesight Taiwan"))
        assertEquals(Category.INDUSTRIAL, wifi("Xiamen Milesight IoT Co., Ltd."))
        assertEquals(Category.INDUSTRIAL, wifi("Espressif Inc."))
        assertEquals(Category.CONSUMER, wifi("Huawei Device Co., Ltd."))
        assertEquals(Category.ROUTER_AP, wifi("Huawei Technologies Co.,Ltd"))
        assertEquals(Category.ROUTER_AP, wifi("zte corporation"))
        assertEquals(Category.ROUTER_AP, wifi("Netgear"))
        assertEquals(Category.NETWORK_INFRA, wifi("Huawei Symantec Technologies Co.,Ltd."))
        assertEquals(Category.NETWORK_INFRA, wifi("Routerboard.com"))
        assertEquals(Category.NETWORK_INFRA, wifi("Hewlett Packard Enterprise"))
        assertEquals(Category.CONSUMER, wifi("Hewlett Packard"))
        assertEquals(Category.CONSUMER, wifi("HP Inc."))
        assertEquals(Category.INDUSTRIAL, wifi("shenzhen RAKwireless technology Co.,Ltd"))
    }

    @Test fun `mot entier pas de sous-chaine`() {
        assertEquals(Category.CONSUMER, wifi("Murata Manufacturing Co., Ltd."))   // pas « ring »
        assertEquals(Category.UNKNOWN, wifi("Tattile Srl"))                        // pas « tile »
        assertEquals(Category.UNKNOWN, wifi("Abbott Diabetes Care"))               // pas « abb »
        assertEquals(Category.UNKNOWN, wifi("Scanivalve Corp."))                   // pas « valve »
    }

    @Test fun `adresses aleatoires`() {
        assertEquals(Category.RANDOMIZED, Classifier.classify("", Kind.WIFI, "02:11:22:33:44:55"))
        assertEquals(Category.UNKNOWN, Classifier.classify("", Kind.WIFI, "00:11:22:33:44:55"))
        assertEquals(Category.RANDOMIZED, Classifier.classify("", Kind.BLE, "C1:11:22:33:44:55"))
        assertEquals(Category.UNKNOWN, Classifier.classify("", Kind.BLE, "00:11:22:33:44:55"))
    }
}
