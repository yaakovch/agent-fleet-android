package com.termux.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.termux.R
import com.termux.app.fleet.AgentFleetContract
import com.termux.app.fleet.DrawerSessionStore
import com.termux.app.fleet.DrawerSessionSurface
import com.termux.app.fleet.FleetSession
import com.termux.app.fleet.FleetSnapshot
import com.termux.app.fleet.FleetSnapshotParser
import com.termux.app.fleet.enableTermuxExec
import com.termux.app.fleet.isFleetSessionAvailable
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE
import com.termux.view.TerminalView
import org.json.JSONObject

/** Real packaged bridge/agent with a deterministic, private tmux inventory adapter. */
internal class LocalFleetDiscoveryProbe(private val context: Context, private val legacyAdvertisement: Boolean = false) : AutoCloseable {
    private val runtime = AgentFleetEmbeddedRegistryTest().prepareRuntime(context)
    private val home = File(context.filesDir, "home")
    private val prefix = File(context.filesDir, "usr")
    private val directory = File(context.cacheDir, "discovery-${UUID.randomUUID()}").apply { mkdirs() }
    private val registry = File(directory, "machines").apply { mkdirs() }
    private val tmux = File(directory, "tmux-fixture").absolutePath
    private val sessionName = "wtmux-discovery"
    private val sshPort = 9840
    private var sshServer: Process? = null
    private var terminalActivity: TermuxActivity? = null
    private var sshUser = ""

    init {
        if (legacyAdvertisement) {
            File(directory, "legacy-agent").apply {
                writeText("""#!${File(prefix, "bin/python3").absolutePath}
import runpy, sys
sys.dont_write_bytecode = True
agent = runpy.run_path('${File(runtime, "current/scripts/wtmux-agent").absolutePath}')
agent['main'].__globals__['CONTRACT_PACKAGE_VERSION'] = '1.5.0'
raise SystemExit(agent['main']())
""")
                check(setExecutable(true, true))
            }
        }
        // The Android image is a client and intentionally has no tmux server.
        // Exercise its packaged protocol against synthetic host inventory;
        // actual tmux behavior is covered by the core host/runtime suite.
        File(directory, "session.json").writeText(JSONObject()
            .put("name", sessionName).put("activity", System.currentTimeMillis() / 1000)
            .put("projectPath", home.absolutePath).toString())
        File(tmux).writeText("""#!${File(prefix, "bin/python3").absolutePath}
import json, pathlib, sys
state = pathlib.Path(__file__).with_name('session.json')
args = sys.argv[1:]
if args == ['-V']:
    print('tmux 3.7b')
elif args and args[0] == 'list-sessions':
    value = json.loads(state.read_text())
    print(chr(31).join([value['name'], str(value['activity']), '0', '1',
        'discovery', 'Discovery session', 'linux', 'bash', '0',
        value['projectPath'], 'project', 'shell', 'linux']))
elif args and args[0] == 'has-session':
    sys.exit(0 if state.is_file() else 1)
""")
        check(File(tmux).setExecutable(true, true))
        val record = JSONObject()
            .put("schemaVersion", 1).put("id", "emulator-host").put("name", "Emulator host")
            .put("roles", org.json.JSONArray().put("host")).put("platform", "linux")
            .put("linuxUsername", "tester").put("tailscaleNode", "").put("projectsRoot", home.absolutePath)
            .put("transport", "ssh").put("wslDistro", "")
            .put("fallback", JSONObject().put("sshHost", "localhost").put("ip", ""))
            .put("hostCommand", File(runtime, "current/scripts/wtmux-host").absolutePath)
        File(registry, "emulator-host.json").writeText(record.toString())
        try {
            prepareSshServer()
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    private fun prepareSshServer() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish") &&
            "x86_64" in android.os.Build.SUPPORTED_ABIS && android.os.Build.VERSION.SDK_INT == 36)
        val requested = context.packageManager.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        for (permission in listOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.POST_NOTIFICATIONS)) {
            if (permission in requested) {
                InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, permission)
            }
        }
        sshUser = execute(listOf("/system/bin/id", "-un")).trim()
        for (name in listOf("ssh-host", "ssh-client")) {
            execute(listOf(File(prefix, "bin/ssh-keygen").absolutePath, "-q", "-t", "ed25519",
                "-N", "", "-f", File(directory, name).absolutePath))
        }
        File(directory, "authorized_keys").writeText(File(directory, "ssh-client.pub").readText())
        val publicHostKey = File(directory, "ssh-host.pub").readText().trim().split(" ").take(2).joinToString(" ")
        File(directory, "known_hosts").writeText("[127.0.0.1]:$sshPort $publicHostKey\n")
        val shell = File(directory, "ssh-shell")
        shell.writeText("""#!${File(prefix, "bin/bash").absolutePath}
export PATH='${File(prefix, "bin").absolutePath}:/system/bin'
export PS1='discovery> '
cd '${home.absolutePath}'
printf 'DISCOVERY_SSH_READY\n'
exec '${File(prefix, "bin/bash").absolutePath}' --noprofile --norc -i
""")
        check(shell.setExecutable(true, true))
        val config = File(directory, "sshd_config")
        config.writeText("""
Port $sshPort
ListenAddress 127.0.0.1
HostKey ${File(directory, "ssh-host").absolutePath}
AuthorizedKeysFile ${File(directory, "authorized_keys").absolutePath}
PidFile ${File(directory, "sshd.pid").absolutePath}
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
StrictModes no
ForceCommand ${shell.absolutePath}
LogLevel ERROR
""".trimIndent())
        sshServer = processBuilder(listOf(File(prefix, "bin/sshd").absolutePath, "-D", "-e", "-f", config.absolutePath))
            .redirectOutput(File(directory, "sshd.log")).start()
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000
        while (android.os.SystemClock.uptimeMillis() < deadline && sshServer?.isAlive == true) {
            if (runCatching { java.net.Socket("127.0.0.1", sshPort).use { true } }.getOrDefault(false)) return
            android.os.SystemClock.sleep(50)
        }
        error("Emulator SSH fixture failed to start: ${File(directory, "sshd.log").readText().take(2048)}")
    }

    fun openTerminal(session: FleetSession) {
        DrawerSessionStore(context).apply {
            recordOpened(session, DrawerSessionSurface.Terminal)
            setActiveFullscreen(session.id)
        }
        val arguments = arrayOf("-tt", "-F", "/dev/null", "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes",
            "-o", "StrictHostKeyChecking=yes", "-o", "GlobalKnownHostsFile=/dev/null",
            "-o", "UserKnownHostsFile=${File(directory, "known_hosts").absolutePath}",
            "-o", "ConnectTimeout=5", "-o", "LogLevel=ERROR", "-i", File(directory, "ssh-client").absolutePath,
            "-p", sshPort.toString(), "$sshUser@127.0.0.1")
        val uri = Uri.Builder().scheme(TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE)
            .path(File(prefix, "bin/ssh").absolutePath).build()
        context.startForegroundService(Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, uri, context, TermuxService::class.java).apply {
            putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, arguments)
            putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, home.absolutePath)
            putExtra(TERMUX_SERVICE.EXTRA_BACKGROUND, false)
            putExtra(TERMUX_SERVICE.EXTRA_SESSION_ACTION,
                TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_DONT_OPEN_ACTIVITY.toString())
            putExtra(TERMUX_SERVICE.EXTRA_COMMAND_LABEL, "Discovery SSH fixture")
            putExtra(TERMUX_SERVICE.EXTRA_COMMAND_DESCRIPTION, AgentFleetContract.WORKSPACE_SESSION_PREFIX + session.id)
            putExtra(AgentFleetContract.EXTRA_SESSION_NAME, session.name)
        })
        context.startActivity(Intent(context, TermuxActivity::class.java).apply {
            putExtra(AgentFleetContract.EXTRA_COMPOSE_INPUT, false)
            putExtra(AgentFleetContract.EXTRA_NATIVE_SESSION, true)
            putExtra(AgentFleetContract.EXTRA_WORKSPACE_SESSION_ID, session.id)
            putExtra(AgentFleetContract.EXTRA_HOST_ID, session.hostId)
            putExtra(AgentFleetContract.EXTRA_PROJECT, session.project)
            putExtra(AgentFleetContract.EXTRA_INTERNAL_SESSION, session.internalName)
            putExtra(AgentFleetContract.EXTRA_INITIAL_SURFACE, AgentFleetContract.SURFACE_TERMINAL)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun <T> onMain(block: () -> T): T {
        val value = AtomicReference<T>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { value.set(block()) }
        return value.get()
    }

    private fun awaitTerminalMarker(marker: String) {
        val deadline = android.os.SystemClock.uptimeMillis() + 20_000
        var observed = ""
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            val ready = onMain {
                terminalActivity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<TermuxActivity>().firstOrNull() ?: terminalActivity
                val view = terminalActivity?.findViewById<TerminalView>(R.id.terminal_view)
                observed = view?.currentSession?.emulator?.screen?.transcriptText.orEmpty()
                view?.isShown == true && marker in observed && view.currentSession?.isRunning == true
            }
            if (ready) return
            android.os.SystemClock.sleep(50)
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val storage = androidx.test.platform.io.PlatformTestStorageRegistry.getInstance()
        storage.openOutputFile("discovery-terminal-failure.png").use {
            instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        storage.openOutputFile("discovery-ssh-fixture.log").use {
            it.write(File(directory, "sshd.log").readBytes().take(8192).toByteArray())
        }
        error("SSH terminal did not reach $marker: ${observed.take(2048)}")
    }

    fun confirmUsableTerminal(connectionEvidence: String): String {
        awaitTerminalMarker("DISCOVERY_SSH_READY")
        onMain {
            val terminal = checkNotNull(terminalActivity?.findViewById<TerminalView>(R.id.terminal_view)?.currentSession)
            val input = "printf '\\nDISCOVERY_%s\\n' INPUT_OK\r".toByteArray(Charsets.UTF_8)
            terminal.write(input, 0, input.size)
        }
        awaitTerminalMarker("DISCOVERY_INPUT_OK")
        return JSONObject(connectionEvidence).put("terminalRoundTrip", "passed")
            .put("observedAt", java.time.Instant.now().toString())
            .put("scope", "packaged bridge/agent with synthetic inventory; real pinned loopback SSH and managed terminal input/output")
            .toString()
    }

    fun snapshot(): FleetSnapshot = FleetSnapshotParser.parse(execute(listOf(
        File(prefix, "bin/python3").absolutePath, File(runtime, "current/scripts/wtmux-bridge").absolutePath,
        "--registry", registry.absolutePath, "--local-machine", "emulator-host", "--snapshot", "--identity-graph"
    )))

    fun confirmConnection(session: FleetSession): String {
        val fresh = snapshot()
        check(fresh.sessions.any { it.id == session.id && isFleetSessionAvailable(fresh, it) })
        execute(listOf(tmux, "has-session", "-t", sessionName))
        return JSONObject().put("hostId", session.hostId).put("sessionId", session.id)
            .put("snapshotRevision", fresh.revision).put("roundTrip", "passed")
            .put("observedAt", java.time.Instant.now().toString())
            .put("scope", "packaged bridge/agent with synthetic tmux inventory").toString()
    }

    private fun processBuilder(command: List<String>): ProcessBuilder =
        ProcessBuilder(command).directory(home).redirectErrorStream(true).apply {
            environment()["HOME"] = home.absolutePath
            environment()["PREFIX"] = prefix.absolutePath
            environment()["PATH"] = "${File(home, ".local/bin")}:${File(prefix, "bin")}"
            environment()["WTMUX_TMUX_BIN"] = tmux
            environment()["WTMUX_STATE_DIR"] = File(directory, "state").absolutePath
            if (legacyAdvertisement) environment()["WTMUX_BRIDGE_AGENT_PATH"] = File(directory, "legacy-agent").absolutePath
            enableTermuxExec(environment(), prefix)
        }

    private fun execute(command: List<String>): String {
        val process = processBuilder(command).start()
        val reader = Executors.newSingleThreadExecutor()
        val output = reader.submit<String> {
            process.inputStream.use { input ->
                val bytes = input.readBytes()
                check(bytes.size <= 256 * 1024)
                bytes.toString(Charsets.UTF_8)
            }
        }
        try {
            check(process.waitFor(20, TimeUnit.SECONDS)) { "Packaged discovery command timed out" }
            val result = output.get(3, TimeUnit.SECONDS)
            check(process.exitValue() == 0) { "Packaged discovery command failed: $result" }
            return result
        } finally {
            process.destroy()
            reader.shutdownNow()
        }
    }

    override fun close() {
        onMain {
            terminalActivity?.findViewById<TerminalView>(R.id.terminal_view)?.currentSession?.finishIfRunning()
            terminalActivity?.finish()
        }
        sshServer?.destroy()
        if (sshServer?.waitFor(3, TimeUnit.SECONDS) == false) sshServer?.destroyForcibly()
        directory.deleteRecursively()
    }
}
