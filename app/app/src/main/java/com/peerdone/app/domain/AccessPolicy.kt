package com.peerdone.app.domain

data class UserDirectoryEntry(
    val userId: String,
    val orgId: String,
    val level: Int,
    val active: Boolean = true,
)

data class AccessPolicy(
    val maxLevel: Int? = null,
    val minLevel: Int? = null,
    val includeOrgs: Set<String> = emptySet(),
    val excludeOrgs: Set<String> = emptySet(),
) {
    val isAll: Boolean
        get() = maxLevel == null &&
            minLevel == null &&
            includeOrgs.isEmpty() &&
            excludeOrgs.isEmpty()

    companion object
}

object PolicyEngine {
    fun matches(user: UserDirectoryEntry, policy: AccessPolicy): Boolean {
        if (!user.active) return false
        if (policy.includeOrgs.isNotEmpty() && user.orgId !in policy.includeOrgs) return false
        if (user.orgId in policy.excludeOrgs) return false

        policy.maxLevel?.let { max ->
            if (user.level > max) return false
        }
        policy.minLevel?.let { min ->
            if (user.level < min) return false
        }
        return true
    }

    fun resolveRecipients(
        policy: AccessPolicy,
        directory: List<UserDirectoryEntry>,
    ): List<UserDirectoryEntry> = directory.filter { matches(it, policy) }
}

