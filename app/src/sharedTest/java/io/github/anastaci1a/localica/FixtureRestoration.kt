package io.github.anastaci1a.localica

/** Attempt every restoration and keep the setup/test failure as the primary diagnostic. */
internal class FixtureRestoration(private var failure: Throwable?) {
    fun attempt(label: String, action: () -> Unit) {
        try { action() }
        catch (secondary: Throwable) {
            val diagnostic = AssertionError("Fixture restoration failed: $label", secondary)
            val primary = failure
            if (primary == null) failure = diagnostic else primary.addSuppressed(diagnostic)
        }
    }
    fun finish() { failure?.let { throw it } }
}
