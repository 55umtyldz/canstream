package com.cloudstream.tr.hub

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Independent source discovery: a slow/failed origin must not gate ready sources.
 * Emission is non-suspending; HubLinkCollector serializes deduplication and callbacks.
 */
internal suspend fun <T> resolveHubSourcesProgressively(
    resolvers: List<suspend () -> List<T>>,
    onBatch: (List<T>) -> Unit,
    onFailure: (Exception) -> Unit = {}
) = coroutineScope {
    resolvers.map { resolver ->
        launch {
            val batch = try {
                resolver()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailure(e)
                emptyList()
            }
            if (batch.isNotEmpty()) onBatch(batch)
        }
    }.joinAll()
}
