package com.kighmu.vpn.profiles

import android.content.Context

class HysteriaProfileRepository(context: Context) {
    private val prefs = context.getSharedPreferences("hysteria_profiles", Context.MODE_PRIVATE)
    private val KEY = "profiles_json"

    fun getAll(): MutableList<HysteriaProfile> {
        val json = prefs.getString(KEY, "[]") ?: "[]"
        return HysteriaProfile.listFromJson(json)
    }

    fun save(profiles: List<HysteriaProfile>) {
        prefs.edit().putString(KEY, HysteriaProfile.listToJson(profiles)).apply()
    }

    fun add(profile: HysteriaProfile) {
        val list = getAll(); list.add(profile); save(list)
    }

    fun update(profile: HysteriaProfile) {
        val list = getAll()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) { list[idx] = profile; save(list) }
    }

    fun delete(id: String) {
        save(getAll().filter { it.id != id })
    }

    fun clone(id: String) {
        val list = getAll()
        val original = list.firstOrNull { it.id == id } ?: return
        list.add(original.copy(id = java.util.UUID.randomUUID().toString(), profileName = "${original.profileName} (copy)"))
        save(list)
    }

    fun getSelected(): List<HysteriaProfile> = getAll().filter { it.isSelected }

    fun updateSelection(id: String, selected: Boolean) {
        val list = getAll()
        list.firstOrNull { it.id == id }?.isSelected = selected
        save(list)
    }
}
