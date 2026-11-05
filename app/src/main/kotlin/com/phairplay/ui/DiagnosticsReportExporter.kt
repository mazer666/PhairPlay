package com.phairplay.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.phairplay.R
import com.phairplay.diagnostics.DiagnosticsLabels
import com.phairplay.diagnostics.DiagnosticsReport
import com.phairplay.diagnostics.DiagnosticsReportBuilder

/**
 * DiagnosticsReportExporter — copies the diagnostics report out of the app.
 *
 * WHY this class exists: the report the owner reads on the television and the report
 * the owner copies into a bug tracker are **not** the same text. The screen names the
 * television and its address, because that is the question the screen exists for
 * ("which address do I look for?"). The copied text leaves the app — into a bug
 * tracker, a chat message, a forum thread — and there it becomes public and
 * permanent, so every identifying value has to be gone by then.
 *
 * The exporter therefore builds the report with
 * [DiagnosticsReportBuilder.buildForExport], which carries no IP address, no MAC
 * address, no UUID, neither the name of the television nor the name of the sender.
 *
 * WHY it is a separate file: the fragment that shows the report is a UI class and
 * this is a protocol-independent action on a data object. Keeping them apart keeps
 * both readable and keeps the file sizes inside the limit of the project.
 *
 * Example:
 *   DiagnosticsReportExporter.copyToClipboard(context, report, labels)
 */
internal object DiagnosticsReportExporter {

    /**
     * Builds the text that leaves the app.
     *
     * @param report the values of the report as they are shown on the screen.
     * @param labels the localized labels of the report.
     * @return the report without any identifying value.
     */
    fun exportText(report: DiagnosticsReport, labels: DiagnosticsLabels): String =
        DiagnosticsReportBuilder.buildForExport(report, labels)

    /**
     * Copies the report into the clipboard of the device.
     *
     * @param context the context of the screen.
     * @param report the values of the report as they are shown on the screen.
     * @param labels the localized labels of the report.
     * @return true when the report was copied, false when the device has no clipboard.
     */
    fun copyToClipboard(
        context: Context,
        report: DiagnosticsReport,
        labels: DiagnosticsLabels
    ): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        val text = exportText(report, labels)
        clipboard.setPrimaryClip(
            ClipData.newPlainText(context.getString(R.string.diagnostics_title), text)
        )
        return true
    }
}
