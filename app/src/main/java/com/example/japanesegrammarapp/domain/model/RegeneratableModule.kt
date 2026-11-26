package com.example.japanesegrammarapp.domain.model

/**
 * 可由用户针对已分析记录单独触发重新生成的模块。
 */
enum class RegeneratableModule {
    /** 仅重新解析词义/读音/词性（保留当前分词，重跑 SEGMENTS + 可选 DECISION_POS） */
    SEGMENTS_ONLY,

    /** 重新分词并完整解析词义与词性（重跑 TOKENIZATION → SEGMENTS + 可选 DECISION_POS） */
    TOKENIZATION_AND_SEGMENTS,

    /** 全文翻译（重跑 TRANSLATION） */
    TRANSLATION,

    /** 句子结构与解释（重跑 CLAUSES） */
    CLAUSES,

    /** 语法要点（重跑 GRAMMAR） */
    GRAMMAR;

    /**
     * 互斥分组：同组模块不能同时重生成；不同组（如翻译与语法要点）允许并发重生成。
     */
    val conflictGroup: String
        get() = when (this) {
            SEGMENTS_ONLY, TOKENIZATION_AND_SEGMENTS -> GROUP_VOCABULARY
            TRANSLATION -> GROUP_TRANSLATION
            CLAUSES -> GROUP_CLAUSES
            GRAMMAR -> GROUP_GRAMMAR
        }

    companion object {
        const val GROUP_VOCABULARY = "VOCABULARY"
        const val GROUP_TRANSLATION = "TRANSLATION"
        const val GROUP_CLAUSES = "CLAUSES"
        const val GROUP_GRAMMAR = "GRAMMAR"
    }
}
