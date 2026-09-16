package com.faceai.pdfreader.service;

import com.faceai.pdfreader.ai.client.AiClient;
import com.faceai.pdfreader.auth.AuthContext;
import com.faceai.pdfreader.model.InterviewAnswerRequest;
import com.faceai.pdfreader.model.InterviewQuestionRecord;
import com.faceai.pdfreader.model.InterviewReportResponse;
import com.faceai.pdfreader.model.InterviewReportSummaryResponse;
import com.faceai.pdfreader.model.InterviewSessionResponse;
import com.faceai.pdfreader.model.InterviewStartRequest;
import com.faceai.pdfreader.model.InterviewTurnRecord;
import com.faceai.pdfreader.model.InterviewTurnResponse;
import com.faceai.pdfreader.repository.InterviewQuestionRepository;
import com.faceai.pdfreader.repository.InterviewRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

@Service
public class InterviewService {

    private static final int MAX_QUESTION_COUNT = 5;
    private static final int RESUME_TEXT_LIMIT = 4000;
    private static final int JD_TEXT_LIMIT = 2000;
    private static final Pattern SCORE_PATTERN = Pattern.compile("(?:分数|score)\\s*[:：]\\s*(\\d{1,3})", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMENT_PATTERN = Pattern.compile("(?:点评|comment)\\s*[:：]\\s*([\\s\\S]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern JSON_PATTERN = Pattern.compile("\\{[\\s\\S]*}");

    private final AiClient aiClient;
    private final InterviewQuestionRepository interviewQuestionRepository;
    private final InterviewRepository interviewRepository;
    private final DocumentService documentService;
    private final InterviewerService interviewerService;
    private final GamificationService gamificationService;
    private final EloRatingService eloRatingService;
    private final ScoringService scoringService;
    private final KnowledgeGraphService knowledgeGraphService;
    private final com.faceai.pdfreader.rag.service.InterviewRagService interviewRagService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public InterviewService(
            AiClient aiClient,
            InterviewQuestionRepository interviewQuestionRepository,
            InterviewRepository interviewRepository,
            DocumentService documentService,
            InterviewerService interviewerService,
            GamificationService gamificationService,
            EloRatingService eloRatingService,
            ScoringService scoringService,
            KnowledgeGraphService knowledgeGraphService,
            com.faceai.pdfreader.rag.service.InterviewRagService interviewRagService
    ) {
        this.aiClient = aiClient;
        this.interviewQuestionRepository = interviewQuestionRepository;
        this.interviewRepository = interviewRepository;
        this.documentService = documentService;
        this.interviewerService = interviewerService;
        this.gamificationService = gamificationService;
        this.eloRatingService = eloRatingService;
        this.scoringService = scoringService;
        this.knowledgeGraphService = knowledgeGraphService;
        this.interviewRagService = interviewRagService;
    }

    public InterviewSessionResponse start(InterviewStartRequest request) {
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        String direction = fallback(request.direction(), "目标岗位");
        String selectedDifficulty = fallback(request.difficulty(), "标准");
        String selectedFocus = fallback(request.focus(), "综合能力");
        // 自适应难度：根据用户 Elo 自动选择难度
        String difficulty;
        if (Boolean.TRUE.equals(request.adaptiveDifficulty())) {
            Long userId = AuthContext.currentUserId();
            difficulty = eloRatingService.selectAdaptiveDifficulty(userId, direction);
        } else if (Boolean.TRUE.equals(request.randomMix())) {
            difficulty = "随机难度";
        } else {
            difficulty = selectedDifficulty;
        }
        String focus = Boolean.TRUE.equals(request.randomMix()) ? "随机偏向" : selectedFocus;
        String style = Boolean.TRUE.equals(request.randomMix()) ? "随机风格" : fallback(request.style(), "常规面试");
        String mode = Boolean.TRUE.equals(request.randomMix()) ? "随机题库" : fallback(request.questionMode(), "即兴");

        String summary = "%s / %s / %s / %s / %s".formatted(direction, difficulty, focus, style, mode);

        // 读取简历文本
        String resumeText = null;
        if (StringUtils.hasText(request.resumeFileId())) {
            try {
                resumeText = documentService.readText(request.resumeFileId());
                if (resumeText != null && resumeText.length() > RESUME_TEXT_LIMIT) {
                    resumeText = resumeText.substring(0, RESUME_TEXT_LIMIT);
                }
            } catch (Exception e) {
                resumeText = null;
            }
        }

        // 读取 JD 文本
        String jdText = fallback(request.jdText(), "");
        if (!StringUtils.hasText(jdText) && StringUtils.hasText(request.jdFileId())) {
            try {
                jdText = documentService.readText(request.jdFileId());
                if (jdText != null && jdText.length() > JD_TEXT_LIMIT) {
                    jdText = jdText.substring(0, JD_TEXT_LIMIT);
                }
            } catch (Exception e) {
                jdText = "";
            }
        }

        // 生成候选人画像
        String candidateProfile = buildCandidateProfile(resumeText, jdText, direction);

        // 生成第一题（基于简历 + JD）
        String firstQuestion = generateQuestion(resumeText, jdText, direction, focus, difficulty, style,
                new ArrayList<>(), 1);

        InterviewSessionResponse response = new InterviewSessionResponse(
                sessionId, "READY", summary, firstQuestion,
                resumeText, jdText, candidateProfile, difficulty
        );
        interviewRepository.save(AuthContext.currentUserId(), sessionId, request, response);
        return response;
    }

    public InterviewSessionResponse get(String sessionId) {
        return interviewRepository.findBySessionId(AuthContext.currentUserId(), sessionId)
                .orElseThrow(() -> new IllegalArgumentException("面试会话不存在"));
    }

    public InterviewTurnResponse submitAnswer(String sessionId, InterviewAnswerRequest request) {
        InterviewSessionResponse session = get(sessionId);
        if (!StringUtils.hasText(request.answer())) {
            throw new IllegalArgumentException("请先填写本题回答");
        }

        int answeredCount = interviewRepository.countTurns(sessionId);
        int questionNo = answeredCount + 1;
        String question = questionNo == 1 ? session.firstQuestion() : buildQuestion(sessionId, session, questionNo);

        // 分项评分
        EvaluationResult eval = evaluateTurn(question, request.answer(), session.summary());
        String scoresJson = scoresToJson(eval.scores());

        // 更新 Elo 评分
        try {
            Long userId = AuthContext.currentUserId();
            String direction = extractDirection(session.summary());
            eloRatingService.updateAfterQuestion(userId, sessionId, questionNo, null, eval.overall(), direction);
        } catch (Exception e) {
            // Elo 更新失败不影响面试流程
        }

        // 更新知识图谱掌握度
        try {
            Long userId = AuthContext.currentUserId();
            knowledgeGraphService.updateMastery(userId, null, eval.overall());
        } catch (Exception e) {
            // 知识图谱更新失败不影响面试流程
        }

        // 判断是否追问
        boolean followUp = false;
        if (eval.overall() != null && eval.overall() < 65 && questionNo <= MAX_QUESTION_COUNT) {
            followUp = shouldFollowUp(question, request.answer());
        }

        boolean finished = !followUp && questionNo >= MAX_QUESTION_COUNT;
        int effectiveQuestionNo = followUp ? questionNo : questionNo;
        String nextQuestion = finished ? null : (followUp
                ? buildFollowUpQuestion(question, request.answer(), session.summary())
                : buildQuestionFromSession(session, questionNo + 1));

        InterviewTurnRecord turn = new InterviewTurnRecord(
                sessionId, effectiveQuestionNo, question, request.answer(),
                normalizeDuration(request.durationSeconds()),
                eval.comment(), eval.overall(), scoresJson, followUp
        );
        interviewRepository.saveTurn(turn);
        evaluateTurnInBackground(turn, session.summary());

        if (finished) {
            interviewRepository.markFinished(sessionId);
            // 游戏化结算
            settleGamification(eval, questionNo);
        }

        return new InterviewTurnResponse(
                sessionId, effectiveQuestionNo, question, request.answer(),
                turn.durationSeconds(), eval.comment(), eval.overall(),
                eval.scores(), followUp, nextQuestion, finished
        );
    }

    private void settleGamification(EvaluationResult eval, int questionCount) {
        try {
            Long userId = AuthContext.currentUserId();
            Map<String, Integer> scores = eval.scores();
            int technical = scores != null ? scores.getOrDefault("technical", 0) : eval.overall();
            int expression = scores != null ? scores.getOrDefault("expression", 0) : eval.overall();
            int match = scores != null ? scores.getOrDefault("match", 0) : eval.overall();
            int problemSolving = scores != null ? scores.getOrDefault("problem_solving", 0) : eval.overall();
            int followUp = scores != null ? scores.getOrDefault("follow_up", 0) : eval.overall();
            int stressResistance = scores != null ? scores.getOrDefault("stress_resistance", 0) : eval.overall();
            gamificationService.settleInterview(userId, eval.overall(), questionCount,
                    technical, expression, match, problemSolving, followUp, stressResistance);
        } catch (Exception e) {
            // 游戏化结算失败不影响面试流程
        }
    }

    public Flux<String> streamAnswer(String sessionId, InterviewAnswerRequest request) {
        InterviewSessionResponse session = get(sessionId);
        if (!StringUtils.hasText(request.answer())) {
            throw new IllegalArgumentException("请先填写本题回答");
        }

        int answeredCount = interviewRepository.countTurns(sessionId);
        int questionNo = answeredCount + 1;
        String question = questionNo == 1 ? session.firstQuestion() : buildQuestion(sessionId, session, questionNo);

        // 先同步保存回答和评分
        EvaluationResult eval = evaluateTurn(question, request.answer(), session.summary());
        String scoresJson = scoresToJson(eval.scores());

        // 更新 Elo 评分
        try {
            Long userId = AuthContext.currentUserId();
            String direction = extractDirection(session.summary());
            eloRatingService.updateAfterQuestion(userId, sessionId, questionNo, null, eval.overall(), direction);
        } catch (Exception e) {
            // Elo 更新失败不影响面试流程
        }

        // 更新知识图谱掌握度
        try {
            Long userId = AuthContext.currentUserId();
            knowledgeGraphService.updateMastery(userId, null, eval.overall());
        } catch (Exception e) {
            // 知识图谱更新失败不影响面试流程
        }

        boolean followUp = false;
        if (eval.overall() != null && eval.overall() < 65 && questionNo <= MAX_QUESTION_COUNT) {
            followUp = shouldFollowUp(question, request.answer());
        }

        boolean finished = !followUp && questionNo >= MAX_QUESTION_COUNT;

        InterviewTurnRecord turn = new InterviewTurnRecord(
                sessionId, questionNo, question, request.answer(),
                normalizeDuration(request.durationSeconds()),
                eval.comment(), eval.overall(), scoresJson, followUp
        );
        interviewRepository.saveTurn(turn);
        evaluateTurnInBackground(turn, session.summary());

        if (finished) {
            interviewRepository.markFinished(sessionId);
            // 游戏化结算
            settleGamification(eval, questionNo);
        }

        // 流式生成下一题
        String prompt = buildQuestionPrompt(
                truncate(session.resumeText(), RESUME_TEXT_LIMIT),
                truncate(session.jdText(), JD_TEXT_LIMIT),
                session.summary(),
                findAskedQuestions(sessionId),
                followUp ? questionNo : questionNo + 1,
                followUp ? question + "\n候选人回答：" + request.answer() + "\n请根据回答薄弱点进行追问。" : null
        );

        String metaJson;
        try {
            metaJson = objectMapper.writeValueAsString(buildTurnMeta(questionNo, eval, followUp, finished));
        } catch (JsonProcessingException e) {
            metaJson = "{\"type\":\"turn_meta\",\"questionNo\":%d,\"overall\":%d,\"followUp\":%b,\"finished\":%b}"
                    .formatted(questionNo, eval.overall(), followUp, finished);
        }
        String turnMeta = "[TURN_META]" + metaJson;

        return aiClient.streamChat(prompt)
                .concatWithValues(turnMeta);
    }

    public InterviewReportResponse generateReport(String sessionId, String userReflection) {
        get(sessionId);
        List<InterviewTurnRecord> turns = interviewRepository.findTurnsBySessionId(sessionId);
        if (turns.isEmpty()) {
            throw new IllegalArgumentException("还没有面试回答，暂时不能生成报告");
        }

        List<InterviewTurnRecord> evaluatedTurns = ensureEvaluations(sessionId, turns);
        int totalScore = Math.round((float) evaluatedTurns.stream()
                .mapToInt(turn -> turn.score() == null ? 0 : turn.score())
                .average()
                .orElse(0));

        Map<String, Integer> aggregateScores = aggregateScores(evaluatedTurns);
        String content = buildLocalReportContent(totalScore, evaluatedTurns);
        String scoresJson = scoresToJson(aggregateScores);

        InterviewReportResponse report = new InterviewReportResponse(
                sessionId, totalScore, content,
                fallback(userReflection, ""), get(sessionId).summary(), "",
                scoresJson, null, null, null
        );
        interviewRepository.markFinished(sessionId);
        return report;
    }

    public InterviewReportResponse refineReport(String sessionId, String userReflection) {
        InterviewSessionResponse session = get(sessionId);
        List<InterviewTurnRecord> turns = interviewRepository.findTurnsBySessionId(sessionId);
        if (turns.isEmpty()) {
            throw new IllegalArgumentException("还没有面试回答，暂时不能优化报告");
        }

        List<InterviewTurnRecord> evaluatedTurns = turns.stream()
                .map(turn -> {
                    EvaluationResult result = turn.score() == null || !StringUtils.hasText(turn.aiComment())
                            ? evaluateTurn(turn.question(), turn.answer(), session.summary())
                            : new EvaluationResult(turn.score(), turn.aiComment(), parseScores(turn.scores()));
                    String scoresJson = scoresToJson(result.scores());
                    interviewRepository.updateTurnEvaluation(sessionId, turn.questionNo(), result.overall(), result.comment(), scoresJson);
                    return new InterviewTurnRecord(
                            turn.sessionId(), turn.questionNo(), turn.question(), turn.answer(),
                            turn.durationSeconds(), result.comment(), result.overall(),
                            scoresJson, turn.followUp()
                    );
                })
                .toList();

        int totalScore = Math.round((float) evaluatedTurns.stream()
                .mapToInt(turn -> turn.score() == null ? 0 : turn.score())
                .average()
                .orElse(0));

        Map<String, Integer> aggregateScores = aggregateScores(evaluatedTurns);
        String content = buildAiReportContent(sessionId, totalScore, evaluatedTurns, session);
        Map<String, String> structured = parseStructuredReport(content);

        InterviewReportResponse report = new InterviewReportResponse(
                sessionId, totalScore, content,
                fallback(userReflection, ""), session.summary(), "",
                scoresToJson(aggregateScores),
                structured.get("strengths"),
                structured.get("weaknesses"),
                structured.get("advice")
        );
        interviewRepository.saveReport(AuthContext.currentUserId(), report);
        interviewRepository.markFinished(sessionId);
        return getReport(sessionId);
    }

    public List<InterviewTurnRecord> listTurns(String sessionId) {
        get(sessionId);
        return interviewRepository.findTurnsBySessionId(sessionId);
    }

    public InterviewReportResponse getReport(String sessionId) {
        return interviewRepository.findReportBySessionId(AuthContext.currentUserId(), sessionId)
                .orElseThrow(() -> new IllegalArgumentException("面试报告不存在，请先生成报告"));
    }

    public List<InterviewReportSummaryResponse> listReports() {
        return interviewRepository.findReportSummaries(AuthContext.currentUserId());
    }

    public void deleteReport(String sessionId) {
        boolean deleted = interviewRepository.deleteReport(AuthContext.currentUserId(), sessionId);
        if (!deleted) {
            throw new IllegalArgumentException("面试报告不存在或已被删除");
        }
    }

    // ==================== 私有方法 ====================

    private String buildCandidateProfile(String resumeText, String jdText, String direction) {
        if (!StringUtils.hasText(resumeText)) return null;
        try {
            String prompt = """
                    请从以下简历中提取候选人画像摘要，100字以内，包括：技术栈、经验年限、核心项目、突出能力。
                    如果有岗位描述(JD)，请额外说明匹配度。

                    方向：%s
                    简历：
                    %s
                    %s
                    """.formatted(
                    direction,
                    truncate(resumeText, 3000),
                    StringUtils.hasText(jdText) ? "\n岗位描述：\n" + truncate(jdText, 1500) : ""
            );
            return aiClient.chat(prompt);
        } catch (Exception e) {
            return null;
        }
    }

    private String generateQuestion(String resumeText, String jdText, String direction, String focus,
                                     String difficulty, String style,
                                     List<String> askedQuestions, int questionNo) {
        String prompt = buildQuestionPrompt(resumeText, jdText, direction + " / " + style, askedQuestions, questionNo, null);
        try {
            String result = aiClient.chat(prompt);
            if (StringUtils.hasText(result)) {
                return result.trim();
            }
        } catch (Exception e) {
            // fallback
        }
        return interviewQuestionRepository
                .findOpeningQuestion(direction, focus, difficulty)
                .map(InterviewQuestionRecord::questionText)
                .orElse("请先用 1-2 分钟介绍一下你自己，并重点说明你和「%s」方向最相关的一段经历。".formatted(direction));
    }

    private String buildQuestionPrompt(String resumeText, String jdText, String summary,
                                        List<String> askedQuestions, int questionNo, String followUpContext) {
        String[] parts = summary.split("/");
        String direction = parts.length > 0 ? parts[0].trim() : "软件开发";
        String style = parts.length > 3 ? parts[3].trim() : "常规面试";

        // 优先使用面试官人格化的 styleDesc
        String stylePrompt = interviewerService.buildInterviewerPrompt(null, getDefaultStylePrompt(style));

        StringBuilder prompt = new StringBuilder();
        prompt.append(stylePrompt).append("\n\n");
        prompt.append("你正在面试一位候选人，目标岗位方向：").append(direction).append("\n");
        prompt.append("当前是第 ").append(questionNo).append(" 题。\n\n");

        if (StringUtils.hasText(resumeText)) {
            prompt.append("候选人简历摘要：\n").append(truncate(resumeText, 2000)).append("\n\n");
        }
        if (StringUtils.hasText(jdText)) {
            prompt.append("目标岗位描述：\n").append(truncate(jdText, 1000)).append("\n\n");
        }

        // RAG 检索相似题目，避免重复
        try {
            List<String> similarQuestions = interviewRagService.searchSimilarQuestions(
                    direction + " " + style, direction, askedQuestions, 3);
            if (!similarQuestions.isEmpty()) {
                prompt.append("题库中相似的题目（请避免出类似的题）：\n");
                for (int i = 0; i < similarQuestions.size(); i++) {
                    prompt.append(i + 1).append(". ").append(truncate(similarQuestions.get(i), 100)).append("\n");
                }
                prompt.append("\n");
            }
        } catch (Exception e) {
            // RAG 不可用时忽略
        }

        if (!askedQuestions.isEmpty()) {
            prompt.append("已问过的问题（请不要重复）：\n");
            for (int i = 0; i < askedQuestions.size(); i++) {
                prompt.append(i + 1).append(". ").append(askedQuestions.get(i)).append("\n");
            }
            prompt.append("\n");
        }

        if (StringUtils.hasText(followUpContext)) {
            prompt.append("上一题和候选人回答：\n").append(followUpContext).append("\n\n");
            prompt.append("请根据回答中的薄弱点进行追问，帮助候选人更深入地展开。");
        } else {
            prompt.append("请生成一个面试问题。要求：\n");
            prompt.append("- 问题要具体，不要泛泛而谈\n");
            if (questionNo == 1) {
                prompt.append("- 第一题可以从自我介绍或简历中最有代表性的项目切入\n");
            } else if (questionNo <= 3) {
                prompt.append("- 可以围绕项目经验、技术选型、难点解决来提问\n");
            } else {
                prompt.append("- 可以考察系统设计、问题排查、学习能力等综合素质\n");
            }
        }

        prompt.append("\n只输出问题本身，不要加任何前缀或解释。");
        return prompt.toString();
    }

    private String buildQuestion(String sessionId, InterviewSessionResponse session, int questionNo) {
        List<String> asked = findAskedQuestions(sessionId);
        return generateQuestion(
                truncate(session.resumeText(), RESUME_TEXT_LIMIT),
                truncate(session.jdText(), JD_TEXT_LIMIT),
                extractDirection(session.summary()),
                extractFocus(session.summary()),
                extractDifficulty(session.summary()),
                extractStyle(session.summary()),
                asked,
                questionNo
        );
    }

    private String buildQuestionFromSession(InterviewSessionResponse session, int questionNo) {
        List<String> asked = findAskedQuestions(session.sessionId());
        return generateQuestion(
                truncate(session.resumeText(), RESUME_TEXT_LIMIT),
                truncate(session.jdText(), JD_TEXT_LIMIT),
                extractDirection(session.summary()),
                extractFocus(session.summary()),
                extractDifficulty(session.summary()),
                extractStyle(session.summary()),
                asked,
                questionNo
        );
    }

    private String buildFollowUpQuestion(String lastQuestion, String lastAnswer, String summary) {
        String prompt = """
                你是一位面试官。候选人对上一个问题回答得不够深入，请生成一个追问。
                要求：针对回答中的薄弱环节或模糊表述进行深挖。

                面试方向：%s
                上一个问题：%s
                候选人回答：%s

                只输出追问本身，不要加前缀或解释。
                """.formatted(extractDirection(summary), lastQuestion, truncate(lastAnswer, 1000));
        try {
            String result = aiClient.chat(prompt);
            if (StringUtils.hasText(result)) return result.trim();
        } catch (Exception e) {
            // fallback
        }
        return "请就上一个回答中的关键点进一步展开说明。";
    }

    private List<String> findAskedQuestions(String sessionId) {
        return interviewRepository.findTurnsBySessionId(sessionId).stream()
                .map(InterviewTurnRecord::question)
                .toList();
    }

    private EvaluationResult evaluateTurn(String question, String answer, String summary) {
        try {
            aiClient.validateConfiguration();
            String direction = extractDirection(summary);
            ScoringService.EvaluationWithConfidence result =
                    scoringService.evaluateWithConfidence(question, answer, direction);
            return new EvaluationResult(result.overall(), result.comment(), result.scores());
        } catch (Exception exception) {
            int fallbackScore = scoreAnswer(answer);
            return new EvaluationResult(fallbackScore, buildLocalComment(answer, fallbackScore), Map.of(
                    "technical", fallbackScore, "expression", fallbackScore, "match", fallbackScore,
                    "problem_solving", fallbackScore, "follow_up", fallbackScore, "stress_resistance", fallbackScore
            ));
        }
    }

    private boolean shouldFollowUp(String question, String answer) {
        try {
            String prompt = """
                    判断候选人对以下面试问题的回答是否足够深入，是否需要追问。
                    只输出 JSON：{"followUp": true/false, "reason": "原因"}

                    问题：%s
                    回答：%s
                    """.formatted(question, truncate(answer, 500));
            String raw = aiClient.chat(prompt);
            Matcher m = JSON_PATTERN.matcher(raw);
            if (m.find()) {
                Map<String, Object> map = objectMapper.readValue(m.group(), new TypeReference<>() {});
                return Boolean.TRUE.equals(map.get("followUp"));
            }
        } catch (Exception e) {
            // 不追问
        }
        return false;
    }

    private EvaluationResult parseStructuredEvaluation(String raw, String answer) {
        Matcher m = JSON_PATTERN.matcher(raw);
        if (m.find()) {
            try {
                Map<String, Object> map = objectMapper.readValue(m.group(), new TypeReference<>() {});
                int fallback = scoreAnswer(answer);
                int technical = toInt(map.get("technical"), fallback);
                int expression = toInt(map.get("expression"), fallback);
                int match = toInt(map.get("match"), fallback);
                int problemSolving = toInt(map.get("problem_solving"), fallback);
                int followUp = toInt(map.get("follow_up"), fallback);
                int stressResistance = toInt(map.get("stress_resistance"), fallback);
                int overall = toInt(map.get("overall"), Math.round((technical + expression + match + problemSolving + followUp + stressResistance) / 6f));
                String comment = map.get("comment") != null ? map.get("comment").toString().trim() : buildLocalComment(answer, overall);
                return new EvaluationResult(clampScore(overall), comment, Map.of(
                        "technical", clampScore(technical),
                        "expression", clampScore(expression),
                        "match", clampScore(match),
                        "problem_solving", clampScore(problemSolving),
                        "follow_up", clampScore(followUp),
                        "stress_resistance", clampScore(stressResistance)
                ));
            } catch (JsonProcessingException e) {
                // fallback below
            }
        }
        // fallback: try old format
        return parseEvaluation(raw, answer);
    }

    private EvaluationResult parseEvaluation(String rawText, String answer) {
        Matcher scoreMatcher = SCORE_PATTERN.matcher(rawText);
        int score = scoreMatcher.find()
                ? clampScore(Integer.parseInt(scoreMatcher.group(1)))
                : scoreAnswer(answer);

        Matcher commentMatcher = COMMENT_PATTERN.matcher(rawText);
        String comment = commentMatcher.find() ? commentMatcher.group(1).trim() : rawText.trim();
        if (!StringUtils.hasText(comment)) {
            comment = buildLocalComment(answer, score);
        }
        return new EvaluationResult(score, comment, Map.of(
                "technical", score, "expression", score, "match", score,
                "problem_solving", score, "follow_up", score, "stress_resistance", score
        ));
    }

    private void evaluateTurnInBackground(InterviewTurnRecord turn, String summary) {
        CompletableFuture.runAsync(() -> {
            EvaluationResult result = evaluateTurn(turn.question(), turn.answer(), summary);
            interviewRepository.updateTurnEvaluation(
                    turn.sessionId(), turn.questionNo(),
                    result.overall(), result.comment(), scoresToJson(result.scores())
            );
        });
    }

    private List<InterviewTurnRecord> ensureEvaluations(String sessionId, List<InterviewTurnRecord> turns) {
        InterviewSessionResponse session = get(sessionId);
        List<InterviewTurnRecord> evaluatedTurns = new ArrayList<>();
        for (InterviewTurnRecord turn : turns) {
            if (turn.score() != null && StringUtils.hasText(turn.aiComment())) {
                evaluatedTurns.add(turn);
                continue;
            }
            EvaluationResult result = evaluateTurn(turn.question(), turn.answer(), session.summary());
            String scoresJson = scoresToJson(result.scores());
            interviewRepository.updateTurnEvaluation(sessionId, turn.questionNo(), result.overall(), result.comment(), scoresJson);
            evaluatedTurns.add(new InterviewTurnRecord(
                    turn.sessionId(), turn.questionNo(), turn.question(), turn.answer(),
                    turn.durationSeconds(), result.comment(), result.overall(),
                    scoresJson, turn.followUp()
            ));
        }
        return evaluatedTurns;
    }

    private Map<String, Integer> aggregateScores(List<InterviewTurnRecord> turns) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (InterviewTurnRecord turn : turns) {
            Map<String, Integer> s = parseScores(turn.scores());
            if (s != null) {
                s.forEach((k, v) -> {
                    totals.merge(k, v, Integer::sum);
                    counts.merge(k, 1, Integer::sum);
                });
            }
        }
        Map<String, Integer> avg = new LinkedHashMap<>();
        totals.forEach((k, v) -> avg.put(k, Math.round((float) v / counts.getOrDefault(k, 1))));
        return avg;
    }

    private Map<String, Integer> parseScores(String scoresJson) {
        if (!StringUtils.hasText(scoresJson)) return null;
        try {
            return objectMapper.readValue(scoresJson, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String scoresToJson(Map<String, Integer> scores) {
        if (scores == null || scores.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(scores);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private Map<String, String> parseStructuredReport(String content) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!StringUtils.hasText(content)) return result;
        // 尝试从 AI 报告中提取亮点、短板、建议
        String[] sections = content.split("(?m)^(?=\\d+[.、])");
        for (String section : sections) {
            if (section.contains("亮点") || section.contains("优势")) {
                result.put("strengths", section.replaceAll("^\\d+[.、]\\s*.*?[:：]?\\s*", "").trim());
            } else if (section.contains("短板") || section.contains("不足") || section.contains("问题")) {
                result.put("weaknesses", section.replaceAll("^\\d+[.、]\\s*.*?[:：]?\\s*", "").trim());
            } else if (section.contains("建议") || section.contains("训练")) {
                result.put("advice", section.replaceAll("^\\d+[.、]\\s*.*?[:：]?\\s*", "").trim());
            }
        }
        return result;
    }

    private Map<String, Object> buildTurnMeta(int questionNo, EvaluationResult eval, boolean followUp, boolean finished) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("type", "turn_meta");
        meta.put("questionNo", questionNo);
        meta.put("overall", eval.overall());
        meta.put("scores", eval.scores());
        meta.put("comment", eval.comment());
        meta.put("followUp", followUp);
        meta.put("finished", finished);
        return meta;
    }

    private String extractDirection(String summary) {
        String[] parts = summary.split("/");
        return parts.length > 0 ? parts[0].trim() : "软件开发";
    }

    private String getDefaultStylePrompt(String style) {
        return switch (style) {
            case "温和引导" -> "你是一位温和的面试官，善于引导候选人表达，适当给予提示和鼓励。";
            case "严格追问" -> "你是一位严格的面试官，会深挖技术细节，质疑候选人的技术选型和项目决策。";
            case "压力面试" -> "你是一位施加压力的面试官，会连续追问，挑战候选人的回答，模拟高压面试场景。";
            default -> "你是一位专业的面试官，根据候选人的回答质量调整提问深度。";
        };
    }

    private String extractStyle(String summary) {
        String[] parts = summary.split("/");
        return parts.length > 3 ? parts[3].trim() : "常规面试";
    }

    private String extractDifficulty(String summary) {
        String[] parts = summary.split("/");
        return parts.length > 1 ? parts[1].trim() : "标准";
    }

    private String extractFocus(String summary) {
        String[] parts = summary.split("/");
        return parts.length > 2 ? parts[2].trim() : "综合能力";
    }

    private int toInt(Object value, int defaultVal) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return defaultVal; }
        }
        return defaultVal;
    }

    private String fallback(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() <= maxLen ? text : text.substring(0, maxLen);
    }

    private int normalizeDuration(Integer durationSeconds) {
        if (durationSeconds == null || durationSeconds < 0) return 0;
        return durationSeconds;
    }

    private int clampScore(int score) {
        if (score < 0) return 0;
        return Math.min(score, 100);
    }

    private int scoreAnswer(String answer) {
        int length = answer.trim().length();
        if (length < 20) return 45;
        if (length < 80) return 62;
        if (length < 180) return 74;
        return 82;
    }

    private String buildLocalComment(String answer, int score) {
        if (score < 60) {
            return "回答已经开始触及问题，但信息还偏少。建议补充具体项目、技术动作、结果数据，以及你个人负责的部分。";
        }
        if (score < 80) {
            return "回答有一定结构，也能看出经历基础。下一步可以把背景、行动、结果讲得更清楚，尤其要突出你的决策和收益。";
        }
        return "回答比较完整，能覆盖经历和行动。继续优化时，可以增加量化结果、困难取舍和复盘思考。";
    }

    private String buildLocalReportContent(int totalScore, List<InterviewTurnRecord> turns) {
        StringBuilder turnDetails = new StringBuilder();
        for (InterviewTurnRecord turn : turns) {
            turnDetails.append("第 ").append(turn.questionNo()).append(" 题：").append(turn.question()).append("\n")
                    .append("回答：").append(turn.answer()).append("\n")
                    .append("用时：").append(turn.durationSeconds()).append(" 秒\n")
                    .append("评分：").append(turn.score());
            Map<String, Integer> scores = parseScores(turn.scores());
            if (scores != null && !scores.isEmpty()) {
                turnDetails.append("（技术：").append(scores.getOrDefault("technical", 0))
                        .append(" 表达：").append(scores.getOrDefault("expression", 0))
                        .append(" 匹配：").append(scores.getOrDefault("match", 0))
                        .append(" 解决：").append(scores.getOrDefault("problem_solving", 0))
                        .append(" 追问：").append(scores.getOrDefault("follow_up", 0))
                        .append(" 抗压：").append(scores.getOrDefault("stress_resistance", 0)).append("）");
            }
            turnDetails.append("\n点评：").append(turn.aiComment());
            if (Boolean.TRUE.equals(turn.followUp())) {
                turnDetails.append(" [AI 追问题]");
            }
            turnDetails.append("\n\n");
        }
        return """
                面试报告

                综合评分：%d 分

                总体表现：
                本次模拟面试共记录 %d 道问题。系统按每道题分别批改，再汇总平均分生成本报告。

                逐题记录：
                %s
                下一步建议：
                建议继续用 STAR 表达法练习回答。每道题尽量讲清楚背景、任务、行动和结果。
                """.formatted(totalScore, turns.size(), turnDetails);
    }

    private String buildAiReportContent(String sessionId, int totalScore, List<InterviewTurnRecord> turns,
                                         InterviewSessionResponse session) {
        aiClient.validateConfiguration();
        StringBuilder turnDetails = new StringBuilder();
        for (InterviewTurnRecord turn : turns) {
            turnDetails.append("第 ").append(turn.questionNo()).append(" 题：").append(turn.question()).append("\n")
                    .append("回答：").append(turn.answer()).append("\n")
                    .append("用时：").append(turn.durationSeconds()).append(" 秒\n")
                    .append("评分：").append(turn.score());
            Map<String, Integer> scores = parseScores(turn.scores());
            if (scores != null) {
                turnDetails.append("（技术：").append(scores.getOrDefault("technical", 0))
                        .append(" 表达：").append(scores.getOrDefault("expression", 0))
                        .append(" 匹配：").append(scores.getOrDefault("match", 0))
                        .append(" 解决：").append(scores.getOrDefault("problem_solving", 0))
                        .append(" 追问：").append(scores.getOrDefault("follow_up", 0))
                        .append(" 抗压：").append(scores.getOrDefault("stress_resistance", 0)).append("）");
            }
            turnDetails.append("\n点评：").append(turn.aiComment());
            if (Boolean.TRUE.equals(turn.followUp())) {
                turnDetails.append(" [追问题]");
            }
            turnDetails.append("\n\n");
        }

        String prompt = """
                你是一名面试复盘教练。请基于下面的真实面试记录生成中文面试报告。
                请使用自然正文格式，不要使用 Markdown 标题、星号、井号、代码块或表情符号。
                报告结构：
                1. 总体评分
                2. 表现亮点
                3. 主要短板
                4. 每道题复盘：问题、用户回答摘要、用时、评分、点评
                5. 下一轮训练建议

                会话 ID：%s
                总分：%d
                面试方向：%s
                面试记录：
                %s
                """.formatted(sessionId, totalScore, session.summary(), turnDetails);
        return aiClient.chat(prompt);
    }

    private record EvaluationResult(Integer overall, String comment, Map<String, Integer> scores) {
    }
}
