package com.kighmu.vpn.profiles

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * Profil ZIVPN UDP pour le multi-profil.
 */
data class ZivpnProfile(
    val id: String = UUID.randomUUID().toString(),
    var profileName: String = "",
    var serverAddress: String = "",
    var serverPort: String = "6000-7750,7751-9500,9501-11250,11251-13000,13001-14750,14751-16500,16501-18250,18251-19999",
    var password: String = "",
    var obfs: String = "",
    var isSelected: Boolean = false
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): ZivpnProfile =
            Gson().fromJson(json, ZivpnProfile::class.java)

        fun listFromJson(json: String): MutableList<ZivpnProfile> {
            val type = object : com.google.gson.reflect.TypeToken<MutableList<ZivpnProfile>>() {}.type
            return Gson().fromJson(json, type) ?: mutableListOf()
        }

        fun listToJson(list: List<ZivpnProfile>): String = Gson().toJson(list)
    }
}
