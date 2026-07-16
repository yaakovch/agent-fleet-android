package com.termux.app.migration

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.concurrent.thread

class AgentFleetMigrationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val caller = callingPackage
        if (intent?.action != AgentFleetMigrationPeer.ACTION_EXPORT || caller == null ||
            caller != AgentFleetMigrationPeer.counterpart(packageName) || !AgentFleetMigrationPeer.sameSigner(this, caller)) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val destination = if (caller == AgentFleetMigrationArchive.FLEET_PACKAGE) "the new Agent Fleet app" else "legacy Agent Fleet"
        AlertDialog.Builder(this)
            .setTitle("Copy Agent Fleet state?")
            .setMessage(
                "This copies Fleet layouts, recent sessions, appearance, pairing configuration, the current verified registry, and SSH files to $destination. " +
                    "Runtime packages, shell history, caches, downloads, and arbitrary home files are not included."
            )
            .setNegativeButton("Cancel") { _, _ -> setResult(RESULT_CANCELED); finish() }
            .setPositiveButton("Copy") { _, _ -> createArchive(caller) }
            .setOnCancelListener { setResult(RESULT_CANCELED); finish() }
            .show()
    }

    private fun createArchive(caller: String) {
        thread(name = "agent-fleet-migration-export", isDaemon = true) {
            runCatching {
                val directory = File(cacheDir, "agent-fleet-migration").apply { mkdirs() }
                directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > MAX_AGE_MS }?.forEach(File::delete)
                val file = File(directory, "agent-fleet-migration-${UUID.randomUUID()}.zip")
                file.outputStream().use { AgentFleetMigrationArchive.export(applicationContext, it) }
                file.setReadable(false, false)
                file.setWritable(false, false)
                file.setReadable(true, true)
                file.setWritable(true, true)
                val uri = FileProvider.getUriForFile(this, "$packageName.agentfleet.images", file)
                grantUriPermission(caller, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                Handler(Looper.getMainLooper()).post {
                    setResult(
                        RESULT_OK,
                        Intent().apply {
                            data = uri
                            clipData = ClipData.newRawUri("Agent Fleet migration", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    )
                    finish()
                    Handler(Looper.getMainLooper()).postDelayed({
                        revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        file.delete()
                    }, GRANT_LIFETIME_MS)
                }
            }.onFailure { error ->
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, error.message ?: "Migration export failed", Toast.LENGTH_LONG).show()
                    setResult(RESULT_CANCELED)
                    finish()
                }
            }
        }
    }

    private companion object {
        const val MAX_AGE_MS = 24L * 60L * 60L * 1000L
        const val GRANT_LIFETIME_MS = 10L * 60L * 1000L
    }
}

object AgentFleetMigrationPeer {
    const val ACTION_EXPORT = "com.yaakovch.fleet.action.EXPORT_MIGRATION"
    private const val ACTIVITY = "com.termux.app.migration.AgentFleetMigrationActivity"

    fun counterpart(packageName: String): String = when (packageName) {
        AgentFleetMigrationArchive.FLEET_PACKAGE -> AgentFleetMigrationArchive.LEGACY_PACKAGE
        AgentFleetMigrationArchive.LEGACY_PACKAGE -> AgentFleetMigrationArchive.FLEET_PACKAGE
        else -> error("Unsupported Agent Fleet package")
    }

    fun exportIntent(context: Context): Intent {
        val peer = counterpart(context.packageName)
        require(sameSigner(context, peer)) { "The other Agent Fleet app is not installed with the same signing certificate" }
        return Intent(ACTION_EXPORT).setComponent(ComponentName(peer, ACTIVITY))
    }

    fun sameSigner(context: Context, otherPackage: String): Boolean = runCatching {
        val own = certificateDigests(context.packageManager, context.packageName)
        val other = certificateDigests(context.packageManager, otherPackage)
        own.isNotEmpty() && own == other
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun certificateDigests(manager: PackageManager, packageName: String): Set<String> {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            manager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = requireNotNull(info.signingInfo)
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else info.signatures
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}
