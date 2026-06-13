package com.github.kr328.clash.core.bridge
import android.webkit.URLUtil
import java.net.URL
object Content {
    @JvmStatic
    fun open(url: String): Int {
        return URL(url).openConnection().getInputStream().use { -1 }
    }
}
