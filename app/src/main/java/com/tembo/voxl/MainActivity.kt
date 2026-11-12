package com.tembo.voxl

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MainActivity : ComponentActivity() {
    /** The desktop on screen, if any: it gets every key first. */
    var remote: RemoteView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { App() } }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean =
        remote?.handleKey(e) == true || super.dispatchKeyEvent(e)
}

@Composable
private fun App() {
    var host by remember { mutableStateOf<Host?>(null) }
    val h = host
    if (h == null) HostPicker(onConnect = { host = it }) else RemoteScreen(h, onBack = { host = null })
}

// MARK: host picker

private sealed interface Dialog {
    data class Password(val host: Host) : Dialog
    data class Edit(val index: Int?) : Dialog
    data class Message(val text: String) : Dialog
}

/** First screen: the saved hosts. Tap one to connect; long-press to edit or delete; + adds a URL. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostPicker(onConnect: (Host) -> Unit) {
    val ctx = LocalContext.current
    val hosts = remember { mutableStateListOf<Host>().apply { addAll(Hosts.all(ctx)) } }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var menuFor by remember { mutableStateOf<Int?>(null) }

    fun connect(h: Host) {
        if (Hosts.password(ctx, h.url) == null) dialog = Dialog.Password(h) else onConnect(h)
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.app_name)) }, actions = {
            TextButton(onClick = { dialog = Dialog.Edit(null) }) { Text("Add", fontSize = 16.sp) }
        })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            if (hosts.isEmpty()) item {
                Text(
                    "Tap Add to connect to a computer running the voxl server.",
                    Modifier.padding(24.dp), color = Color.Gray, fontSize = 16.sp,
                )
            }
            itemsIndexed(hosts) { i, h ->
                Box {
                    ListItem(
                        headlineContent = { Text(h.name) },
                        supportingContent = { Text(h.url) },
                        trailingContent = {
                            TextButton(onClick = { menuFor = i }) { Text("⋯", fontSize = 22.sp) }
                        },
                        modifier = Modifier.clickable { connect(h) },
                    )
                    DropdownMenu(expanded = menuFor == i, onDismissRequest = { menuFor = null }) {
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { menuFor = null; dialog = Dialog.Edit(i) })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = {
                            menuFor = null
                            Hosts.setPassword(ctx, h.url, null)
                            hosts.removeAt(i)
                            Hosts.save(ctx, hosts)
                        })
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        is Dialog.Password -> PasswordDialog(d.host, onDismiss = { dialog = null }) { pw ->
            Hosts.setPassword(ctx, d.host.url, pw)
            dialog = null
            onConnect(d.host)
        }
        is Dialog.Edit -> EditDialog(d.index?.let { hosts[it] }, onDismiss = { dialog = null }) { name, rawUrl, pw ->
            val url = Hosts.normalize(rawUrl)
            when {
                url == null -> dialog = Dialog.Message("That isn't a host or ws:// / wss:// URL (without a password in it).")
                // One password per URL, so each URL appears once.
                hosts.indices.any { it != d.index && hosts[it].url == url } -> dialog = Dialog.Message("That URL is already in the list.")
                else -> {
                    val host = Host(name.trim().ifEmpty { Hosts.displayHost(url) }, url)
                    val old = d.index?.let { hosts[it] }
                    if (old != null && old.url != url) {
                        if (pw.isEmpty()) Hosts.setPassword(ctx, url, Hosts.password(ctx, old.url))
                        Hosts.setPassword(ctx, old.url, null)
                    }
                    if (pw.isNotEmpty()) Hosts.setPassword(ctx, url, pw)
                    if (d.index != null) hosts[d.index] = host else hosts.add(host)
                    Hosts.save(ctx, hosts)
                    dialog = null
                }
            }
        }
        is Dialog.Message -> AlertDialog(
            onDismissRequest = { dialog = null },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("OK") } },
            text = { Text(d.text) },
        )
        null -> {}
    }
}

@Composable
private fun PasswordField(value: String, label: String, onDone: () -> Unit = {}, onChange: (String) -> Unit) = OutlinedTextField(
    value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
    visualTransformation = PasswordVisualTransformation(),
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
    keyboardActions = KeyboardActions(onDone = { onDone() }),
)

@Composable
private fun PasswordDialog(host: Host, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(host.name) },
        text = {
            Column {
                Text("Password for ${host.url}")
                PasswordField(pw, "Password", onDone = { if (pw.isNotEmpty()) onDone(pw) }) { pw = it }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { TextButton(enabled = pw.isNotEmpty(), onClick = { onDone(pw) }) { Text("Connect") } },
    )
}

/** Adds a host (old == null) or edits one. A blank password keeps the saved one. */
@Composable
private fun EditDialog(old: Host?, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf(old?.name ?: "") }
    var url by remember { mutableStateOf(old?.url ?: "") }
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (old == null) "Add Host" else "Edit Host") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    url, { url = it }, label = { Text("wss://host:port/ws") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                )
                PasswordField(pw, if (old == null) "Password" else "Password (unchanged)") { pw = it }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = { onSave(name, url, pw) }) { Text("Save") } },
    )
}

// MARK: remote

@Composable
private fun RemoteScreen(host: Host, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as MainActivity
    var askPassword by remember { mutableStateOf(false) }
    val remote = remember { RemoteView(ctx, host) { askPassword = true } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(remote) {
        activity.remote = remote
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> remote.session.start()
                Lifecycle.Event.ON_STOP -> { remote.releaseAll(); remote.session.stop() }
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            remote.releaseAll()
            remote.session.close()
            activity.remote = null
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    BackHandler(onBack = onBack)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ remote }, Modifier.fillMaxSize())
        remote.status?.let {
            Text(
                it, color = Color.White, fontSize = 17.sp,
                modifier = Modifier.align(Alignment.Center).clickable { if (!remote.session.connected) remote.session.start() },
            )
        }
        Chrome(remote, onBack, Modifier.align(Alignment.TopEnd))
        if (!askPassword) KeyBar(remote, Modifier.align(Alignment.BottomCenter))
    }

    if (askPassword) PasswordDialog(host, onDismiss = { askPassword = false; onBack() }) { pw ->
        Hosts.setPassword(ctx, host.url, pw)
        askPassword = false
        remote.session.start()
    }
}

@Composable
private fun ChromeButton(glyph: String, label: String, onClick: () -> Unit) = Box(
    Modifier.size(40.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape)
        .clickable(onClickLabel = label, onClick = onClick).semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
) { Text(glyph, color = Color.White, fontSize = 18.sp) }

@Composable
private fun Chrome(remote: RemoteView, onBack: () -> Unit, modifier: Modifier) {
    var displayMenu by remember { mutableStateOf(false) }
    var sizeMenu by remember { mutableStateOf(false) }
    var textSize by remember { mutableIntStateOf(remote.textSize) }
    Row(modifier.safeDrawingPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ChromeButton("‹", "Hosts", onBack)
        if (remote.displays > 1) Box {
            ChromeButton("⧉", "Display") { displayMenu = true }
            DropdownMenu(displayMenu, { displayMenu = false }) {
                for (i in 0 until remote.displays) DropdownMenuItem(
                    text = { Text((if (i == remote.display) "✓ " else "") + if (i == 0) "Display 1 (main)" else "Display ${i + 1}") },
                    onClick = { displayMenu = false; if (i != remote.display) remote.session.send("dp,$i") },
                )
            }
        }
        // How much of the Mac fits: Mac points as big as dp (large text) up to one Mac pixel
        // per device pixel. Mac displays only offer fixed modes; the host takes the largest that fits.
        Box {
            ChromeButton("Aa", "Text size") { sizeMenu = true }
            DropdownMenu(sizeMenu, { sizeMenu = false }) {
                listOf("Mac's resolution (no change)", "Large", "Medium", "More space (pixel perfect)").forEachIndexed { i, name ->
                    DropdownMenuItem(
                        text = { Text((if (i == textSize) "✓ " else "") + name) },
                        onClick = { sizeMenu = false; textSize = i; remote.textSize = i },
                    )
                }
            }
        }
        ChromeButton("⌨", "Keyboard") { remote.toggleKeyboard(true) }
        ChromeButton(if (remote.trackpad) "▭" else "☝", if (remote.trackpad) "Trackpad mode" else "Touch mode") { remote.trackpad = !remote.trackpad }
    }
}

/** Keys Android keeps for itself or a soft keyboard lacks; shown above the soft keyboard. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun KeyBar(remote: RemoteView, modifier: Modifier) {
    if (!WindowInsets.isImeVisible) {
        SideEffect { remote.covered = 0 }
        return
    }
    Row(
        modifier.fillMaxWidth().imePadding()
            // Everything from the key bar's top down hides the desktop.
            .onGloballyPositioned { remote.covered = (remote.height - it.positionInRoot().y).toInt().coerceAtLeast(0) }
            .background(Color(0xff202124))
            .horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        @Composable fun k(label: String, on: Boolean = false, action: () -> Unit) = TextButton(onClick = action) {
            Text(label, color = if (on) Color(0xff8ab4f8) else Color.White, fontSize = 16.sp)
        }
        k("Esc") { remote.tap(0xff1b) }
        k("Tab") { remote.tap(0xff09) }
        for ((label, sym) in listOf("Shift" to 0xffe1, "Cmd" to 0xffeb, "Ctrl" to 0xffe3, "Opt" to 0xffe9)) {
            k(label, sym in remote.stickyMods) { remote.toggleSticky(sym) }
        }
        k("⌘Space") { remote.cmdCombo(0x20) }
        k("⌘Tab") { remote.cmdCombo(0xff09) }
        k("←") { remote.tap(0xff51) }
        k("↑") { remote.tap(0xff52) }
        k("↓") { remote.tap(0xff54) }
        k("→") { remote.tap(0xff53) }
        k("Hide") { remote.toggleKeyboard(false) }
    }
}
