package com.vector.console.benchmark

import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Rule
import org.junit.Test

/** Startup baseline. Scroll-frame baseline needs a physical device and a static feed fixture. */
class HomeScrollBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = rule.measureRepeated(
        packageName = "com.vector.console",
        metrics = listOf(StartupTimingMetric()),
        iterations = 5
    ) {}
}
