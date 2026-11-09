package com.tembo.voxl

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Host(val name: String, val url: String)

/**
 * Saved hosts. The list lives in plain preferences; each host's password (keyed by URL) is
 * encrypted with an AES-GCM key that stays inside Android Keystore, so the stored blob is
 * useless off this device. Passwords are never logged or shown.
 */
object Hosts {
    private const val KEY_ALIAS = "voxl-passwords"

    fun all(ctx: Context): List<Host> {
        val s = ctx.getSharedPreferences("hosts", Context.MODE_PRIVATE).getString("hosts", null)
            ?: return emptyList()
        return runCatching {
            val a = JSONArray(s)
            List(a.length()) { a.getJSONObject(it).let { o -> Host(o.getString("name"), o.getString("url")) } }
        }.getOrDefault(emptyList())
    }

    fun save(ctx: Context, hosts: List<Host>) {
        val a = JSONArray()
        hosts.forEach { a.put(JSONObject().put("name", it.name).put("url", it.url)) }
        ctx.getSharedPreferences("hosts", Context.MODE_PRIVATE).edit().putString("hosts", a.toString()).apply()
    }

    /** "mac.ts.net:6901" or "https://mac.ts.net" -> "wss://mac.ts.net:6901/ws". Null if unusable. */
    fun normalize(input: String): String? {
        var t = input.trim()
        if (!t.contains("://")) t = "wss://$t"
        val u = runCatching { URI(t) }.getOrNull() ?: return null
        // A password belongs in the Keystore, never in a stored, displayed URL.
        if (u.host.isNullOrEmpty() || u.rawUserInfo != null) return null
        val scheme = when (u.scheme?.lowercase()) {
            "https", "wss" -> "wss"
            "http", "ws" -> "ws"
            else -> return null
        }
        val path = if (u.rawPath.isNullOrEmpty() || u.rawPath == "/") "/ws" else u.rawPath
        val port = if (u.port >= 0) ":${u.port}" else ""
        val query = u.rawQuery?.let { "?$it" } ?: ""
        return "$scheme://${u.host}$port$path$query"
    }

    fun displayHost(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url

    fun password(ctx: Context, url: String): String? {
        val blob = secrets(ctx).getString("pw:$url", null) ?: return null
        return runCatching {
            val (iv, ct) = blob.split(':').map { Base64.decode(it, Base64.NO_WRAP) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        }.getOrNull() // a blob the key can't open (key lost) counts as no password
    }

    fun setPassword(ctx: Context, url: String, pw: String?) {
        val e = secrets(ctx).edit()
        if (pw.isNullOrEmpty()) {
            e.remove("pw:$url")
        } else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val enc = { b: ByteArray -> Base64.encodeToString(b, Base64.NO_WRAP) }
            e.putString("pw:$url", enc(c.iv) + ":" + enc(c.doFinal(pw.toByteArray(Charsets.UTF_8))))
        }
        e.apply()
    }

    private fun secrets(ctx: Context) = ctx.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return g.generateKey()
    }
}
