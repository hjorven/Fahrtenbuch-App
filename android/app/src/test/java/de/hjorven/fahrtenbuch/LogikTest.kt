package de.hjorven.fahrtenbuch

import de.hjorven.fahrtenbuch.data.Antrieb
import de.hjorven.fahrtenbuch.data.Ausgleich
import de.hjorven.fahrtenbuch.data.FahrtBearbeitung
import de.hjorven.fahrtenbuch.data.Plausibel
import de.hjorven.fahrtenbuch.data.Update
import de.hjorven.fahrtenbuch.location.Bewegung
import de.hjorven.fahrtenbuch.data.Fahrt
import de.hjorven.fahrtenbuch.data.Fahrzeug
import de.hjorven.fahrtenbuch.data.PbDatum
import de.hjorven.fahrtenbuch.data.Prefs
import de.hjorven.fahrtenbuch.data.ScanLeser
import de.hjorven.fahrtenbuch.data.Polyline
import de.hjorven.fahrtenbuch.data.Statistik
import de.hjorven.fahrtenbuch.data.Tankvorgang
import de.hjorven.fahrtenbuch.data.neueId
import de.hjorven.fahrtenbuch.data.Verbinden
import de.hjorven.fahrtenbuch.data.hoechstens
import de.hjorven.fahrtenbuch.location.Diagnose
import de.hjorven.fahrtenbuch.location.GpsPunkt
import de.hjorven.fahrtenbuch.location.StillstandErkennung
import de.hjorven.fahrtenbuch.location.Streckenrechner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogikTest {

    @Test
    fun polylineWieGoogleBeispiel() {
        val punkte = listOf(Polyline.Punkt(38.5, -120.2), Polyline.Punkt(40.7, -120.95), Polyline.Punkt(43.252, -126.453))
        val text = Polyline.encode(punkte)
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", text)
        val zurueck = Polyline.decode(text)
        assertEquals(3, zurueck.size)
        assertEquals(43.252, zurueck[2].lat, 1e-6)
        assertEquals(-126.453, zurueck[2].lon, 1e-6)
    }

    @Test
    fun idHatPocketBaseFormat() {
        repeat(50) { assertTrue(neueId().matches(Regex("^[a-z0-9]{15}$"))) }
    }

    @Test
    fun datumHinUndZurueck() {
        val t = 1_793_600_000_123L
        assertEquals(t, PbDatum.parse(PbDatum.aus(t)))
        assertNull(PbDatum.parse(""))
        assertEquals("", PbDatum.aus(null))
    }

    private fun tank(km: Double, liter: Double, preis: Double, voll: Boolean = true, fahrer: String = "a") =
        Tankvorgang(neueId(), "bus", fahrer, 0L, km, liter, gesamtpreis = preis, vollgetankt = voll)

    @Test
    fun verbrauchZwischenVollenTankungen() {
        val t = listOf(
            tank(10_000.0, 50.0, 90.0),
            tank(10_300.0, 10.0, 18.0, voll = false),
            tank(10_500.0, 35.0, 63.0),
        )
        val v = Statistik.verbrauch(t)!!
        // 45 L auf 500 km = 9 L/100 km, 81 Euro auf 500 km
        assertEquals(9.0, v.literPro100km, 1e-9)
        assertEquals(0.162, v.kostenProKm, 1e-9)
    }

    @Test
    fun verbrauchElektroZaehltJedenLadevorgang() {
        // Teilladungen ohne "voll": beim E-Auto zählt trotzdem jeder Ladevorgang.
        val t = listOf(
            tank(1_000.0, 20.0, 6.0, voll = false),
            tank(1_100.0, 15.0, 4.5, voll = false),
            tank(1_200.0, 15.0, 9.0, voll = false),
        )
        assertNull(Statistik.verbrauch(t))
        val v = Statistik.verbrauch(t, elektrisch = true)!!
        // 30 kWh auf 200 km = 15 kWh/100 km
        assertEquals(15.0, v.literPro100km, 1e-9)
        assertEquals(0.0675, v.kostenProKm, 1e-9)
    }

    @Test
    fun adresseNormalisieren() {
        assertEquals("http://fahrtenbuch.local:8090", Prefs.normalisieren(" fahrtenbuch.local:8090/ "))
        assertEquals("https://x.ts.net", Prefs.normalisieren("https://x.ts.net"))
        assertEquals("", Prefs.normalisieren("  "))
    }

    @Test
    fun namensabgleich() {
        assertEquals(Antrieb.namensSchluessel("E-Auto"), Antrieb.namensSchluessel(" E-Auto"))
    }

    @Test
    fun verbrauchBrauchtZweiVolle() {
        assertNull(Statistik.verbrauch(listOf(tank(1.0, 10.0, 10.0))))
    }

    @Test
    fun festerPreisUeberschreibt() {
        val fz = Fahrzeug("bus", "Bus", preisProKm = 0.3)
        assertEquals(0.3, Statistik.kostenProKm(fz, emptyList())!!, 1e-9)
    }

    @Test
    fun abrechnungWieImPlan() {
        fun fahrt(fahrer: String, km: Double) = Fahrt(neueId(), "bus", fahrer, start = 100L, ende = 200L, streckeKm = km)
        val fahrten = listOf(fahrt("ben", 600.0), fahrt("admin", 300.0), fahrt("anna", 100.0))
        val tank = listOf(
            Tankvorgang(neueId(), "bus", "ben", 100L, 1.0, 1.0, gesamtpreis = 140.0),
            Tankvorgang(neueId(), "bus", "anna", 100L, 1.0, 1.0, gesamtpreis = 20.0),
        )
        val r = Statistik.abrechnung(fahrten, tank, mapOf("bus" to 0.16), 0L, 1000L).associateBy { it.fahrerId }
        assertEquals(44.0, r["ben"]!!.saldo, 1e-9)
        assertEquals(-48.0, r["admin"]!!.saldo, 1e-9)
        assertEquals(4.0, r["anna"]!!.saldo, 1e-9)
    }

    @Test
    fun stillstandBeendetFahrtNachZehnMinuten() {
        val s = StillstandErkennung(10 * 60_000L)
        s.punkt(GpsPunkt(0, 50.0, 8.0, 15.0))
        assertNull(s.stehtSeit)
        s.punkt(GpsPunkt(60_000, 50.0, 8.0, 0.0))
        assertEquals(60_000L, s.stehtSeit)
        s.punkt(GpsPunkt(300_000, 50.0001, 8.0001, 0.2))
        assertFalse(s.sollEnden(5 * 60_000L))
        assertTrue(s.sollEnden(11 * 60_000L))
    }

    @Test
    fun kurzerStoppBleibtEineFahrt() {
        val s = StillstandErkennung(10 * 60_000L)
        s.punkt(GpsPunkt(0, 50.0, 8.0, 0.0))
        s.punkt(GpsPunkt(5 * 60_000L, 50.0, 8.0, 0.0))
        s.punkt(GpsPunkt(6 * 60_000L, 50.001, 8.0, 12.0)) // weiter gefahren
        assertNull(s.stehtSeit)
        assertFalse(s.sollEnden(12 * 60_000L))
    }

    @Test
    fun streckeIgnoriertZitternUndUngenauePunkte() {
        val r = Streckenrechner()
        r.hinzufuegen(GpsPunkt(0, 50.0, 8.0, 10.0, 5f))
        r.hinzufuegen(GpsPunkt(1000, 50.00001, 8.0, 0.1, 5f)) // ~1 m im Stand
        r.hinzufuegen(GpsPunkt(2000, 50.01, 8.0, 10.0, 80f)) // ungenau
        r.hinzufuegen(GpsPunkt(60_000, 50.009, 8.0, 10.0, 5f)) // ~1 km in einer Minute
        assertEquals(1000.0, r.meter, 5.0)
        r.abschneidenNach(1500)
        assertEquals(0.0, r.meter, 1e-6)
    }

    @Test
    fun qrVerbindenHinUndZurueck() {
        val d = Verbinden.Daten(heim = "http://fahrtenbuch.local:8090", tail = "http://100.1.2.3:8090",
            dl = "http://fahrtenbuch.local:8091", einladung = "ABCD2345EFGH")
        val text = Verbinden.bauen(d)
        assertTrue(text.startsWith("fahrtenbuch://verbinden?heim=http%3A%2F%2F"))
        assertEquals(d, Verbinden.lesen(text))
        assertNull(Verbinden.lesen("https://example.com"))
        // Abgetippt mit Bindestrichen und klein
        assertEquals("ABCD2345EFGH", Verbinden.code("abcd-2345-efgh"))
        assertEquals("", Verbinden.code("ZUKURZ"))
        assertEquals("XYZW2345ABCD", Verbinden.lesen("fahrtenbuch://verbinden?reset=XYZW2345ABCD")!!.reset)
    }

    @Test
    fun csvExport() {
        val f = Fahrt(id = "a", fahrzeugId = "g", fahrerId = "j", start = 1_793_700_000_000L, ende = 1_793_701_800_000L,
            kmStart = 1000.0, kmEnde = 1012.5, streckeKm = 12.0, zweck = "geschaeftlich", startOrt = "A; B", zielOrt = "C",
            notiz = "sagt \"hallo\"")
        val test = f.copy(id = "t", test = true)
        val csv = de.hjorven.fahrtenbuch.data.Export.fahrtenCsv(listOf(f, test), mapOf("j" to "Admin"), mapOf("g" to "Kombi"))
        val zeilen = csv.removePrefix("\uFEFF").trimEnd().split("\r\n")
        assertEquals(2, zeilen.size)  // Kopf + eine Fahrt, Test-Fahrt fehlt
        assertTrue(zeilen[1].contains(";Kombi;Admin;Geschäftlich;12,5;1000;1013;\"A; B\";C;\"sagt \"\"hallo\"\"\""))
    }

    @Test
    fun textAufServerLaengeKuerzen() {
        assertEquals("abc", "abc".hoechstens(5))
        assertEquals(2000, "x".repeat(2500).hoechstens(2000).length)
        // Emojis zählen wie beim Server als ein Zeichen und werden nicht zerschnitten
        val e = "🚗ä".repeat(2500).hoechstens(2000)
        assertEquals(2000, e.codePointCount(0, e.length))
        assertTrue(e.endsWith("ä"))
    }

    @Test
    fun gpsSprungZaehltNicht() {
        val r = Streckenrechner()
        r.hinzufuegen(GpsPunkt(0, 50.0, 8.0, 14.0, 5f))
        r.hinzufuegen(GpsPunkt(10_000, 50.0013, 8.0, 14.0, 5f))           // 145 m in 10 s
        r.hinzufuegen(GpsPunkt(11_000, 50.45, 8.0, 250.0, 5f))            // 50 km daneben, 900 km/h
        r.hinzufuegen(GpsPunkt(20_000, 50.0025, 8.0, 14.0, 5f))           // wieder auf der Strecke
        assertEquals(278.0, r.meter, 10.0)
        assertTrue(r.maxMps < 20.0)
        assertEquals(3, r.punkte.size)
    }

    @Test
    fun falscherStartWirdNachDreiPunktenKorrigiert() {
        val r = Streckenrechner()
        r.hinzufuegen(GpsPunkt(0, 51.0, 8.0, 0.0, 5f))                    // falscher erster Fix
        r.hinzufuegen(GpsPunkt(5_000, 50.0, 8.0, 10.0, 5f))
        r.hinzufuegen(GpsPunkt(15_000, 50.0009, 8.0, 10.0, 5f))
        r.hinzufuegen(GpsPunkt(25_000, 50.0018, 8.0, 10.0, 5f))
        assertEquals(200.0, r.meter, 10.0)                                  // ohne die 111 km
        assertEquals(50.0018, r.punkte.last().lat, 1e-9)
        assertEquals(50.0, r.punkte.first().lat, 1e-9)                    // falscher Start entfernt
    }

    @Test
    fun tunnelLueckeIstKeinSprung() {
        val r = Streckenrechner()
        r.hinzufuegen(GpsPunkt(0, 50.0, 8.0, 25.0, 5f))
        r.hinzufuegen(GpsPunkt(5 * 60_000L, 50.06, 8.0, 25.0, 5f))         // 6,7 km in 5 min = 80 km/h
        assertEquals(6670.0, r.meter, 50.0)
    }

    @Test
    fun langeLueckeImStandIstHalt() {
        val s = StillstandErkennung(10 * 60_000L)
        val vorher = GpsPunkt(0, 50.0, 8.0, 14.0)
        // 1 Stunde später 500 m weiter: App war eingefroren, Auto hat geparkt.
        assertTrue(s.lueckeIstHalt(vorher, GpsPunkt(60 * 60_000L, 50.0045, 8.0, 10.0)))
        // Tunnel: 12 Minuten, 9 km weiter (45 km/h) ist kein Halt.
        assertFalse(s.lueckeIstHalt(vorher, GpsPunkt(12 * 60_000L, 50.081, 8.0, 14.0)))
        // Kurze Lücke zählt nie.
        assertFalse(s.lueckeIstHalt(vorher, GpsPunkt(2 * 60_000L, 50.0, 8.0, 0.0)))
        assertFalse(s.lueckeIstHalt(null, vorher))
    }

    @Test
    fun keinSignalNachDoppelterZeit() {
        val s = StillstandErkennung(10 * 60_000L)
        val letzter = GpsPunkt(0, 50.0, 8.0, 5.0)
        assertFalse(s.keinSignal(letzter, 19 * 60_000L))
        assertTrue(s.keinSignal(letzter, 20 * 60_000L))
        assertFalse(s.keinSignal(null, 60 * 60_000L))
    }

    // ---------- Neue Funktionen (Ideenliste 2026-11) ----------

    private fun fahrtMit(fahrer: String, km: Double, start: Long = 100L) =
        Fahrt(neueId(), "bus", fahrer, start = start, ende = start + 100L, streckeKm = km)

    @Test
    fun ausgleichGleichtSaldoAus() {
        val fahrten = listOf(fahrtMit("ben", 600.0), fahrtMit("admin", 300.0), fahrtMit("anna", 100.0))
        val tank = listOf(
            Tankvorgang(neueId(), "bus", "ben", 100L, 1.0, 1.0, gesamtpreis = 140.0),
            Tankvorgang(neueId(), "bus", "anna", 100L, 1.0, 1.0, gesamtpreis = 20.0),
        )
        val ohne = Statistik.abrechnung(fahrten, tank, mapOf("bus" to 0.16), 0L, 1000L)
        val vorschlag = Statistik.ueberweisungen(ohne)
        assertEquals(listOf(Statistik.Ueberweisung("admin", "ben", 44.0), Statistik.Ueberweisung("admin", "anna", 4.0)), vorschlag)
        val bezahlt = vorschlag.map { Ausgleich(neueId(), it.von, it.an, it.betrag, "2026-10", 0L) }
        val mit = Statistik.abrechnung(fahrten, tank, mapOf("bus" to 0.16), 0L, 1000L, bezahlt)
        mit.forEach { assertEquals(0.0, it.saldo, 1e-9) }
        assertTrue(Statistik.ueberweisungen(mit).isEmpty())
    }

    @Test
    fun zuhauseLadenKostetNichts() {
        val t = listOf(
            Tankvorgang(neueId(), "leap", "a", 0L, 1_000.0, 20.0, gesamtpreis = 6.0, ladeort = "zuhause"),
            Tankvorgang(neueId(), "leap", "a", 0L, 1_100.0, 15.0, gesamtpreis = 9.0, ladeort = "oeffentlich"),
        )
        assertEquals(0.0, t[0].kosten, 1e-9)
        val v = Statistik.verbrauch(t, elektrisch = true)!!
        assertEquals(15.0, v.literPro100km, 1e-9)
        assertEquals(0.09, v.kostenProKm, 1e-9)
    }

    @Test
    fun laufendeFahrtZaehltNichtInDerAbrechnung() {
        val laufend = Fahrt(neueId(), "bus", "admin", start = 100L, streckeKm = 50.0)
        assertTrue(Statistik.abrechnung(listOf(laufend), emptyList(), mapOf("bus" to 0.2), 0L, 1000L).isEmpty())
    }

    @Test
    fun tachoKmGehenVorGps() {
        val f = Fahrt(neueId(), "bus", "a", start = 0L, ende = 1L, kmStart = 1_000.0, kmEnde = 1_052.0, streckeKm = 50.0)
        assertEquals(52.0, f.km, 1e-9)
        val ohneStand = f.copy(kmStart = 0.0, kmEnde = 50.0)
        assertEquals(50.0, ohneStand.km, 1e-9)
        val abw = Statistik.tachoAbweichung(listOf(f))!!
        assertEquals(1, abw.fahrten)
        assertEquals((50.0 - 52.0) / 52.0 * 100.0, abw.prozent, 1e-9)
    }

    @Test
    fun zusammenlegenUndTeilen() {
        val a = Fahrt("a", "bus", "j", start = 0L, ende = 600_000L, kmStart = 100.0, kmEnde = 110.0, streckeKm = 10.0,
            maxKmh = 80.0, startOrt = "Zuhause", zielOrt = "Bäcker",
            route = Polyline.encode(listOf(Polyline.Punkt(50.0, 8.0), Polyline.Punkt(50.01, 8.0))))
        val b = Fahrt("b", "bus", "j", start = 900_000L, ende = 1_500_000L, kmStart = 110.0, kmEnde = 120.0, streckeKm = 10.0,
            maxKmh = 100.0, startOrt = "Bäcker", zielOrt = "Arbeit",
            route = Polyline.encode(listOf(Polyline.Punkt(50.01, 8.0), Polyline.Punkt(50.02, 8.0))))
        val m = FahrtBearbeitung.zusammenlegen(a, b)
        assertEquals("a", m.id)
        assertEquals(1_500_000L, m.ende)
        assertEquals(20.0, m.streckeKm, 1e-9)
        assertEquals(120.0, m.kmEnde, 1e-9)
        assertEquals(100.0, m.maxKmh, 1e-9)
        assertEquals("Zuhause", m.startOrt)
        assertEquals("Arbeit", m.zielOrt)
        assertEquals(4, Polyline.decode(m.route).size)

        val (eins, zwei) = FahrtBearbeitung.teilen(m, 0.5, 1, "neu")
        assertEquals("a", eins.id)
        assertEquals("neu", zwei.id)
        assertEquals(750_000L, eins.ende)
        assertEquals(750_000L, zwei.start)
        assertEquals(10.0, eins.streckeKm, 1e-9)
        assertEquals(110.0, eins.kmEnde, 1e-9)
        assertEquals(110.0, zwei.kmStart, 1e-9)
        assertEquals(2, Polyline.decode(eins.route).size)
        assertEquals(3, Polyline.decode(zwei.route).size)
        assertFalse(zwei.aufServer)
    }

    @Test
    fun anteilBeiPunkt() {
        val p = listOf(Polyline.Punkt(50.0, 8.0), Polyline.Punkt(50.01, 8.0), Polyline.Punkt(50.03, 8.0))
        assertEquals(1.0 / 3.0, FahrtBearbeitung.anteilBeiPunkt(p, 1), 1e-3)
    }

    @Test
    fun plausibilitaet() {
        assertNull(Plausibel.tank(40.0, 1.8, 120_000.0))
        assertTrue(Plausibel.tank(500.0, 1.8, 120_000.0) != null)
        assertTrue(Plausibel.tank(40.0, 18.0, 120_000.0) != null)
        assertTrue(Plausibel.tank(40.0, 1.8, null) != null)
        assertTrue(Plausibel.tank(40.0, 1.8, 0.0) != null)
        val halbeStunde = 30 * 60_000L
        assertNull(Plausibel.fahrt(0L, halbeStunde, 100.0, 120.0))
        assertTrue(Plausibel.fahrt(10L, 0L, 100.0, 120.0) != null)
        assertTrue(Plausibel.fahrt(0L, halbeStunde, 120.0, 100.0) != null)
        // Tippfehler im Tacho: 123.356 km in einer halben Stunde
        assertTrue(Plausibel.fahrt(0L, halbeStunde, 100.0, 123_456.0) != null)
        // ohne bekannten Start keine Geschwindigkeitsprüfung möglich
        assertNull(Plausibel.fahrt(0L, halbeStunde, 0.0, 123_456.0))
        // 120 km in einer halben Stunde sind 240 km/h: gerade noch erlaubt
        assertNull(Plausibel.fahrt(0L, halbeStunde, 1000.0, 1120.0))
    }

    @Test
    fun fahrtInDerZukunft() {
        val jetzt = 1_800_000_000_000L
        assertTrue(Plausibel.fahrt(jetzt + 3_600_000, jetzt + 7_200_000, 0.0, 0.0, jetzt) != null)
        // Uhr geht 5 Minuten vor: noch erlaubt
        assertNull(Plausibel.fahrt(jetzt - 600_000, jetzt + 300_000, 0.0, 0.0, jetzt))
    }

    @Test
    fun startAusTacho() {
        // Erster Tacho-Stand nach 12,5 km GPS: Start wird 12,5 km davor gesetzt
        assertEquals(123_443.5, Plausibel.startAusTacho(0.0, 123_456.0, 12.5), 1e-6)
        // bekannter Start bleibt
        assertEquals(500.0, Plausibel.startAusTacho(500.0, 520.0, 12.5), 1e-6)
        // nie negativ
        assertEquals(0.0, Plausibel.startAusTacho(0.0, 5.0, 12.5), 1e-6)
    }

    @Test
    fun stauHaeltFahrtOffen() {
        val s = StillstandErkennung(10 * 60_000L)
        s.punkt(GpsPunkt(0, 50.0, 8.0, 0.0))
        assertTrue(s.sollEnden(11 * 60_000L))
        assertFalse(s.sollEnden(11 * 60_000L, imFahrzeug = true))
        // "im Fahrzeug" gilt 3 Minuten, danach oder nach "ausgestiegen" läuft der Zähler wieder.
        assertTrue(Bewegung.imFahrzeug(jetzt = 100_000L, fahrzeug = 60_000L, ausgestiegen = 0L))
        assertFalse(Bewegung.imFahrzeug(jetzt = 400_000L, fahrzeug = 60_000L, ausgestiegen = 0L))
        assertFalse(Bewegung.imFahrzeug(jetzt = 100_000L, fahrzeug = 60_000L, ausgestiegen = 90_000L))
        assertFalse(Bewegung.imFahrzeug(jetzt = 100_000L, fahrzeug = 0L, ausgestiegen = 0L))
    }

    @Test
    fun verbrauchsVerlaufProAbschnitt() {
        val t = listOf(
            tank(10_000.0, 50.0, 90.0),
            tank(10_500.0, 45.0, 80.0),
            tank(11_000.0, 40.0, 70.0),
        )
        val v = Statistik.verbrauchVerlauf(t)
        assertEquals(2, v.size)
        assertEquals(9.0, v[0].wert, 1e-9)
        assertEquals(8.0, v[1].wert, 1e-9)
    }

    @Test
    fun updateQuelleVomPi() {
        val json = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"versionCode":27,"versionName":"1.0.27","datei":"Fahrtenbuch-27.apk","sha256":"ABC"}"""
        ).let { it as kotlinx.serialization.json.JsonObject }
        val neu = Update.auswerten(json, "http://fahrtenbuch.local:8091/")!!
        assertEquals(27, neu.nummer)
        assertEquals("http://fahrtenbuch.local:8091/Fahrtenbuch-27.apk", neu.apkUrl)
        assertEquals("abc", neu.sha256)
        // Kein Pfad in "datei", keine fremden Dateien
        val boese = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"versionCode":28,"datei":"../x.apk"}"""
        ).let { it as kotlinx.serialization.json.JsonObject }
        assertEquals(null, Update.auswerten(boese, "http://a:8091/"))
    }

    @Test
    fun updateAdressenAusServerAdressen() {
        val q = Update.quellen(
            listOf("http://192.168.1.10:8090", "", "https://fahrtenbuch.beispiel.ts.net", "192.168.1.10:8090/")
        )
        assertEquals(
            listOf(
                "http://192.168.1.10:8091/",
                "http://fahrtenbuch.beispiel.ts.net:8091/",
                "http://fahrtenbuch.local:8091/",
            ),
            q,
        )
    }

    @Test
    fun zeitraumText() {
        val c = java.util.Calendar.getInstance().apply { set(2026, 9, 15, 12, 0) }
        assertEquals("2026-10", Statistik.zeitraum(c.timeInMillis))
    }

    @Test
    fun diagnoseZaehltPunkteUndLuecken() {
        val d = Diagnose(akkuStart = 80, akkuEnde = 74, aufnahmeMeter = 25)
        val r = Streckenrechner()
        val punkte = listOf(
            GpsPunkt(0, 52.0, 9.0, 10.0, 5f),
            GpsPunkt(3_000, 52.0003, 9.0, 10.0, 5f),
            GpsPunkt(6_000, 52.0003, 9.0, 0.0, 60f), // ungenau
            GpsPunkt(9_000, 52.00031, 9.0, 0.0, 5f), // Zittern im Stand
            GpsPunkt(100_000, 52.003, 9.0, 10.0, 8f), // Lücke 91 s
        )
        punkte.forEach { d.punkt(it, r.hinzufuegen(it)) }
        assertEquals(5, d.punkte)
        assertEquals(3, d.punkteGezaehlt)
        assertEquals(1, d.verworfenUngenau)
        assertEquals(1, d.verworfenZittern)
        assertEquals(1, d.luecken)
        assertEquals(91L, d.groessteLueckeSek)
        assertEquals(60.0, d.genauigkeitMaxM, 0.01)

        // Hin und zurück über JSON, auch nach einem Neustart des Dienstes geht nichts verloren.
        val zurueck = Diagnose.ausJson(d.alsJson())!!
        assertEquals(d, zurueck)
        zurueck.punkt(GpsPunkt(200_000, 52.004, 9.0, 10.0, 5f), true)
        assertEquals(2, zurueck.luecken)

        val zeilen = d.zeilen().toMap()
        assertEquals("80 % → 74 % (6 %)", zeilen["Akku"])
        assertEquals("alle 25 m", zeilen["Aufnahme"])
        assertNull(Diagnose.ausJson(""))
        assertNull(Diagnose.ausJson("kaputt"))
    }

    @Test
    fun testFahrtUndTankungStandardmaessigNormal() {
        assertFalse(Fahrt(id = "a", fahrzeugId = "f", fahrerId = "u", start = 0).test)
        assertFalse(Tankvorgang(id = "t", fahrzeugId = "f", fahrerId = "u", datum = 0, kmStand = 1.0, liter = 1.0).test)
    }

    // ---------- Beleg- und Tacho-Scan ----------

    private fun ms(jahr: Int, monat: Int, tag: Int, stunde: Int = 12): Long =
        java.util.Calendar.getInstance().apply { clear(); set(jahr, monat - 1, tag, stunde, 0, 0) }.timeInMillis

    @Test
    fun belegAralDiesel() {
        val b = ScanLeser.beleg(
            listOf(
                "ARAL Tankstelle",
                "Hauptstr. 12, 12345 Musterstadt",
                "03.11.2026 14:22",
                "Säule 4 Diesel",
                "45,23 l x 1,789 EUR/l",
                "80,92 EUR A",
                "Summe EUR 80,92",
                "MwSt 19% 12,92",
                "Girocard 80,92",
            ),
            elektro = false,
            heute = ms(2026, 11, 3, 18),
        )
        assertEquals(45.23, b.menge!!, 1e-9)
        assertEquals("L", b.einheit)
        assertEquals(1.789, b.preisProEinheit!!, 1e-9)
        assertEquals(80.92, b.gesamt!!, 1e-9)
        assertEquals("Aral", b.tankstelle)
        assertEquals(ms(2026, 11, 3), b.datum)
    }

    @Test
    fun belegZweispaltigOhneEinheitBeimPreis() {
        val b = ScanLeser.beleg(
            listOf("Shell Station", "Datum: 28.10.26", "Diesel", "Menge 52,10 L", "Preis 1,699", "Gesamt", "88,52"),
            elektro = false,
            heute = ms(2026, 11, 3),
        )
        assertEquals(52.10, b.menge!!, 1e-9)
        assertEquals(1.699, b.preisProEinheit!!, 1e-9)
        assertEquals(88.52, b.gesamt!!, 1e-9)
        assertEquals("Shell", b.tankstelle)
        assertEquals(ms(2026, 10, 28), b.datum)
    }

    @Test
    fun belegLadenKwh() {
        val b = ScanLeser.beleg(
            listOf("EnBW mobility+", "Ladevorgang 01.11.2026", "Energie 23,456 kWh", "Preis 0,59 EUR/kWh", "Gesamtbetrag 13,84 EUR"),
            elektro = true,
            heute = ms(2026, 11, 3),
        )
        assertEquals(23.456, b.menge!!, 1e-9)
        assertEquals("kWh", b.einheit)
        assertEquals(0.59, b.preisProEinheit!!, 1e-9)
        assertEquals(13.84, b.gesamt!!, 1e-9)
        assertEquals("EnBW", b.tankstelle)
    }

    @Test
    fun belegFehlenderPreisWirdBerechnet() {
        val b = ScanLeser.beleg(listOf("JET", "Diesel 40,00 l", "zu zahlen 70,00"), elektro = false, heute = ms(2026, 11, 3))
        assertEquals(40.0, b.menge!!, 1e-9)
        assertEquals(1.75, b.preisProEinheit!!, 1e-9)
        assertEquals(70.0, b.gesamt!!, 1e-9)
    }

    @Test
    fun belegUnlesbarIstLeerUndZukunftsdatumZaehltNicht() {
        val b = ScanLeser.beleg(listOf("Vielen Dank", "Gute Fahrt", "24.12.2027"), elektro = false, heute = ms(2026, 11, 3))
        assertTrue(b.leer)
        assertNull(b.datum)
        assertNull(b.tankstelle)
    }

    @Test
    fun tachoNimmtGesamtKmNichtTagesKmOderTempo() {
        val texte = listOf(
            ScanLeser.TachoText("123456 km", 40f),
            ScanLeser.TachoText("87 km/h", 60f),
            ScanLeser.TachoText("234,5", 20f),
            ScanLeser.TachoText("12:45", 20f),
            ScanLeser.TachoText("18°C", 20f),
        )
        assertEquals(123456, ScanLeser.tacho(texte, 123000.0).first())
        assertEquals(123456, ScanLeser.tacho(texte, 0.0).first())
    }

    @Test
    fun tachoMitTausenderpunktUndOhneVorwissen() {
        assertEquals(listOf(123456), ScanLeser.tacho(listOf(ScanLeser.TachoText("ODO 123.456 km")), 0.0))
        val ohne = ScanLeser.tacho(listOf(ScanLeser.TachoText("15:30"), ScanLeser.TachoText("045210"), ScanLeser.TachoText("312.4 km")), 0.0)
        assertEquals(45210, ohne.first())
    }

    @Test
    fun tachoLaeuftNichtRueckwaerts() {
        val v = ScanLeser.tacho(listOf(ScanLeser.TachoText("49990", 50f), ScanLeser.TachoText("50120", 30f)), 50000.0)
        assertEquals(listOf(50120), v)
    }

    @Test
    fun kostenBetragLesen() {
        assertEquals(189.9, de.hjorven.fahrtenbuch.ui.betragLesen("189,90")!!, 1e-9)
        assertEquals(1200.5, de.hjorven.fahrtenbuch.ui.betragLesen("1.200,50 €")!!, 1e-9)
        assertEquals(45.0, de.hjorven.fahrtenbuch.ui.betragLesen("45")!!, 1e-9)
        assertEquals(null, de.hjorven.fahrtenbuch.ui.betragLesen("0"))
        assertEquals(null, de.hjorven.fahrtenbuch.ui.betragLesen("-5"))
        assertEquals(null, de.hjorven.fahrtenbuch.ui.betragLesen("60000"))
        assertEquals(null, de.hjorven.fahrtenbuch.ui.betragLesen("abc"))
    }

    @Test
    fun fehlertextMitWasTun() {
        val F = de.hjorven.fahrtenbuch.data.Fehlertext
        val api = { c: Int, m: String -> de.hjorven.fahrtenbuch.data.ApiFehler(c, m) }
        assertTrue(F.aus(api(401, "x")).contains("neu anmelden"))
        assertEquals("Datum liegt in der Zukunft.", F.aus(api(400, "Datum liegt in der Zukunft.")))
        assertTrue(F.aus(api(400, "HTTP 400"), "Speichern").startsWith("Speichern ging nicht"))
        assertTrue(F.aus(api(502, "HTTP 502")).contains("Problem melden"))
        assertEquals(F.OFFLINE, F.aus(java.net.UnknownHostException("fahrtenbuch.local")))
        assertEquals(F.OFFLINE, F.aus(java.net.SocketTimeoutException()))
    }

    @Test
    fun monatsabrechnungNachricht() {
        val M = de.hjorven.fahrtenbuch.data.Monatsabrechnung
        val u = { v: String, a: String, b: Double -> Statistik.Ueberweisung(v, a, b) }
        val namen = mapOf("ben" to "Ben", "anna" to "Anna")
        val n = M.nachricht(listOf(u("ich", "ben", 23.4)), "ich", namen, 9)!!
        assertEquals("Abrechnung Oktober", n.titel)
        assertEquals("Du zahlst Ben 23,40 €.", n.text)
        assertEquals("ben", n.bezahlen?.an)
        val b = M.nachricht(listOf(u("anna", "ich", 5.0)), "ich", namen, 0)!!
        assertEquals("Anna zahlt dir 5,00 €.", b.text)
        assertNull(b.bezahlen)
        assertNull(M.nachricht(listOf(u("anna", "ben", 5.0)), "ich", namen, 0))
        assertNull(M.nachricht(listOf(u("ich", "ben", 1.0), u("ich", "anna", 2.0)), "ich", namen, 0)!!.bezahlen)
        val (z, von, bis) = M.vormonat(java.util.GregorianCalendar(2026, 10, 3, 12, 0).timeInMillis)
        assertEquals("2026-10", z)
        assertEquals(java.util.GregorianCalendar(2026, 9, 1).timeInMillis, von)
        assertEquals(java.util.GregorianCalendar(2026, 10, 1).timeInMillis, bis)
    }

    @Test
    fun abrechnungMitMitfahrernUndVorlaeufig() {
        val bus = mapOf("bus" to 0.20)
        val fahrten = listOf(
            // 100 km = 20 €: Ben fährt, Anna fährt mit -> je 10 €, km nur beim Ben
            Fahrt(neueId(), "bus", "ben", start = 100L, ende = 200L, streckeKm = 100.0, mitfahrer = "anna"),
            // vorläufig: zählt nicht
            Fahrt(neueId(), "bus", "ben", start = 300L, ende = 400L, streckeKm = 500.0, vorlaeufig = true),
        )
        val r = Statistik.abrechnung(fahrten, emptyList(), bus, 0L, 1000L).associateBy { it.fahrerId }
        assertEquals(100.0, r["ben"]!!.km, 1e-9)
        assertEquals(10.0, r["ben"]!!.anteil, 1e-9)
        assertEquals(0.0, r["anna"]!!.km, 1e-9)
        assertEquals(10.0, r["anna"]!!.anteil, 1e-9)
        assertEquals(listOf("a", "b"), Fahrt("x", "bus", "v", 0L, mitfahrer = "a,,b").mitfahrerIds)
    }

    @Test
    fun bluetoothZuordnung() {
        val B = de.hjorven.fahrtenbuch.location.AutoBluetooth
        val m = mapOf("AA:BB" to "kombi", "CC:DD" to "bus")
        assertEquals(m, B.lesen(B.schreiben(m)))
        assertEquals(emptyMap<String, String>(), B.lesen(""))
        assertEquals(mapOf("AA" to "x"), B.lesen("AA=x;kaputt;=y;z="))
    }

    @Test
    fun ladeAppScreenshot() {
        // Typischer Screenshot einer Lade-App (Roadmap Nr. 14)
        val z = listOf("Ladevorgang beendet", "EnBW HPC Raststätte", "Geladene Energie", "32,45 kWh", "Dauer 00:41:12",
            "Preis pro kWh 0,59 €", "Gesamtbetrag", "19,15 €")
        val b = ScanLeser.beleg(z, elektro = true)
        assertEquals(32.45, b.menge!!, 1e-9)
        assertEquals(19.15, b.gesamt!!, 1e-9)
    }

    @Test
    fun unbenutzteStartautosNichtDoppeltHochladen() {
        assertTrue(Antrieb.unbenutztesStartauto("Kombi", 0.0, 0, true))
        assertFalse(Antrieb.unbenutztesStartauto("Kombi", 0.0, 1, true))
        assertFalse(Antrieb.unbenutztesStartauto("Kombi", 1200.0, 0, true))
        assertFalse(Antrieb.unbenutztesStartauto("Kombi", 0.0, 0, false))
        assertFalse(Antrieb.unbenutztesStartauto("Mein Auto", 0.0, 0, true))
    }
}
