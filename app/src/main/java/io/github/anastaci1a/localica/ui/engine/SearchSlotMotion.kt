package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.*
import kotlin.math.abs

/** Read-only projection of the popup's bound bottom edge onto the list vacancy; no clock or trajectory.
 * Only an in-window presentation vacates the slot: Closed, Admitting and Restoring all rest at expansion 0. */
internal class SearchSlotMotion(private val owner: PopupTakeoverOwner) {
    private var edge by mutableStateOf<MotionValue?>(null)
    var travel by mutableStateOf<SearchSlotTravel?>(null); private set
    val fraction: Float get()=if(owner.takeover!=PopupTakeover.InWindow) 0f else edge?.let { travel?.fraction(it.value()) }?:0f
    fun bindBottom(value: MotionValue,destination: Float,target: Float) {
        if(edge===value && travel?.toBottom==destination && travel?.toFraction==target) return
        travel=SearchSlotTravel(fraction,value.value(),destination,target); edge=value
    }
}

/** A basis change retains the current vacancy before borrowing the next bottom travel. Arrival is exactly [toFraction]. */
internal data class SearchSlotTravel(val fromFraction: Float,val fromBottom: Float,val toBottom: Float,val toFraction: Float=1f) {
    fun fraction(bottom: Float): Float {
        val distance=toBottom-fromBottom
        val traveled=if(abs(distance)<=.0001f) 1f else ((bottom-fromBottom)/distance).coerceIn(0f,1f)
        return (toFraction-(toFraction-fromFraction)*(1f-traveled)).coerceIn(0f,1f)
    }
}
