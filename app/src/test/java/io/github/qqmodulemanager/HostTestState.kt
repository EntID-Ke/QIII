package io.github.qqmodulemanager

import kotlinx.coroutines.*

/** Robolectric reuses module statics while replacing its Application between tests. */
internal fun resetHostTestState() {
    val field=HostFeatures::class.java.getDeclaredField("instance").apply{isAccessible=true}
    val host=field.get(null)
    if(host!=null) {
        val scope=HostFeatures::class.java.getDeclaredField("scope").apply{isAccessible=true}.get(host) as CoroutineScope
        runBlocking{withTimeout(5000){scope.coroutineContext.job.cancelAndJoin()}}
        (host as HostFeatures).endpoint.close()
        (HostFeatures::class.java.getDeclaredField("runtime").apply{isAccessible=true}.get(host) as FeatureRuntime).close()
        field.set(null,null)
    }
    DiagnosticLog::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
}
