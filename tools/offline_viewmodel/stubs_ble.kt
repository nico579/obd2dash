@file:Suppress("UNUSED_PARAMETER")
package com.nico.obd2dash

import android.bluetooth.BluetoothDevice
import android.content.Context

/** This ViewModel harness exercises real TCP only; Android radio access is forbidden. */
internal class AndroidBleGattDriver(context: Context, device: BluetoothDevice) : BleGattDriver {
    override fun connect(events: BleGattDriver.Events) { error("BLE disabled in the offline ViewModel harness") }
    override fun discoverServices(): Boolean = error("BLE disabled")
    override fun enableNotifications(): Boolean = error("BLE disabled")
    override fun write(value: ByteArray): Boolean = error("BLE disabled")
    override fun close() {}
}

internal class AndroidBleScanner(scanner: Any) {
    fun start(onDevice: (BluetoothDevice) -> Unit, onError: (Int) -> Unit) { error("BLE scan disabled in the offline harness") }
    fun stop() {}
}
