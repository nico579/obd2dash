package android.content.res

import com.nico.obd2dash.AuditResourceValues

class Resources {
    fun getQuantityString(id: Int, quantity: Int, vararg args: Any): String =
        AuditResourceValues.plural(id, quantity, *args)
}
