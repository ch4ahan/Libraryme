package com.personal.novellibrary.diagnostics

import kotlin.math.roundToInt

/**
 * Coarse-grained PRD coverage tracker used by the diagnostics/roadmap screens.
 * Percentages are intentionally conservative: a feature is not counted as complete
 * until it is wired through UI, persistence, and tests where applicable.
 */
data class FeatureCoverageItem(
    val area: String,
    val implementedPercent: Int,
    val notes: String,
)

object PrdCoverage {
    /** Release readiness is deliberately lower than feature-skeleton coverage. */
    const val commercialReadinessPercent: Int = 65
    val items: List<FeatureCoverageItem> = listOf(
        FeatureCoverageItem("프로젝트/빌드/CI", 99, "CI builds artifacts/checksums/reports and publishes tagged debug prereleases for temporary installs; signed production release remains pending."),
        FeatureCoverageItem("Room DB/마이그레이션", 92, "Core tables and v1→v5 migrations exist, including deterministic platform-listing and collection deduplication plus title-key backfill."),
        FeatureCoverageItem("TXT 폴더 선택/스캔", 90, "SAF scan discovery is separated from one transactional DB reconciliation; 2k/10k emulator baselines are scheduled, while physical-device validation remains pending."),
        FeatureCoverageItem("로컬 라이브러리 UI", 94, "Core screens and result inspection are connected; scanning, backup/restore/export, and platform jobs expose progress bars."),
        FeatureCoverageItem("관리 기능", 93, "User data, trash, reading records, collections, tags, and locked manual metadata editing are connected to Room."),
        FeatureCoverageItem("스마트 컬렉션/추천", 75, "Rule matcher and recommendation engine exist; persisted smart collection UI remains pending."),
        FeatureCoverageItem("플랫폼 검색", 90, "Five public-page adapters never generate fake data (가짜 데이터 없음); cache/exclusion controls, structured synopsis extraction, result inspection and source-page opening exist; device validation remains pending."),
        FeatureCoverageItem("백업/복원/내보내기", 90, "ZIP/CSV/JSON export and bounded, validated merge restore are connected to UI."),
        FeatureCoverageItem("진단/설정", 90, "Persisted scan/search settings and diagnostic display/copy/permission actions are connected to UI."),
        FeatureCoverageItem("테스트", 87, "JVM, migration, UI, and 2k/10k repeated-scan tests gate CI; the five-platform live smoke test additionally runs on the scheduled emulator job."),
    )

    val overallPercent: Int = items.map { it.implementedPercent }.average().roundToInt()

    fun markdownReport(): String = buildString {
        appendLine("# PRD Coverage")
        appendLine()
        appendLine("Overall estimated implementation: $overallPercent%")
        appendLine("Commercial release readiness: $commercialReadinessPercent%")
        appendLine()
        appendLine("| Area | Estimate | Notes |")
        appendLine("| --- | ---: | --- |")
        items.forEach { item ->
            appendLine("| ${item.area} | ${item.implementedPercent}% | ${item.notes} |")
        }
    }
}
