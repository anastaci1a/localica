package io.github.anastaci1a.localica.scanner

import org.junit.Assert.*
import org.junit.Test

class ScanRequestsTest {
    @Test fun duplicateDiscoveryCoalescesButFullScanSurvivesActiveDiscovery() {
        val requests = ScanRequests()
        requests.add(listOf("a", "a", "b"), ScanMode.NewFiles)
        assertEquals(ScanRequest("a", ScanMode.NewFiles), requests.take())
        requests.add(listOf("a", "b"), ScanMode.NewFiles)
        requests.add(listOf("a", "b"), ScanMode.Full)
        requests.add(listOf("a", "b"), ScanMode.NewFiles)
        assertEquals(3, requests.size)
        requests.complete()
        assertEquals(ScanRequest("b", ScanMode.Full), requests.take())
        requests.complete()
        assertEquals(ScanRequest("a", ScanMode.Full), requests.take())
        requests.complete()
        assertNull(requests.take())
    }

    @Test fun activeFullScanSubsumesDiscoveryAndCancellationClearsPendingRequests() {
        val requests = ScanRequests()
        requests.add(listOf("a"), ScanMode.Full)
        requests.take()
        requests.add(listOf("a"), ScanMode.NewFiles)
        requests.add(listOf("a"), ScanMode.Full)
        assertEquals(1, requests.size)
        requests.add(listOf("b"), ScanMode.Full)
        requests.clear()
        assertEquals(0, requests.size)
        assertNull(requests.take())
    }
}
