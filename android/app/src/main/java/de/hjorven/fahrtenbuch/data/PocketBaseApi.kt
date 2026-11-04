package de.hjorven.fahrtenbuch.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ApiFehler(val status: Int, message: String) : Exception(message)

/** Minimaler PocketBase-Client: Login, Liste seit Zeitpunkt, Anlegen, Ändern. */
class PocketBaseApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    /** Kurze Zeitlimits nur für die Erreichbarkeitsprüfung, damit der Wechsel zur zweiten Adresse schnell geht. */
    private val pruefClient = client.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    /** Antwortet unter dieser Adresse ein PocketBase-Server? */
    suspend fun erreichbar(serverUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(serverUrl.trimEnd('/') + "/api/health").get().build()
            pruefClient.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }
    /** Beliebige Adresse erreichbar (z. B. android.json auf Port 8091), kurze Zeitgrenze. */
    suspend fun erreichbarRoh(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            pruefClient.newCall(Request.Builder().url(url).get().build()).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }
    private val jsonType = "application/json".toMediaType()

    /** [passwortAendern]: Konto hat noch das Start-Passwort (Feld users.passwort_aendern). */
    data class LoginErgebnis(val token: String, val userId: String, val name: String, val passwortAendern: Boolean = false)

    private fun anmeldungLesen(res: JsonObject): LoginErgebnis {
        val record = res["record"]!!.jsonObject
        return LoginErgebnis(
            token = res["token"]!!.jsonPrimitive.content,
            userId = record["id"]!!.jsonPrimitive.content,
            name = record["name"]?.jsonPrimitive?.content ?: "",
            passwortAendern = record["passwort_aendern"]?.jsonPrimitive?.content == "true",
        )
    }

    /** Mit Einladungscode ein Konto anlegen (Roadmap Nr. 1/2), danach angemeldet. */
    suspend fun beitreten(serverUrl: String, code: String, name: String, email: String, passwort: String): LoginErgebnis =
        anmeldungLesen(senden(serverUrl, null, "POST", "/api/fahrtenbuch/beitreten", buildJsonObject {
            put("code", code); put("name", name); put("email", email); put("passwort", passwort)
        }))

    /** Neues Passwort mit Reset-Code vom Admin (Nachtplan 5c), danach angemeldet. */
    suspend fun zuruecksetzen(serverUrl: String, code: String, passwort: String): LoginErgebnis =
        anmeldungLesen(senden(serverUrl, null, "POST", "/api/fahrtenbuch/zuruecksetzen", buildJsonObject {
            put("code", code); put("passwort", passwort)
        }))

    /** "Problem melden" (Roadmap Nr. 21): Text, App-Version, Gerät und Diagnose an den Admin. */
    suspend fun berichtSenden(serverUrl: String, token: String, userId: String, text: String, version: String, geraet: String, diagnose: String) {
        senden(serverUrl, token, "POST", "/api/collections/bericht/records", buildJsonObject {
            put("von", userId); put("text", text.take(4000)); put("app_version", version); put("geraet", geraet)
            if (diagnose.isNotBlank()) put("diagnose", Json.parseToJsonElement(diagnose))
        })
    }

    /** Einladungscode für ein neues Familienmitglied (24 h gültig). */
    /** "Ich fahre mit" bzw. austragen (Roadmap Nr. 6). Gibt die neue Mitfahrer-Liste zurück. */
    suspend fun mitfahren(serverUrl: String, token: String, fahrtId: String, raus: Boolean): List<String> {
        val r = senden(serverUrl, token, "POST", "/api/fahrtenbuch/mitfahren", buildJsonObject {
            put("fahrt", fahrtId); put("raus", raus)
        })
        return (r["mitfahrer"] as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
    }

    suspend fun einladungErstellen(serverUrl: String, token: String): String =
        senden(serverUrl, token, "POST", "/api/fahrtenbuch/einladung", buildJsonObject {})["code"]!!.jsonPrimitive.content

    suspend fun login(serverUrl: String, email: String, passwort: String): LoginErgebnis {
        val body = buildJsonObject {
            put("identity", email)
            put("password", passwort)
        }
        val res = senden(serverUrl, null, "POST", "/api/collections/users/auth-with-password", body)
        val record = res["record"]!!.jsonObject
        return LoginErgebnis(
            token = res["token"]!!.jsonPrimitive.content,
            userId = record["id"]!!.jsonPrimitive.content,
            name = record["name"]?.jsonPrimitive?.content ?: "",
            passwortAendern = record["passwort_aendern"]?.jsonPrimitive?.content == "true",
        )
    }

    /** Erneuert das Token (PocketBase-Tokens laufen nach einigen Tagen ab). */
    /** Neues Token und ob das Konto noch das Start-Passwort hat. */
    suspend fun tokenErneuern(serverUrl: String, token: String): Pair<String, Boolean> {
        val res = senden(serverUrl, token, "POST", "/api/collections/users/auth-refresh", JsonObject(emptyMap()))
        val aendern = res["record"]?.jsonObject?.get("passwort_aendern")?.jsonPrimitive?.content == "true"
        return res["token"]!!.jsonPrimitive.content to aendern
    }

    /** Eigenes Passwort ändern. Danach ist das alte Token ungültig, also neu anmelden. */
    suspend fun passwortAendern(serverUrl: String, token: String, userId: String, alt: String, neu: String) {
        val body = buildJsonObject {
            put("oldPassword", alt)
            put("password", neu)
            put("passwordConfirm", neu)
        }
        senden(serverUrl, token, "PATCH", "/api/collections/users/records/$userId", body)
    }

    /** Alle Datensätze, deren updated >= seit ist, aufsteigend sortiert, über alle Seiten. */
    suspend fun liste(serverUrl: String, token: String, collection: String, seit: String): List<JsonObject> {
        val alle = ArrayList<JsonObject>()
        var seite = 1
        while (true) {
            val url = (serverUrl.trimEnd('/') + "/api/collections/$collection/records").toHttpUrl().newBuilder()
                .addQueryParameter("page", seite.toString())
                .addQueryParameter("perPage", "200")
                .addQueryParameter("sort", "updated")
                .addQueryParameter("skipTotal", "1")
                .apply { if (seit.isNotEmpty()) addQueryParameter("filter", "updated >= '$seit'") }
                .build()
            val res = ausfuehren(Request.Builder().url(url).header("Authorization", token).get().build())
            val items = res["items"]?.jsonArray ?: JsonArray(emptyList())
            items.forEach { alle.add(it.jsonObject) }
            val perPage = res["perPage"]?.jsonPrimitive?.int ?: 200
            if (items.size < perPage) break
            seite++
        }
        return alle
    }

    /** Adressen des Servers (GET /api/fahrtenbuch/info, Roadmap Nr. 3). */
    suspend fun info(serverUrl: String, token: String): JsonObject =
        ausfuehren(Request.Builder().url(serverUrl.trimEnd('/') + "/api/fahrtenbuch/info").header("Authorization", token).get().build())

    suspend fun anlegen(serverUrl: String, token: String, collection: String, body: JsonObject): JsonObject =
        senden(serverUrl, token, "POST", "/api/collections/$collection/records", body)

    suspend fun aendern(serverUrl: String, token: String, collection: String, id: String, body: JsonObject): JsonObject =
        senden(serverUrl, token, "PATCH", "/api/collections/$collection/records/$id", body)

    /** Belegfoto als Datei an einen Tankvorgang hängen (multipart PATCH). */
    suspend fun belegHochladen(serverUrl: String, token: String, id: String, datei: java.io.File): JsonObject {
        val body = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("beleg", "beleg.jpg", datei.asRequestBody("image/jpeg".toMediaType()))
            .build()
        val req = Request.Builder()
            .url(serverUrl.trimEnd('/') + "/api/collections/tankvorgang/records/$id")
            .patch(body)
            .header("Authorization", token)
            .build()
        return ausfuehren(req)
    }

    /** Belegfoto vom Server holen. Die Fotos sind geschützt, deshalb erst ein Datei-Token. */
    suspend fun belegLaden(serverUrl: String, token: String, id: String, name: String, ziel: java.io.File) {
        val dateiToken = senden(serverUrl, token, "POST", "/api/files/token", JsonObject(emptyMap()))["token"]!!.jsonPrimitive.content
        val url = (serverUrl.trimEnd('/') + "/api/files/tankvorgang/$id/$name").toHttpUrl().newBuilder()
            .addQueryParameter("token", dateiToken)
            .build()
        withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) throw ApiFehler(resp.code, "HTTP ${resp.code}")
                ziel.parentFile?.mkdirs()
                val tmp = java.io.File(ziel.path + ".tmp")
                tmp.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
                tmp.renameTo(ziel)
            }
        }
    }

    private suspend fun senden(serverUrl: String, token: String?, methode: String, pfad: String, body: JsonObject): JsonObject {
        val req = Request.Builder()
            .url(serverUrl.trimEnd('/') + pfad)
            .method(methode, body.toString().toRequestBody(jsonType))
            .apply { if (token != null) header("Authorization", token) }
            .build()
        return ausfuehren(req)
    }

    private suspend fun ausfuehren(req: Request): JsonObject = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = try {
                    json.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.content
                } catch (_: Exception) {
                    null
                }
                throw ApiFehler(resp.code, msg ?: "HTTP ${resp.code}")
            }
            if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text).jsonObject
        }
    }
}
