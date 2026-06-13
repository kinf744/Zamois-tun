package com.kighmu.vpn.profiles

import android.content.Context

class V2rayDnsProfileRepository(context: Context) {
    private val prefs = context.getSharedPreferences("v2raydns_profiles", Context.MODE_PRIVATE)
    private val KEY = "profiles_json"
    
    fun getAll(): MutableList<V2rayDnsProfile> {
        val json = prefs.getString(KEY, "[]") ?: "[]"
        return V2rayDnsProfile.listFromJson(json)
    }

    fun save(profiles: List<V2rayDnsProfile>) {
        prefs.edit().putString(KEY, V2rayDnsProfile.listToJson(profiles)).apply()
    }

    fun add(profile: V2rayDnsProfile) {
        val list = getAll()
        list.add(profile)
        save(list)
    }

    fun update(profile: V2rayDnsProfile) {
        val list = getAll()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) { 
            list[idx] = profile
            save(list) 
        }
    }

    fun delete(id: String) {
        val list = getAll().filter { it.id != id }.toMutableList()
        save(list)
    }
    
    fun clone(id: String) {
        val list = getAll()
        val original = list.firstOrNull { it.id == id } ?: return
        val cloned = original.copy(
            id = java.util.UUID.randomUUID().toString(),
            profileName = "${original.profileName} (copy)"
        )
        list.add(cloned)
        save(list)
    }
    
    fun getSelected(): List<V2rayDnsProfile> = getAll().filter { it.isSelected }
    
    fun updateSelection(id: String, selected: Boolean) {
        val list = getAll()
        list.firstOrNull { it.id == id }?.isSelected = selected
        save(list)
    }
}
