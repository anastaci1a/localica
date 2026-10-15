package io.github.anastaci1a.localica.playback

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import io.github.anastaci1a.localica.library.documentIdentity

/** Wrap the whole DefaultDataSource, including content providers, preload and retry opens. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class TrackedDataSource(private val delegate: DataSource, private val readers: NativeReaders) : DataSource by delegate {
    // Media3 uses each DataSource on its loading thread; it does not call close concurrently with
    // open/read. No registry lock is held while entering provider code, including failed-open cleanup.
    private var reader: NativeReaders.Reader? = null

    override fun open(dataSpec: DataSpec): Long {
        check(reader == null) { "A playback source must be closed before reopening" }
        reader = readers.opening(documentIdentity(dataSpec.uri.toString()))
        try {
            return delegate.open(dataSpec)
        } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    override fun close() {
        val closing = reader ?: return
        reader = null
        try {
            delegate.close()
            closing.closed()
        } catch (failure: Throwable) {
            closing.failedClose(failure)
            throw failure
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun NativeReaders.track(factory: DataSource.Factory): DataSource.Factory =
    DataSource.Factory { TrackedDataSource(factory.createDataSource(), this) }
