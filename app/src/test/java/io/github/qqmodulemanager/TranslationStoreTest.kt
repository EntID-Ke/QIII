package io.github.qqmodulemanager

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class TranslationStoreTest {
    private fun vault()=CredentialVault(RuntimeEnvironment.getApplication(),KeyGenerator.getInstance("AES").apply{init(256)}.generateKey())
    @Test fun keysAreEncryptedAndPublicSettingsDoNotRevealThem() {
        val app=RuntimeEnvironment.getApplication();val vault=vault();val store=TranslationStore(app,vault)
        val document=JSONObject().put("id","provider").put("name","My API").put("protocol","chat").put("url","https://example.com/v1").put("keyChanged",true).put("key","real-test-key")
        store.saveProvider(document)
        assertEquals("real-test-key",vault.read("provider"))
        val disk=app.getSharedPreferences("translation-secrets",0).getString("provider","")!!
        assertFalse(disk.contains("real-test-key"))
        val settings=store.settings();assertFalse(settings.toString().contains("real-test-key"));assertFalse(settings.toString().contains("keyChanged"));assertTrue(settings.getJSONArray("providers").getJSONObject(0).getBoolean("keyConfigured"))
        store.saveProvider(store.provider("provider").put("model","new-model"));assertEquals("real-test-key",vault.read("provider"))
        store.saveProvider(store.provider("provider").put("keyChanged",true).put("key",""));assertFalse(vault.contains("provider"));store.close()
    }
    @Test fun cachedTranslationsPersistAcrossReopenButAreIsolatedByAccountAndTextFingerprint() {
        val app=RuntimeEnvironment.getApplication();var store=TranslationStore(app,vault())
        store.put("1","msg",digest("old"),JSONObject().put("text","译文").put("providerName","原服务"),store.generationFor("1","msg"));store.expand("1","msg",digest("old"),false);store.close()
        store=TranslationStore(app,vault())
        assertEquals("译文",store.lookup("1","msg",digest("old"))!!.getString("text"));assertFalse(store.lookup("1","msg",digest("old"))!!.getBoolean("expanded"))
        assertNull(store.lookup("2","msg",digest("old")));assertNull(store.lookup("1","msg",digest("modified")))
        assertEquals("原服务",store.lookup("1","msg",digest("old"))!!.getString("providerName"));store.close()
    }
    @Test fun clearingOneMessageBlocksItsLateWriteButAllowsOtherRequestsToFinish() {
        val store=TranslationStore(RuntimeEnvironment.getApplication(),vault());val first=store.generationFor("1","a");val second=store.generationFor("1","b")
        store.clear("1","a")
        store.put("1","a","hash",JSONObject().put("text","late"),first);store.put("1","b","hash",JSONObject().put("text","keep"),second)
        assertNull(store.lookup("1","a","hash"));assertEquals("keep",store.lookup("1","b","hash")!!.getString("text"))
        val before=store.generationFor("1","b");store.clear("1",null);store.put("1","b","hash",JSONObject().put("text","late"),before);assertNull(store.lookup("1","b","hash"));store.close()
    }
    @Test fun deletingAProviderRemovesItsKeyAndPreservesOldTranslationAttribution() {
        val app=RuntimeEnvironment.getApplication();val vault=vault();val store=TranslationStore(app,vault)
        store.saveProvider(JSONObject().put("id","p").put("name","API").put("protocol","chat").put("url","https://example.com/v1").put("keyChanged",true).put("key","key"))
        store.put("1","m","hash",JSONObject().put("text","old").put("providerName","API"),0)
        store.saveSettings(store.settings().put("providers",org.json.JSONArray()))
        assertFalse(vault.contains("p"));assertEquals("API",store.lookup("1","m","hash")!!.getString("providerName"));store.close()
    }
    @Test fun aLateResultForAnOldBodyCannotOverwriteTheEditedMessagesTranslation() {
        val store=TranslationStore(RuntimeEnvironment.getApplication(),vault())
        store.put("1","m",digest("edited"),JSONObject().put("text","new translation"),0)
        store.put("1","m",digest("old"),JSONObject().put("text","old translation"),0)
        assertEquals("new translation",store.lookup("1","m",digest("edited"))!!.getString("text"))
        store.clear("1","m");assertNull(store.lookup("1","m",digest("edited")));assertNull(store.lookup("1","m",digest("old")));store.close()
    }
}
