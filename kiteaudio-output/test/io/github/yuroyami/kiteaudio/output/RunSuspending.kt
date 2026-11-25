package io.github.yuroyami.kiteaudio.output

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Runs [block] and returns its result. The tests have no coroutine library, so [block] must
 * finish without suspending, or resume before this returns on the same thread.
 */
internal fun <T> runSuspending(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
    return checkNotNull(outcome) { "the block suspended" }.getOrThrow()
}
