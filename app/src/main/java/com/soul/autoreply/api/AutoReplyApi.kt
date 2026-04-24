package com.soul.autoreply.api

import android.content.Context
import com.google.gson.Gson
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object AutoReplyApi {

    private var cachedKey: String? = null

    // Chat style/persona options
    data class ChatStyle(val id: String, val name: String, val description: String, val systemPrompt: String)

    val CHAT_STYLES = listOf(
        ChatStyle(
            id = "friendly",
            name = "友善聊天",
            description = "温暖友好，像朋友一样自然对话",
            systemPrompt = "你是一个温暖友善的朋友，正在和好友轻松聊天。语气自然、亲切、幽默。不要过于正式，用口语化的方式回复。"
        ),
        ChatStyle(
            id = "flirty",
            name = "撩人暧昧",
            description = "带点小暧昧，适合交友场景",
            systemPrompt = "你是一个有魅力、会撩人的聊天高手，擅长制造心动感。语气暧昧、调皮、有趣。适当使用表情和语气词，保持神秘感和吸引力。"
        ),
        ChatStyle(
            id = "cute",
            name = "可爱萌系",
            description = "撒娇可爱，活泼俏皮",
            systemPrompt = "你是一个可爱撒娇系的聊天对象，说话带点萌感，喜欢用语气词和表情。语气活泼、可爱、有点小撒娇。"
        ),
        ChatStyle(
            id = "mature",
            name = "成熟稳重",
            description = "成熟体贴，会关心人",
            systemPrompt = "你是一个成熟稳重、有内涵的聊天对象。说话得体、体贴、关心对方。语气温和但不失幽默，给人有安全感的感觉。"
        ),
        ChatStyle(
            id = "mystery",
            name = "神秘感",
            description = "保持神秘，让人好奇",
            systemPrompt = "你是一个充满神秘感的人，说话不多但每句都耐人寻味。不会轻易透露自己的事，喜欢制造悬念和好奇感。回复简洁有力。"
        ),
        ChatStyle(
            id = "witty",
            name = "机智风趣",
            description = "幽默风趣，妙语连珠",
            systemPrompt = "你是一个幽默风趣的人，擅长开玩笑和调侃。反应敏捷、妙语连珠、善于制造轻松氛围。偶尔自嘲，但不失魅力。"
        )
    )

    // All supported models with official base URLs
    val ALL_MODELS = listOf(
        // === GLM (智谱) - https://open.bigmodel.cn ===
        ModelInfo("glm-4-flash",    "GLM-4-Flash",    "https://open.bigmodel.cn/api/paas/v4/chat/completions",   authType = AuthType.BEARER),
        ModelInfo("glm-4",           "GLM-4",          "https://open.bigmodel.cn/api/paas/v4/chat/completions",   authType = AuthType.BEARER),
        ModelInfo("glm-4-plus",      "GLM-4-Plus",     "https://open.bigmodel.cn/api/paas/v4/chat/completions",   authType = AuthType.BEARER),
        ModelInfo("glm-3-turbo",    "GLM-3-Turbo",    "https://open.bigmodel.cn/api/paas/v4/chat/completions",   authType = AuthType.BEARER),

        // === DeepSeek - https://api.deepseek.com ===
        ModelInfo("deepseek-chat",    "DeepSeek-Chat",   "https://api.deepseek.com/chat/completions",             authType = AuthType.BEARER),
        ModelInfo("deepseek-coder",   "DeepSeek-Coder",  "https://api.deepseek.com/chat/completions",             authType = AuthType.BEARER),
        ModelInfo("deepseek-reasoner", "DeepSeek-R1",    "https://api.deepseek.com/chat/completions",             authType = AuthType.BEARER),

        // === Qwen (通义千问) - https://dashscope.aliyuncs.com ===
        ModelInfo("qwen-turbo",      "Qwen-Turbo",      "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", authType = AuthType.BEARER),
        ModelInfo("qwen-plus",       "Qwen-Plus",       "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", authType = AuthType.BEARER),
        ModelInfo("qwen-max",        "Qwen-Max",        "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", authType = AuthType.BEARER),
        ModelInfo("qwen-long",       "Qwen-Long",       "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", authType = AuthType.BEARER),
        ModelInfo("qwen-coder-plus", "Qwen-Coder-Plus", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", authType = AuthType.BEARER),

        // === Minimax - https://api.minimax.chat ===
        ModelInfo("MiniMax-Chat",    "Minimax-Chat",    "https://api.minimax.chat/v1/text/chatcompletion_v2",      authType = AuthType.MINIMAX),
        ModelInfo("abab6-chat",      "ABAB-6-Chat",     "https://api.minimax.chat/v1/text/chatcompletion_v2",      authType = AuthType.MINIMAX),
    )

    enum class AuthType { BEARER, MINIMAX }

    data class ModelInfo(
        val id: String,
        val displayName: String,
        val baseUrl: String,
        val authType: AuthType = AuthType.BEARER
    )

    fun getCurrentModel(context: Context): ModelInfo {
        val modelId = context.getSharedPreferences("soul_reply_prefs", Context.MODE_PRIVATE)
            .getString("model", "glm-4-flash") ?: "glm-4-flash"
        return ALL_MODELS.find { it.id == modelId } ?: ALL_MODELS.first()
    }

    fun saveModel(context: Context, modelId: String) {
        context.getSharedPreferences("soul_reply_prefs", Context.MODE_PRIVATE)
            .edit().putString("model", modelId).apply()
    }

    fun getCurrentStyle(context: Context): ChatStyle {
        val styleId = context.getSharedPreferences("soul_reply_prefs", Context.MODE_PRIVATE)
            .getString("chat_style", "friendly") ?: "friendly"
        return CHAT_STYLES.find { it.id == styleId } ?: CHAT_STYLES.first()
    }

    fun saveStyle(context: Context, styleId: String) {
        context.getSharedPreferences("soul_reply_prefs", Context.MODE_PRIVATE)
            .edit().putString("chat_style", styleId).apply()
    }

    fun getApiKey(context: Context): String {
        if (cachedKey != null) return cachedKey!!
        return context.getSharedPreferences("soul_reply_prefs", Context.MODE_PRIVATE)
            .getString("api_key", "") ?: "".also { cachedKey = it }
    }

    fun saveApiKey(context: Context, key: String) {
        cachedKey = key
        context.getSharedPreferences("soul_reply_prefs", Context.MODE_PRIVATE)
            .edit().putString("api_key", key).apply()
    }

    fun hasApiKey(context: Context): Boolean = getApiKey(context).isNotBlank()

    fun sendReply(context: Context, message: String, callback: (String) -> Unit) {
        Thread {
            try {
                val apiKey = getApiKey(context)
                if (apiKey.isBlank()) {
                    callback("请先配置API Key")
                    return@Thread
                }

                val model = getCurrentModel(context)
                val style = getCurrentStyle(context)
                val messages = listOf(
                    mapOf("role" to "system", "content" to style.systemPrompt),
                    mapOf("role" to "user", "content" to message)
                )
                val request = mapOf("model" to model.id, "messages" to messages)
                val json = Gson().toJson(request)

                val url = URL(model.baseUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 30000
                conn.readTimeout = 60000

                when (model.authType) {
                    AuthType.BEARER -> conn.setRequestProperty("Authorization", "Bearer $apiKey")
                    AuthType.MINIMAX -> {
                        conn.setRequestProperty("Authorization", "Bearer $apiKey")
                    }
                }

                conn.outputStream.use { os ->
                    OutputStreamWriter(os, StandardCharsets.UTF_8).use { writer ->
                        writer.write(json)
                        writer.flush()
                    }
                }

                val responseCode = conn.responseCode
                val reader = BufferedReader(InputStreamReader(
                    if (responseCode in 200..299) conn.inputStream else conn.errorStream,
                    StandardCharsets.UTF_8
                ))
                val response = reader.readText()
                reader.close()

                if (responseCode in 200..299) {
                    val chatResp = Gson().fromJson(response, ChatResponse::class.java)
                    val reply = chatResp?.choices?.firstOrNull()?.message?.content
                    callback(reply?.trim() ?: "服务器返回为空")
                } else {
                    callback("API错误 ($responseCode): ${response.take(150)}")
                }
                conn.disconnect()
            } catch (e: Exception) {
                callback("网络错误: ${e.message}")
            }
        }.start()
    }

    data class ChatResponse(val choices: List<Choice>)
    data class Choice(val message: Message)
    data class Message(val role: String, val content: String)
}
