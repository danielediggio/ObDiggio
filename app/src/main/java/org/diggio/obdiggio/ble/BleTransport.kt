package org.diggio.obdiggio.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import org.diggio.obdiggio.core.obd.Transport
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class AdapterConnection { BLE, CLASSIC }

data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val connection: AdapterConnection = AdapterConnection.BLE
) {
    fun looksLikeObd(): Boolean {
        val n = name.uppercase()
        return listOf("OBD", "ELM", "ICAR", "VGATE", "VIECAR", "VLINK").any { n.contains(it) }
    }
    override fun toString() = "$name [$address] $rssi dBm ${connection.name}"
}

class BleTransport(
    private val context: Context,
    private val connectTimeoutMs: Long = 20_000
) : Transport() {

    private val TAG = "BleTransport"
    private val manager = context.getSystemService("bluetooth") as BluetoothManager
    private val adapter = manager.adapter

    val devices = mutableListOf<BleDevice>()
    private var scanResults: Map<String, BluetoothDevice> = emptyMap()
    private var selected: BluetoothDevice? = null

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var writeSupportsResponse = false
    @Volatile private var gattConnected = false
    private var servicesLatch: CountDownLatch? = null
    private var writeLatch: CountDownLatch? = null

    companion object {
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val KNOWN_NOTIFY_UUIDS = setOf(
            UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        )
        private val KNOWN_WRITE_UUIDS = setOf(
            UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        )
    }

    override val isConnected: Boolean get() = gattConnected && writeChar != null

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) g.discoverServices() else gattConnected = false
                }
                BluetoothGatt.STATE_DISCONNECTED -> gattConnected = false
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            bindCharacteristics(g)
            servicesLatch?.countDown()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            ch.value?.let { feed(it) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            feed(value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            writeLatch?.countDown()
        }
    }

    fun scan(timeoutMs: Long = 8000): List<BleDevice> {
        devices.clear()
        val scanner = adapter.bluetoothLeScanner ?: return emptyList()
        val found = mutableMapOf<String, BluetoothDevice>()
        val cb = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val dev = result.device ?: return
                if (found.put(dev.address, dev) == null) {
                    devices.add(BleDevice(dev.name ?: "?", dev.address, result.rssi))
                }
            }
        }
        scanner.startScan(cb)
        Thread.sleep(timeoutMs)
        scanner.stopScan(cb)
        scanResults = found
        return devices.toList()
    }

    fun select(device: BleDevice) {
        selected = scanResults[device.address] ?: adapter.getRemoteDevice(device.address)
    }

    override fun open() {
        val device = selected ?: error("Nessun device selezionato: chiama scan()/select() prima")
        servicesLatch = CountDownLatch(1)
        gatt = device.connectGatt(context, false, gattCallback)
        val ready = servicesLatch!!.await(connectTimeoutMs, TimeUnit.MILLISECONDS)
        if (!ready || writeChar == null) {
            close()
            error("Connessione BLE non riuscita: caratteristiche non trovate")
        }
        notifyChar?.let { enableNotifications(it) }
        gattConnected = true
    }

    override fun close() {
        gattConnected = false
        try { gatt?.close() } catch (e: Exception) { Log.w(TAG, "close", e) }
        gatt = null
        writeChar = null
        notifyChar = null
        writeSupportsResponse = false
        writeLatch = null
    }

    override fun write(data: ByteArray) {
        val g = gatt ?: error("GATT non connesso")
        val ch = writeChar ?: error("Caratteristica di scrittura non disponibile")
        data.toList().chunked(20).forEach { chunk ->
            val bytes = chunk.toByteArray()
            if (writeSupportsResponse) {
                val latch = CountDownLatch(1)
                writeLatch = latch
                val accepted = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        ch.value = bytes
                        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        g.writeCharacteristic(ch)
                    }
                }
                check(accepted) { "Scrittura BLE rifiutata dall'adattatore" }
                check(latch.await(2, TimeUnit.SECONDS)) { "Timeout scrittura BLE" }
            } else {
                if (Build.VERSION.SDK_INT >= 33) {
                    check(g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS) {
                        "Scrittura BLE rifiutata dall'adattatore"
                    }
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        ch.value = bytes
                        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        check(g.writeCharacteristic(ch)) { "Scrittura BLE rifiutata dall'adattatore" }
                    }
                }
                Thread.sleep(25)
            }
        }
    }

    private fun enableNotifications(ch: BluetoothGattCharacteristic) {
        val g = gatt ?: return
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD_UUID) ?: return
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
    }

    private fun bindCharacteristics(g: BluetoothGatt) {
        var notify: BluetoothGattCharacteristic? = null
        var write: BluetoothGattCharacteristic? = null
        for (service in g.services) {
            for (ch in service.characteristics) {
                val uuid = ch.uuid
                val props = ch.properties
                val canNotify = (props and 0x30) != 0
                val canWrite = (props and 0x0C) != 0
                if (notify == null && (uuid in KNOWN_NOTIFY_UUIDS || canNotify)) notify = ch
                if (write == null && (uuid in KNOWN_WRITE_UUIDS || canWrite)) write = ch
            }
        }
        notifyChar = notify
        writeChar = write
        writeSupportsResponse = write?.let { (it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 } == true
    }
}
