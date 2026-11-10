package io.github.anastaci1a.localica

import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import io.github.anastaci1a.localica.preferences.PreparedPreferences
import kotlinx.coroutines.*

/** Keep each existing owned test namespace and custom editor, with the same off-Main preparation contract. */
internal fun preparedTestOwner(context: Context,
    names: List<String> = PreparedPreferences.defaultNames): PreparedPreferences {
    check(Looper.myLooper() != Looper.getMainLooper()) { "Prepare fixture preferences before entering Main" }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    return try {
        PreparedPreferences(context, scope, names).also { runBlocking { it.awaitReady() } }
    } finally { scope.cancel() }
}

internal fun preparedTestContext(context: Context,
    names: List<String> = PreparedPreferences.defaultNames): Context {
    val owner = preparedTestOwner(context, names)
    val application = context.applicationContext
    return object : ContextWrapper(context), PreparedPreferences.Owner {
        override val preparedPreferences = owner
        override fun getApplicationContext(): Context = if (application === context) this else application
    }
}
