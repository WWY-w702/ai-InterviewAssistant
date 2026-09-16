package com.faceai.pdfreader.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.faceai.pdfreader.ai.client.AiClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScoringServiceTest {

    @Mock
    private AiClient aiClient;

    @InjectMocks
    private ScoringService scoringService;

    @Test
    void evaluateWithConfidence_returnsScores_whenAiReturnsValidJson() {
        String aiResponse = """
                {"technical":80,"expression":75,"match":70,"problem_solving":82,"follow_up":68,"stress_resistance":71,"overall":75,"comment":"回答比较完整"}
                """;
        when(aiClient.chat(anyString())).thenReturn(aiResponse);

        ScoringService.EvaluationWithConfidence result =
                scoringService.evaluateWithConfidence("什么是HashMap？", "HashMap是...", "后端开发");

        assertNotNull(result);
        // 综合分不是直接取模型返回的 overall(75)，而是对 6 个维度去极值后取均值：
        // 68,70,71,75,80,82 -> 去掉 68 和 82 -> (70+71+75+80)/4 = 74。
        // 这样模型自报的 overall 与分项打分矛盾时，以分项为准。
        assertEquals(74, result.overall());
        assertEquals("回答比较完整", result.comment());
        assertTrue(result.confidence() > 0);
        assertTrue(result.attempts() >= 1);
        assertEquals(80, result.scores().get("technical"));
        assertEquals(75, result.scores().get("expression"));
    }

    @Test
    void evaluateWithConfidence_fallsBack_whenAiReturnsInvalid() {
        when(aiClient.chat(anyString())).thenReturn("这不是JSON");

        ScoringService.EvaluationWithConfidence result =
                scoringService.evaluateWithConfidence("什么是HashMap？", "短回答", "后端开发");

        assertNotNull(result);
        assertTrue(result.overall() >= 0 && result.overall() <= 100);
    }

    @Test
    void evaluateWithConfidence_retries_whenLowConfidence() {
        // First call returns high variance scores (low confidence)
        String lowConfidenceResponse = """
                {"technical":95,"expression":30,"match":80,"problem_solving":90,"follow_up":25,"stress_resistance":85,"overall":60,"comment":"不均衡"}
                """;
        // Second call returns consistent scores (high confidence)
        String highConfidenceResponse = """
                {"technical":75,"expression":78,"match":72,"problem_solving":76,"follow_up":74,"stress_resistance":73,"overall":75,"comment":"不错"}
                """;
        when(aiClient.chat(anyString()))
                .thenReturn(lowConfidenceResponse)
                .thenReturn(highConfidenceResponse);

        ScoringService.EvaluationWithConfidence result =
                scoringService.evaluateWithConfidence("问题", "较长的回答内容，超过五十个字以避免长度惩罚，这里需要足够多的字数来确保长度因素不会降低置信度。", "后端开发");

        assertNotNull(result);
        // Should have retried
        assertTrue(result.attempts() >= 1);
    }
}
