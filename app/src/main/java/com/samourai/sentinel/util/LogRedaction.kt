package com.samourai.sentinel.util

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import timber.log.Timber
import timber.log.Timber.DebugTree
import java.util.logging.Filter
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Redaction at the logging boundary (#264/#96).
 *
 * The onion-host class never appears in app-authored message text -
 * the host rides inside exception messages (UnknownHostException:
 * Unable to resolve host "<onion>"), and Timber 4.7.1's private
 * Tree.prepareLog appends the throwable's full stack to the message
 * before the abstract log() runs - deliberately refusing
 * Log.getStackTraceString because it "hides UnknownHostException".
 * So this class can only be masked at a Tree seam, never at the call
 * sites and never inside Timber's plumbing.
 *
 * Over-redaction fails safe: the rules are narrow enough to keep
 * ordinary log text readable, and anything they do catch is not
 * worth printing in a debug logcat that can leave the device.
 */

object LogRedaction {

    /**
     * Zero-width characters that can split a secret without
     * changing what it names: BOM (U+FEFF) not included - a log
     * line can legitimately start with it. #568.
     */
    private val ZERO_WIDTH = Regex("[\\u200B-\\u200D\\u2060]")

    /**
     * A newline (plus any following indentation) inside a run of
     * base58/base32/hex characters only ever wraps a secret that
     * did not fit the line: loggers indent continuation lines.
     * #568.
     */
    private val WRAPPED_SECRET = Regex("([0-9A-Za-z])\\n[ \\t]*([0-9A-Za-z])")

    /**
     * One level of normalization before the rules run: a secret
     * split across lines is joined, and zero-width characters are
     * dropped, so the shape-based rules see the full secret. The
     * result keeps the line structure the caller wants to read.
     * #568.
     */
    private fun normalize(message: String): String =
        message
            .replace(ZERO_WIDTH, "")
            .replace(WRAPPED_SECRET) { it.groupValues[1] + it.groupValues[2] }

    /**
     * Onion service hostnames: version-2 hosts are 16 chars,
     * version-3 are 56, both base32 (a-z, 2-7), rendered lowercase
     * by UnknownHostException. The 16-char floor keeps ordinary
     * English words before ".onion" untouched. Case-insensitive
     * since #568/#585: a mixed-case host reaching a log line is
     * not a reason to keep it.
     */
    private val ONION_HOST = Regex("[a-z2-7]{16,56}\\.onion", RegexOption.IGNORE_CASE)

    /**
     * 64-char hex: the txid shape. #266 closed the app-authored
     * arms; this catches the exception-message-embeds-txid class
     * those pins deliberately tolerated. Word boundaries demand the
     * full 64 - a 63- or 65-char run is left alone. Uppercase hex
     * is the same txid (#568/#585).
     */
    private val HEX_64 = Regex("\\b[0-9a-fA-F]{64}\\b")

    /**
     * Extended public keys as they ride URLs and payloads (#96's
     * acceptance condition: txids/addresses/xpubs masked). The
     * 90-char floor keeps anything shorter than a real key
     * (111-ish chars total) untouched; the prefixes are the
     * complete SLIP-132 extended-public set - mainnet
     * xpub/ypub/zpub, testnet tpub/upub/vpub. upub was missing
     * until the #96 device pass (2026-10-22) caught a raw
     * testnet key riding a wire line - no CI case had ever
     * exercised the prefix.
     */
    private val XPUB = Regex("\\b(xpub|zpub|ypub|tpub|upub|vpub)[1-9A-HJ-NP-Za-km-z]{90,}")

    /**
     * The Dojo access-token query parameter (Arc B). ApiService's
     * at-interceptor and TokenAuthenticator's retry both
     * addQueryParameter("at", ...), so every wire line carries
     * the token. The word boundary keeps longer parameter names
     * (format=, latitude=) untouched; the value runs to the
     * next & or whitespace.
     */
    private val AT_TOKEN = Regex("\\bat=[^&\\s\"']+")

    /**
     * The Dojo API key as a query parameter (#585): authenticateDojo
     * posts it, and a wire line or an exception message can carry
     * it. Same shape as [AT_TOKEN]; the key name stays so the line
     * stays readable.
     */
    private val API_KEY_PARAM = Regex("\\bapikey=[^&\\s\"']+")

    /**
     * The Dojo API key in JSON ("apikey"), plus the auth-body
     * fields [JSON_TOKEN_FIELD] already covers. #585: the JSON
     * form of the API key pair rule.
     */
    private val JSON_API_KEY_FIELD = Regex("\"apikey\"\\s*:\\s*\"[^\"]*\"")

    /**
     * The same access token as JSON: `"at":"..."`, plus the auth-body
     * fields `access_token` and `refresh_token`. The query rule above
     * does not match this shape. The key is kept so the line stays
     * readable; only the value is replaced.
     */
    private val JSON_TOKEN_FIELD = Regex(
        "\"(at|access_token|refresh_token)\"\\s*:\\s*\"[^\"]*\""
    )

    /**
     * Subscribe payloads put the watched value in `"addr"`. Mask the
     * whole field so the format (address, xpub, or anything else)
     * does not matter.
     */
    private val JSON_ADDR_FIELD = Regex("\"addr\"\\s*:\\s*\"[^\"]*\"")

    /**
     * Bech32 addresses (bc1 / tb1 / bcrt1). The 14-char data floor
     * leaves short fragments like "bc1q" alone. Case-insensitive:
     * BIP173 allows an all-uppercase form.
     */
    private val BECH32_ADDR = Regex(
        "\\b(?:bcrt1|bc1|tb1)[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{14,}\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Base58 addresses (mainnet 1/3, testnet m/n/2). Total length
     * 26-35. Shorter digit runs stay visible.
     */
    private val BASE58_ADDR = Regex("\\b[13mn2][1-9A-HJ-NP-Za-km-z]{25,34}\\b")

    /**
     * BIP47 payment codes. Version byte 0x47 base58-encodes as PM8T.
     * A real code is the 80-byte payload plus checksum, 116 characters
     * for the donation code in SamouraiWalletConst. The 80-character
     * floor after the prefix leaves the four letters themselves, and
     * anything shorter, readable. The same rule covers the release
     * crash file: CrashReportingTree redacts the stack with [redact].
     * Case-insensitive since #568: lowercase pm8t is the same code
     * wearing different letters, and a mixed-case code rode a log
     * line unmasked (#585).
     */
    private val PAYMENT_CODE = Regex("\\b[Pp][Mm]8[Tt][1-9A-HJ-NP-Za-km-z]{80,}")

    internal fun containsPaymentCode(value: String): Boolean =
        PAYMENT_CODE.containsMatchIn(normalize(value))

    /**
     * Applies every rule, in order, to one log line. The line is
     * normalized first (#568): zero-width characters dropped,
     * newline-wrapped secrets joined, so a secret split across
     * lines or padded with U+200B does not slip through the
     * shape rules.
     */
    fun redact(message: String): String {
        val line = normalize(message)
        return line
            .replace(ONION_HOST, "ONION_REDACTED")
            .replace(HEX_64, "HEX_REDACTED")
            .replace(XPUB, "XPUB_REDACTED")
            .replace(AT_TOKEN, "at=REDACTED")
            .replace(API_KEY_PARAM, "apikey=REDACTED")
            .replace(JSON_API_KEY_FIELD, "\"apikey\":\"REDACTED\"")
            .replace(JSON_TOKEN_FIELD) { match ->
                "\"${match.groupValues[1]}\":\"REDACTED\""
            }
            .replace(JSON_ADDR_FIELD, "\"addr\":\"REDACTED\"")
            .replace(BECH32_ADDR, "ADDR_REDACTED")
            .replace(BASE58_ADDR, "ADDR_REDACTED")
            .replace(PAYMENT_CODE, "PAYMENT_CODE_REDACTED")
    }
}

/**
 * The HTTP wire logger (Arc B). HttpLoggingInterceptor's
 * DEFAULT logger writes through android.util.Log directly - it
 * never crosses Timber, so the MaskingDebugTree seam cannot see
 * it. This logger rides Timber instead (explicit tag - wire
 * lines are not caller-attributed) and redacts explicitly as
 * well, so masking never depends on which trees happen to be
 * planted. The install sites choose Level.BASIC - request and
 * response lines only, never headers or bodies - and the
 * secrets a BASIC line can carry (onion host, at token, xpub
 * in wallet URLs) are the rules above.
 */
class MaskedHttpLogger : HttpLoggingInterceptor.Logger {
    override fun log(message: String) {
        Timber.tag("OkHttp").d(LogRedaction.redact(message))
    }
}

/**
 * The debug-build Tree: DebugTree with the seam masked.
 * Timber 4.7.1's DebugTree.log(int, String?, String, Throwable?)
 * is open (not final), so this override is the one legal
 * subclassing point; tag inference is untouched because
 * prepareLog -> getTag() runs before log() - the calling-class
 * stack is exactly what it was before this class existed.
 */
class MaskingDebugTree : DebugTree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        super.log(priority, tag, LogRedaction.redact(message), t)
    }
}

/**
 * #304: OkHttp's connection-pool leak warning does not cross Timber,
 * [MaskedHttpLogger], or any `android.util.Log` call this app owns.
 * `RealConnectionPool` calls `Platform.logCloseableLeak`, and
 * `Platform.log` writes `java.util.logging` logger `okhttp3.OkHttpClient`.
 * On Android, OkHttp's `AndroidLog` bridges that logger to logcat with
 * tag `okhttp.OkHttpClient` and `useParentHandlers = false`. The record
 * is filtered before any handler runs (including that bridge), so
 * redacting here covers the leak line and every other message on the
 * same logger (callback failures, the "set level to FINE" hint).
 *
 * The filter mutates the record and still returns true: the warning
 * stays, the Dojo URL does not. Silencing the logger would hide the
 * leak that #293 exists to remove.
 */
object OkHttpLogRedaction {
    private val redacting = Filter { record ->
        try {
            redact(record)
        } catch (_: Exception) {
            // A failure here must not publish the original record.
            record.message = "okhttp log redaction failed"
            record.thrown = null
        }
        true
    }

    /** Idempotent. Safe to call from every Dojo client builder. */
    fun install() {
        Logger.getLogger(OkHttpClient::class.java.name).filter = redacting
    }

    internal fun redact(record: LogRecord) {
        record.message?.let { raw ->
            val redacted = LogRedaction.redact(raw)
            if (redacted != raw) record.message = redacted
        }
        val thrown = record.thrown ?: return
        val rendered = thrown.stackTraceToString()
        val redacted = LogRedaction.redact(rendered)
        if (redacted == rendered) return
        record.thrown = Throwable(redacted).also { it.stackTrace = emptyArray() }
    }
}
