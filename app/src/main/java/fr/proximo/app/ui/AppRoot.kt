package fr.proximo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import fr.proximo.app.MainActivity
import fr.proximo.app.core.Db
import fr.proximo.app.core.Identity
import fr.proximo.app.mesh.MeshService
import fr.proximo.app.call.CallState

@Composable
fun AppRoot(activity: MainActivity) {
    val ctx = LocalContext.current
    val identity = remember { Identity.get(ctx) }
    var ready by remember { mutableStateOf(identity.isReady) }

    if (!ready) { Onboarding(onDone = { name -> identity.name = name; ready = true; activity.requestPermissions() }); return }
    if (!activity.permissionsOk) { PermissionsScreen(onAsk = { activity.requestPermissions() }, deniedOnce = activity.permissionsDeniedOnce, onSettings = { activity.openAppSettings() }); return }
    val settings = remember { fr.proximo.app.core.Settings.get(ctx) }
    var batteryStep by remember { mutableStateOf(!activity.batteryOk && !settings.batteryAsked) }
    if (batteryStep) { BatteryScreen(onAllow = { activity.requestBatteryExemption(); settings.batteryAsked = true; batteryStep = false }, onLater = { settings.batteryAsked = true; batteryStep = false }); return }

    LaunchedEffect(Unit) { MeshService.start(ctx) }
    val svc by serviceState()
    LaunchedEffect(svc) { svc?.updater?.checkOnline() }

    val nav = rememberNavController()
    val back by nav.currentBackStackEntryAsState()
    val route = back?.destination?.route ?: "radar"
    val unread by dbState(0) { it.unreadTotal() }

    // Ouverture d'une discussion depuis une notification
    LaunchedEffect(activity.pendingChat) {
        activity.pendingChat?.let { c ->
            activity.pendingChat = null
            // Déjà sur cette discussion : on ne l'empile pas une deuxième fois.
            val cur = nav.currentBackStackEntry
            val already = if (c == "contacts") cur?.destination?.route == "contacts" else cur?.destination?.route == "chat/{id}" && cur.arguments?.getString("id") == c
            if (!already) nav.navigate(if (c == "contacts") "contacts" else "chat/$c") { launchSingleTop = true }
        }
    }

    val callFlow = svc?.calls?.state
    val call = if (callFlow != null) callFlow.collectAsState().value else null
    // L'écran d'appel se pose PAR-DESSUS le reste (plus bas) : après l'appel, on retrouve l'écran et le texte en cours.
    val inCall = call != null && call.phase != CallState.IDLE
    val settingsTick by settings.changes.collectAsState()
    val stopped = remember(settingsTick) { settings.stoppedManually }
    // Service toujours absent 5 s après l'ouverture (ou arrêté à la main) : on le dit, au lieu de laisser des boutons sans effet.
    val svcMissing by produceState(false, svc) { value = false; if (svc == null) { kotlinx.coroutines.delay(5000); value = true } }

    val showBar = route in setOf("radar", "chats", "all", "profile")
    Scaffold(
        containerColor = Px.Bg,
        bottomBar = {
            if (showBar) NavigationBar(containerColor = Px.Bar) {
                val items = listOf(
                    Triple("radar", "Radar", Icons.Filled.Radar),
                    Triple("chats", "Discussions", Icons.Filled.Chat),
                    Triple("all", "Tout le monde", Icons.Filled.Groups),
                    Triple("profile", "Profil", Icons.Filled.Person),
                )
                items.forEach { (r, label, icon) ->
                    val selected = back?.destination?.hierarchy?.any { it.route == r } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = { nav.navigate(r) { popUpTo("radar") { saveState = true }; launchSingleTop = true; restoreState = true } },
                        icon = {
                            if (r == "chats" && unread > 0) BadgedBox(badge = { Badge(containerColor = Px.Accent, contentColor = Px.Bg) { Text("$unread") } }) { Icon(icon, label) }
                            else Icon(icon, label)
                        },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Px.Accent, selectedTextColor = Px.Accent, indicatorColor = Px.Card, unselectedIconColor = Px.Muted, unselectedTextColor = Px.Muted),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad)) {
            NavHost(nav, startDestination = "radar") {
                composable("radar") { RadarScreen(onOpenChat = { nav.navigate("chat/$it") }) }
                composable("chats") { ChatsScreen(onOpen = { nav.navigate("chat/$it") }, onNewGroup = { nav.navigate("newgroup") }, onAdd = { nav.navigate("contacts") }) }
                composable("all") { ChatScreen(Db.ALL, onBack = null, onOpenContacts = { nav.navigate("contacts") }) }
                composable("profile") { ProfileScreen(onContacts = { nav.navigate("contacts") }, onSettings = { nav.navigate("settings") }) }
                composable("chat/{id}") { e -> ChatScreen(e.arguments?.getString("id") ?: Db.ALL, onBack = { nav.popBackStack() }, onOpenContacts = { nav.navigate("contacts") }) }
                composable("contacts") { ContactsScreen(onBack = { nav.popBackStack() }, onOpenChat = { nav.navigate("chat/$it") }) }
                composable("newgroup") { NewGroupScreen(onBack = { nav.popBackStack() }, onCreated = { nav.popBackStack(); nav.navigate("chat/$it") }) }
                composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
            }
            UpdateBanner(Modifier.align(Alignment.TopCenter))
            EventToast(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
            if (svc == null && (stopped || svcMissing)) Text(
                if (stopped) "Réseau Proximo arrêté : rien ne part, rien n'arrive. Appuie ici pour le démarrer." else "Le réseau Proximo ne tourne pas. Appuie ici pour le démarrer.",
                color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Px.Danger).clickable { MeshService.resume(ctx) }.padding(12.dp),
            )
        }
    }
    if (inCall && call != null) {
        // Le clavier se ferme : il ne doit pas cacher « Répondre / Refuser ».
        val focus = androidx.compose.ui.platform.LocalFocusManager.current
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) { focus.clearFocus(force = true); keyboard?.hide() }
        androidx.activity.compose.BackHandler {}
        CallScreen(call)
    }
}

@Composable
fun Onboarding(onDone: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Px.Bg).systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Logo(120.dp)
        Spacer(Modifier.height(20.dp))
        Text("Proximo", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text("Messagerie sans Internet", color = Px.Accent, fontSize = 15.sp)
        Spacer(Modifier.height(36.dp))
        Text("Choisis ton pseudo : c'est le nom que verront les téléphones autour de toi.", color = Px.Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(name, { if (it.length <= 24) name = it }, label = { Text("Pseudo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))
        Button(onClick = { onDone(name.trim()) }, enabled = name.trim().length >= 2, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Commencer", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(40.dp))
        Text("Aucun compte, aucun numéro : tout reste sur ton téléphone.", color = Px.Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun PermissionsScreen(onAsk: () -> Unit, deniedOnce: Boolean, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Px.Bg).systemBarsPadding().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Logo(90.dp)
        Spacer(Modifier.height(24.dp))
        Text("Autorisations nécessaires", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            "Proximo a besoin du Bluetooth et du Wi-Fi direct pour trouver les téléphones autour de toi, du micro pour le talkie-walkie et les appels, et des notifications pour te prévenir.\n\nAndroid demande aussi la « position » : elle sert uniquement à autoriser le Bluetooth et le Wi-Fi direct, et au partage de position si tu le choisis.",
            color = Px.Muted, textAlign = TextAlign.Center, fontSize = 14.sp,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAsk, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Autoriser", fontWeight = FontWeight.Bold) }
        if (deniedOnce) {
            Spacer(Modifier.height(12.dp))
            Text("Si Android ne te repose plus la question, ouvre les réglages de l'appli et autorise « Appareils à proximité » (ou Bluetooth / Position).", color = Px.Warn, fontSize = 13.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.TextButton(onClick = onSettings) { Text("Ouvrir les réglages de l'appli") }
        }
    }
}

@Composable
fun BatteryScreen(onAllow: () -> Unit, onLater: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Px.Bg).systemBarsPadding().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Logo(90.dp)
        Spacer(Modifier.height(24.dp))
        Text("Laisser Proximo tourner en arrière-plan", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "Pour recevoir les messages écran éteint, Android ne doit pas mettre Proximo en veille. À l'écran suivant, choisis « Autoriser ».\n\nSur Samsung, Xiaomi, Huawei, Oppo : vérifie aussi dans Paramètres → Batterie que Proximo est « Non restreinte » et dans « Applications jamais mises en veille ».",
            color = Px.Muted, textAlign = TextAlign.Center, fontSize = 14.sp,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAllow, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Autoriser", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.TextButton(onClick = onLater) { Text("Plus tard", color = Px.Muted) }
    }
}

@Composable
fun Logo(size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(fr.proximo.app.R.drawable.ic_launcher_foreground),
            contentDescription = "Proximo",
            modifier = Modifier.size(size).background(Px.Card, androidx.compose.foundation.shape.RoundedCornerShape(size / 4)),
        )
    }
}
