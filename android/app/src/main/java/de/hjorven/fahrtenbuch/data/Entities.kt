package de.hjorven.fahrtenbuch.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Lokale Kopien der PocketBase-Tabellen (siehe docs/DATENMODELL.md).
 *
 * dirty = lokal geändert, muss noch hochgeladen werden.
 * aufServer = Datensatz existiert schon auf dem Server (dann PATCH statt POST).
 */

@Entity(tableName = "fahrzeug")
data class Fahrzeug(
    @PrimaryKey val id: String,
    val name: String,
    val kennzeichen: String = "",
    val kraftstoff: String = "",
    val kmStand: Double = 0.0,
    val preisProKm: Double = 0.0,
    val farbe: String = "#1565C0",
    val aktiv: Boolean = true,
    val dirty: Boolean = false,
    val aufServer: Boolean = true,
)

@Entity(tableName = "benutzer")
data class Benutzer(
    @PrimaryKey val id: String,
    val name: String,
)

@Entity(tableName = "fahrt", indices = [Index("fahrzeugId"), Index("start")])
data class Fahrt(
    @PrimaryKey val id: String,
    val fahrzeugId: String,
    val fahrerId: String,
    val start: Long,
    val ende: Long? = null,
    val kmStart: Double = 0.0,
    val kmEnde: Double = 0.0,
    val streckeKm: Double = 0.0,
    val maxKmh: Double = 0.0,
    val schnittKmh: Double = 0.0,
    val zweck: String = "privat",
    val startOrt: String = "",
    val zielOrt: String = "",
    val route: String = "",
    val notiz: String = "",
    val geloescht: Boolean = false,
    /** Im Test-Modus aufgezeichnet: zählt nirgends mit und hebt den km-Stand nicht an. */
    val test: Boolean = false,
    /** Nur bei Test-Fahrten: Diagnose als JSON (siehe location/Diagnose.kt). */
    val diagnose: String = "",
    val dirty: Boolean = true,
    val aufServer: Boolean = false,
    /** Mitfahrer (Benutzer-IDs, mit Komma getrennt). Setzt nur der Server (POST /api/fahrtenbuch/mitfahren). */
    val mitfahrer: String = "",
    /** Automatisch gestartet und noch nicht bestätigt: zählt nirgends (Roadmap Nr. 6). */
    val vorlaeufig: Boolean = false,
) {
    val laeuft: Boolean get() = ende == null

    val mitfahrerIds: List<String> get() = mitfahrer.split(',').filter { it.isNotBlank() }

    /**
     * Gefahrene km: km_ende - km_start, wenn beide plausibel sind (z. B. nach Tacho-Abgleich),
     * sonst die GPS-Strecke. Dieselbe Regel nutzt das Display.
     */
    val km: Double
        get() = if (kmStart > 0 && kmEnde > kmStart) kmEnde - kmStart else streckeKm
}

@Entity(tableName = "tankvorgang", indices = [Index("fahrzeugId"), Index("datum")])
data class Tankvorgang(
    @PrimaryKey val id: String,
    val fahrzeugId: String,
    val fahrerId: String,
    val datum: Long,
    val kmStand: Double,
    val liter: Double,
    val preisProLiter: Double = 0.0,
    val gesamtpreis: Double = 0.0,
    val vollgetankt: Boolean = true,
    val tankstelle: String = "",
    /** Nur bei E-Autos: "zuhause" oder "oeffentlich". Dann steht liter für kWh. */
    val ladeort: String = "",
    val notiz: String = "",
    val geloescht: Boolean = false,
    /** Im Test-Modus eingetragen: zählt nirgends mit. */
    val test: Boolean = false,
    /** Dateiname des Belegfotos auf dem Server ("" = keins oder noch nicht hochgeladen). */
    val beleg: String = "",
    /** Lokales Belegfoto (Belege.datei) muss noch hochgeladen werden. */
    val belegOffen: Boolean = false,
    val dirty: Boolean = true,
    val aufServer: Boolean = false,
) {
    /** Laden zuhause kostet nichts, unabhängig vom eingetragenen Preis. */
    val kosten: Double get() = if (ladeort == "zuhause") 0.0 else gesamtpreis
}

/**
 * Ausgleichszahlung für die Monatsabrechnung: [von] hat [an] den Betrag für [zeitraum]
 * ("2026-10") bezahlt. Ändern darf, wer beteiligt ist.
 */
@Entity(tableName = "ausgleich", indices = [Index("zeitraum")])
data class Ausgleich(
    @PrimaryKey val id: String,
    val von: String,
    val an: String,
    val betrag: Double,
    val zeitraum: String,
    val datum: Long,
    val notiz: String = "",
    val geloescht: Boolean = false,
    val dirty: Boolean = true,
    val aufServer: Boolean = false,
)
