package app.bililisten.platform

import org.junit.Assert.*
import org.junit.Test

class AccountLoginRulesTest {
    @Test fun cookieAppearingAfterLoginIsAutomaticallySelectedWithOnlyAllowedFields() {
        val watch=LoginSessionWatch()
        assertNull(watch.next("buvid3=tracking",0))
        assertNull(watch.next("SESSDATA= ; DedeUserID=7",1000))
        assertEquals("DedeUserID=7; SESSDATA=fixture; bili_jct=csrf",watch.next("buvid3=tracking; bili_jct=csrf; SESSDATA=fixture; DedeUserID=7",2000))
        assertNull(watch.next("SESSDATA=fixture; DedeUserID=7; bili_jct=csrf",2001))
    }
    @Test fun automaticFailuresHaveBoundedRetriesAndChangedCandidateStartsAgain() {
        val watch=LoginSessionWatch()
        assertNotNull(watch.next("SESSDATA=a",0));assertNull(watch.next("SESSDATA=a",2999))
        assertNotNull(watch.next("SESSDATA=a",3000));assertNull(watch.next("SESSDATA=a",12999))
        assertNotNull(watch.next("SESSDATA=a",13000));assertNull(watch.next("SESSDATA=a",99999))
        assertNotNull(watch.next("SESSDATA=b",100000));watch.stop();assertNull(watch.next("SESSDATA=b",999999))
    }
}
