package com.termux.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.termux.app.fleet.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

@Composable
internal fun HostSetupDialog(
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
    knownHosts: List<FleetHost> = emptyList(),
    setup: FleetHostSetup? = null
) {
    val context = LocalContext.current
    val service = remember(setup) { setup ?: RuntimeFleetHostSetup(context) }
    val scope = rememberCoroutineScope()
    var hosts by remember { mutableStateOf<List<TailnetHost>>(emptyList()) }
    var selection by remember { mutableStateOf<TailnetHost?>(null) }
    var review by remember { mutableStateOf<TailnetHostReview?>(null) }
    var repairSelection by remember { mutableStateOf<Pair<String, String>?>(null) }
    var username by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    fun run(detail: String, operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = detail
        scope.launch {
            try { operation() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.message?.take(256) ?: "Host setup could not finish. Retry when Tailscale is connected." }
            finally { busy = false }
        }
    }
    fun discover() = run("Finding hosts on your Tailnet…") {
        hosts = runInterruptible(Dispatchers.IO) { service.discover() }
        message = if (hosts.isEmpty()) "No host machines found on this Tailnet" else "Choose a host to check access and pair"
    }
    LaunchedEffect(service) { discover() }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Find and repair hosts") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(message, Modifier.testTag("host-setup-status"))
                val selected = selection
                val checked = review
                val repairing = repairSelection
                when {
                    repairing != null -> {
                        Text("Repair ${repairing.second}?")
                        Text("Install the verified host runtime supplied with this app, then reconnect. Running sessions stay open. Tailscale SSH access is required.")
                        Button(enabled = !busy, modifier = Modifier.testTag("host-setup-repair-confirm"), onClick = {
                            run("Repairing ${repairing.second}…") {
                                runInterruptible(Dispatchers.IO) { service.repair(repairing.first) }
                                message = "Host runtime repaired; reconnecting"
                                repairSelection = null
                                onChanged()
                            }
                        }) { Text("Repair host") }
                    }
                    checked != null -> {
                        Text("Pair ${checked.name}?")
                        Text("${checked.username}@${checked.address}")
                        Text(if (checked.runtimePresent) "Host runtime found" else "Host runtime is missing; use Repair host after pairing")
                        Text(if (checked.tmuxPresent) "Session service found" else "tmux is missing on this host")
                        Button(enabled = !busy, modifier = Modifier.testTag("host-setup-pair"), onClick = {
                            run("Pairing ${checked.name}…") {
                                val hostId = runInterruptible(Dispatchers.IO) { service.pair(checked.reviewId) }
                                review = null
                                selection = null
                                hosts = hosts.map { if (it.nodeId == selected?.nodeId) it.copy(hostId = hostId) else it }
                                message = "Host paired; reconnecting"
                                onChanged()
                                if (!checked.runtimePresent) repairSelection = hostId to checked.name
                            }
                        }) { Text("Pair host") }
                    }
                    selected != null -> {
                        Text(selected.name)
                        Text(selected.address)
                        OutlinedTextField(username, { username = it }, enabled = !busy, singleLine = true,
                            label = { Text("Linux account on this host") }, modifier = Modifier.testTag("host-setup-username"))
                        Button(enabled = !busy && username.isNotBlank(), modifier = Modifier.testTag("host-setup-review"), onClick = {
                            run("Checking SSH access and required tools…") {
                                review = runInterruptible(Dispatchers.IO) { service.review(selected.nodeId, username.trim()) }
                                message = "Tailscale host identity and SSH access verified"
                            }
                        }) { Text("Review host") }
                    }
                    else -> {
                        hosts.forEach { host ->
                            Text(host.name, style = MaterialTheme.typography.titleSmall)
                            Text("${host.address} · ${if (host.online) "Online on Tailscale" else "Offline"}${if (host.hostId.isNotBlank()) " · Paired" else ""}")
                            if (host.hostId.isNotBlank()) {
                                TextButton(enabled = !busy, onClick = { repairSelection = host.hostId to host.name }) { Text("Repair host") }
                            } else if (host.platform == "windows") {
                                Text("Select this machine’s WSL Linux node to pair.")
                            } else {
                                TextButton(enabled = !busy && host.online, modifier = Modifier.testTag("host-setup-select-${host.nodeId}"),
                                    onClick = { selection = host; username = "" }) { Text("Set up host") }
                            }
                        }
                        knownHosts.filter { known -> hosts.none { it.hostId == known.id } }.forEach { host ->
                            Text(host.name)
                            Text(TransportContract.recoveryFor(host.errorCode)?.title ?: host.status)
                            TextButton(enabled = !busy, onClick = { repairSelection = host.id to host.name }) { Text("Repair host") }
                        }
                        TextButton(enabled = !busy, onClick = { discover() }) { Text("Find hosts again") }
                    }
                }
                if (selected != null || checked != null || repairing != null) TextButton(enabled = !busy, onClick = {
                    selection = null; review = null; repairSelection = null
                }) { Text("Back to hosts") }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Done") } }
    )
}
