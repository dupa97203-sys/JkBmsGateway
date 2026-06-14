package com.jkbms.gateway.ble

import android.util.Log
import com.jkbms.gateway.model.BmsData

object JkBmsDecoder {

    private const val TAG = "JkBmsDecoder"

    val CMD_CELL_INFO   = byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x90.toByte(), 0xEB.toByte())
    val CMD_DEVICE_INFO = byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x90.toByte(), 0xEC.toByte())

    // Komendy sterowania MOSFET
    val CMD_CHG_ON  = byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x90.toByte(), 0x97.toByte(), 0x01.toByte())
    val CMD_CHG_OFF = byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x90.toByte(), 0x97.toByte(), 0x00.toByte())
    val CMD_DSG_ON  = byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x90.toByte(), 0x98.toByte(), 0x01.toByte())
    val CMD_DSG_OFF = byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x90.toByte(), 0x98.toByte(), 0x00.toByte())

    fun decode(raw: ByteArray, deviceName: String, deviceAddress: String): BmsData? {
        if (raw.size < 12) return null
        if (raw[0] != 0x4E.toByte() || raw[1] != 0x57.toByte()) return null

        val payload = raw.drop(11).toByteArray()
        return parseRecords(payload, deviceName, deviceAddress)
    }

    private fun parseRecords(data: ByteArray, deviceName: String, deviceAddress: String): BmsData {
        var i = 0
        val cellVoltages = mutableListOf<Float>()
        var totalVoltage = 0f; var current = 0f; var soc = 0
        var remainingCap = 0f; var nominalCap = 0f; var cycleCount = 0
        var tempMos = 0f; var tempS1 = 0f; var tempS2 = 0f
        var chargeMos = false; var dischargeMos = false; var balancing = false
        val alarms = mutableListOf<String>()

        while (i < data.size - 1) {
            val recId = data[i].toInt() and 0xFF
            i++
            try {
                when (recId) {
                    0x79 -> {
                        val byteCount = data[i].toInt() and 0xFF; i++
                        repeat(byteCount / 2) { c ->
                            val idx = i + c * 2
                            if (idx + 1 < data.size) {
                                val v = ((data[idx].toInt() and 0xFF) shl 8) or (data[idx+1].toInt() and 0xFF)
                                cellVoltages.add(v / 1000f)
                            }
                        }
                        i += byteCount
                    }
                    0x85 -> { totalVoltage = read2U(data, i) / 100f; i += 2 }
                    0x84 -> { current = read2S(data, i) / 100f; i += 2 }
                    0x89 -> { soc = data[i].toInt() and 0xFF; i++ }
                    0x8A -> { remainingCap = read4U(data, i) / 1000f; i += 4 }
                    0x8B -> { nominalCap = read4U(data, i) / 1000f; i += 4 }
                    0x8C -> { cycleCount = read4U(data, i).toInt(); i += 4 }
                    0x80 -> { tempMos = (read2U(data, i) - 2731) / 10f; i += 2 }
                    0x81 -> { tempS1 = (read2U(data, i) - 2731) / 10f; i += 2 }
                    0x82 -> { tempS2 = (read2U(data, i) - 2731) / 10f; i += 2 }
                    0xAB -> { chargeMos = data[i].toInt() and 0xFF != 0; i++ }
                    0xAC -> { dischargeMos = data[i].toInt() and 0xFF != 0; i++ }
                    0x87 -> { balancing = data[i].toInt() and 0xFF != 0; i++ }
                    0x8E -> {
                        val flags = read4U(data, i).toInt()
                        if (flags and 0x01 != 0) alarms.add("LOW_CAPACITY")
                        if (flags and 0x02 != 0) alarms.add("MOS_OVERTEMPERATURE")
                        if (flags and 0x04 != 0) alarms.add("CHARGE_OVERVOLTAGE")
                        if (flags and 0x08 != 0) alarms.add("DISCHARGE_UNDERVOLTAGE")
                        if (flags and 0x10 != 0) alarms.add("BATTERY_OVERTEMPERATURE")
                        if (flags and 0x20 != 0) alarms.add("CHARGE_OVERCURRENT")
                        if (flags and 0x40 != 0) alarms.add("DISCHARGE_OVERCURRENT")
                        if (flags and 0x80 != 0) alarms.add("SHORT_CIRCUIT")
                        i += 4
                    }
                    else -> i++
                }
            } catch (e: Exception) {
                Log.w(TAG, "Parse error at rec 0x${recId.toString(16)}: $e")
                break
            }
        }

        val minV = cellVoltages.minOrNull() ?: 0f
        val maxV = cellVoltages.maxOrNull() ?: 0f

        return BmsData(
            deviceName = deviceName, deviceAddress = deviceAddress,
            connectionStatus = "CONNECTED",
            totalVoltage = totalVoltage, cellVoltages = cellVoltages,
            cellVoltageMin = minV, cellVoltageMax = maxV,
            cellVoltageDiff = (maxV - minV) * 1000f,
            cellCount = cellVoltages.size,
            current = current, power = totalVoltage * current,
            soc = soc, remainingCapacity = remainingCap,
            nominalCapacity = nominalCap, chargeCycles = cycleCount,
            temperatureMos = tempMos, temperatureSensor1 = tempS1, temperatureSensor2 = tempS2,
            chargeMosfet = chargeMos, dischargeMosfet = dischargeMos,
            balancingActive = balancing, alarms = alarms
        )
    }

    private fun read2U(d: ByteArray, i: Int) = ((d[i].toInt() and 0xFF) shl 8) or (d[i+1].toInt() and 0xFF)
    private fun read2S(d: ByteArray, i: Int) = read2U(d, i).let { if (it and 0x8000 != 0) it - 0x10000 else it }
    private fun read4U(d: ByteArray, i: Int) =
        ((d[i].toLong() and 0xFF) shl 24) or ((d[i+1].toLong() and 0xFF) shl 16) or
        ((d[i+2].toLong() and 0xFF) shl 8) or (d[i+3].toLong() and 0xFF)
}
