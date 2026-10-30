package com.byd.tripstats.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The performance overlay's readings: which thermal zones it takes for the GPU and the CPU, how
 * it reads them, what it shows when a value is missing, and that a file it can't read is dropped.
 */
class PerformanceOverlayTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** The dev Seal's zones (SM6125), in part: sensors, their "-step"/"-lowf" mirrors, and others. */
    private val sealZones = listOf(
        "pm6125-tz", "cpuss-0-usr", "cpuss-1-usr", "cpu-1-0-usr", "cpu-1-3-usr", "gpu-usr",
        "lmh-dcvs-00", "gpu-step", "hepta-cpu-max-step", "cpuss-0-step", "cpu-1-0-lowf", "gpu-lowf",
    ).map { ThermalZone(it, File(it)) }

    @Test
    fun `the dev Seal's GPU sensor is gpu-usr, not its step or lowf mirror`() {
        assertEquals("gpu-usr", pickGpuZone(sealZones)?.type)
    }

    @Test
    fun `the dev Seal's CPU sensors are the usr zones only`() {
        assertEquals(
            listOf("cpuss-0-usr", "cpuss-1-usr", "cpu-1-0-usr", "cpu-1-3-usr"),
            pickCpuZones(sealZones).map { it.type },
        )
    }

    @Test
    fun `a chip without usr zones falls back to its plain sensors`() {
        val zones = listOf("gpuss-0", "gpuss-0-step", "cpu0", "cpu1", "battery").map { ThermalZone(it, File(it)) }
        assertEquals("gpuss-0", pickGpuZone(zones)?.type)
        assertEquals(listOf("cpu0", "cpu1"), pickCpuZones(zones).map { it.type })
    }

    @Test
    fun `a chip with no GPU or CPU zones gives none`() {
        val zones = listOf("battery", "skin-therm").map { ThermalZone(it, File(it)) }
        assertNull(pickGpuZone(zones))
        assertEquals(emptyList<ThermalZone>(), pickCpuZones(zones))
    }

    @Test
    fun `temperatures read in millidegrees or whole degrees`() {
        assertEquals(30.6, parseTemperatureC("30600\n")!!, 1e-9)
        assertEquals(41.0, parseTemperatureC("41")!!, 1e-9)
        assertEquals(-5.5, parseTemperatureC("-5500")!!, 1e-9)
    }

    @Test
    fun `an unreadable or impossible temperature is no temperature`() {
        assertNull(parseTemperatureC(""))
        assertNull(parseTemperatureC("Invalid argument"))
        assertNull(parseTemperatureC("999999"))
    }

    @Test
    fun `a full sample reads like the drive logger`() {
        val (frames, chip) = PerformanceSample(
            appCpuPercent = 24.4, fps = 11.2, uiMs = 3.21, renderMs = 9.08,
            gpuMhz = 320, gpuTempC = 41.2, cpuMhz = listOf(1401, 614), cpuTempC = 43.4,
        ).lines()
        assertEquals("App 24% · 11.2 fps · UI 3.2 ms · render 9.1 ms", frames)
        assertEquals("GPU 320 MHz 41°C · CPU 1401/614 MHz 43°C", chip)
    }

    @Test
    fun `what can't be read shows as a dash`() {
        val (frames, chip) = PerformanceSample(
            appCpuPercent = 3.0, fps = 0.0, uiMs = null, renderMs = null,
            gpuMhz = null, gpuTempC = null, cpuMhz = emptyList(), cpuTempC = null,
        ).lines()
        assertEquals("App 3% · 0.0 fps", frames)
        assertEquals("GPU — · CPU —", chip)
    }

    @Test
    fun `clocks convert from Hz and kHz, biggest cluster first as given`() {
        val gpu = tmp.newFile("gpuclk").apply { writeText("320000000\n") }
        val big = tmp.newFile("policy4").apply { writeText("1401600\n") }
        val little = tmp.newFile("policy0").apply { writeText("614400\n") }
        val sensors = HeadUnitSensors(gpu, listOf(big, little), null, emptyList())
        assertEquals(320, sensors.gpuMhz())
        assertEquals(listOf(1401, 614), sensors.cpuMhz())
        assertNull(sensors.gpuTempC())
        assertNull(sensors.cpuTempC())
    }

    @Test
    fun `a file that fails once is not read again`() {
        val gpu = tmp.newFile("gpuclk").apply { writeText("320000000") }
        val zone = tmp.newFile("gpu-temp").apply { writeText("41000") }
        val hot = tmp.newFile("cpu-hot").apply { writeText("45000") }
        val cool = tmp.newFile("cpu-cool").apply { writeText("39000") }
        val sensors = HeadUnitSensors(gpu, emptyList(), zone, listOf(hot, cool))
        assertEquals(41.0, sensors.gpuTempC()!!, 1e-9)
        assertEquals(45.0, sensors.cpuTempC()!!, 1e-9)

        gpu.delete(); zone.delete(); hot.delete()
        assertNull(sensors.gpuMhz())
        assertNull(sensors.gpuTempC())
        assertEquals(39.0, sensors.cpuTempC()!!, 1e-9)

        gpu.writeText("320000000"); zone.writeText("41000"); hot.writeText("45000")
        assertNull(sensors.gpuMhz())
        assertNull(sensors.gpuTempC())
        assertEquals(39.0, sensors.cpuTempC()!!, 1e-9)
    }
}
