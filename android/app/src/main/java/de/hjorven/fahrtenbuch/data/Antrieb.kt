package de.hjorven.fahrtenbuch.data

/** Antriebsart steht im Feld kraftstoff: benzin, diesel, elektro oder sonstiges. */
val Fahrzeug.elektrisch: Boolean get() = kraftstoff == "elektro"

/** Einheit der Tank- bzw. Lademenge. */
val Fahrzeug.einheit: String get() = if (elektrisch) "kWh" else "L"

object Antrieb {
    val ARTEN = listOf("diesel" to "Diesel", "benzin" to "Benzin", "elektro" to "Elektro", "sonstiges" to "Sonstiges")

    /** Namen der drei Startautos (siehe FahrtenbuchApp.STARTAUTOS), als Schlüssel. */
    val STARTAUTO_NAMEN = setOf("e-auto", "kombi", "bus")

    /** Startauto ohne Fahrten, Tankungen und km wird nicht hochgeladen, wenn der Server schon Autos hat. */
    fun unbenutztesStartauto(name: String, km: Double, benutzt: Int, serverHatAutos: Boolean): Boolean =
        serverHatAutos && benutzt == 0 && km <= 0 && namensSchluessel(name) in STARTAUTO_NAMEN

    /** Schlüssel für den Namensabgleich zwischen lokalen und Server-Autos. */
    fun namensSchluessel(name: String): String = name.trim().lowercase()
}
