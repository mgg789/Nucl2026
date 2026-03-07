package com.peerdone.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class DeviceKeyStoreSigner {
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private var currentAlias: String? = null

    fun useIdentity(userId: String) {
        val alias = "mesh_sign_$userId"
        if (!keyStore.containsAlias(alias)) {
            generate(alias)
        }
        currentAlias = alias
    }

    fun publicKeyBase64(): String {
        val alias = requireNotNull(currentAlias) { "Identity is not selected" }
        val cert = keyStore.getCertificate(alias)
        val publicKey = cert.publicKey as ECPublicKey
        return Base64.encode(publicKey.encoded)
    }

    fun sign(input: String): String {
        val alias = requireNotNull(currentAlias) { "Identity is not selected" }
        val privateKey = keyStore.getKey(alias, null) as PrivateKey
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(input.encodeToByteArray())
        return Base64.encode(signature.sign())
    }

    private fun generate(alias: String) {
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .build()
        kpg.initialize(spec)
        kpg.generateKeyPair()
    }
}

