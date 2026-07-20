package com.personal.novellibrary

import com.personal.novellibrary.diagnostics.PrdCoverage
import org.junit.Assert.assertTrue
import org.junit.Test

class PrdCoverageTest {
    @Test
    fun coverageReportIncludesOverallEstimateAndPlatformCaveat() {
        val report = PrdCoverage.markdownReport()
        assertTrue(PrdCoverage.overallPercent in 1..99)
        assertTrue(report.contains("Overall estimated implementation"))
        assertTrue(PrdCoverage.commercialReadinessPercent in 1 until PrdCoverage.overallPercent)
        assertTrue(report.contains("Commercial release readiness"))
        assertTrue(report.contains("no fake data") || report.contains("가짜"))
    }
}
