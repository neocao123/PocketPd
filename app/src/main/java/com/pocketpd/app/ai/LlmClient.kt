package com.pocketpd.app.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Chat-completions client speaking the two wire formats selectable in
 * Settings (no dependencies — the build is offline, so HttpURLConnection +
 * org.json):
 *
 *  - "anthropic" (default): Messages API — POST <base>/v1/messages, auth in
 *    the `x-api-key` header, system prompt as a top-level `system` field,
 *    reply in content[].text.
 *  - "openai": chat/completions — POST <base>/chat/completions, auth in
 *    `Authorization: Bearer`, system prompt as messages[0], reply in
 *    choices[0].message.content.
 *
 * Requests always stream (SSE) so the caller can surface the model's
 * reasoning while it runs: anthropic thinking blocks are requested with the
 * `thinking` parameter (temperature 1, as the API requires; a relay that
 * rejects the parameter with a 400 gets one plain retry), openai-style
 * endpoints stream `delta.reasoning_content`. An endpoint that ignores
 * stream:true and answers a single JSON body still parses via the fallback.
 *
 * One request at a time; [cancel] aborts the in-flight call (and any newer
 * request bumps the epoch, so stale threads stay silent).
 *
 * The system prompt pins the model to the exact .pd text format this app's
 * parser reads (see PatchDocument.parse): header line, #X obj/msg/floatatom/
 * symbolatom/text rows, 0-based #X connect indices in file order.
 */
object LlmClient {

    const val FORMAT_ANTHROPIC = "anthropic"
    const val FORMAT_OPENAI = "openai"

    /** One conversation turn: role "user" or "assistant" (system implied). */
    data class Msg(val role: String, val content: String)

    /**
     * System prompt. Kept lean on purpose: a model that knows pd already
     * knows the .pd format, and one that doesn't won't be saved by prose —
     * so only the constraints THIS parser actually depends on (file shape,
     * row indexing, iemgui empties) plus the two historically most common
     * model errors (intatom/floatatom-as-obj, signal into control inlet)
     * are stated. No dac~-terminated example patches: they biased every
     * generation into wiring a dac~ even for pure modules.
     */
    const val SYSTEM_PROMPT: String =
        "You are a Pure Data (pd) patch generator inside an Android patch " +
        "editor. Reply with ONLY the raw text of a .pd patch file — no " +
        "explanations, no markdown, no code fences.\n" +
        "You know pd; this parser is just strict about file shape, so:\n" +
        "- First line: #N canvas 0 0 450 300 10;\n" +
        "- One row per box, in file order: #X obj <x> <y> <name> <args...>; " +
        "#X msg <x> <y> <words...>; #X text <x> <y> <words...>; number " +
        "boxes MUST be rows like #X floatatom 40 80 5 0 0 0 0 0 0; (never " +
        "\"floatatom\" as an #X obj, never \"intatom\"); symbol boxes the " +
        "same with symbolatom.\n" +
        "- Connections last: #X connect <from> <outlet> <to> <inlet>; all " +
        "indices 0-based, counting EVERY row above (obj, msg, floatatom, " +
        "symbolatom, text) in file order.\n" +
        "- GUI widgets are objects in full iemgui form; the two send/" +
        "receive fields MUST be \"empty\" when unused, never \"0\" or " +
        "\"-\". The toggle is spelled tgl, the bang bng.\n" +
        "- Only signal inlets take ~ outlets; read a signal into the " +
        "control world through snapshot~.\n" +
        "- random/rand.f generate on a BANG (bng, metro, trigger): a float " +
        "into the left inlet only sets the seed, and random always needs a " +
        "range argument. `button` is available: it sends 1 on press, 0 on " +
        "release.\n" +
        "- `euclid hits steps rotation` (steps 1-64) is an app UI widget: " +
        "a circular clock. A BANG into its left inlet fires the current " +
        "step — outlet 0 emits 1 (hit) or 0 (rest), outlet 1 emits the " +
        "hit's index within the circle (0-based, only on hits) — and the " +
        "position advances. A plain float jumps to that step (mod steps) " +
        "and fires it. `hit n` / `step n` / `rotation n` messages (single " +
        "or chained: `hit 3 step 8 rotation 1`) reconfigure it live. Its " +
        "right inlet resets to step 0. Tapping the widget triggers it. " +
        "Use metro/bng to drive its left inlet; wire outlet 0 wherever " +
        "hits gate sound.\n" +
        "- `circle5 key size` is a circle-of-fifths UI widget: 12 positions " +
        "clockwise (1=C, 2=G ...), rings major/minor/diminished. Tapping a " +
        "cell outputs that chord's midi notes as a list (size 3 = triad, " +
        "4 = seventh) and sets the current key: outer ring = that major " +
        "key, middle ring = that relative minor key (key is signed: 1 = C " +
        "major, -1 = a minor — same seven chords, degrees numbered from " +
        "their own tonic, so degree 1 of -1 is am and degree 3 is C). A " +
        "float 1..7 into its inlet emits that diatonic degree's chord. " +
        "`chord c` / `chord am` (= chord -1) / `chord 2` / `size 4` " +
        "messages reconfigure it live. It has one inlet and one outlet.\n" +
        "- `pad width height gridx gridy` is a multi-touch XY pad UI widget. " +
        "Outlet 0 emits the finger group as ONE list: `n x1 y1 e1 x2 y2 e2 " +
        "...` — n is the finger count, then each finger contributes `x y e` " +
        "(x/y normalized 0..1, top-left origin; e: 2 = press, 1 = " +
        "held/dragging, 0 = this finger lifted — it shows once with 0 then " +
        "leaves the group; fingers appear in press order). Split with " +
        "[list split 1 1] for n, then walk strides of 3. Outlet 1 streams " +
        "`col row e` grid-cell events (e: 1 = cell entered, 0 = left; " +
        "slide-follow while dragging). The grid never affects outlet 0. " +
        "Drag output coalesces to one group per frame. `gridx n` / " +
        "`gridy n` / `size w h` messages into its inlet reconfigure it " +
        "live.\n" +
        "- `ym2612~` is a YM2612-style 4-operator FM voice (one chip channel) " +
        "implemented as a coded native object — place the box and connect it. " +
        "Inlet 1: pitch as a midi-note float, a `note <midi> [vel]` message " +
        "(key-on), a `pitch vel` list, or params: `alg 0-7` (routing: 0 " +
        "chain op1>op3>op2>op4 carrier op4, 4 pairs (op1>op3)+(op2>op4) " +
        "carriers op3+op4, 5 op1 fans to op2-4, 7 all four carriers), " +
        "`fb 0-7` (op1 self-feedback), `op k p v` per operator (k 1-4; " +
        "p = mul 0-15 ratio, det 0-7, tl 0-127 attenuation, ar/dr/sr/rr " +
        "0-31 speeds, sl 0-15 sustain attenuation: 0 full 15 silent), " +
        "`gm 1-128` General MIDI Level 1 preset (overrides alg/fb/op, " +
        "leaves dac), `dac 0-100` output grit (9-bit DAC curve + MSB " +
        "bump), `gate 0/1`. Inlet 2: gate float, " +
        "1 = key on, 0 = release. Outlet: audio. Example: feed pitch/gate " +
        "from a keyboard or seq, send the outlet through gain into dac~. " +
        "Multiple `ym2612~` boxes stack like the chip's 6 channels.\n" +
        "Build what the request asks for: add dac~ only when the patch " +
        "should actually sound — a module can end in its own outlets, " +
        "sends, or tables. Available objects: vanilla pd 0.51, the bundled " +
        "libraries else, cyclone, zexy, iemlib, fftease, bsaylor, creb, " +
        "cxc, mrpeach osc/midifile/binfile, tracker, and the vanilla " +
        "extras sigmund~, fiddle~, bonk~, bob~, loop~, lrshift~, pique, " +
        "choice, stdout — no other externals. Spread coordinates so cords " +
        "stay readable. Never wrap in ``` fences, never add prose."

    @Volatile
    private var conn: HttpURLConnection? = null

    /** Bumped by cancel() and by every new request; stale threads go silent. */
    private val epoch = java.util.concurrent.atomic.AtomicLong()

    /** Aborts the in-flight request (no-op when idle). */
    fun cancel() {
        epoch.incrementAndGet()
        conn?.disconnect()
    }

    /** One-shot convenience (Settings probe): [stream] with no deltas. */
    fun complete(
        endpoint: String,
        key: String,
        model: String,
        format: String,
        userMessage: String,
        onResult: (String?, String?) -> Unit,
    ) = stream(endpoint, key, model, format, listOf(Msg("user", userMessage)), {}, onResult)

    /**
     * Streams one chat completion in [format] ("anthropic" | "openai") over
     * the given message history. [onThinking] fires on the worker thread per
     * reasoning chunk as it arrives; [onResult] runs once with exactly one
     * of (replyText, errorMessage) non-null — unless the request was
     * cancelled or superseded, then neither fires.
     */
    fun stream(
        endpoint: String,
        key: String,
        model: String,
        format: String,
        messages: List<Msg>,
        onThinking: (String) -> Unit,
        onResult: (String?, String?) -> Unit,
    ) {
        val my = epoch.incrementAndGet()
        val anthropic = format == FORMAT_ANTHROPIC
        Thread {
            var c: HttpURLConnection? = null
            try {
                // strict anthropic endpoints require temperature 1 when the
                // thinking parameter is on; relays that don't know the
                // parameter answer 400 and get one plain retry (0.2 back)
                var thinking = anthropic
                c = open(my, endpoint, key, model, format, messages, thinking)
                var code = c.responseCode
                if (code == 400 && anthropic) {
                    val body = errorBody(c)
                    if (epoch.get() != my) return@Thread
                    if (thinking && body.lowercase().contains("thinking")) {
                        c.disconnect()
                        thinking = false
                        c = open(my, endpoint, key, model, format, messages, false)
                        code = c.responseCode
                    } else {
                        onResult(null, "HTTP 400: ${body.take(200)}")
                        return@Thread
                    }
                }
                if (epoch.get() != my) return@Thread
                if (code !in 200..299) {
                    onResult(null, httpError(c, code))
                    return@Thread
                }
                consumeStream(c, anthropic, my, onThinking, onResult)
            } catch (e: Exception) {
                if (epoch.get() != my) return@Thread // cancelled or superseded
                onResult(null, e.message ?: e.javaClass.simpleName)
            } finally {
                c?.disconnect()
                if (epoch.get() == my) conn = null
            }
        }.start()
    }

    /** Builds the stream request body and returns the connected connection. */
    private fun open(
        epochMark: Long,
        endpoint: String,
        key: String,
        model: String,
        format: String,
        messages: List<Msg>,
        thinking: Boolean,
    ): HttpURLConnection {
        val anthropic = format == FORMAT_ANTHROPIC
        val c = URL(normalizeEndpoint(format, endpoint)).openConnection()
            as HttpURLConnection
        conn = c
        c.requestMethod = "POST"
        c.connectTimeout = 10_000
        // reasoning models can pause well past a minute before the next
        // SSE line; a read timeout here would kill a healthy stream
        c.readTimeout = 300_000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        if (key.isNotEmpty()) {
            if (anthropic) {
                c.setRequestProperty("x-api-key", key)
                c.setRequestProperty("anthropic-version", "2023-06-01")
            } else {
                c.setRequestProperty("Authorization", "Bearer $key")
            }
        }
        val turns = JSONArray().apply {
            for (m in messages) {
                put(JSONObject().put("role", m.role).put("content", m.content))
            }
        }
        val body = JSONObject().apply {
            put("model", model)
            put("stream", true)
            if (anthropic) {
                put("max_tokens", if (thinking) 8192 else 4096)
                put("temperature", if (thinking) 1 else 0.2)
                if (thinking) {
                    put(
                        "thinking",
                        JSONObject().put("type", "enabled").put("budget_tokens", 2048),
                    )
                }
                put("system", SYSTEM_PROMPT)
                put("messages", turns)
            } else {
                put("temperature", 0.2)
                put(
                    "messages",
                    JSONArray().put(
                        JSONObject().put("role", "system").put("content", SYSTEM_PROMPT)
                    ).apply { for (m in messages) put(JSONObject().put("role", m.role).put("content", m.content)) },
                )
            }
        }
        c.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
        return c
    }

    /**
     * Reads the SSE event stream: feeds reasoning chunks to [onThinking],
     * accumulates the reply text, and ends with exactly one [onResult].
     * A body with no SSE framing at all is re-parsed as a plain JSON
     * reply (endpoint ignored stream:true).
     */
    private fun consumeStream(
        c: HttpURLConnection,
        anthropic: Boolean,
        my: Long,
        onThinking: (String) -> Unit,
        onResult: (String?, String?) -> Unit,
    ) {
        val text = StringBuilder()
        val raw = StringBuilder()
        var sawSse = false
        var streamError: String? = null
        // one reader for the whole stream — a fresh one per line would drop
        // whatever its predecessor buffered ahead
        val reader = c.inputStream.bufferedReader(StandardCharsets.UTF_8)
        loop@ while (true) {
            val line = reader.readLine() ?: break
            if (epoch.get() != my) return
            raw.append(line).append('\n')
            if (!line.startsWith("data:")) continue
            sawSse = true
            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]") break
            val obj = runCatching { JSONObject(payload) }.getOrNull() ?: continue
            if (anthropic) {
                when (obj.optString("type")) {
                    "content_block_delta" -> {
                        val d = obj.optJSONObject("delta") ?: continue@loop
                        when (d.optString("type")) {
                            "thinking_delta" -> {
                                val t = d.textOrEmpty("thinking")
                                if (t.isNotEmpty()) onThinking(t)
                            }
                            "text_delta" -> text.append(d.textOrEmpty("text"))
                        }
                    }
                    "error" -> {
                        streamError =
                            obj.optJSONObject("error")?.optString("message")
                                ?.takeIf { it.isNotEmpty() } ?: "unspecified stream error"
                        break@loop
                    }
                    "message_stop" -> break@loop
                }
            } else {
                val d = obj.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("delta") ?: continue@loop
                val r = d.textOrEmpty("reasoning_content")
                if (r.isNotEmpty()) onThinking(r)
                text.append(d.textOrEmpty("content"))
            }
        }
        if (epoch.get() != my) return
        streamError?.let { onResult(null, "stream error: $it"); return }
        val reply = if (sawSse) text.toString()
        else runCatching { parseReply(raw.toString(), anthropic) }.getOrNull()
        if (reply.isNullOrBlank()) {
            onResult(
                null,
                if (reply == null) "bad JSON reply: ${raw.take(120)}"
                else "empty reply in stream",
            )
        } else {
            onResult(stripFences(reply), null)
        }
    }

    /**
     * org.json's optString returns the literal STRING "null" for a JSON
     * null value — and GLM-style streams carry `"content": null` on every
     * reasoning delta and `"reasoning_content": null` on every content
     * delta. Read through optString, that filled the THINK line and the
     * front of every reply with "null" repeated. This accessor treats
     * JSON null as empty, like every missing key.
     */
    private fun JSONObject.textOrEmpty(key: String): String =
        if (isNull(key)) "" else optString(key)

    /** Pulls the text out of one API's response envelope. */
    private fun parseReply(text: String, anthropic: Boolean): String? {
        if (!anthropic) {
            val msg = JSONObject(text)
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message") ?: return ""
            return msg.textOrEmpty("content")
        }
        val content = JSONObject(text).optJSONArray("content") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") sb.append(block.textOrEmpty("text"))
        }
        return sb.toString()
    }

    /**
     * Accepts full URLs or bare bases; appends the format's completions
     * path when it is missing. Existing /v1-style endings are respected.
     */
    fun normalizeEndpoint(format: String, raw: String): String {
        var url = raw.trim().trimEnd('/')
        if (format == FORMAT_ANTHROPIC) {
            if (!url.endsWith("/messages")) {
                url += if (url.endsWith("/v1")) "/messages" else "/v1/messages"
            }
        } else if (!url.endsWith("/chat/completions")) {
            url += "/chat/completions"
        }
        return url
    }

    /** Raw error body for the 400-retry decision (empty when unreadable). */
    private fun errorBody(c: HttpURLConnection): String = runCatching {
        c.errorStream?.bufferedReader(StandardCharsets.UTF_8)
            ?.use(BufferedReader::readText)
    }.getOrNull().orEmpty()

    /** Best-effort message from an error body: {"error":{"message":…}}. */
    private fun httpError(c: HttpURLConnection, code: Int): String {
        val body = errorBody(c)
        if (body.isNotBlank()) {
            runCatching {
                val msg = JSONObject(body).optJSONObject("error")?.optString("message")
                if (!msg.isNullOrBlank()) return "HTTP $code: $msg"
            }
            return "HTTP $code: ${body.take(200)}"
        }
        return "HTTP $code"
    }

    /**
     * Models like to fence their output despite instructions; if the reply
     * contains a ``` block, keep only its content (preferring a pd-tagged
     * fence), otherwise the raw reply.
     */
    fun stripFences(reply: String): String {
        val fences = Regex("```([A-Za-z0-9+#]*)\\s*\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
            .findAll(reply).toList()
        if (fences.isEmpty()) return reply.trim()
        val pd = fences.firstOrNull {
            val tag = it.groupValues[1].lowercase()
            tag in setOf("pd", "puredata", "pure-data", "")
        }
        return (pd ?: fences.first()).groupValues[2].trim()
    }
}
