@file:Suppress("UNUSED_PARAMETER")
package android.net

import java.net.Socket

class Network { fun bindSocket(socket: Socket) {} }
class NetworkCapabilities {
    companion object { const val TRANSPORT_WIFI = 1; const val NET_CAPABILITY_INTERNET = 12 }
}
class NetworkRequest {
    class Builder {
        fun addTransportType(value: Int) = this
        fun removeCapability(value: Int) = this
        fun build() = NetworkRequest()
    }
}
class ConnectivityManager {
    open class NetworkCallback { open fun onAvailable(network: Network) {} }
    fun requestNetwork(request: NetworkRequest, callback: NetworkCallback) {
        callback.onAvailable(Network())
    }
    fun unregisterNetworkCallback(callback: NetworkCallback) {}
}
