package com.vector.console.data.payload

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * APK Signature Scheme v2 signer, in pure Java.
 *
 * Why not `apksigner`: it is a build-tool JAR, not something an Android app can invoke. Why not
 * v1: v1 is JAR signing and needs PKCS#7 SignedData, which means either BouncyCastle or hand
 * rolling a whole ASN.1 certificate chain. v2 has been accepted since Android 7.0 (API 24), and
 * this project's minSdk is 26 — so **v2 alone is sufficient on every device that can install
 * the app**, and the entire PKCS#7 problem disappears.
 *
 * The block is spliced between the last local entry and the central directory:
 *
 * ```
 * [entries][APK Signing Block][central directory][EOCD]
 * ```
 *
 * The digest covers three ranges, and the third one is the subtle part: the EOCD is digested
 * with its central-directory offset pointing at where the signing block *will* start, because
 * that is the value the verifier will read after stripping the block.
 */
object ApkSignerV2 {

    private const val V2_BLOCK_ID = 0x7109871a
    private const val MAGIC = "APK Sig Block 42"

    /** ECDSA P-256 with SHA-256. Compact, supported everywhere, no provider quirks. */
    private const val SIG_ALG_ID = 0x0201
    private const val CONTENT_DIGEST = "SHA-256"

    /** 1 MiB, the value every APK verifier uses for CHUNKED_SHA256. */
    private const val CHUNK_SIZE = 1 shl 20

    data class Credentials(val keyPair: KeyPair, val certificate: ByteArray)

    /**
     * P-256 keypair plus a self-signed certificate. Android does not validate the chain on
     * install — it only requires that the signing certificate exists and that updates match it.
     */
    fun generateCredentials(): Credentials {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val pair = generator.generateKeyPair()
        return Credentials(pair, selfSignedCertificate(pair))
    }

    fun sign(zip: ZipArchive.Layout, credentials: Credentials): ByteArray {
        val bytes = zip.bytes
        val cenOffset = zip.centralDirOffset
        val cenSize = zip.centralDirSize

        val chunk1 = bytes.copyOfRange(0, cenOffset)
        val chunk2 = bytes.copyOfRange(cenOffset, cenOffset + cenSize)

        // chunk3: the EOCD with the central-directory offset rewritten to the block start.
        val eocd = bytes.copyOfRange(zip.eocdOffset, bytes.size)
        if (eocd.size < 22) throw IllegalStateException("EOCD too short.")
        putLe32(eocd, 16, cenOffset.toLong())

        val contentDigest = chunkedDigest(listOf(chunk1, chunk2, eocd))

        val digestRecord = ByteArrayOutputStream().apply {
            write(le32(SIG_ALG_ID))
            write(lp(contentDigest))
        }.toByteArray()

        val certRecord = lp(credentials.certificate)

        // Each of these three fields is a *sequence* of length-prefixed records, and the
        // sequence itself is length-prefixed. `digestRecord` and `signatureRecord` hold only
        // the record body, so they need both levels; `certRecord` already carries its element
        // length, so it needs one. Getting this wrong shifts every following field by four
        // bytes and the verifier reports a malformed certificate.
        val signedData = ByteArrayOutputStream().apply {
            write(lp(lp(digestRecord)))
            write(lp(certRecord))
            write(lp(ByteArray(0))) // additional attributes
        }.toByteArray()

        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(credentials.keyPair.private)
            update(signedData)
            sign()
        }

        val signatureRecord = ByteArrayOutputStream().apply {
            write(le32(SIG_ALG_ID))
            write(lp(signature))
        }.toByteArray()

        val signerContent = ByteArrayOutputStream().apply {
            write(lp(signedData))
            write(lp(lp(signatureRecord)))
            write(lp(credentials.keyPair.public.encoded)) // SubjectPublicKeyInfo DER
        }.toByteArray()

        // value = lp( sequence of lp(signer) ); one signer means a double wrap.
        val value = lp(lp(signerContent))

        val pairs = ByteArrayOutputStream().apply {
            write(le64(4L + value.size))
            write(le32(V2_BLOCK_ID))
            write(value)
        }.toByteArray()

        // The size field is the length of everything after it: pairs + trailing size + magic.
        // i.e. (8 + pairs + 8 + 16) - 8. Getting this eight bytes wrong makes the verifier and
        // every reader compute a block start that does not exist.
        val sizeOfBlock = pairs.size + 24
        val block = ByteArrayOutputStream().apply {
            write(le64(sizeOfBlock.toLong()))
            write(pairs)
            write(le64(sizeOfBlock.toLong()))
            write(MAGIC.toByteArray(Charsets.ISO_8859_1))
        }.toByteArray()

        // Splice, then repoint the real EOCD at the relocated central directory.
        val out = ByteArrayOutputStream(bytes.size + block.size)
        out.write(bytes, 0, cenOffset)
        out.write(block)
        out.write(bytes, cenOffset, cenSize)
        val tail = eocd.copyOf()
        putLe32(tail, 16, (cenOffset + block.size).toLong())
        out.write(tail)
        return out.toByteArray()
    }

    /**
     * The chunked content digest, which is two nested hashes and is easy to get wrong:
     *
     * ```
     * perChunk_i = H( 0xa5 || le32(chunk_i.size) || chunk_i )
     * content    = H( 0x5a || le32(chunkCount) || perChunk_1 || ... || perChunk_n )
     * ```
     *
     * The `0xa5` prefix and the per-chunk *length* go inside each sub-digest; the `0x5a` prefix
     * and the *chunk count* go outside. Mixing those two up — using the count where the length
     * belongs, or the other prefix — produces a digest that is internally consistent but that
     * every real verifier rejects as `CHUNKED_SHA256 digest mismatch`.
     *
     * Each section is cut into 1 MiB pieces first, so the count is the total number of pieces
     * across all sections, not the number of sections.
     */
    internal fun chunkedDigest(chunks: List<ByteArray>): ByteArray {
        val pieces = ArrayList<ByteArray>()
        chunks.forEach { chunk ->
            var offset = 0
            while (offset < chunk.size) {
                val end = minOf(chunk.size, offset + CHUNK_SIZE)
                pieces.add(chunk.copyOfRange(offset, end))
                offset = end
            }
        }

        val outer = MessageDigest.getInstance(CONTENT_DIGEST)
        outer.update(0x5a.toByte())
        outer.update(le32(pieces.size))
        pieces.forEach { piece ->
            val inner = MessageDigest.getInstance(CONTENT_DIGEST)
            inner.update(0xa5.toByte())
            inner.update(le32(piece.size))
            inner.update(piece)
            outer.update(inner.digest())
        }
        return outer.digest()
    }

    // ------------------------------------------------------------ certificate

    /**
     * Minimal self-signed X.509 v3. Only the fields Android's verifier touches:
     * serial, validity, subject, and the SPKI. No extensions — a signing certificate with no
     * key usage is accepted, and every extension is more DER that can be wrong.
     */
    private fun selfSignedCertificate(pair: KeyPair): ByteArray {
        val algorithm = Der.sequence(Der.oid("1.2.840.10045.4.3.2"))
        val name = Der.sequence(
            Der.set(Der.sequence(Der.oid("2.5.4.3"), Der.utf8("OVERSIGHT")))
        )
        val validity = Der.sequence(
            Der.utcTime(now(-1)),
            Der.utcTime(now(3650))
        )
        val serial = BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE)

        val tbs = Der.sequence(
            Der.explicit(0, Der.integer(BigInteger.valueOf(2))),
            Der.integer(serial),
            algorithm,
            name,
            validity,
            name,
            pair.public.encoded
        )

        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(tbs)
            sign()
        }

        return Der.sequence(tbs, algorithm, Der.bitString(signature))
    }

    private fun now(offsetDays: Int): String {
        val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(System.currentTimeMillis() + offsetDays * 86_400_000L))
    }

    // ------------------------------------------------------------------ ASN.1

    internal object Der {

        fun sequence(vararg parts: ByteArray): ByteArray = tagged(0x30, parts.concat())

        fun set(vararg parts: ByteArray): ByteArray = tagged(0x31, parts.concat())

        fun integer(value: BigInteger): ByteArray = tagged(0x02, value.toByteArray())

        fun utf8(value: String): ByteArray = tagged(0x0C, value.toByteArray(Charsets.UTF_8))

        fun utcTime(value: String): ByteArray = tagged(0x17, value.toByteArray(Charsets.US_ASCII))

        fun bitString(value: ByteArray): ByteArray = tagged(0x03, byteArrayOf(0) + value)

        /** Wraps already-encoded DER in a context-specific tag (used for [0] EXPLICIT version). */
        fun explicit(tag: Int, content: ByteArray): ByteArray =
            tagged(0xA0 or tag, content)

        fun oid(dotted: String): ByteArray {
            val arcs = dotted.split('.').map { it.toLong() }
            val out = ByteArrayOutputStream()
            out.write((arcs[0] * 40 + arcs[1]).toInt())
            arcs.drop(2).forEach { arc ->
                var value = arc
                val stack = ArrayDeque<Int>()
                stack.addFirst((value and 0x7F).toInt())
                value = value shr 7
                while (value > 0) {
                    stack.addFirst(((value and 0x7F) or 0x80).toInt())
                    value = value shr 7
                }
                stack.forEach { out.write(it) }
            }
            return tagged(0x06, out.toByteArray())
        }

        private fun tagged(tag: Int, content: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(content.size + 6)
            out.write(tag)
            out.write(length(content.size))
            out.write(content)
            return out.toByteArray()
        }

        private fun length(size: Int): ByteArray = when {
            size < 0x80 -> byteArrayOf(size.toByte())
            size < 0x100 -> byteArrayOf(0x81.toByte(), size.toByte())
            size < 0x10000 -> byteArrayOf(
                0x82.toByte(), (size shr 8).toByte(), size.toByte()
            )
            else -> byteArrayOf(
                0x83.toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()
            )
        }

        private fun Array<out ByteArray>.concat(): ByteArray {
            val out = ByteArrayOutputStream(sumOf { it.size })
            forEach { out.write(it) }
            return out.toByteArray()
        }
    }

    // --------------------------------------------------------------- encoding

    /** Length prefix, little endian, as every APK signing structure uses. */
    internal fun lp(bytes: ByteArray): ByteArray = le32(bytes.size) + bytes

    internal fun le32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    internal fun le64(value: Long): ByteArray = ByteArray(8) { i ->
        ((value shr (8 * i)) and 0xFF).toByte()
    }

    private fun putLe32(target: ByteArray, at: Int, value: Long) {
        target[at] = (value and 0xFF).toByte()
        target[at + 1] = ((value shr 8) and 0xFF).toByte()
        target[at + 2] = ((value shr 16) and 0xFF).toByte()
        target[at + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
