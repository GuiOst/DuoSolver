package com.guiost.duosolver

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Gemini {

    class GeminiException(msg: String) : Exception(msg)

    fun ask(apiKey: String, model: String, prompt: String): String {
        return try {
            call(apiKey, model, prompt, disableThinking = true)
        } catch (e: GeminiException) {
            // Alguns modelos não aceitam thinkingBudget=0; tenta de novo sem ele.
            if (e.message?.startsWith("HTTP 400") == true) call(apiKey, model, prompt, disableThinking = false)
            else throw e
        }
    }

    private fun call(apiKey: String, model: String, prompt: String, disableThinking: Boolean): String {
        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                URLEncoder.encode(model, "UTF-8") + ":generateContent?key=" + URLEncoder.encode(apiKey, "UTF-8")
        )
        val gen = JSONObject()
            .put("temperature", 0)
            .put("responseMimeType", "application/json")
        if (disableThinking) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))

        val body = JSONObject()
            .put(
                "contents", JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "parts", JSONArray().put(JSONObject().put("text", prompt))
                    )
                )
            )
            .put("generationConfig", gen)

        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 40000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) throw GeminiException("HTTP $code: ${text.take(200)}")

        val json = JSONObject(text)
        val parts = json.getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (!p.optBoolean("thought", false)) sb.append(p.optString("text"))
        }
        return sb.toString()
    }
}
