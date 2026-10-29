package ch.nokillswit

import java.lang.ref.WeakReference

/**
 * Whether [ref]'s referent was garbage-collected within [timeoutMillis], forcing collections while it waits (`config/detekt/detekt.yml`
 * excludes this file from `ExplicitGarbageCollectionCall`): the tests that prove a reference is dropped
 * (`PooledConnectionPinTest`, `DetachingConnectionFactoryTest`) cannot wait for the collector to run on its own.
 */
internal fun awaitCollected(ref: WeakReference<*>, timeoutMillis: Long = 10_000): Boolean {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
    while (ref.get() != null && System.nanoTime() < deadline) {
        System.gc()
        Thread.sleep(50)
    }
    return ref.get() == null
}
