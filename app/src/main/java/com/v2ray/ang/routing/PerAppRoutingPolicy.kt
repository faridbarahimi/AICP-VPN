package com.v2ray.ang.routing

/** Single source of truth for checkbox semantics: checked means routed through VPN. */
internal object PerAppRoutingPolicy {
    data class NormalizedSettings(
        val selectedVpnPackages: Set<String>,
        val perAppEnabled: Boolean,
        val bypassApps: Boolean = false,
    )

    sealed interface BuilderPlan {
        data class AllowOnly(val packages: Set<String>) : BuilderPlan
        data class Disallow(val packages: Set<String>) : BuilderPlan
    }

    /** The exact allow/disallow plan consumed by CoreVpnService and covered by unit tests. */
    fun builderPlan(
        installedPackages: Collection<String>,
        selfPackageName: String,
        perAppEnabled: Boolean,
        bypassApps: Boolean,
        selectedPackages: Set<String>,
        forceAllApps: Boolean = false,
    ): BuilderPlan {
        val installed = installedPackages.toSet()
        if (forceAllApps) return BuilderPlan.Disallow(setOf(selfPackageName))
        if (!perAppEnabled) return BuilderPlan.Disallow(setOf(selfPackageName))
        if (bypassApps) {
            val selectedInstalled = selectedPackages intersect installed
            return BuilderPlan.Disallow(if (selectedInstalled.isEmpty()) setOf(selfPackageName) else selectedInstalled + selfPackageName)
        }
        val allowed = (selectedPackages intersect installed) - selfPackageName
        return if (allowed.isEmpty()) {
            // An absent Android allow-list means all apps are allowed, so explicitly deny all.
            BuilderPlan.Disallow(installed + selfPackageName)
        } else {
            BuilderPlan.AllowOnly(allowed)
        }
    }

    fun normalize(
        installedPackages: Collection<String>,
        storedSelection: Set<String>,
        perAppEnabled: Boolean,
        bypassApps: Boolean,
    ): NormalizedSettings {
        val installed = installedPackages.toSet()
        // The persisted selection has mode-dependent meaning: allowed apps in normal mode,
        // bypassed apps in bypass mode. Never silently complement or erase it during load.
        val selected = storedSelection intersect installed
        return NormalizedSettings(selected, perAppEnabled, bypassApps = bypassApps)
    }

    fun isVpnSelected(
        packageName: String,
        perAppEnabled: Boolean,
        selectedPackages: Set<String>,
        bypassApps: Boolean = false,
    ): Boolean = when {
        !perAppEnabled -> true
        bypassApps -> packageName !in selectedPackages
        else -> packageName in selectedPackages
    }

    fun selectionAfterToggle(
        packageName: String,
        installedPackages: Collection<String>,
        selectedVpnPackages: Set<String>,
        perAppEnabled: Boolean,
    ): Set<String> {
        val effectiveSelection = if (perAppEnabled) selectedVpnPackages else installedPackages.toSet()
        return if (packageName in effectiveSelection) effectiveSelection - packageName
        else effectiveSelection + packageName
    }
}
