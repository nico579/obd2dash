@file:Suppress("UNUSED_PARAMETER")
package android.bluetooth

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class BluetoothManager { val adapter: BluetoothAdapter? = null }
class BluetoothAdapter {
    val isEnabled = false
    val bondedDevices = emptySet<BluetoothDevice>()
    fun getRemoteDevice(address: String) = BluetoothDevice()
}
class BluetoothDevice {
    val name: String? = "offline-harness"
    val address = "00:00:00:00:00:00"
    fun createRfcommSocketToServiceRecord(uuid: UUID) = BluetoothSocket()
}
class BluetoothSocket {
    val isConnected = false
    val inputStream: InputStream = ByteArrayInputStream(byteArrayOf())
    val outputStream: OutputStream = ByteArrayOutputStream()
    fun connect() { error("Bluetooth transport is disabled in the offline harness") }
    fun close() {}
}
