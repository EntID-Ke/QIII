package io.github.qqmodulemanager

import android.app.Activity
import android.os.Bundle

/** Explicit foreground launch can start the configuration process even after its task was closed. */
class BridgeBootstrapActivity:Activity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        contentResolver.notifyChange(PolicyStore.URI,null)
        finish()
    }
}
