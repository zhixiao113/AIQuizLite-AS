package com.example.aiquizlite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MaterialDocumentAnalysisTest {
    @Test
    public void parsesLearningMaterialRecommendationFromAiJson() throws Exception {
        MaterialDocumentAnalysis analysis = MaterialDocumentAnalysis.fromModelText(
                "```json\n{\"documentKind\":\"learning_material\","
                        + "\"confidence\":0.92,\"reason\":\"以知识讲解为主\","
                        + "\"recommendedQuestionCount\":12,"
                        + "\"typeCounts\":{\"single\":5,\"multi\":3,\"judge\":2,\"fill_blank\":2}}\n```"
        );

        assertFalse(analysis.isQuestionBank());
        assertEquals(12, analysis.getRecommendedQuestionCount());
        assertEquals(5, (int) analysis.getTypeQuotas().get(MaterialQuestionRules.SINGLE));
        assertEquals("以知识讲解为主", analysis.getReason());
    }

    @Test
    public void recognizesQuestionBankAliases() throws Exception {
        MaterialDocumentAnalysis analysis = MaterialDocumentAnalysis.fromModelText(
                "{\"documentKind\":\"题库\",\"confidence\":1,"
                        + "\"reason\":\"包含题干、选项和答案\",\"detectedQuestionCount\":36}"
        );

        assertTrue(analysis.isQuestionBank());
        assertEquals(36, analysis.getDetectedQuestionCount());
    }

    @Test
    public void allowsLearningChunkWithoutTestableKnowledgePoints() throws Exception {
        MaterialDocumentAnalysis analysis = MaterialDocumentAnalysis.fromModelText(
                "{\"documentKind\":\"learning_material\",\"recommendedQuestionCount\":0,"
                        + "\"typeCounts\":{\"single\":0,\"multi\":0,\"judge\":0,\"fill_blank\":0}}"
        );

        assertEquals(0, analysis.getRecommendedQuestionCount());
        assertTrue(analysis.getTypeQuotas().isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsResponseWithoutJsonObject() throws Exception {
        MaterialDocumentAnalysis.fromModelText("这是一份学习资料");
    }
}
