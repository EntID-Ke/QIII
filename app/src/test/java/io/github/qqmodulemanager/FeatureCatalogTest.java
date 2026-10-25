package io.github.qqmodulemanager;

import io.github.qqmodulemanager.bridge.FeatureCatalog;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class FeatureCatalogTest {
    @Test public void capabilitiesHaveDistinctProvidersAndUnambiguousClassRoutes(){
        Set<String> ids=new HashSet<>(),classes=new HashSet<>();
        for(FeatureCatalog.Feature f:FeatureCatalog.ALL){
            assertTrue(ids.add(f.id));assertTrue(f.modules().size()>=2);
            for(String module:f.modules())for(String type:f.classes(module)){
                assertTrue(classes.add(type));assertEquals(f.id,FeatureCatalog.forClass(type));
            }
        }
    }
    @Test public void bubbleAndFontAreIndependentAndUnrelatedClassesRemainUnmanaged(){
        assertEquals("default_bubble",FeatureCatalog.forClass("me.yxp.qfun.hook.chat.DefaultBubble"));
        assertEquals("default_font",FeatureCatalog.forClass("me.yxp.qfun.hook.chat.DefaultFont"));
        assertEquals("",FeatureCatalog.forClass("me.yxp.qfun.hook.chat.DefaultBubbleAndFont"));
        assertEquals("",FeatureCatalog.forClass("third.party.AntiRevoke"));
        assertFalse(FeatureCatalog.get("message_count").coldSwitch());assertTrue(FeatureCatalog.get("device_model").coldSwitch());
    }
}
