@file:Suppress("unused")
package android.app

import android.content.Context
import java.io.File

open class Application(dir: File) : Context(dir) {
    companion object { const val CONNECTIVITY_SERVICE = "connectivity" }
}
