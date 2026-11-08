package io.github.anastaci1a.localica

import io.github.anastaci1a.localica.ui.engine.PopupWindowMotionTest
import org.junit.Test

/** Run the same numerical host contracts on ART as on the JVM, alongside real window witnesses. */
class PopupWindowHostContractTest {
    @Test fun ordinaryWindowWaitsForNativeReceipt() =
        PopupWindowMotionTest().ordinaryWindowWaitsAtHomeForItsNativeReceiptAndReusesItsMeasuredEdges()
    @Test fun preparedOriginKeepsItsTwoReceiptGates() =
        PopupWindowMotionTest().preparedOriginAndPopupShareOnePhaseAcrossBothReceiptGates()
    @Test fun newHomeKeepsTheSamePhaseBoundViewAndGeneration() =
        PopupWindowMotionTest().freshWindowSeedsItsMeasuredHomeAfterThePreviousWindowRetired()
    @Test fun inFlightReplacementPreservesC0AndC1() =
        PopupWindowMotionTest().replacingAWindowDuringAFlightPreservesItsPresentedKinematics()
    @Test fun presentedHostCannotInitializeAgain() =
        PopupWindowMotionTest().aPresentedHostCannotReinitializeTheSourceRectangle()
}
