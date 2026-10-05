package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Test

class IpFilterTest {
    @Test
    fun datAndP2pLinesParseAndJunkIsSkipped() {
        val lines = sequenceOf(
            "# a comment",
            "001.002.003.000 - 001.002.003.255 , 100 , Blocked range",
            "010.000.000.000 - 010.255.255.255 , 200 , Allowed: level above 127",
            "Some company:5.6.7.0-5.6.7.255",
            "not an ip line",
            "300.1.1.1 - 300.1.1.2 , 0 , impossible address"
        )
        assertEquals(
            listOf(IpRange("1.2.3.0", "1.2.3.255"), IpRange("5.6.7.0", "5.6.7.255")),
            IpFilterParser.parse(lines)
        )
    }
}
