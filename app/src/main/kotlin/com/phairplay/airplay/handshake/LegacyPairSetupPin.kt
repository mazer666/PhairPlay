package com.phairplay.airplay.handshake

import com.phairplay.util.Logger
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * LegacyPairSetupPin — server side of the legacy AirPlay `/pair-setup-pin` PIN handshake (the Apple
 * TV 2/3 protocol macOS still uses for AirPlay PIN access control). Plist-based SRP-6a:
 * RFC 5054 2048-bit group, generator 2, SHA-1.
 *
 *   step 1: client `{method:"pin", user:<id>}`  → reply `{pk:B, salt}` (PIN shown on the TV)
 *   step 2: client `{pk:A, proof:M1}`           → verify the PIN proof, reply `{proof:M2}`
 *
 * The crypto matches Apple exactly (verified against openairplay/AirPlayAuth's Apple SRP6 routines):
 *   k  = H(N | g)                                       (unpadded, minimal big-endian bytes)
 *   u  = H(A | B)
 *   x  = H(salt | H(user | ":" | pin))
 *   S  = (A · v^u)^b
 *   K  = H(S | 00000000) ‖ H(S | 00000001)              (40 bytes — Apple's two-counter hash)
 *   M1 = H( (H(N) XOR H(g)) | H(user) | salt | A | B | K )
 *   M2 = H( A | M1 | K )
 */
class LegacyPairSetupPin(private val pin: String, private val accessoryEdPublic: ByteArray) {

    private val random = SecureRandom()
    private var username = ""
    private var salt = ByteArray(0)
    private var serverPrivate = BigInteger.ZERO     // b
    private var serverPublic = BigInteger.ZERO      // B
    private var serverPublicBytes = ByteArray(0)    // B, canonical bytes (as sent + hashed in M1)
    private var verifier = BigInteger.ZERO          // v
    private var sessionK = ByteArray(0)             // SRP session key (retained for step-3 AES exchange)

    /** reply = plist to return (null on bad input); complete/failed drive the PIN UI + lockout. */
    data class Result(val reply: Map<String, Any>?, val complete: Boolean = false, val failed: Boolean = false)

    fun handle(plist: Map<String, Any?>): Result = when {
        plist.containsKey("epk") && plist.containsKey("authTag") -> step3(plist)
        plist.containsKey("pk") && plist.containsKey("proof") -> step2(plist)
        plist["method"] == "pin" || plist.containsKey("user") -> step1(plist)
        else -> { Logger.w("pair-setup-pin: unrecognised body keys ${plist.keys}"); Result(null, failed = true) }
    }

    /** Step 1: build the verifier from (user, PIN) + a fresh salt; return salt + server public B. */
    private fun step1(plist: Map<String, Any?>): Result {
        username = (plist["user"] as? String).orEmpty()
        // 16-byte salt with a non-zero first byte so its canonical (sign-stripped) form is stable.
        salt = ByteArray(16).also { random.nextBytes(it); if (it[0].toInt() == 0) it[0] = 1 }
        val x = BigInteger(1, sha1(salt, sha1("$username:$pin".toByteArray())))    // x = H(s | H(user:pin))
        verifier = G.modPow(x, N)                                                  // v = g^x
        serverPrivate = BigInteger(256, random)
        val k = BigInteger(1, sha1(pad(N), pad(G)))                                // k = H(PAD(N) | PAD(g))
        serverPublic = k.multiply(verifier).add(G.modPow(serverPrivate, N)).mod(N) // B = kv + g^b
        serverPublicBytes = toBytes(serverPublic)
        // WHY the user name is not logged: it identifies the controller that wants to
        // pair. The step itself is visible from the sizes.
        Logger.i("pair-setup-pin step1: pk(${serverPublicBytes.size}B) + salt(${salt.size}B)")
        return Result(mapOf("pk" to serverPublicBytes, "salt" to salt))
    }

    /** Step 2: derive the shared secret, verify the client's PIN proof M1, return M2. */
    private fun step2(plist: Map<String, Any?>): Result {
        val aBytes = plist["pk"] as? ByteArray ?: return Result(null, failed = true)
        val clientM1 = plist["proof"] as? ByteArray ?: return Result(null, failed = true)
        val a = BigInteger(1, aBytes)
        if (a.mod(N) == BigInteger.ZERO) { Logger.w("pair-setup-pin: A ≡ 0"); return Result(null, failed = true) }
        val aCanon = toBytes(a)

        val u = BigInteger(1, sha1(pad(a), pad(serverPublic)))                     // u = H(PAD(A) | PAD(B))
        val s = a.multiply(verifier.modPow(u, N)).modPow(serverPrivate, N)         // S = (A · v^u)^b
        val k = sessionKey(toBytes(s))                                             // K (Apple two-counter)

        val expectedM1 = sha1(hNxorHg(), sha1(username.toByteArray()), salt, aCanon, serverPublicBytes, k)
        if (!MessageDigest.isEqual(expectedM1, clientM1)) {
            Logger.w("pair-setup-pin step2: PIN proof mismatch (wrong code)")
            return Result(null, failed = true)
        }
        sessionK = k   // retained for the step-3 AES-GCM key exchange
        val m2 = sha1(aCanon, clientM1, k)                                         // M2 = H(A | M1 | K)
        Logger.i("pair-setup-pin step2: PIN verified → returning M2 (awaiting step-3 key exchange)")
        return Result(mapOf("proof" to m2))   // NOT complete yet — step 3 (epk/authTag) finishes pairing
    }

    /**
     * Step 3: AES-128-GCM key exchange. The client sends its Ed25519 public key encrypted under a key
     * derived from the SRP session key K; we decrypt it (authenticating the client knew the PIN) and
     * return our Ed25519 public key encrypted the same way. This completes legacy pairing.
     */
    private fun step3(plist: Map<String, Any?>): Result {
        val epk = plist["epk"] as? ByteArray ?: return Result(null, failed = true)
        val authTag = plist["authTag"] as? ByteArray ?: return Result(null, failed = true)
        if (sessionK.isEmpty()) { Logger.w("pair-setup-pin step3 before step2"); return Result(null, failed = true) }

        val aesKey = sha512("Pair-Setup-AES-Key".toByteArray(), sessionK).copyOf(16)
        val aesIv = sha512("Pair-Setup-AES-IV".toByteArray(), sessionK).copyOf(16)
        incrementIv(aesIv)   // Apple increments the derived IV by 1 (big-endian, with carry) before use

        // Decrypt the client's key (epk‖authTag) at IV+1 — verifies the client derived the same K.
        val clientKey = aesGcm(false, aesKey, aesIv, epk + authTag)
            ?: run { Logger.w("pair-setup-pin step3: client epk auth failed"); return Result(null, failed = true) }
        Logger.i("pair-setup-pin step3: client key (${clientKey.size}B) verified → returning accessory key")

        // Encrypt our Ed25519 public key at IV+2 (the IV is a per-message counter: the client's
        // request used IV+1, the server's reply uses the next value).
        incrementIv(aesIv)
        val out = aesGcm(true, aesKey, aesIv, accessoryEdPublic)!!
        return Result(
            mapOf("epk" to out.copyOf(out.size - 16), "authTag" to out.copyOfRange(out.size - 16, out.size)),
            complete = true
        )
    }

    /** AES-128-GCM (128-bit tag). Returns ciphertext‖tag (encrypt) or plaintext / null (decrypt auth fail). */
    private fun aesGcm(encrypt: Boolean, key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray? = try {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val mode = if (encrypt) javax.crypto.Cipher.ENCRYPT_MODE else javax.crypto.Cipher.DECRYPT_MODE
        cipher.init(mode, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, iv))
        cipher.doFinal(input)
    } catch (_: Exception) { null }

    /** Increment a 16-byte IV by 1 as a big-endian counter (carry from the last byte). */
    private fun incrementIv(iv: ByteArray) {
        for (i in iv.indices.reversed()) {
            iv[i] = (iv[i] + 1).toByte()
            if (iv[i].toInt() != 0) break
        }
    }

    private fun sha512(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    /** Apple session key K = H(S | 0x00000000) ‖ H(S | 0x00000001). */
    private fun sessionKey(sBytes: ByteArray): ByteArray =
        sha1(sBytes, byteArrayOf(0, 0, 0, 0)) + sha1(sBytes, byteArrayOf(0, 0, 0, 1))

    /** H(N) XOR H(g), each 20 bytes (SHA-1). */
    private fun hNxorHg(): ByteArray {
        val hn = sha1(toBytes(N))
        val hg = sha1(toBytes(G))
        return ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
    }

    private fun sha1(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-1")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    /** Minimal big-endian bytes, stripping a single leading sign byte (matches Nimbus bigIntegerToBytes). */
    private fun toBytes(bi: BigInteger): ByteArray {
        val b = bi.toByteArray()
        return if (b.size > 1 && b[0].toInt() == 0) b.copyOfRange(1, b.size) else b
    }

    /** Left-pad to N's byte length (256). SRP6 computeU/computeK pad each value to N's length (RFC 5054). */
    private fun pad(bi: BigInteger): ByteArray {
        val raw = toBytes(bi)
        if (raw.size >= N_BYTES) return raw
        return ByteArray(N_BYTES).also { System.arraycopy(raw, 0, it, N_BYTES - raw.size, raw.size) }
    }

    companion object {
        private const val N_BYTES = 256   // 2048-bit group → 256-byte padding for computeU/computeK
        private val G = BigInteger.valueOf(2)
        /** RFC 5054 2048-bit SRP group prime. */
        private val N = BigInteger(
            "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050" +
            "A37329CBB4A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50" +
            "E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B8" +
            "55F97993EC975EEAA80D740ADBF4FF747359D041D5C33EA71D281E446B14773B" +
            "CA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748" +
            "544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6" +
            "AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB6" +
            "94B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73", 16
        )
    }
}
