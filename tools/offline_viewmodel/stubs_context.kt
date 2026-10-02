@file:Suppress("UNUSED_PARAMETER", "unused")
package android.content

import android.net.ConnectivityManager
import com.nico.obd2dash.AuditResourceValues
import java.io.File

open class Context(val filesDir: File) {
    val applicationContext: Context get() = this
    private val prefs = SharedPreferences()
    val packageName = "com.nico.obd2dash"
    val packageManager = android.content.pm.PackageManager()
    val resources = android.content.res.Resources()
    fun getString(id: Int, vararg args: Any): String = AuditResourceValues.string(id, *args)
    fun getSharedPreferences(name: String, mode: Int) = prefs
    fun getSystemService(name: String): Any = ConnectivityManager()
    fun <T> getSystemService(type: Class<T>): T? = null
    fun stopService(intent: Intent) = true
    companion object { const val MODE_PRIVATE = 0 }
}

class Intent(context: Context, type: Class<*>)

class SharedPreferences {
    // No saved Bluetooth address: constructor auto-connect has no target.
    // The test explicitly connects to its loopback server through the real ViewModel.
    private val data = mutableMapOf(
        "conn_mode" to "BLUETOOTH",
        "conn_host" to "127.0.0.1",
        "conn_port" to "1",
    )
    fun getString(key: String, default: String?): String? = data[key] ?: default
    fun edit() = Editor(data)
    class Editor(private val data: MutableMap<String, String>) {
        fun putString(key: String, value: String): Editor { data[key] = value; return this }
        fun apply() {}
    }
}
