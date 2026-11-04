package de.hjorven.fahrtenbuch.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Fahrzeug::class, Benutzer::class, Fahrt::class, Tankvorgang::class, Ausgleich::class],
    version = 6,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fahrzeugDao(): FahrzeugDao
    abstract fun benutzerDao(): BenutzerDao
    abstract fun fahrtDao(): FahrtDao
    abstract fun tankDao(): TankDao
    abstract fun ausgleichDao(): AusgleichDao

    companion object {
        fun erstellen(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "fahrtenbuch.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()

        /** Version 2: Laden für E-Autos (ladeort), Antriebsart der Startautos. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tankvorgang ADD COLUMN ladeort TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE fahrzeug SET kraftstoff = 'elektro' WHERE kraftstoff = '' AND lower(trim(name)) = 'e-auto'")
                db.execSQL("UPDATE fahrzeug SET kraftstoff = 'diesel' WHERE kraftstoff = '' AND lower(trim(name)) IN ('kombi', 'bus')")
            }
        }

        /** Version 3: Ausgleichszahlungen für die Monatsabrechnung. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ausgleich` (`id` TEXT NOT NULL, `von` TEXT NOT NULL, " +
                        "`an` TEXT NOT NULL, `betrag` REAL NOT NULL, `zeitraum` TEXT NOT NULL, `datum` INTEGER NOT NULL, " +
                        "`notiz` TEXT NOT NULL, `geloescht` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, " +
                        "`aufServer` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ausgleich_zeitraum` ON `ausgleich` (`zeitraum`)")
            }
        }

        /** Version 4: Test-Modus (test, diagnose). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE fahrt ADD COLUMN test INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE fahrt ADD COLUMN diagnose TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tankvorgang ADD COLUMN test INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Version 6: Mitfahrer und vorläufige Fahrten (Roadmap Nr. 6). */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE fahrt ADD COLUMN mitfahrer TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE fahrt ADD COLUMN vorlaeufig INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Version 5: Belegfoto am Tank-/Ladevorgang. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tankvorgang ADD COLUMN beleg TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tankvorgang ADD COLUMN belegOffen INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
