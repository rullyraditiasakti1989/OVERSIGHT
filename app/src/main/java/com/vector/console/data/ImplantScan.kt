package com.vector.console.data

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Host implant audit. See `docs/THREAT-MODELS.md` §5.2.
 *
 * The six rules below are the artefacts that actually carry signal on Android. Each one is a
 * *combination*, never a single permission: an app with accessibility alone is a screen
 * reader, an app with accessibility plus RECORD_AUDIO is not.
 *
 * What this cannot do, stated plainly so nobody over-reads a CLEAN verdict: an app has no
 * kernel read access and no view of a hidden process, so a 0-click kernel-level implant is
 * invisible here. This catches the commodity tier and operator mistakes.
 */
data class PackageFacts(
    val pkg: String,
    val label: String,
    val isSystem: Boolean,
    val hasLauncher: Boolean,
    val installer: String?,
    val permissions: Set<String>,
    val hasAccessibilityService: Boolean,
    val hasNotificationListener: Boolean,
    val isDeviceAdmin: Boolean
) {
    fun holds(permission: String): Boolean = permission in permissions
    val collectsAudio: Boolean get() = holds(android.Manifest.permission.RECORD_AUDIO)
    val readsSms: Boolean get() = holds(android.Manifest.permission.READ_SMS)
    val online: Boolean get() = holds(android.Manifest.permission.INTERNET)
    val boots: Boolean get() = holds(android.Manifest.permission.RECEIVE_BOOT_COMPLETED)
    val overlays: Boolean get() = holds(android.Manifest.permission.SYSTEM_ALERT_WINDOW)
}

enum class Rule(val weight: Int, val title: String) {
    ACCESSIBILITY_EXFIL(
        35,
        "Accessibility service plus audio or SMS"
    ),
    SMS_EXFIL(
        25,
        "Reads SMS, has network, nothing to open"
    ),
    OVERLAY_HEADLESS(
        20,
        "Draws over other apps, no launcher"
    ),
    DEVICE_ADMIN(
        15,
        "Device administrator outside a managed fleet"
    ),
    HEADLESS_NETWORK(
        12,
        "Runs a service with network but no UI"
    ),
    NOTIFICATION_EXFIL(
        10,
        "Reads notifications with network access"
    ),
    SIDELOAD_SENSITIVE(
        8,
        "Sideloaded and holds audio, SMS, or accessibility"
    ),
    BOOT_HEADLESS(
        6,
        "Starts at boot with nothing to open"
    )
}

data class Finding(
    val rule: Rule,
    val pkg: String,
    val label: String,
    val evidence: String
)

enum class Verdict { CLEAN, REVIEW, SUSPECT }

data class ScanResult(
    val findings: List<Finding>,
    val score: Int,
    val scanned: Int
) {
    val verdict: Verdict
        get() = when {
            score >= 40 -> Verdict.SUSPECT
            score >= 15 -> Verdict.REVIEW
            else -> Verdict.CLEAN
        }

    fun byPackage(): List<Pair<String, List<Finding>>> =
        findings.groupBy { it.pkg }
            .map { (pkg, list) -> pkg to list.sortedByDescending { it.rule.weight } }
            .sortedByDescending { (_, list) -> list.sumOf { it.rule.weight } }
}

object ImplantScan {

    /** Installers that mean "a store vetted this", not "a link did". */
    val KNOWN_STORES: Set<String> = setOf(
        "com.android.vending",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.samsung.android.galaxyapps",
        "com.amazon.venezia",
        "com.huawei.appmarket",
        "com.sec.android.app.samsungapps"
    )

    /**
     * Pure evaluation over already-collected facts, so every rule is unit-testable without a
     * device. Ordering of the output is deterministic: strongest rule first, then package.
     */
    fun evaluate(facts: List<PackageFacts>, ownPackage: String? = null): ScanResult {
        val findings = mutableListOf<Finding>()

        facts.filter { it.pkg != ownPackage && !it.isSystem }.forEach { p ->
            if (p.hasAccessibilityService && (p.collectsAudio || p.readsSms)) {
                val held = buildList {
                    add("accessibility")
                    if (p.collectsAudio) add("RECORD_AUDIO")
                    if (p.readsSms) add("READ_SMS")
                }
                findings += Finding(
                    Rule.ACCESSIBILITY_EXFIL, p.pkg, p.label,
                    "holds " + held.joinToString(" + ")
                )
            }

            if (p.readsSms && p.online && !p.hasLauncher) {
                findings += Finding(
                    Rule.SMS_EXFIL, p.pkg, p.label,
                    "READ_SMS + INTERNET, no launcher activity"
                )
            }

            if (p.overlays && !p.hasLauncher) {
                findings += Finding(
                    Rule.OVERLAY_HEADLESS, p.pkg, p.label,
                    "SYSTEM_ALERT_WINDOW with no launcher activity"
                )
            }

            if (p.isDeviceAdmin) {
                findings += Finding(
                    Rule.DEVICE_ADMIN, p.pkg, p.label,
                    "active device administrator — blocks its own uninstall"
                )
            }

            if (!p.hasLauncher && p.online) {
                findings += Finding(
                    Rule.HEADLESS_NETWORK, p.pkg, p.label,
                    "network capability, no user-facing entry point"
                )
            }

            if (p.hasNotificationListener && p.online) {
                findings += Finding(
                    Rule.NOTIFICATION_EXFIL, p.pkg, p.label,
                    "notification listener + INTERNET — reads OTPs"
                )
            }

            val sideloaded = p.installer == null || p.installer !in KNOWN_STORES
            val sensitive = p.collectsAudio || p.readsSms || p.hasAccessibilityService
            if (sideloaded && sensitive) {
                val from = p.installer ?: "unknown installer"
                findings += Finding(
                    Rule.SIDELOAD_SENSITIVE, p.pkg, p.label,
                    "installed by $from and holds a sensitive capability"
                )
            }

            if (p.boots && !p.hasLauncher) {
                findings += Finding(
                    Rule.BOOT_HEADLESS, p.pkg, p.label,
                    "starts at boot, nothing to open"
                )
            }
        }

        val ordered = findings.sortedWith(
            compareByDescending<Finding> { it.rule.weight }.thenBy { it.pkg }
        )
        return ScanResult(
            findings = ordered,
            score = ordered.sumOf { it.rule.weight },
            scanned = facts.size
        )
    }

    /**
     * Reads the device. `QUERY_ALL_PACKAGES` is required on API 30+ — without it the package
     * list is filtered down to packages that already interact with this one, which would make
     * every rule above silently useless.
     */
    fun scan(context: Context, ownPackage: String = context.packageName): ScanResult {
        val pm = context.packageManager
        val admins = activeAdmins(context)

        val flags = PackageManager.GET_PERMISSIONS or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS

        val packages = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(flags)
            }
        }.getOrDefault(emptyList())

        val facts = packages.mapNotNull { info ->
            val app = info.applicationInfo ?: return@mapNotNull null
            val pkg = info.packageName
            val permissions = info.requestedPermissions?.toSet().orEmpty()
            val services = info.services.orEmpty()

            PackageFacts(
                pkg = pkg,
                label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(pkg),
                isSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
                hasLauncher = runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull() != null,
                installer = installerOf(pm, pkg),
                permissions = permissions,
                hasAccessibilityService = services.any {
                    it.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE"
                },
                hasNotificationListener = services.any {
                    it.permission == "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
                },
                isDeviceAdmin = pkg in admins
            )
        }

        return evaluate(facts, ownPackage)
    }

    private fun activeAdmins(context: Context): Set<String> {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return emptySet()
        return runCatching {
            dpm.activeAdmins.orEmpty().map { it.packageName }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun installerOf(pm: PackageManager, pkg: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val source = pm.getInstallSourceInfo(pkg)
            source.installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(pkg)
        }
    }.getOrNull()
}
