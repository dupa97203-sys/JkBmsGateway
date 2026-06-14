package com.jkbms.gateway.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jkbms.gateway.model.ScannedDevice
import kotlinx.coroutines.*
import java.util.UUID

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        val SERVICE_UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        val CHAR_NOTIFY  = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
        val CHAR_WRITE   = UUID.fromString("0000FFE2-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID    = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        private const val RECONNECT_DELAY = 5_000L
        private const val POLL_INTERVAL   = 2_000L
        private const val SCAN_DURATION   = 10_000L
    }

    private val btAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var targetAddress = ""
    private var targetName = ""
    private var pollJob: Job? = null
    private var reconnectJob: Job? = null

    private val frameBuffer = mutableListOf<Byte>()
    private var expectedLength = 0

    var onStatusChanged: ((String) -> Unit)? = null
    var onDataReceived: ((ByteArray) -> Unit)? = null

    // Mapa skanowanych urządzeń
    private val scanResults = mutableMapOf<String, ScannedDevice>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            scanResults[result.device.address] = ScannedDevice(
                name = name,
                address = result.device.address,
                rssi = result.rssi
            )
            BmsRepository.updateScannedDevices(scanResults.values.sortedByDescending { it.rssi })
        }
        override fun onScanFailed(errorCode: Int) {
            BmsRepository.setScanning(false)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    onStatusChanged?.invoke("CONNECTED_DISCOVERING")
                    handler.postDelayed({ gatt.discoverServices() }, 500)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    onStatusChanged?.invoke("DISCONNECTED")
                    BmsRepository.setDisconnected(targetAddress)
                    cleanupGatt()
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = gatt.getService(SERVICE_UUID) ?: return
            val notifyChar = service.getCharacteristic(CHAR_NOTIFY) ?: return
            writeChar = service.getCharacteristic(CHAR_WRITE)

            gatt.setCharacteristicNotification(notifyChar, true)
            notifyChar.getDescriptor(CCCD_UUID)?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
            }

            onStatusChanged?.invoke("CONNECTED")
            startPolling()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
            if (char.uuid == CHAR_NOTIFY) accumulateFrame(char.value ?: return)
        }
    }

    // --- Publiczne API ---

    fun startScan() {
        scanResults.clear()
        BmsRepository.updateScannedDevices(emptyList())
        BmsRepository.setScanning(true)

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        btAdapter.bluetoothLeScanner?.startScan(null, settings, scanCallback)

        handler.postDelayed({
            stopScan()
        }, SCAN_DURATION)
    }

    fun stopScan() {
        btAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        BmsRepository.setScanning(false)
    }

    fun connectTo(address: String, name: String) {
        stopScan()
        targetAddress = address
        targetName = name
        BmsRepository.setConnecting(address)
        onStatusChanged?.invoke("CONNECTING")
        val device = btAdapter.getRemoteDevice(address)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        reconnectJob?.cancel()
        pollJob?.cancel()
        cleanupGatt()
        targetAddress = ""
    }

    fun sendCommand(cmd: ByteArray) {
        val char = writeChar ?: return
        char.value = cmd
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        gatt?.writeCharacteristic(char)
    }

    // --- Prywatne ---

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                sendCommand(JkBmsDecoder.CMD_CELL_INFO)
                delay(POLL_INTERVAL)
            }
        }
    }

    private fun scheduleReconnect() {
        if (targetAddress.isEmpty()) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY)
            connectTo(targetAddress, targetName)
        }
    }

    private fun cleanupGatt() {
        pollJob?.cancel()
        writeChar = null
        try { gatt?.disconnect(); gatt?.close() } catch (e: Exception) { }
        gatt = null
        frameBuffer.clear()
    }

    private fun accumulateFrame(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (bytes.size >= 2 && bytes[0] == 0x4E.toByte() && bytes[1] == 0x57.toByte()) {
            frameBuffer.clear()
            if (bytes.size >= 4)
                expectedLength = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF) + 4
        }
        frameBuffer.addAll(bytes.toList())
        if (frameBuffer.size >= expectedLength && expectedLength > 0) {
            val frame = frameBuffer.toByteArray()
            frameBuffer.clear(); expectedLength = 0
            onDataReceived?.invoke(frame)
        }
    }
}
