package com.kighmu.vpn.profiles

import android.content.Context

class HttpProxyProfileRepository(context: Context) {
    private val prefs = context.getSharedPreferences("httpproxy_profiles", Context.MODE_PRIVATE)
    private val KEY = "profiles_json"

    fun getAll(): MutableList<HttpProxyProfile> {
        val json = prefs.getString(KEY, "[]") ?: "[]"
        return HttpProxyProfile.listFromJson(json)
    }

    fun save(profiles: List<HttpProxyProfile>) {
        prefs.edit().putString(KEY, HttpProxyProfile.listToJson(profiles)).apply()
    }

    fun add(profile: HttpProxyProfile) {
        val list = getAll()
        list.add(profile)
        save(list)
    }

    fun update(profile: HttpProxyProfile) {
        val list = getAll()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) { list[idx] = profile; save(list) }
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

    fun getSelected(): List<HttpProxyProfile> = getAll().filter { it.isSelected }

    fun updateSelection(id: String, selected: Boolean) {
        val list = getAll()
        list.firstOrNull { it.id == id }?.isSelected = selected
        save(list)
    }
}
