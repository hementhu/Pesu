package app.pesu.tamil

import android.net.Uri
import android.webkit.JavascriptInterface
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs Gemma 4 E2B on the phone with Google's LiteRT-LM runtime.
 *
 * Exposed to the page as `window.Local`. Replies stream back through the same callbacks the
 * cloud path uses (NativeCb.onHttpChunk / onHttpEnd, OpenAI-style deltas), so the tutor logic
 * in index.html doesn't care where the model runs. Status events go to NativeCb.onLocal.
 */
class LocalLlm(private val act: MainActivity) {

    private val modelDir = File(act.filesDir, "models")
    private val modelFile = File(modelDir, "gemma-4-E2B-it.litertlm")
    private val partFile = File(modelDir, "gemma-4-E2B-it.litertlm.part")

    // Engine work is serialized on one thread; cancel() is called directly from the JS thread.
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var loading = false
    @Volatile private var backendName = ""
    @Volatile private var lastError = ""
    @Volatile private var downloading = false
    @Volatile private var stopDownload = false
    @Volatile private var doneBytes = 0L
    @Volatile private var totalBytes = 0L

    // ---------------- status ----------------

    @JavascriptInterface
    fun status(): String = JSONObject().apply {
        put("downloaded", modelFile.exists() && modelFile.length() > 0)
        put("bytes", if (modelFile.exists()) modelFile.length() else partFile.length())
        put("downloading", downloading)
        put("done", doneBytes)
        put("total", totalBytes)
        put("loading", loading)
        put("ready", engine != null)
        put("backend", backendName)
        put("error", lastError)
        put("freeBytes", act.filesDir.usableSpace)
    }.toString()

    // ---------------- download (resumable) ----------------

    @JavascriptInterface
    fun download(url: String) {
        if (downloading || modelFile.exists()) return
        downloading = true
        stopDownload = false
        lastError = ""
        Thread {
            var conn: HttpURLConnection? = null
            try {
                modelDir.mkdirs()
                var have = if (partFile.exists()) partFile.length() else 0L
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 20000
                    readTimeout = 60000
                    if (have > 0) setRequestProperty("Range", "bytes=$have-")
                }
                val code = conn.responseCode
                if (code == 200) have = 0L
                else if (code != 206) throw RuntimeException("Download failed (HTTP $code)")
                val length = conn.contentLengthLong
                totalBytes = if (length > 0) have + length else 0L
                doneBytes = have
                if (length > 0 && act.filesDir.usableSpace < length + 300L * 1024 * 1024) {
                    throw RuntimeException("Not enough free space: the model needs about ${length / (1024 * 1024)} MB.")
                }
                conn.inputStream.use { input ->
                    FileOutputStream(partFile, have > 0).use { out -> copy(input, out) }
                }
                if (stopDownload) {
                    act.call("onLocal", "cancelled", "")
                } else {
                    if (!partFile.renameTo(modelFile)) throw RuntimeException("Couldn't save the model file.")
                    act.call("onLocal", "downloaded", "")
                }
            } catch (t: Throwable) {
                lastError = t.message ?: t.toString()
                act.call("onLocal", "error", lastError)
            } finally {
                conn?.disconnect()
                downloading = false
            }
        }.start()
    }

    private fun copy(input: InputStream, out: FileOutputStream) {
        val buf = ByteArray(1 shl 20)
        var lastReport = 0L
        while (!stopDownload) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            doneBytes += n
            val now = System.currentTimeMillis()
            if (now - lastReport > 500) {
                lastReport = now
                act.call("onLocal", "progress", JSONObject().put("done", doneBytes).put("total", totalBytes).toString())
            }
        }
    }

    @JavascriptInterface
    fun cancelDownload() {
        stopDownload = true
    }

    /** Copy a .litertlm file the user picked (e.g. downloaded in the browser) into app storage. */
    fun importFrom(uri: Uri) {
        if (downloading) return
        downloading = true
        stopDownload = false
        lastError = ""
        Thread {
            try {
                modelDir.mkdirs()
                totalBytes = act.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                doneBytes = 0L
                act.contentResolver.openInputStream(uri)!!.use { input ->
                    FileOutputStream(partFile, false).use { out -> copy(input, out) }
                }
                if (stopDownload) act.call("onLocal", "cancelled", "")
                else {
                    if (!partFile.renameTo(modelFile)) throw RuntimeException("Couldn't save the model file.")
                    act.call("onLocal", "downloaded", "")
                }
            } catch (t: Throwable) {
                lastError = t.message ?: t.toString()
                act.call("onLocal", "error", lastError)
            } finally {
                downloading = false
            }
        }.start()
    }

    @JavascriptInterface
    fun delete() {
        worker.execute {
            closeAll()
            modelFile.delete()
            partFile.delete()
            doneBytes = 0; totalBytes = 0; lastError = ""
            act.call("onLocal", "deleted", "")
        }
    }

    // ---------------- engine ----------------

    @JavascriptInterface
    fun load() {
        if (engine != null || loading || !modelFile.exists()) return
        loading = true
        lastError = ""
        worker.execute {
            try {
                engine = startEngine()
                act.call("onLocal", "ready", backendName)
            } catch (t: Throwable) {
                lastError = "Couldn't start the phone model: " + (t.message ?: t.toString())
                act.call("onLocal", "error", lastError)
            } finally {
                loading = false
            }
        }
    }

    /** GPU first (fast), then CPU (works everywhere). Text only: vision/audio would cost extra memory. */
    private fun startEngine(): Engine {
        var last: Throwable? = null
        for (backend in listOf<Backend>(Backend.GPU(), Backend.CPU())) {
            try {
                val e = Engine(
                    EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = backend,
                        maxNumTokens = 8192,
                        cacheDir = act.cacheDir.absolutePath,
                    )
                )
                e.initialize()
                backendName = backend.name
                return e
            } catch (t: Throwable) {
                last = t
            }
        }
        throw last ?: RuntimeException("unknown error")
    }

    /**
     * One tutor turn. `json` = {sessionId, system, history:[{role:"user"|"assistant", content}], message, temperature}.
     * A conversation is kept per sessionId so the model doesn't re-read the whole chat every turn;
     * a new sessionId (or an empty history on a fresh id) starts over.
     */
    @JavascriptInterface
    fun chat(reqId: String, json: String) {
        worker.execute {
            val eng = engine
            if (eng == null) {
                act.call("onHttpEnd", reqId, 503, err("The phone model isn't loaded yet."))
                return@execute
            }
            try {
                val o = JSONObject(json)
                val sid = o.getString("sessionId")
                if (sid != sessionId || conversation == null) {
                    try { conversation?.close() } catch (_: Throwable) {}
                    val initial = mutableListOf<Message>()
                    val hist = o.optJSONArray("history") ?: JSONArray()
                    for (i in 0 until hist.length()) {
                        val m = hist.getJSONObject(i)
                        val text = m.getString("content")
                        initial.add(if (m.getString("role") == "assistant") Message.model(text) else Message.user(text))
                    }
                    conversation = eng.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(o.getString("system")),
                            initialMessages = initial,
                            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = o.optDouble("temperature", 0.7)),
                        )
                    )
                    sessionId = sid
                }
                val conv = conversation!!
                val acc = StringBuilder()
                val finished = CountDownLatch(1)
                conv.sendMessageAsync(Message.user(o.getString("message")), object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val text = message.toString()
                        // Works whether the runtime streams new pieces or the whole text so far.
                        val delta = if (acc.isNotEmpty() && text.startsWith(acc)) text.substring(acc.length) else text
                        if (delta.isEmpty()) return
                        acc.append(delta)
                        val chunk = JSONObject().put(
                            "choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", delta)))
                        )
                        act.call("onHttpChunk", reqId, chunk.toString())
                    }

                    override fun onDone() {
                        act.call("onHttpEnd", reqId, 200, "")
                        finished.countDown()
                    }

                    override fun onError(throwable: Throwable) {
                        sessionId = null
                        act.call("onHttpEnd", reqId, 500, err(throwable.message ?: throwable.toString()))
                        finished.countDown()
                    }
                })
                if (!finished.await(180, TimeUnit.SECONDS)) {
                    try { conv.cancelProcess() } catch (_: Throwable) {}
                    sessionId = null
                    act.call("onHttpEnd", reqId, 504, err("The phone model took too long."))
                }
            } catch (t: Throwable) {
                sessionId = null
                act.call("onHttpEnd", reqId, 500, err(t.message ?: t.toString()))
            }
        }
    }

    @JavascriptInterface
    fun cancel() {
        try { conversation?.cancelProcess() } catch (_: Throwable) {}
        sessionId = null
    }

    @JavascriptInterface
    fun resetSession() {
        sessionId = null
    }

    private fun err(msg: String) = JSONObject().put("error", JSONObject().put("message", msg)).toString()

    private fun closeAll() {
        try { conversation?.close() } catch (_: Throwable) {}
        conversation = null
        sessionId = null
        try { engine?.close() } catch (_: Throwable) {}
        engine = null
        backendName = ""
    }

    fun shutdown() {
        stopDownload = true
        worker.execute { closeAll() }
        worker.shutdown()
    }
}
