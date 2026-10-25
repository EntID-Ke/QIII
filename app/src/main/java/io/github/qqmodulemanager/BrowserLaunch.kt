package io.github.qqmodulemanager

import android.content.Context
import android.content.Intent
import android.net.Uri

internal object BrowserLaunch {
    fun intent(context:Context,url:String):Intent {
        val target=Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        // Resolve a generic page to avoid Lens App Links taking over the browser result.
        val browser=Intent(Intent.ACTION_VIEW,Uri.parse("https://example.com/")).addCategory(Intent.CATEGORY_BROWSABLE).resolveActivity(context.packageManager)
        if(browser!=null && browser.packageName !in listOf("android","com.tencent.mobileqq"))target.setPackage(browser.packageName)
        return target
    }
}
