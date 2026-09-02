package org.diggio.obdiggio.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import org.diggio.obdiggio.core.obd.Transport
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Bluetooth Classic SPP transport used by many Vgate and ELM327 adapters. */
class ClassicBluetoothTransport(
    private val context: Context,
    private val address: String
) : Transport() {
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    @Volatile private var connected = false
    private var reader: Thread? = null

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        fun bondedDevices(@Suppress("UNUSED_PARAMETER") context: Context): List<BleDevice> {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
            return adapter.bondedDevices.orEmpty()
                .filter { device -> device.type == BluetoothDevice.DEVICE_TYPE_CLASSIC || device.type == BluetoothDevice.DEVICE_TYPE_DUAL }
                .map { device ->
                    BleDevice(
                        name = device.name ?: "Bluetooth OBD",
                        address = device.address,
                        rssi = 0,
                        connection = AdapterConnection.CLASSIC
                    )
                }
        }
    }

    override val isConnected: Boolean get() = connected

    override fun open() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: error("Bluetooth non disponibile")
        val device = adapter.getRemoteDevice(address)
        adapter.cancelDiscovery()
        val openedSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
        openedSocket.connect()
        socket = openedSocket
        input = openedSocket.inputStream
        output = openedSocket.outputStream
        connected = true
        startReader()
    }

    override fun close() {
        connected = false
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
        reader = null
    }

    @Synchronized
    override fun write(data: ByteArray) {
        check(connected) { "Bluetooth Classic non connesso" }
        val stream = output ?: error("Output Bluetooth non disponibile")
        stream.write(data)
        stream.flush()
    }

    private fun startReader() {
        reader = Thread {
            val buffer = ByteArray(512)
            while (connected) {
                val count = runCatching { input?.read(buffer) ?: -1 }.getOrDefault(-1)
                if (count <= 0) {
                    connected = false
                    break
                }
                feed(buffer.copyOf(count))
            }
        }.apply {
            name = "ObDiggio-ClassicBT"
            isDaemon = true
            start()
        }
    }
}
