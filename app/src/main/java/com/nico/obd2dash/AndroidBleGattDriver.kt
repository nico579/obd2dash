package com.nico.obd2dash

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import java.io.IOException
import java.util.UUID

/** Profil FFF0/FFF1/FFF2 observé sur la KONNWEI, pas une sélection arbitraire de service. */
@SuppressLint("MissingPermission") // Permission vérifiée par l'écran ; les refus sont propagés au client.
internal class AndroidBleGattDriver(
    private val context: Context,
    private val device: BluetoothDevice
) : BleGattDriver {
    private val lock = Any()
    private var gatt: BluetoothGatt? = null
    private var closed = false
    private lateinit var events: BleGattDriver.Events
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var cccd: BluetoothGattDescriptor? = null
    private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

    // connectGatt peut rappeler avant de retourner : accepter son premier handle,
    // mais jamais un callback tardif après close() ou provenant d'un autre handle.
    private fun accept(candidate: BluetoothGatt): Boolean = synchronized(lock) {
        if (closed) false else if (gatt == null) { gatt = candidate; true } else gatt === candidate
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!accept(gatt)) return
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                events.failed(IOException("Connexion BLE interrompue (statut $status)"))
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                events.connected()
            }
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!accept(gatt)) return
            if (status == BluetoothGatt.GATT_SUCCESS) events.servicesDiscovered()
            else events.failed(IOException("Découverte BLE en échec (statut $status)"))
        }
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (!accept(gatt) || descriptor !== cccd) return
            if (status == BluetoothGatt.GATT_SUCCESS) events.notificationsEnabled()
            else events.failed(IOException("Abonnement BLE en échec (statut $status)"))
        }
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (!accept(gatt) || characteristic !== writeCharacteristic) return
            if (status == BluetoothGatt.GATT_SUCCESS) events.writeCompleted()
            else events.failed(IOException("Écriture BLE en échec (statut $status)"))
        }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (accept(gatt) && characteristic === notifyCharacteristic) events.bytesReceived(value)
        }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && accept(gatt) && characteristic === notifyCharacteristic) {
                characteristic.value?.let { events.bytesReceived(it) }
            }
        }
    }

    override fun connect(events: BleGattDriver.Events) {
        synchronized(lock) {
            if (closed) throw IOException("Connexion BLE fermée")
            this.events = events
        }
        val candidate = device.connectGatt(context.applicationContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("Ouverture BLE impossible")
        if (!accept(candidate)) {
            runCatching { candidate.disconnect() }
            runCatching { candidate.close() }
        }
    }

    override fun discoverServices(): Boolean = synchronized(lock) {
        !closed && gatt?.discoverServices() == true
    }

    @Suppress("DEPRECATION")
    override fun enableNotifications(): Boolean = synchronized(lock) {
        val connection = gatt?.takeUnless { closed } ?: return false
        val service = connection.getService(SERVICE)
            ?: throw IOException("Profil BLE FFF0/FFF1/FFF2 non pris en charge par cette sonde")
        val notify = service.getCharacteristic(NOTIFY)
            ?: throw IOException("Canal de réception BLE FFF1 absent")
        val write = service.getCharacteristic(WRITE)
            ?: throw IOException("Canal d’écriture BLE FFF2 absent")
        val notificationValue = when {
            notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ->
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            notify.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 ->
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> throw IOException("Canal de réception BLE sans notifications")
        }
        writeType = when {
            write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else -> throw IOException("Canal BLE non inscriptible")
        }
        val descriptor = notify.getDescriptor(CCCD)
            ?: throw IOException("Descripteur d’abonnement BLE absent")
        notifyCharacteristic = notify
        writeCharacteristic = write
        cccd = descriptor
        if (!connection.setCharacteristicNotification(notify, true)) return false
        if (Build.VERSION.SDK_INT >= 33) {
            connection.writeDescriptor(descriptor, notificationValue) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = notificationValue
            connection.writeDescriptor(descriptor)
        }
    }

    @Suppress("DEPRECATION")
    override fun write(value: ByteArray): Boolean = synchronized(lock) {
        val connection = gatt?.takeUnless { closed } ?: return false
        val characteristic = writeCharacteristic ?: return false
        if (Build.VERSION.SDK_INT >= 33) {
            connection.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = writeType
            characteristic.value = value
            connection.writeCharacteristic(characteristic)
        }
    }

    override fun close() {
        val connection = synchronized(lock) {
            if (closed) return
            closed = true
            gatt.also { gatt = null }
        }
        runCatching { connection?.disconnect() }
        runCatching { connection?.close() }
    }

    private companion object {
        val SERVICE: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val NOTIFY: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        val WRITE: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
