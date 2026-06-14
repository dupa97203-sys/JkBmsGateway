package com.jkbms.gateway.model

import com.google.gson.annotations.SerializedName

data class BmsData(
    @SerializedName("timestamp")            val timestamp: Long = System.currentTimeMillis(),
    @SerializedName("device_name")          val deviceName: String = "",
    @SerializedName("device_address")       val deviceAddress: String = "",
    @SerializedName("connection_status")    val connectionStatus: String = "DISCONNECTED",
    @SerializedName("total_voltage")        val totalVoltage: Float = 0f,
    @SerializedName("cell_voltages")        val cellVoltages: List<Float> = emptyList(),
    @SerializedName("cell_voltage_min")     val cellVoltageMin: Float = 0f,
    @SerializedName("cell_voltage_max")     val cellVoltageMax: Float = 0f,
    @SerializedName("cell_voltage_diff")    val cellVoltageDiff: Float = 0f,
    @SerializedName("cell_count")           val cellCount: Int = 0,
    @SerializedName("current")              val current: Float = 0f,
    @SerializedName("power")               val power: Float = 0f,
    @SerializedName("soc")                 val soc: Int = 0,
    @SerializedName("remaining_capacity")   val remainingCapacity: Float = 0f,
    @SerializedName("nominal_capacity")     val nominalCapacity: Float = 0f,
    @SerializedName("charge_cycles")        val chargeCycles: Int = 0,
    @SerializedName("temperature_mos")      val temperatureMos: Float = 0f,
    @SerializedName("temperature_sensor1")  val temperatureSensor1: Float = 0f,
    @SerializedName("temperature_sensor2")  val temperatureSensor2: Float = 0f,
    @SerializedName("charge_mosfet")        val chargeMosfet: Boolean = false,
    @SerializedName("discharge_mosfet")     val dischargeMosfet: Boolean = false,
    @SerializedName("balancing_active")     val balancingActive: Boolean = false,
    @SerializedName("alarms")              val alarms: List<String> = emptyList()
) {
    companion object {
        fun empty(address: String = "", status: String = "DISCONNECTED") =
            BmsData(deviceAddress = address, connectionStatus = status)
    }
}

data class ScannedDevice(
    val name: String,
    val address: String,
    val rssi: Int
)
