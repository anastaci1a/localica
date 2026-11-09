package io.github.anastaci1a.localica.toast

import org.junit.Assert.*
import org.junit.Test

class ToastOutcomeReporterTest {
    @Test fun fullAdmissionsRetainTheLatestExactDestinationAndCountEveryDecline() {
        val seen = mutableListOf<CommittedToastReceipt>()
        val reporter = ToastOutcomeReporter({ ToastEnqueue.Full }, seen::add)
        val first = ToastMessage("Saved first", tap = ToastCommand.OpenQueue("queue", "first-occurrence", false))
        val second = ToastMessage("Saved second", tap = ToastCommand.OpenQueue("queue", "second-occurrence", false))
        reporter.report(first); reporter.report(second)
        assertEquals(listOf(first, second), seen.map { it.content })
        assertEquals(2L, reporter.state.value.total)
        assertEquals(2L, reporter.state.value.declined)
        assertEquals(second, reporter.state.value.lastUndelivered!!.content)
    }

    @Test fun enqueueAndDiagnosticExceptionsBecomeReceiptsWithoutEscapingTheCommittedEdit() {
        val reporter = ToastOutcomeReporter({ throw IllegalStateException("Admission probe") },
            { throw IllegalArgumentException("Diagnostic probe") })
        val content = ToastMessage("Saved", tap = ToastCommand.OpenList("list", "exact-occurrence"))
        val receipt = reporter.report(content)
        assertEquals(ToastAdmission.Failed, receipt.admission)
        assertEquals(content, receipt.content)
        assertEquals(1L, reporter.state.value.failures)
        assertEquals(1L, reporter.state.value.reportingFailures)
        assertEquals(receipt, reporter.state.value.lastUndelivered)
    }
}
