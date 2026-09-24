@file:Suppress("UNUSED_PARAMETER")
package android.content.pm

class PackageInfo { val versionName = "offline-viewmodel-harness" }
class PackageManager { fun getPackageInfo(name: String, flags: Int) = PackageInfo() }
