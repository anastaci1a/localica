package io.github.anastaci1a.localica.toast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ToastAdmission { Admitted, Full, Failed }
data class CommittedToastReceipt(val sequence: Long, val content: ToastMessage, val admission: ToastAdmission,
    val accepted: ToastEnqueue.Accepted? = null, val problem: String? = null)
data class ToastOutcomeReport(val total: Long = 0, val declined: Long = 0, val failures: Long = 0,
    val reportingFailures: Long = 0, val last: CommittedToastReceipt? = null,
    val lastUndelivered: CommittedToastReceipt? = null)

/** A counted reporting ledger, not a second pending queue. Durable outcome truth remains with
 * the committed queue/list. Retain the latest exact undelivered destination and cumulative counts;
 * the Application reports every refused/failed admission through its non-UI diagnostic sink.
 * Neither queue pressure nor diagnostic failure can convert a committed write into a failed edit. */
class ToastOutcomeReporter(private val enqueue: (ToastMessage) -> ToastEnqueue,
    private val onUndelivered: (CommittedToastReceipt) -> Unit = {}) {
    private val mutable = MutableStateFlow(ToastOutcomeReport())
    val state = mutable.asStateFlow()

    @Synchronized fun report(content: ToastMessage): CommittedToastReceipt {
        val before = mutable.value
        val frozen = content.copy(artworkUris = content.artworkUris.take(3).toList())
        val receipt = try {
            when (val admission = enqueue(frozen)) {
                is ToastEnqueue.Accepted -> CommittedToastReceipt(before.total + 1, frozen, ToastAdmission.Admitted, admission)
                ToastEnqueue.Full -> CommittedToastReceipt(before.total + 1, frozen, ToastAdmission.Full)
            }
        } catch (failure: Exception) {
            CommittedToastReceipt(before.total + 1, frozen, ToastAdmission.Failed, problem = failure.javaClass.simpleName)
        }
        mutable.value = before.copy(total = receipt.sequence,
            declined = before.declined + if (receipt.admission == ToastAdmission.Full) 1 else 0,
            failures = before.failures + if (receipt.admission == ToastAdmission.Failed) 1 else 0,
            last = receipt, lastUndelivered = if (receipt.admission == ToastAdmission.Admitted) before.lastUndelivered else receipt)
        if (receipt.admission != ToastAdmission.Admitted) try { onUndelivered(receipt) }
        catch (_: Exception) { mutable.value = mutable.value.copy(reportingFailures = mutable.value.reportingFailures + 1) }
        return receipt
    }
}
