package com.partflasher.app.core.model

enum class ValidationSeverity { INFO, WARNING, ERROR }

data class ValidationIssue(
    val severity: ValidationSeverity,
    val code: String,
    val message: String,
)

/**
 * Result of checking an image against the selected partition and device.
 * [canFlash] must be false whenever an ERROR issue exists.
 */
data class ImageValidation(
    val issues: List<ValidationIssue> = emptyList(),
    val canFlash: Boolean = false,
    val requiresExtraConfirm: Boolean = false,
    val summary: String = "",
) {
    val errors: List<ValidationIssue> get() = issues.filter { it.severity == ValidationSeverity.ERROR }
    val warnings: List<ValidationIssue> get() = issues.filter { it.severity == ValidationSeverity.WARNING }
    val infos: List<ValidationIssue> get() = issues.filter { it.severity == ValidationSeverity.INFO }
}
