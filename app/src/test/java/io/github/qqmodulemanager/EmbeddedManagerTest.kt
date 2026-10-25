package io.github.qqmodulemanager

import android.app.Activity
import android.content.ComponentName
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowDialog
import io.github.qqmodulemanager.bridge.BridgeClient

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmbeddedManagerTest {
    @Test fun settingsPageStaysInsideTheHostAndReleasesItsLifecycleOnClose() {
        val provider=Robolectric.buildContentProvider(PolicyProvider::class.java).create().get()
        ShadowContentResolver.registerProviderInternal(BridgeClient.AUTHORITY,provider)
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val host=controller.get()
        EmbeddedManager.open(host)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val dialog=ShadowDialog.getLatestDialog()
        assertTrue(dialog.isShowing);assertSame(host,dialog.ownerActivity)
        val lifecycle=(dialog as LifecycleOwner).lifecycle
        assertEquals(Lifecycle.State.RESUMED,lifecycle.currentState)
        assertNull(Shadows.shadowOf(host).nextStartedActivity)
        dialog.dismiss()
        assertEquals(Lifecycle.State.DESTROYED,lifecycle.currentState)
        controller.pause().stop().destroy()
    }
}
