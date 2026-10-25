package io.github.qqmodulemanager

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.view.Window
import androidx.activity.ComponentDialog
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy

/** A window owned by the current QQ Activity; no activity proxy or external app launch. */
internal class EmbeddedManager private constructor(private val host:Activity,context:Context):ComponentDialog(context) {
    private val callbacks=object:Application.ActivityLifecycleCallbacks {
        override fun onActivityDestroyed(activity:Activity){if(activity===host)dismiss()}
        override fun onActivityPaused(activity:Activity){}
        override fun onActivityResumed(activity:Activity){}
        override fun onActivityCreated(activity:Activity,state:Bundle?){}
        override fun onActivityStarted(activity:Activity){}
        override fun onActivityStopped(activity:Activity){}
        override fun onActivitySaveInstanceState(activity:Activity,state:Bundle){}
    }
    override fun onCreate(state:Bundle?) {
        setOwnerActivity(host)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(state)
        val ui=ManagerUi(context){dismiss()}
        setContentView(ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent{ui.Content()}
        })
        window!!.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window!!.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)
        host.application.registerActivityLifecycleCallbacks(callbacks)
    }
    override fun dismiss() {
        super.dismiss()
        host.application.unregisterActivityLifecycleCallbacks(callbacks)
    }
    companion object {
        @JvmStatic fun open(context:Context) {
            val activity=generateSequence(context){(it as? ContextWrapper)?.baseContext}.filterIsInstance<Activity>().firstOrNull()
                ?:throw IllegalArgumentException("QQ settings must provide an Activity context")
            EmbeddedManager(activity,themedContext(activity)).show()
        }
        fun themedContext(activity:Activity):Context {
            val moduleResources=activity.createPackageContext("io.github.qqmodulemanager",0).resources
            val moduleTheme=moduleResources.newTheme().apply{applyStyle(android.R.style.Theme_Material_Light_NoActionBar,true)}
            return object:ContextThemeWrapper(activity,android.R.style.Theme_Material_Light_NoActionBar) {
                override fun getResources():Resources=moduleResources
                override fun getAssets()=moduleResources.assets
                override fun getTheme():Resources.Theme=moduleTheme
                override fun getClassLoader():ClassLoader=EmbeddedManager::class.java.classLoader!!
                override fun getPackageName()="io.github.qqmodulemanager"
            }
        }
    }
}
