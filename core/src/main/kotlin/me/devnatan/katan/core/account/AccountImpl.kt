package me.devnatan.katan.core.account

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.devnatan.katan.api.account.Account
import me.devnatan.katan.api.permission.Permission
import me.devnatan.katan.api.permission.PermissionFlag
import java.util.*

data class AccountImpl(
    override val id: UUID,
    override val username: String,
    override var password: String = ""
) : Account {

    override val permissions: MutableMap<Permission, PermissionFlag> = hashMapOf()

}