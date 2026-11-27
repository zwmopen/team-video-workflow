package com.zwm.livecore;
import org.junit.Test;
import static org.junit.Assert.*;

public class LiveEnginePreferenceTest {
    @Test public void checksAllAvailabilityAndConsentCombinations() {
        for (LiveEnginePreference p : LiveEnginePreference.values()) {
            for (int i=0;i<16;i++) {
                boolean ai=(i&1)!=0, auth=(i&2)!=0, z=(i&4)!=0, f=(i&8)!=0;
                LiveEnginePreference.Route expected=LiveEnginePreference.Route.LOCAL;
                if(ai&&auth&&p==LiveEnginePreference.ZHIPU_FIRST&&z) expected=LiveEnginePreference.Route.ZHIPU;
                if(ai&&auth&&p==LiveEnginePreference.FLOW_FIRST&&f) expected=LiveEnginePreference.Route.FLOW;
                assertEquals(expected,p.resolve(ai,auth,z,f));
            }
        }
        assertEquals(LiveEnginePreference.ZHIPU_FIRST,LiveEnginePreference.fromId(null));
        assertEquals(LiveEnginePreference.ZHIPU_FIRST,LiveEnginePreference.fromId("unknown"));
    }
}
