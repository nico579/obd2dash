package com.nico.obd2dash

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import java.util.concurrent.atomic.AtomicBoolean

/** Découverte radio seulement ; ne se connecte pas et n'envoie aucune commande ELM. */
@SuppressLint("MissingPermission") // MainActivity demande les permissions avant start().
internal class AndroidBleScanner(private val scanner: BluetoothLeScanner) {
    private val active = AtomicBoolean(false)
    private var callback: ScanCallback? = null

    fun start(onDevice: (BluetoothDevice) -> Unit, onError: (Int) -> Unit) {
        check(active.compareAndSet(false, true))
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (active.get()) onDevice(result.device)
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                if (active.get()) results.forEach { onDevice(it.device) }
            }
            override fun onScanFailed(errorCode: Int) {
                if (active.compareAndSet(true, false)) onError(errorCode)
            }
        }
        callback = scanCallback
        try {
            scanner.startScan(emptyList(), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    fun stop() {
        active.set(false)
        callback?.let { runCatching { scanner.stopScan(it) } }
        callback = null
    }
}
