package io.github.anastaci1a.localica

/** Test-only observer. Never requests focus, changes insets, hides IME or ends editing. */
internal class ImeExposureShadow(private val record: (String) -> Unit) {
    enum class Receipt { NONE, ACTIVE, SUSPENDED }
    private data class Pending(val owner: Long?, val epoch: Long, val before: Boolean?,
        val historical: Receipt, val prepareAt: Long, var started: Boolean = false,
        var target: Boolean? = null)
    var receipt = Receipt.NONE; private set
    var confirms = 0; private set
    var suspendedHideCompletions = 0; private set
    var rejectedCompletions = 0; private set
    private var owner: Long? = null
    private var epoch = 0L
    private var focused = false
    private var controlled = false
    private var visible: Boolean? = null
    private val pending = mutableMapOf<Int, Pending>()
    private var lastEditingEnd: Pair<Long?, Long>? = null
    private fun emit(event: String) = record("shadow $event owner=$owner epoch=$epoch receipt=$receipt pending=${pending.size}")

    fun availability(focus: Boolean, control: Boolean? = null) {
        val nextControl = control ?: controlled
        if ((focused && !focus) || (controlled && !nextControl)) {
            epoch++
            // Keep records solely to report rejected stale callbacks; epoch prevents reuse.
            if (receipt != Receipt.NONE) receipt = Receipt.SUSPENDED
        }
        focused = focus; controlled = nextControl
        emit("availability focus=$focus controlled=$controlled")
    }
    fun insets(nowVisible: Boolean?) { visible = nowVisible }
    fun input(event: String, now: Long) {
        val generation = Regex("\\bgeneration=(\\d+)").find(event)?.groupValues?.get(1)?.toLong()
        when {
            "inputSession phase=start" in event && generation != null -> {
                owner = generation; epoch++
                if (pending.isNotEmpty()) receipt = Receipt.NONE
                if (receipt == Receipt.ACTIVE && (!focused || !controlled || visible != true)) receipt = Receipt.SUSPENDED
                emit("input-start generation=$generation")
            }
            "inputSession phase=retire" in event && generation == owner -> {
                owner = null; epoch++; emit("input-retire generation=$generation")
            }
            "endEditing reason=" in event -> {
                lastEditingEnd = owner to now
                emit("production-end time=$now reason=${event.substringAfter("reason=").substringBefore(' ')}")
                // Do not fabricate a new show/hide certificate from the product decision.
            }
        }
    }
    fun prepare(id: Int, before: Boolean?, now: Long) {
        pending[id] = Pending(owner, epoch, before, receipt, now)
        emit("prepare id=$id before=$before")
    }
    fun start(id: Int, target: Boolean?) {
        pending[id]?.let { it.started = true; it.target = target }
        // Only an actually observed new hidden->visible transaction revokes a prior receipt.
        if (pending[id]?.before == false && target == true) receipt = Receipt.NONE
        emit("start id=$id target=$target")
    }
    fun end(id: Int, fraction: Float, nowVisible: Boolean?, now: Long) {
        val p = pending.remove(id) ?: return
        val live = p.owner != null && owner == p.owner && epoch == p.epoch && focused && controlled
        val show = p.before == false && p.target == true
        val hide = p.before == true && p.target == false
        val complete = p.started && fraction == 1f
        if (complete && show && nowVisible == true && live && pending.isEmpty()) {
            receipt = Receipt.ACTIVE; confirms++
            emit("confirmed id=$id")
        } else {
            rejectedCompletions++
            emit("rejected id=$id live=$live started=${p.started} fraction=$fraction before=${p.before} target=${p.target} visible=$nowVisible")
        }
        if (complete && hide && nowVisible == false) {
            if (p.historical == Receipt.SUSPENDED) {
                suspendedHideCompletions++
                val delay = lastEditingEnd?.takeIf { it.first == p.owner }?.let { now - it.second }
                emit("suspended-hide-evidence id=$id live=$live sincePrepare=${now-p.prepareAt} afterProductionEnd=$delay")
            }
            if (live || owner == null) receipt = Receipt.NONE
        }
        visible = nowVisible
    }
    fun snapshot(label: String) = emit("snapshot=$label confirms=$confirms suspendedHideCompletions=$suspendedHideCompletions rejected=$rejectedCompletions")
}
