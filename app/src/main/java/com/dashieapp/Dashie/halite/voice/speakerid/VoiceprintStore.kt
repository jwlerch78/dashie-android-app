package com.dashieapp.Dashie.halite.voice.speakerid

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Per-family-member voiceprint profiles: a SET of embedding vectors per
 * member (Apple "Personalized Hey Siri" model — explicit enrollment seeds
 * ~7 vectors, implicit/confirmed turns append up to [MAX_VECTORS]), NOT a
 * single centroid. Scoring against the set is SpeakerIdentifier's job.
 *
 * Persistence: JSON at files/speakerid/voiceprints.json, vectors as
 * base64 float32-LE. Deleting a member's voiceprint removes vectors AND
 * their retained enrollment recordings (files/speakerid/enrollment/<id>/).
 *
 * Anti-poisoning rule (enforced by callers, supported here via [Source]):
 * only EXPLICIT (wizard) or confirmed/high-confidence turns may be added.
 * Eviction removes oldest IMPLICIT vectors first so wizard-quality seeds
 * survive.
 */
class VoiceprintStore(private val context: Context) {

    enum class Source { EXPLICIT, IMPLICIT }

    data class VoiceprintVector(
        val vector: FloatArray,
        val source: Source,
        val timestampMs: Long,
    )

    data class Profile(
        val memberId: String,
        val memberName: String,
        val vectors: MutableList<VoiceprintVector> = mutableListOf(),
    )

    private val lock = Any()
    private var profiles: MutableMap<String, Profile>? = null

    private val file: File
        get() = File(File(context.filesDir, DIR_NAME), FILE_NAME)

    /** All profiles (loaded lazily). Returned map is a defensive snapshot. */
    fun getProfiles(): Map<String, Profile> = synchronized(lock) {
        HashMap(loadLocked())
    }

    fun hasAnyProfiles(): Boolean = synchronized(lock) {
        loadLocked().values.any { it.vectors.isNotEmpty() }
    }

    fun addVector(
        memberId: String,
        memberName: String,
        vector: FloatArray,
        source: Source,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        synchronized(lock) {
            val all = loadLocked()
            val profile = all.getOrPut(memberId) { Profile(memberId, memberName) }
            profile.vectors.add(VoiceprintVector(vector.copyOf(), source, nowMs))
            while (profile.vectors.size > MAX_VECTORS) {
                val victim = profile.vectors
                    .filter { it.source == Source.IMPLICIT }
                    .minByOrNull { it.timestampMs }
                    ?: profile.vectors.minByOrNull { it.timestampMs }!!
                profile.vectors.remove(victim)
            }
            saveLocked(all)
        }
    }

    /** Removes the member's vectors and any retained enrollment audio. */
    fun deleteMember(memberId: String) {
        synchronized(lock) {
            val all = loadLocked()
            if (all.remove(memberId) != null) saveLocked(all)
        }
        File(File(File(context.filesDir, DIR_NAME), ENROLLMENT_DIR), memberId)
            .deleteRecursively()
    }

    fun enrollmentAudioDir(memberId: String): File =
        File(File(File(context.filesDir, DIR_NAME), ENROLLMENT_DIR), memberId)
            .apply { mkdirs() }

    // ── persistence ──────────────────────────────────────────────────────────

    private fun loadLocked(): MutableMap<String, Profile> {
        profiles?.let { return it }
        val loaded = mutableMapOf<String, Profile>()
        try {
            if (file.exists()) {
                val root = JSONObject(file.readText())
                val members = root.getJSONObject("members")
                for (memberId in members.keys()) {
                    val m = members.getJSONObject(memberId)
                    val profile = Profile(memberId, m.getString("name"))
                    val vecs = m.getJSONArray("vectors")
                    for (i in 0 until vecs.length()) {
                        val v = vecs.getJSONObject(i)
                        profile.vectors.add(
                            VoiceprintVector(
                                vector = decodeVector(v.getString("b64")),
                                source = if (v.optString("src") == "explicit")
                                    Source.EXPLICIT else Source.IMPLICIT,
                                timestampMs = v.optLong("ts"),
                            )
                        )
                    }
                    loaded[memberId] = profile
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load voiceprints — starting empty", e)
            loaded.clear()
        }
        profiles = loaded
        return loaded
    }

    private fun saveLocked(all: Map<String, Profile>) {
        try {
            val members = JSONObject()
            for ((memberId, profile) in all) {
                val vecs = JSONArray()
                for (v in profile.vectors) {
                    vecs.put(
                        JSONObject()
                            .put("b64", encodeVector(v.vector))
                            .put("src", if (v.source == Source.EXPLICIT) "explicit" else "implicit")
                            .put("ts", v.timestampMs)
                    )
                }
                members.put(
                    memberId,
                    JSONObject().put("name", profile.memberName).put("vectors", vecs)
                )
            }
            val root = JSONObject().put("version", 1).put("members", members)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save voiceprints", e)
        }
    }

    companion object {
        private const val TAG = "VoiceprintStore"
        const val MAX_VECTORS = 40
        private const val DIR_NAME = "speakerid"
        private const val ENROLLMENT_DIR = "enrollment"
        private const val FILE_NAME = "voiceprints.json"

        fun encodeVector(vector: FloatArray): String {
            val buf = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (f in vector) buf.putFloat(f)
            return Base64.encodeToString(buf.array(), Base64.NO_WRAP)
        }

        fun decodeVector(b64: String): FloatArray {
            val bytes = Base64.decode(b64, Base64.NO_WRAP)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / 4) { buf.getFloat(it * 4) }
        }
    }
}
