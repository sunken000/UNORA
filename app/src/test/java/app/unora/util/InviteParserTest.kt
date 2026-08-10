package app.unora.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteParserTest {
    @Test fun acceptsCode() = assertEquals("A7BK29", InviteParser.parse("a7bk29").getOrThrow())
    @Test fun acceptsCustomLink() = assertEquals("A7BK29", InviteParser.parse("unora://party/A7BK29").getOrThrow())
    @Test fun acceptsHttpsLink() = assertEquals("A7BK29", InviteParser.parse("https://unora.app/p/A7BK29").getOrThrow())
    @Test fun rejectsOtherHosts() = assertTrue(InviteParser.parse("https://example.com/p/A7BK29").isFailure)
}
