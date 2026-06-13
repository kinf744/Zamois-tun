package com.kighmu.vpn.profiles

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * Profil Hysteria UDP pour le multi-profil.
 */
data class HysteriaProfile(
    val id: String = UUID.randomUUID().toString(),
    var profileName: String = "",
    var serverAddress: String = "",

    var authPassword: String = "",
    var uploadMbps: Int = 100,
    var downloadMbps: Int = 100,
    var obfsPassword: String = "",
    var portHopping: String = "20000-50000",
    var isSelected: Boolean = false
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): HysteriaProfile =
            Gson().fromJson(json, HysteriaProfile::class.java)

        fun listFromJson(json: String): MutableList<HysteriaProfile> {
            val type = object : TypeToken<MutableList<HysteriaProfile>>() {}.type
            return Gson().fromJson(json, type) ?: mutableListOf()
        }

        fun listToJson(list: List<HysteriaProfile>): String = Gson().toJson(list)
    }
}
