package ch.lab77.radar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeProtocolTest {
    @Test fun `lignes du protocole`() {
        val w = ProbeProtocol.parse("W;f4:1e:57:9e:36:16;AP-Iot;-52;2417;[WPA2-PSK-CCMP][ESS]\n") as ProbeProtocol.Msg.Reading
        assertEquals(Kind.WIFI, w.kind); assertEquals("F4:1E:57:9E:36:16", w.id); assertEquals(-52, w.rssi); assertEquals(2417, w.frequency)
        val s = ProbeProtocol.parse("S;A4:83:E7:12:34:56;-71;INFINITUM56C4,Hyperion") as ProbeProtocol.Msg.Reading
        assertEquals(Kind.STATION, s.kind); assertEquals("STA-A4:83:E7:12:34:56", s.id); assertTrue(s.caps.contains("Hyperion"))
        val l = ProbeProtocol.parse("L;868100000;-97;7.5;meshtastic;!a1b2c3d4 SF7 BW125") as ProbeProtocol.Msg.Reading
        assertEquals(Kind.LORA, l.kind); assertEquals("LORA-a1b2c3d4", l.id); assertEquals(868100, l.frequency); assertEquals("meshtastic", l.vendor)
        val c = ProbeProtocol.parse("C;433920000;-64;OOK;pulse=350us") as ProbeProtocol.Msg.Reading
        assertEquals("SUBGHZ-433920-OOK", c.id)
        val g = ProbeProtocol.parse("G;20.661553;-87.046571;12.0;2.5;9") as ProbeProtocol.Msg.Gps
        assertEquals(9, g.sats); assertEquals(2.5f, g.acc, 0.001f)
        val i = ProbeProtocol.parse("I;Radar-TBeam-01;0.3.0;87;120") as ProbeProtocol.Msg.Info
        assertEquals(87, i.battery)
        assertTrue(ProbeProtocol.parse("R;868300000;-112") is ProbeProtocol.Msg.Sweep)
        assertTrue(ProbeProtocol.parse("E;boum") is ProbeProtocol.Msg.Error)
    }

    @Test fun `lignes illisibles`() {
        assertNull(ProbeProtocol.parse(""))
        assertNull(ProbeProtocol.parse("X;1;2"))
        assertNull(ProbeProtocol.parse("W;;;;"))
        assertNull(ProbeProtocol.parse("W;F4:1E:57:9E:36:16;AP;pas-un-nombre;2417;"))
    }
}
