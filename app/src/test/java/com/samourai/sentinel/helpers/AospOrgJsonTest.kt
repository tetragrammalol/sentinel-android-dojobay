package com.samourai.sentinel.helpers

import aosp.json.JSONArray
import aosp.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * #567. These inputs are run through AOSP libcore org.json (copied
 * under aosp.json; the unit-test jar is a different parser and wraps
 * [StackOverflowError] in a [org.json.JSONException]). A comment, a
 * single quote, a bare key, `=>`, or `;` lets that parser recurse on
 * brackets a double-quote scan never counts. [SafeJson] must refuse
 * the same text with [UnsafeJsonException] and no cause.
 *
 * [org.json.JSONArray.toString] walks a tree by recursion and is not
 * inside SafeJson's catch. A tree that deep is only possible if the
 * pre-scan let the document through.
 *
 * The overflow characterization starts a Thread with a 256 KiB stack
 * and depth [DEEP]. The 256 KiB is only a hint (some JVMs did not
 * overflow at 10k: this check failed on a #554 PR run after passing
 * on master and on #574, #582/#583), but [DEEP] recursion frames
 * cannot fit in any 256 KiB stack, so the overflow is not a race:
 * the parse must overflow before it can finish or throw the
 * unterminated-structure JSONException. That makes the StackOverflowError
 * assert strict - no skip - so a real parser change can never hide
 * behind a JVM that doesn't reproduce it. Every SafeJson refusal stays
 * strict and always runs.
 */
class AospOrgJsonTest {

    /** Overflow depth: any JVM's 256 KiB stack overflows long before [DEEP] frames. */
    private val DEEP = 200_000

    @Test
    fun lenientNestingOverflowsAospAndIsRefusedByThePreScan() {
        val hidden = "[".repeat(DEEP)
        val cases = listOf(
            "/*\"*/$hidden",
            "//\"\n$hidden",
            "#\"\n$hidden",
            "['\"',$hidden",
            "{a:$hidden}",
            "{\"a\"=>$hidden}",
            "[1;$hidden",
            """{"authorizations":{"access_token":/*"*/$hidden}}""",
        )
        for (text in cases) {
            val aosp = aosp(text)
            val refused = refuse(text)
            assertEquals(text.take(32), SafeJson.SYNTAX_MESSAGE, refused.message)
            assertNull(text.take(32), refused.cause)
            assertTrue(
                "${text.take(32)}: expected StackOverflowError from AOSP, " +
                    "got ${aosp?.javaClass?.name}: ${aosp?.message}",
                aosp is StackOverflowError,
            )
        }
    }

    @Test
    fun aospToStringReturnsNullWhenANumberIsNotFinite() {
        val parsed = aosp.json.JSONObject(
            """{"authorizations":{"access_token":"a"},"x":[1e400]}"""
        )
        assertEquals("a", parsed.getJSONObject("authorizations").getString("access_token"))
        assertNull(parsed.toString())
    }

    @Test
    fun aospToStringOfADeepTreeOverflowsOutsideTheParse() {
        var inner = JSONArray()
        repeat(DEEP) {
            val outer = JSONArray()
            outer.put(inner)
            inner = outer
        }
        val thrown = AtomicReference<Throwable>()
        val thread = Thread(
            null,
            Runnable {
                try {
                    inner.toString()
                } catch (t: Throwable) {
                    thrown.set(t)
                }
            },
            "aosp-tostring",
            256L * 1024,
        )
        thread.isDaemon = true
        thread.start()
        thread.join(30_000)
        check(!thread.isAlive) { "AOSP toString still running after 30s" }
        val refused = refuse("[".repeat(DEEP) + "1" + "]".repeat(DEEP))
        assertEquals(SafeJson.DEPTH_MESSAGE, refused.message)
        assertNull(refused.cause)
        assertTrue(
            "toString: expected StackOverflowError from AOSP, got ${thrown.get()?.javaClass?.name}",
            thrown.get() is StackOverflowError,
        )
    }

    private fun aosp(text: String): Throwable? {
        val thrown = AtomicReference<Throwable>()
        val thread = Thread(
            null,
            Runnable {
                try {
                    JSONTokener(text).nextValue()
                } catch (t: Throwable) {
                    thrown.set(t)
                }
            },
            "aosp-parse",
            256L * 1024,
        )
        thread.isDaemon = true
        thread.start()
        thread.join(30_000)
        check(!thread.isAlive) { "AOSP parse still running after 30s for ${text.take(32)}" }
        return thrown.get()
    }

    private fun refuse(text: String): UnsafeJsonException {
        try {
            SafeJson.parseObject(text)
        } catch (e: UnsafeJsonException) {
            return e
        }
        try {
            SafeJson.parseArray(text)
        } catch (e: UnsafeJsonException) {
            return e
        }
        throw AssertionError("SafeJson parsed ${text.take(32)}")
    }
}
