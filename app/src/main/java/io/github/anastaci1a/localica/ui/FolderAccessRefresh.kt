package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.library.FolderSelection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** A permission observation belongs to one requested folder list and UI lifetime, never a long-lived grant cache. */
internal fun refreshFolderAccess(requests: LatestUiRequest, scope: CoroutineScope, dispatcher: CoroutineDispatcher,
    folders: List<FolderSelection>, currentFolders: () -> List<FolderSelection>,
    read: suspend (List<FolderSelection>) -> Set<String>, publish: (Set<String>) -> Unit): Job =
    requests.submit(scope, dispatcher, { currentFolders() === folders }, { read(folders) }, publish)
