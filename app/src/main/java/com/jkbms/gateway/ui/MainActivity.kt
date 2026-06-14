package com.jkbms.gateway.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jkbms.gateway.BootReceiver
import com.jkbms.gateway.R
import com.jkbms.gateway.ble.BmsRepository
import com.jkbms.gateway.ble.BmsService
import com.jkbms.gateway.databinding.ActivityMainBinding
import com.jkbms.gateway.model.BmsData
import com.jkbms.gateway.model.ScannedDevice
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var service: BmsService? = null
    private var bound = false
    private lateinit var deviceAdapter: DeviceAdapter

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as BmsService.LocalBinder).get()
            bound = true
        }
        override fun onServiceDisconnected(name: ComponentName) { bound = false; service = null }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) onPermissionsGranted()
        else Toast.makeText(this, "Brak uprawnień BLE", Toast.LENGTH_LONG).show()
    }

    private val btLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { checkPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupButtons()
        updateIp()
        observeData()
        checkPermissions()

        BmsService.start(this)
        bindService(Intent(this, BmsService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        if (bound) unbindService(connection)
        super.onDestroy()
    }

    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter { device ->
            // Kliknięcie na urządzenie → połącz
            service?.bleManager?.connectTo(device.address, device.name)
            Toast.makeText(this, "Łączę z ${device.name}...", Toast.LENGTH_SHORT).show()
            binding.layoutDevices.visibility = View.GONE
            binding.layoutConnected.visibility = View.VISIBLE
        }
        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = deviceAdapter
    }

    private fun setupButtons() {
        binding.btnScan.setOnClickListener {
            service?.bleManager?.startScan()
            binding.layoutDevices.visibility = View.VISIBLE
            binding.layoutConnected.visibility = View.GONE
            binding.tvScanStatus.text = "Skanowanie..."
        }

        binding.btnDisconnect.setOnClickListener {
            service?.bleManager?.disconnect()
            binding.layoutDevices.visibility = View.GONE
            binding.layoutConnected.visibility = View.GONE
        }

        binding.btnBattery.setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun observeData() {
        lifecycleScope.launch {
            BmsRepository.bmsData.collectLatest { data ->
                updateBmsUI(data)
            }
        }
        lifecycleScope.launch {
            BmsRepository.scannedDevices.collectLatest { devices ->
                deviceAdapter.setDevices(devices)
                binding.tvScanStatus.text = if (devices.isEmpty()) "Skanowanie..." else "Znaleziono ${devices.size} urządzeń — kliknij aby połączyć"
            }
        }
        lifecycleScope.launch {
            BmsRepository.isScanning.collectLatest { scanning ->
                binding.btnScan.text = if (scanning) "⏳ Skanowanie..." else "🔍 Skanuj BLE"
            }
        }
    }

    private fun updateBmsUI(data: BmsData) {
        val statusColor = when (data.connectionStatus) {
            "CONNECTED" -> getColor(android.R.color.holo_green_dark)
            "DISCONNECTED" -> getColor(android.R.color.holo_red_dark)
            else -> getColor(android.R.color.holo_orange_dark)
        }
        binding.tvStatus.text = data.connectionStatus
        binding.tvStatus.setTextColor(statusColor)
        binding.tvDevice.text = if (data.deviceName.isNotEmpty()) "${data.deviceName} [${data.deviceAddress}]" else "–"

        if (data.connectionStatus != "CONNECTED") {
            binding.layoutData.visibility = View.GONE
            return
        }

        binding.layoutData.visibility = View.VISIBLE
        binding.tvSoc.text = "${data.soc}%"
        binding.tvVoltage.text = "%.2fV".format(data.totalVoltage)
        binding.tvCurrent.text = "%.2fA".format(data.current)
        binding.tvPower.text = "%.0fW".format(data.power)
        binding.tvTempMos.text = "MOS: %.1f°C".format(data.temperatureMos)
        binding.tvTempS1.text = "S1: %.1f°C".format(data.temperatureSensor1)
        binding.tvCapacity.text = "%.1f/%.1f Ah".format(data.remainingCapacity, data.nominalCapacity)
        binding.tvDiff.text = "Δ %.1f mV".format(data.cellVoltageDiff)
        binding.progressSoc.progress = data.soc

        val cells = data.cellVoltages.mapIndexed { i, v -> "C${i+1}: %.3fV".format(v) }
            .chunked(3).joinToString("\n") { it.joinToString("   ") }
        binding.tvCells.text = cells

        binding.tvMosfet.text = "CHG: ${if (data.chargeMosfet) "ON" else "OFF"}   DSG: ${if (data.dischargeMosfet) "ON" else "OFF"}   BAL: ${if (data.balancingActive) "ON" else "OFF"}"

        if (data.alarms.isEmpty()) {
            binding.tvAlarms.text = "✓ Brak alarmów"
            binding.tvAlarms.setTextColor(getColor(android.R.color.holo_green_dark))
        } else {
            binding.tvAlarms.text = "⚠ ${data.alarms.joinToString(", ")}"
            binding.tvAlarms.setTextColor(getColor(android.R.color.holo_red_dark))
        }
    }

    private fun updateIp() {
        val ip = try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress ?: "127.0.0.1"
        } catch (e: Exception) { "127.0.0.1" }
        binding.tvApiUrl.text = "http://$ip:${BmsService.PORT}/api"
        binding.tvDashUrl.text = "http://$ip:${BmsService.PORT}/"
    }

    private fun checkPermissions() {
        val bt = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (bt == null || !bt.isEnabled) {
            btLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return
        }
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!hasPerm(Manifest.permission.BLUETOOTH_SCAN)) add(Manifest.permission.BLUETOOTH_SCAN)
                if (!hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (needed.isEmpty()) onPermissionsGranted() else permLauncher.launch(needed.toTypedArray())
    }

    private fun onPermissionsGranted() {
        Toast.makeText(this, "Uprawnienia OK — kliknij Skanuj", Toast.LENGTH_SHORT).show()
    }

    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
}

// RecyclerView adapter dla listy urządzeń BLE
class DeviceAdapter(private val onClick: (ScannedDevice) -> Unit) :
    RecyclerView.Adapter<DeviceAdapter.VH>() {

    private val devices = mutableListOf<ScannedDevice>()

    fun setDevices(list: List<ScannedDevice>) {
        devices.clear()
        devices.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(val view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvDeviceName)
        val tvAddr: TextView = view.findViewById(R.id.tvDeviceAddr)
        val tvRssi: TextView = view.findViewById(R.id.tvDeviceRssi)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val d = devices[position]
        holder.tvName.text = d.name
        holder.tvAddr.text = d.address
        holder.tvRssi.text = "${d.rssi} dBm"
        holder.view.setOnClickListener { onClick(d) }
    }

    override fun getItemCount() = devices.size
}
