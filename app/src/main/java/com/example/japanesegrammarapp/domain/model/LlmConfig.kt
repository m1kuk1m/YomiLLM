package com.example.japanesegrammarapp.domain.model

object LlmConfig {
    val providers = listOf("Gemini", "Vertex AI", "DeepSeek", "Qwen", "OpenAI Compatible")

    val defaultUrls = mapOf(
        "Gemini" to "https://generativelanguage.googleapis.com/v1beta",
        "Vertex AI" to "https://aiplatform.googleapis.com/v1/publishers/google",
        "DeepSeek" to "https://api.deepseek.com",
        "Qwen" to "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "OpenAI Compatible" to "https://api.openai.com/v1"
    )

    val defaultModels = mapOf(
        "Gemini" to listOf("gemini-3.5-flash", "gemini-3.1-flash-lite", "gemini-2.5-flash", "gemini-2.5-pro", "gemini-1.5-flash", "gemini-1.5-pro"),
        "Vertex AI" to listOf("gemini-1.5-flash", "gemini-1.5-pro"),
        "DeepSeek" to listOf("deepseek-chat", "deepseek-coder"),
        "Qwen" to listOf("qwen-max", "qwen-plus", "qwen-turbo", "qwen-long", "qwen2.5-72b-instruct", "qwen2.5-32b-instruct", "qwen2.5-14b-instruct", "qwen2.5-7b-instruct"),
        "OpenAI Compatible" to listOf("gpt-4o", "gpt-4o-mini", "gpt-3.5-turbo")
    )

    val qwenKnownModels = listOf(
        "qwen-max", "qwen-max-latest",
        "qwen-plus", "qwen-plus-latest",
        "qwen-turbo", "qwen-turbo-latest",
        "qwen-long",
        "qwen-vl-max", "qwen-vl-plus",
        "qwen2.5-72b-instruct", "qwen2.5-32b-instruct", "qwen2.5-14b-instruct", "qwen2.5-7b-instruct"
    )

    val geminiKnownModels = listOf(
        "gemini-3.5-flash",
        "gemini-3.1-flash-lite",
        "gemini-2.5-flash",
        "gemini-2.5-pro",
        "gemini-2.5-flash-lite",
        "gemini-1.5-flash",
        "gemini-1.5-pro"
    )

    private val GEMINI_MAJOR_VERSION_REGEX = Regex("""gemini-(\d+)(?:\.(\d+))?""")
    private val OPENAI_REASONING_MODEL_REGEX = Regex("""(^|[/:\-])(o[134]($|[-._])|gpt-5($|[-._]))""")
    private val GPT5_MODEL_REGEX = Regex("""(^|[/:\-])gpt-5($|[-._])""")

    fun isGemini3OrLater(modelName: String): Boolean {
        val clean = modelName.removePrefix("models/").lowercase()
        val major = GEMINI_MAJOR_VERSION_REGEX.find(clean)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return major != null && major >= 3
    }

    fun isGeminiReasoningModel(modelName: String): Boolean {
        val clean = modelName.removePrefix("models/").lowercase()
        if (clean.contains("gemini-1.5")) return false
        return isGemini3OrLater(clean) || clean.contains("gemini-2.5") || clean.contains("flash-lite")
    }

    fun isGemini3Model(modelName: String): Boolean = isGemini3OrLater(modelName)

    fun resolveGeminiTemperature(modelName: String): Double? =
        if (isGemini3OrLater(modelName)) null else 0.1

    fun isOpenAiReasoningModel(modelName: String): Boolean =
        OPENAI_REASONING_MODEL_REGEX.containsMatchIn(modelName.trim().lowercase())

    fun resolveOpenAiTemperature(modelName: String): Double? =
        if (isOpenAiReasoningModel(modelName)) null else 0.1

    fun resolveOpenAiReasoningEffort(modelName: String, level: ReasoningLevel): String? {
        if (!isOpenAiReasoningModel(modelName)) return null
        val isGpt5 = GPT5_MODEL_REGEX.containsMatchIn(modelName.trim().lowercase())
        return when (level) {
            ReasoningLevel.AUTO -> null
            ReasoningLevel.OFF -> if (isGpt5) "minimal" else null
            ReasoningLevel.LOW -> "low"
            ReasoningLevel.MEDIUM -> "medium"
            ReasoningLevel.HIGH -> "high"
        }
    }
}
