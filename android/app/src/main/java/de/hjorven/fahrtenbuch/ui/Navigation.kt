package de.hjorven.fahrtenbuch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import de.hjorven.fahrtenbuch.data.SyncStatus
import de.hjorven.fahrtenbuch.ui.screens.EinrichtungScreen
import de.hjorven.fahrtenbuch.ui.screens.EinstellungenScreen
import de.hjorven.fahrtenbuch.ui.screens.FahrtDetailScreen
import de.hjorven.fahrtenbuch.ui.screens.FahrtScreen
import de.hjorven.fahrtenbuch.ui.screens.FahrtenScreen
import de.hjorven.fahrtenbuch.ui.screens.KarteScreen
import de.hjorven.fahrtenbuch.ui.screens.LoginScreen
import de.hjorven.fahrtenbuch.ui.screens.StatistikScreen
import de.hjorven.fahrtenbuch.ui.screens.TankenScreen

private data class Ziel(val route: String, val titel: String, val icon: ImageVector)

private val HAUPTZIELE = listOf(
    Ziel("fahrt", "Fahrt", Icons.Filled.DirectionsCar),
    Ziel("fahrten", "Fahrten", Icons.Filled.ListAlt),
    Ziel("tanken", "Tanken", Icons.Filled.LocalGasStation),
    Ziel("statistik", "Statistik", Icons.Filled.BarChart),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FahrtenbuchOberflaeche(vm: HauptViewModel = viewModel()) {
    // Kein Anmeldezwang: ohne Server läuft die App rein lokal.
    val anmeldung by vm.anmeldung.collectAsStateWithLifecycle()
    val einrichtung by vm.einrichtungZeigen.collectAsStateWithLifecycle()
    if (einrichtung) {
        EinrichtungScreen(fertig = { vm.einrichtung(false) })
        return
    }

    val nav = rememberNavController()
    val eintrag by nav.currentBackStackEntryAsState()
    val route = eintrag?.destination?.route ?: "fahrt"
    val hauptziel = HAUPTZIELE.firstOrNull { it.route == route }
    val sync by vm.syncStatus.collectAsStateWithLifecycle()
    val testModus by vm.testModus.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            route == "einstellungen" -> "Einstellungen"
                            route == "login" -> "Mit Server verbinden"
                            route == "karte" -> "Karte"
                            route.startsWith("fahrt/") -> "Fahrt"
                            hauptziel?.route == "fahrt" -> "Fahrtenbuch"
                            else -> hauptziel?.titel ?: "Fahrtenbuch"
                        }
                    )
                },
                navigationIcon = {
                    if (hauptziel == null) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                        }
                    }
                },
                actions = {
                    if (anmeldung != null) {
                        IconButton(onClick = {
                            if ((sync as? SyncStatus.Fehler)?.abgelaufen == true) nav.navigate("login") { launchSingleTop = true }
                            else vm.syncJetzt()
                        }) {
                            when (sync) {
                                SyncStatus.Laeuft -> CircularProgressIndicator(modifier = Modifier.padding(12.dp), strokeWidth = 2.dp)
                                is SyncStatus.Fehler -> Icon(Icons.Filled.SyncProblem, contentDescription = "Sync-Fehler")
                                SyncStatus.Offline -> Icon(Icons.Filled.CloudOff, contentDescription = "Offline")
                                SyncStatus.Bereit -> Icon(Icons.Filled.Sync, contentDescription = "Synchronisieren")
                            }
                        }
                    }
                    if (route != "einstellungen" && route != "login") {
                        IconButton(onClick = { nav.navigate("einstellungen") }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (hauptziel != null) {
                NavigationBar {
                    HAUPTZIELE.forEach { z ->
                        NavigationBarItem(
                            selected = z.route == route,
                            onClick = {
                                nav.navigate(z.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(z.icon, contentDescription = null) },
                            label = { Text(z.titel) },
                        )
                    }
                }
            }
        },
    ) { innen ->
        Column(Modifier.padding(innen)) {
        // Test-Modus: gut sichtbar in allen Menüs, damit er nicht aus Versehen an bleibt.
        if (testModus) {
            Surface(
                color = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary,
                modifier = Modifier.fillMaxWidth().clickable { nav.navigate("einstellungen") { launchSingleTop = true } },
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Science, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "TEST-MODUS: neue Fahrten und Tankungen zählen nicht mit. Tippen zum Ausschalten.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        // Sichtbar in allen Menüs, solange angemeldet und kein Server antwortet.
        if (anmeldung != null && sync == SyncStatus.Offline) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth().clickable { vm.syncJetzt() },
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CloudOff, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Keine Verbindung zum Heim-Server. Alles wird auf dem Handy gespeichert und später übertragen. Tippen: erneut versuchen.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        // Token abgelaufen (z. B. Passwort auf einem anderen Gerät geändert): ohne Hinweis
        // würde nur das kleine Sync-Symbol rot, und Fahrten blieben unbemerkt auf dem Handy.
        if (anmeldung != null && (sync as? SyncStatus.Fehler)?.abgelaufen == true && route != "login") {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth().clickable { nav.navigate("login") { launchSingleTop = true } },
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.SyncProblem, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Anmeldung abgelaufen. Tippen, um dich neu anzumelden. Deine Fahrten bleiben auf dem Handy.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        UpdateHinweis(vm)
        PasswortDialog(vm)
        val geteilt by Geteilt.bild.collectAsStateWithLifecycle()
        LaunchedEffect(geteilt) {
            if (geteilt != null) nav.navigate("tanken") { launchSingleTop = true }
        }
        NavHost(nav, startDestination = "fahrt", modifier = Modifier.weight(1f)) {
            composable("fahrt") { FahrtScreen(vm, oeffnen = { nav.navigate("fahrt/$it") }) }
            composable("fahrten") {
                FahrtenScreen(vm, oeffnen = { nav.navigate("fahrt/$it") }, karte = { nav.navigate("karte") })
            }
            composable("karte") { KarteScreen(vm) }
            composable("tanken") { TankenScreen(vm) }
            composable("statistik") { StatistikScreen(vm) }
            composable("einstellungen") { EinstellungenScreen(vm, serverVerbinden = { nav.navigate("login") { launchSingleTop = true } }) }
            composable("login") { LoginScreen(vm, fertig = { nav.popBackStack() }) }
            composable("fahrt/{id}") { e ->
                FahrtDetailScreen(
                    vm, e.arguments?.getString("id") ?: "",
                    zurueck = { nav.popBackStack() },
                    // Nach dem Teilen den zweiten Teil zeigen.
                    oeffnen = { id -> nav.navigate("fahrt/$id") { popUpTo("fahrt/{id}") { inclusive = true } } },
                )
            }
        }
        }
    }
}
