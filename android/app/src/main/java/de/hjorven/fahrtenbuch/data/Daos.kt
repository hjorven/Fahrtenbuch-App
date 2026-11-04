package de.hjorven.fahrtenbuch.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FahrzeugDao {
    @Query("SELECT * FROM fahrzeug WHERE aktiv = 1 ORDER BY name")
    fun aktive(): Flow<List<Fahrzeug>>

    @Query("SELECT * FROM fahrzeug ORDER BY name")
    fun alle(): Flow<List<Fahrzeug>>

    @Query("SELECT * FROM fahrzeug WHERE id = :id")
    suspend fun get(id: String): Fahrzeug?

    @Query("SELECT * FROM fahrzeug WHERE dirty = 1")
    suspend fun dirty(): List<Fahrzeug>

    @Query("SELECT COUNT(*) FROM fahrzeug")
    suspend fun anzahl(): Int

    @Query("SELECT * FROM fahrzeug WHERE aufServer = 0")
    suspend fun lokale(): List<Fahrzeug>

    @Query("DELETE FROM fahrzeug WHERE id = :id")
    suspend fun loeschen(id: String)

    @Upsert
    suspend fun upsert(f: Fahrzeug)
}

@Dao
interface BenutzerDao {
    @Query("SELECT * FROM benutzer")
    fun alle(): Flow<List<Benutzer>>

    @Upsert
    suspend fun upsert(b: Benutzer)
}

@Dao
interface FahrtDao {
    @Query("SELECT * FROM fahrt WHERE geloescht = 0 ORDER BY start DESC")
    fun alle(): Flow<List<Fahrt>>

    @Query("SELECT * FROM fahrt WHERE id = :id")
    fun beobachten(id: String): Flow<Fahrt?>

    @Query("SELECT * FROM fahrt WHERE id = :id")
    suspend fun get(id: String): Fahrt?

    @Query("SELECT * FROM fahrt WHERE ende IS NULL AND fahrerId = :fahrerId AND geloescht = 0 LIMIT 1")
    suspend fun laufende(fahrerId: String): Fahrt?

    @Query("SELECT * FROM fahrt WHERE dirty = 1")
    suspend fun dirty(): List<Fahrt>

    /** Laufende Fahrt eines anderen mit diesem Auto (Roadmap Nr. 6: "Ben fährt den Kombi"). */
    @Query("SELECT * FROM fahrt WHERE fahrzeugId = :fahrzeugId AND ende IS NULL AND fahrerId != :ich AND geloescht = 0 AND start > :nach ORDER BY start DESC LIMIT 1")
    suspend fun laufendeAndere(fahrzeugId: String, ich: String, nach: Long): Fahrt?

    /** Nächste beendete Fahrt desselben Fahrers mit demselben Auto (zum Zusammenlegen). */
    @Query(
        "SELECT * FROM fahrt WHERE fahrerId = :fahrerId AND fahrzeugId = :fahrzeugId AND geloescht = 0 " +
            "AND ende IS NOT NULL AND start >= :nach AND id != :ohne AND test = :test ORDER BY start LIMIT 1"
    )
    suspend fun naechste(fahrerId: String, fahrzeugId: String, nach: Long, ohne: String, test: Boolean): Fahrt?

    @Query("UPDATE fahrt SET fahrzeugId = :neu, dirty = 1 WHERE fahrzeugId = :alt")
    suspend fun fahrzeugTauschen(alt: String, neu: String)

    @Query("UPDATE fahrt SET fahrerId = :neu, dirty = 1 WHERE fahrerId = :alt")
    suspend fun fahrerTauschen(alt: String, neu: String)

    /** "Alle Test-Fahrten löschen": weich löschen, damit kein Handy sie wieder hochlädt. */
    @Query("UPDATE fahrt SET geloescht = 1, dirty = 1 WHERE test = 1 AND geloescht = 0 AND ende IS NOT NULL AND fahrerId = :fahrerId")
    suspend fun testLoeschen(fahrerId: String): Int

    @Query("SELECT * FROM fahrt WHERE test = 1 AND diagnose != '' ORDER BY start DESC LIMIT 1")
    suspend fun letzteMitDiagnose(): Fahrt?

    /** Eigene Fahrten, die im Zeitraum angefangen haben (Roadmap Nr. 7). */
    @Query("SELECT COUNT(*) FROM fahrt WHERE fahrerId = :fahrerId AND geloescht = 0 AND start BETWEEN :von AND :bis")
    suspend fun zwischen(fahrerId: String, von: Long, bis: Long): Int

    @Query("SELECT COUNT(*) FROM fahrt WHERE fahrzeugId = :fahrzeugId")
    suspend fun anzahlMitAuto(fahrzeugId: String): Int

    @Query("SELECT COUNT(*) FROM fahrt WHERE dirty = 1")
    suspend fun anzahlDirty(): Int

    @Upsert
    suspend fun upsert(f: Fahrt)
}

@Dao
interface TankDao {
    @Query("SELECT * FROM tankvorgang WHERE geloescht = 0 ORDER BY datum DESC")
    fun alle(): Flow<List<Tankvorgang>>

    @Query("SELECT * FROM tankvorgang WHERE dirty = 1")
    suspend fun dirty(): List<Tankvorgang>

    @Query("UPDATE tankvorgang SET fahrzeugId = :neu, dirty = 1 WHERE fahrzeugId = :alt")
    suspend fun fahrzeugTauschen(alt: String, neu: String)

    @Query("UPDATE tankvorgang SET fahrerId = :neu, dirty = 1 WHERE fahrerId = :alt")
    suspend fun fahrerTauschen(alt: String, neu: String)

    @Query("SELECT * FROM tankvorgang WHERE id = :id")
    suspend fun get(id: String): Tankvorgang?

    @Query("UPDATE tankvorgang SET geloescht = 1, dirty = 1 WHERE test = 1 AND geloescht = 0 AND fahrerId = :fahrerId")
    suspend fun testLoeschen(fahrerId: String): Int

    @Query("SELECT COUNT(*) FROM tankvorgang WHERE fahrzeugId = :fahrzeugId")
    suspend fun anzahlMitAuto(fahrzeugId: String): Int

    @Query("SELECT COUNT(*) FROM tankvorgang WHERE dirty = 1")
    suspend fun anzahlDirty(): Int

    /** Belegfotos, die noch hoch müssen (Datensatz selbst ist schon auf dem Server). */
    @Query("SELECT * FROM tankvorgang WHERE belegOffen = 1 AND aufServer = 1 AND dirty = 0")
    suspend fun belegOffen(): List<Tankvorgang>

    @Query("UPDATE tankvorgang SET belegOffen = 0, beleg = :name WHERE id = :id")
    suspend fun belegHochgeladen(id: String, name: String)

    @Upsert
    suspend fun upsert(t: Tankvorgang)
}

@Dao
interface AusgleichDao {
    @Query("SELECT * FROM ausgleich WHERE geloescht = 0 ORDER BY datum DESC")
    fun alle(): Flow<List<Ausgleich>>

    @Query("SELECT * FROM ausgleich WHERE id = :id")
    suspend fun get(id: String): Ausgleich?

    @Query("SELECT * FROM ausgleich WHERE dirty = 1")
    suspend fun dirty(): List<Ausgleich>

    @Query("UPDATE ausgleich SET von = :neu, dirty = 1 WHERE von = :alt")
    suspend fun vonTauschen(alt: String, neu: String)

    @Query("UPDATE ausgleich SET an = :neu, dirty = 1 WHERE an = :alt")
    suspend fun anTauschen(alt: String, neu: String)

    @Upsert
    suspend fun upsert(a: Ausgleich)
}
