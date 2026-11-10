package io.github.anastaci1a.localica.ui.layout.menu

import org.junit.Assert.*
import org.junit.Test

/**
 * #757 M0: events reach the current callback of the current declaration (DESIGN 3.2 case table), through the real
 * publication path: [MenuHostCore.prepare] during composition, [MenuHostCore.publish] after a successful apply.
 */
class MenuDispatcherTest {
    private val log = ArrayList<String>()
    private val core = MenuHostCore()
    private val drops = ArrayList<DispatchResult>().also { list -> core.dispatcher.onDrop = { result, _, _ -> list += result } }

    private fun declare(name: String = "first", toggle: Boolean = true, toggleEnabled: Boolean = true, options: List<String> = listOf("a", "b"),
        records: RecordSnapshot = snapshot(3), editor: Boolean = true, broken: Boolean = false): PageDraft = page(TestPage.Main) {
        section("s") {
            if (toggle) toggle(local("toggle", if (broken) "" else "Toggle"), false, { log += "$name:toggle=$it" }, enabled = toggleEnabled)
            choice(local("choice", "Choice"), options.map { Option(it, it.uppercase()) }, options.first(), { log += "$name:choice=$it" })
            records(local("records"), records, "Empty", { log += "$name:record=$it" })
            if (editor) value(local("name", "Name"), "Current", opens = textEntry("Rename", "Name", "Current", { log += "$name:rename=$it" },
                onDismiss = { log += "$name:dismiss" }))
        }
    }
    private fun show(draft: PageDraft): PendingPublication = core.prepare(draft).also(core::publish)
    private fun PendingPublication.token(path: String) = token(QualifiedKey(path))!!
    private val toggle = "Main/s/local:toggle"
    private val choice = "Main/s/local:choice"
    private val records = "Main/s/local:records"
    private val editor = "Main/s/local:name"

    @Test fun equalContentWithANewCallbackKeepsTheIncarnationAndCallsTheNewCallback() {
        val first = show(declare("first"))
        val second = show(declare("second"))
        assertSame("equal content is the same instance, so the row is skipped", first.accepted!!.content, second.accepted!!.content)
        assertEquals(first.token(toggle), second.token(toggle))
        assertEquals(1, core.cache.admissions)
        assertEquals(DispatchResult.Delivered, core.dispatcher.toggle(first.token(toggle), true))
        assertEquals(listOf("second:toggle=true"), log)
    }

    @Test fun aRemovedRowIsRetiredAndItsLateEventIsDropped() {
        val token = show(declare()).token(toggle)
        val next = show(declare("second", toggle = false))
        assertNull(next.token(QualifiedKey(toggle)))
        assertEquals(DispatchResult.DroppedRetired, core.dispatcher.toggle(token, true))
        assertEquals(emptyList<String>(), log)
        assertEquals(listOf(DispatchResult.DroppedRetired), drops)
    }

    @Test fun aRowAddedAgainUnderTheSameKeyGetsANewIncarnationAndAQueuedEventIsNeverRedirected() {
        val old = show(declare("first")).token(toggle)
        show(declare("second", toggle = false))
        val added = show(declare("third")).token(toggle)
        assertEquals(old.key, added.key)
        assertNotEquals(old, added)
        assertEquals(DispatchResult.DroppedRetired, core.dispatcher.toggle(old, true))
        assertEquals(emptyList<String>(), log)
        assertEquals(DispatchResult.Delivered, core.dispatcher.toggle(added, true))
        assertEquals(listOf("third:toggle=true"), log)
    }

    @Test fun anOptionOrEntryThatIsNoLongerInTheContentIsDropped() {
        val first = show(declare("first"))
        assertEquals(DispatchResult.Delivered, core.dispatcher.select(first.token(choice), "b"))
        assertEquals(DispatchResult.Delivered, core.dispatcher.select(first.token(records), "id-2", ActionSlot.Click))
        show(declare("second", options = listOf("a", "c"), records = snapshot(2, version = 2)))
        assertEquals(DispatchResult.DroppedStalePayload, core.dispatcher.select(first.token(choice), "b"))
        assertEquals(DispatchResult.DroppedStalePayload, core.dispatcher.select(first.token(records), "id-2", ActionSlot.Click))
        assertEquals(DispatchResult.Delivered, core.dispatcher.select(first.token(choice), "c"))
        assertEquals(listOf("first:choice=b", "first:record=id-2", "second:choice=c"), log)
    }

    @Test fun aRowThatIsNowDisabledDropsTheEventEvenIfAnOldFrameShowedItEnabled() {
        val token = show(declare("first")).token(toggle)
        show(declare("second", toggleEnabled = false))
        assertEquals(DispatchResult.DroppedDisabled, core.dispatcher.toggle(token, true))
        assertEquals(emptyList<String>(), log)
    }

    @Test fun aSlotWithoutABindingOrWithAnotherPayloadTypeDeliversNothing() {
        val first = show(declare())
        assertEquals(DispatchResult.DroppedUnbound, core.dispatcher.click(first.token(toggle)))
        assertEquals(DispatchResult.DroppedUnbound, core.dispatcher.text(first.token(toggle), "text"))
        assertEquals(emptyList<String>(), log)
    }

    @Test fun preparingChangesNothingAndANewRowHasItsTokenBeforePublication() {
        val first = show(declare("first", toggle = false))
        val pending = core.prepare(declare("second"))
        val proposed = pending.token(toggle)
        assertFalse("not live before the apply", core.dispatcher.isLive(proposed))
        assertEquals(DispatchResult.DroppedRetired, core.dispatcher.toggle(proposed, true))
        assertEquals("the live page still answers with its own callbacks", DispatchResult.Delivered, core.dispatcher.select(first.token(choice), "b"))
        assertEquals(listOf("first:choice=b"), log)
        core.publish(pending)
        assertTrue(core.dispatcher.isLive(proposed))
        assertEquals(DispatchResult.Delivered, core.dispatcher.toggle(proposed, true))
        assertEquals(listOf("first:choice=b", "second:toggle=true"), log)
    }

    @Test fun anAbandonedCompositionNeverRevokesOrReplacesTheLivePage() {
        val live = show(declare("live"))
        val abandonedRejection = core.prepare(declare("abandoned", broken = true))
        val abandonedRemoval = core.prepare(declare("abandoned", toggle = false))
        assertTrue(abandonedRejection.admission is Admission.Rejected)
        assertEquals(DispatchResult.Delivered, core.dispatcher.toggle(live.token(toggle), true))
        assertEquals(listOf("live:toggle=true"), log)
        // A later composition publishes; the abandoned results are now stale and are ignored if they ever arrive.
        val next = show(declare("next"))
        assertFalse(core.dispatcher.publish(abandonedRejection))
        assertFalse(core.dispatcher.publish(abandonedRemoval))
        assertEquals(DispatchResult.Delivered, core.dispatcher.toggle(next.token(toggle), false))
        assertEquals(listOf("live:toggle=true", "next:toggle=false"), log)
    }

    @Test fun anInitialRejectionPublishesNoAuthority() {
        val pending = show(declare(broken = true))
        assertNull(pending.accepted)
        assertNull(pending.token(QualifiedKey(choice)))
        assertEquals(listOf(AdmissionRule.BlankText), (pending.admission as Admission.Rejected).violations.map { it.rule })
    }

    @Test fun rejectionRevokesEveryIncarnationInTheSamePublishAndQuarantinesAnOpenEditor() {
        val first = show(declare("first"))
        val queued = first.token(toggle)
        assertTrue(core.popups.open(first.token(editor), first.accepted!!.content.row<ValueContent>().opens!!))
        val entry = core.popups.open.single()
        entry.draft = "typed but not saved"

        show(declare("second", broken = true))
        assertEquals("a queued event from the previous page is dropped", DispatchResult.DroppedRevoked, core.dispatcher.toggle(queued, true))
        assertEquals(DispatchResult.DroppedRevoked, core.dispatcher.select(first.token(choice), "b"))
        assertSame("the popup stays with its owner", entry, core.popups.open.single())
        assertTrue(entry.quarantined)
        assertFalse("rejection disposes nothing", entry.departing)
        assertEquals("typed but not saved", entry.draft)
        assertEquals("no old Confirm is dispatched", DispatchResult.DroppedRevoked, core.popups.text(entry))
        assertEquals(DispatchResult.DroppedRevoked, core.popups.click(entry, ActionSlot.Dismiss))
        assertFalse("nothing opens while rejected", core.popups.open(first.token(editor), entry.content))
        assertEquals(emptyList<String>(), log)
    }

    @Test fun recoveryPublishesFreshIncarnationsAndRebindsAQuarantinedPopupWithItsDraft() {
        val first = show(declare("first"))
        core.popups.open(first.token(editor), first.accepted!!.content.row<ValueContent>().opens!!)
        val entry = core.popups.open.single()
        entry.draft = "kept"
        show(declare("second", broken = true))
        val recovered = show(declare("third"))
        assertNotEquals(first.token(toggle), recovered.token(toggle))
        assertEquals(DispatchResult.DroppedRetired, core.dispatcher.toggle(first.token(toggle), true))
        assertEquals(DispatchResult.Delivered, core.dispatcher.toggle(recovered.token(toggle), true))
        assertFalse(entry.quarantined)
        assertEquals("kept", entry.draft)
        assertEquals(DispatchResult.Delivered, core.popups.text(entry))
        assertEquals(listOf("third:toggle=true", "third:rename=kept"), log)
    }

    @Test fun aQuarantinedPopupWhoseKeyIsAbsentOnRecoveryLeavesThroughDeparture() {
        val first = show(declare("first"))
        core.popups.open(first.token(editor), first.accepted!!.content.row<ValueContent>().opens!!)
        val entry = core.popups.open.single()
        show(declare("second", broken = true))
        show(declare("third", editor = false))
        assertTrue(entry.departing)
        assertEquals(DispatchResult.DroppedRevoked, core.popups.text(entry))
        core.popups.retired(entry)
        assertTrue(core.popups.open.isEmpty())
        assertEquals(emptyList<String>(), log)
    }

    @Test fun popupActionsAreRefreshedWithEveryRebuildWhileThePopupIsOpen() {
        val first = show(declare("first"))
        core.popups.open(first.token(editor), first.accepted!!.content.row<ValueContent>().opens!!)
        val entry = core.popups.open.single()
        show(declare("second"))
        entry.draft = "New name"
        assertEquals(DispatchResult.Delivered, core.popups.text(entry))
        assertEquals(DispatchResult.Delivered, core.popups.click(entry, ActionSlot.Dismiss))
        assertEquals(listOf("second:rename=New name", "second:dismiss"), log)
    }

    @Test fun anOwnerRemovedWhileItsPopupIsOpenMakesThePopupDepartAndDropsItsEvents() {
        val first = show(declare("first"))
        core.popups.open(first.token(editor), first.accepted!!.content.row<ValueContent>().opens!!)
        val entry = core.popups.open.single()
        show(declare("second", editor = false))
        assertTrue(entry.departing)
        assertFalse(entry.quarantined)
        assertEquals(DispatchResult.DroppedRevoked, core.popups.text(entry))
        assertEquals(emptyList<String>(), log)
    }

    @Test fun aDisabledOrRetiredOwnerCannotOpenAPopup() {
        val first = show(declare("first"))
        val popup = first.accepted!!.content.row<ValueContent>().opens!!
        show(declare("second", editor = false))
        assertFalse(core.popups.open(first.token(editor), popup))
        assertTrue(core.popups.open.isEmpty())
    }
}
