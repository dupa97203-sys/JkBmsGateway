package com.jkbms.gateway.ble

import com.jkbms.gateway.model.BmsData
import com.jkbms.gateway.model.ScannedDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object BmsRepository {
    private val _bmsData = MutableStateFlow(BmsData.empty())
    val bmsData: StateFlow<BmsData> = _bmsData.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    fun update(data: BmsData) { _bmsData.value = data }
    fun getCurrent(): BmsData = _bmsData.value
    fun setDisconnected(address: String) { _bmsData.value = BmsData.empty(address, "DISCONNECTED") }
    fun setConnecting(address: String) { _bmsData.value = BmsData.empty(address, "CONNECTING") }
    fun updateScannedDevices(devices: List<ScannedDevice>) { _scannedDevices.value = devices }
    fun setScanning(scanning: Boolean) { _isScanning.value = scanning }
}
