-- ============================================================
-- AI 面试助手 - 数据库表结构
-- 通过反推 14 个 Repository 类的 SQL 语句生成
-- 使用 CREATE TABLE IF NOT EXISTS 以支持 spring.sql.init.mode=always
-- ============================================================

-- ============ 1. 用户与认证 ============

CREATE TABLE IF NOT EXISTS user_profiles (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    display_name    VARCHAR(100) NOT NULL,
    target_role     VARCHAR(100) NOT NULL DEFAULT 'Java 后端开发',
    skill_keywords  VARCHAR(500)          DEFAULT 'Java, Spring Boot, MySQL, Redis, Vue3, Docker',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS app_users (
    id              BIGINT       NOT NULL,
    username        VARCHAR(100) NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_users_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============ 2. AI Agent 会话 ============

CREATE TABLE IF NOT EXISTS agent_sessions (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    session_id      VARCHAR(32)  NOT NULL,
    title           VARCHAR(255)          DEFAULT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_sessions_user_session (user_id, session_id),
    KEY idx_agent_sessions_session_id (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_messages (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    session_id      VARCHAR(32)  NOT NULL,
    role            VARCHAR(20)  NOT NULL,
    content         LONGTEXT,
    tool_name       VARCHAR(100)          DEFAULT NULL,
    tool_call_id    VARCHAR(100)          DEFAULT NULL,
    tool_arguments  TEXT,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_agent_messages_session_id (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============ 3. 面试官配置 ============

CREATE TABLE IF NOT EXISTS interviewer_profiles (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    name            VARCHAR(50)  NOT NULL,
    avatar          VARCHAR(255)          DEFAULT NULL,
    personality     VARCHAR(100)          DEFAULT NULL,
    style_desc      VARCHAR(500)          DEFAULT NULL,
    greeting        VARCHAR(500)          DEFAULT NULL,
    catchphrase     VARCHAR(255)          DEFAULT NULL,
    is_default      TINYINT(1)   NOT NULL DEFAULT 0,
    UNIQUE KEY uk_interviewer_profiles_name (name),
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============ 4. 题库 ============

CREATE TABLE IF NOT EXISTS interview_questions (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    direction         VARCHAR(50) NOT NULL,
    category          VARCHAR(50) NOT NULL,
    difficulty        VARCHAR(20) NOT NULL DEFAULT '标准',
    question_text     TEXT        NOT NULL,
    reference_answer  TEXT,
    source_name       VARCHAR(255)         DEFAULT NULL,
    source_url        VARCHAR(500)         DEFAULT NULL,
    enabled           TINYINT(1)  NOT NULL DEFAULT 1,
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_interview_questions_direction (direction),
    KEY idx_interview_questions_category (category),
    KEY idx_interview_questions_difficulty (difficulty),
    UNIQUE KEY uk_interview_questions_text (question_text(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============ 5. 面试会话与回合 ============

CREATE TABLE IF NOT EXISTS interview_sessions (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    user_id             BIGINT       NOT NULL,
    session_id          VARCHAR(32)  NOT NULL,
    resume_file_id      VARCHAR(64)           DEFAULT NULL,
    jd_file_id          VARCHAR(64)           DEFAULT NULL,
    jd_text             LONGTEXT,
    resume_text         LONGTEXT,
    candidate_profile   LONGTEXT,
    direction           VARCHAR(50)  NOT NULL,
    difficulty           VARCHAR(20)  NOT NULL DEFAULT '标准',
    focus_area           VARCHAR(50)           DEFAULT NULL,
    interviewer_style    VARCHAR(50)           DEFAULT NULL,
    question_mode        VARCHAR(20)           DEFAULT NULL,
    random_mix           TINYINT(1)   NOT NULL DEFAULT 0,
    status               VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    summary              LONGTEXT,
    first_question       LONGTEXT,
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_interview_sessions_user_session (user_id, session_id),
    KEY idx_interview_sessions_session_id (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS interview_turns (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    session_id          VARCHAR(32)  NOT NULL,
    question_no         INT          NOT NULL,
    question            TEXT         NOT NULL,
    answer              LONGTEXT,
    duration_seconds    INT                   DEFAULT NULL,
    ai_comment          LONGTEXT,
    score                TINYINT               DEFAULT NULL,
    scores               TEXT,
    follow_up            TINYINT(1)   NOT NULL DEFAULT 0,
    reviewed             TINYINT(1)   NOT NULL DEFAULT 0,
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_interview_turns_session_question (session_id, question_no),
    KEY idx_interview_turns_session_id (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS interview_reports (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    user_id           BIGINT       NOT NULL,
    session_id        VARCHAR(32)  NOT NULL,
    total_score       TINYINT               DEFAULT NULL,
    report_content    LONGTEXT,
    user_reflection   TEXT,
    scores            TEXT,
    strengths         TEXT,
    weaknesses        TEXT,
    advice            TEXT,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_interview_reports_user_session (user_id, session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============ 6. 文档与简历 ============

CREATE TABLE IF NOT EXISTS document_files (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    file_id     VARCHAR(64)  NOT NULL,
    file_name   VARCHAR(255) NOT NULL,
    file_type   VARCHAR(50)           DEFAULT NULL,
    file_url    VARCHAR(500)          DEFAULT NULL,
    full_text   LONGTEXT,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_document_files_user_file (user_id, file_id),
    KEY idx_document_files_file_id (file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS resume_versions (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    user_id           BIGINT       NOT NULL,
    file_id           VARCHAR(64)  NOT NULL,
    file_name         VARCHAR(255) NOT NULL,
    file_type         VARCHAR(50)           DEFAULT NULL,
    version_no        INT          NOT NULL,
    text_length       INT          NOT NULL DEFAULT 0,
    skill_keywords    VARCHAR(500)          DEFAULT NULL,
    content_preview   TEXT,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_resume_versions_user_file (user_id, file_id),
    KEY idx_resume_versions_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============ 7. 岗位匹配 ============

CREATE TABLE IF NOT EXISTS job_match_analyses (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    user_id           BIGINT       NOT NULL,
    resume_file_id    VARCHAR(64)           DEFAULT NULL,
    jd_text          LONGTEXT,
    match_score       TINYINT               DEFAULT NULL,
    analysis_content  LONGTEXT,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_job_match_analyses_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS job_projects (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    user_id              BIGINT       NOT NULL,
    company_name         VARCHAR(100) NOT NULL,
    job_title            VARCHAR(100) NOT NULL,
    jd_text              LONGTEXT,
    resume_version_id    BIGINT                DEFAULT NULL,
    resume_file_id       VARCHAR(64)           DEFAULT NULL,
    match_analysis_id    BIGINT                DEFAULT NULL,
    match_score          TINYINT               DEFAULT NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT '待分析',
    resume_suggestions   LONGTEXT,
    tailored_resume_text LONGTEXT,
    final_conclusion     LONGTEXT,
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_job_projects_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============ 8. ELO 评分系统 ============

CREATE TABLE IF NOT EXISTS user_elo_ratings (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    direction       VARCHAR(50)  NOT NULL,
    category        VARCHAR(50)  NOT NULL DEFAULT '',
    elo_rating      DOUBLE       NOT NULL DEFAULT 1000,
    games_played    INT          NOT NULL DEFAULT 0,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_elo_ratings_user_dir_cat (user_id, direction, category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS question_difficulty_ratings (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    question_id     BIGINT       NOT NULL,
    elo_difficulty  DOUBLE       NOT NULL DEFAULT 1000,
    attempt_count   INT          NOT NULL DEFAULT 0,
    avg_score       DOUBLE       NOT NULL DEFAULT 0,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_question_difficulty_ratings_question_id (question_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS difficulty_trajectories (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    user_id           BIGINT       NOT NULL,
    session_id        VARCHAR(32)  NOT NULL,
    question_no       INT          NOT NULL,
    user_elo_before   DOUBLE       NOT NULL DEFAULT 0,
    question_elo      DOUBLE       NOT NULL DEFAULT 0,
    score             INT          NOT NULL DEFAULT 0,
    user_elo_after    DOUBLE       NOT NULL DEFAULT 0,
    difficulty_label  VARCHAR(50)           DEFAULT NULL,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_difficulty_trajectories_user_session (user_id, session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============ 9. 游戏化激励 ============

CREATE TABLE IF NOT EXISTS user_gamification (
    user_id            BIGINT       NOT NULL,
    exp_points          INT          NOT NULL DEFAULT 0,
    level              INT          NOT NULL DEFAULT 1,
    title              VARCHAR(50)           DEFAULT '新手',
    streak_days        INT          NOT NULL DEFAULT 0,
    last_practice_date DATE                  DEFAULT NULL,
    total_interviews   INT          NOT NULL DEFAULT 0,
    total_questions    INT          NOT NULL DEFAULT 0,
    best_score         INT          NOT NULL DEFAULT 0,
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS achievements (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    code             VARCHAR(50)  NOT NULL,
    name             VARCHAR(100) NOT NULL,
    description      VARCHAR(500)          DEFAULT NULL,
    icon             VARCHAR(255)          DEFAULT NULL,
    category         VARCHAR(50)  NOT NULL,
    condition_type   VARCHAR(50)  NOT NULL,
    condition_value  INT          NOT NULL DEFAULT 0,
    exp_reward       INT          NOT NULL DEFAULT 0,
    rarity           VARCHAR(20)           DEFAULT 'COMMON',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_achievements_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_achievements (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    achievement_id  BIGINT       NOT NULL,
    unlocked_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_achievements_user_achv (user_id, achievement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS daily_tasks (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    task_date       DATE         NOT NULL,
    task_type      VARCHAR(50)  NOT NULL,
    description    VARCHAR(500) NOT NULL,
    target_count    INT          NOT NULL DEFAULT 1,
    current_count   INT          NOT NULL DEFAULT 0,
    completed       TINYINT(1)   NOT NULL DEFAULT 0,
    claimed         TINYINT(1)   NOT NULL DEFAULT 0,
    exp_reward      INT          NOT NULL DEFAULT 0,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_daily_tasks_user_date_type (user_id, task_date, task_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============ 10. 知识图谱 ============

CREATE TABLE IF NOT EXISTS knowledge_points (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    direction       VARCHAR(50)  NOT NULL,
    category        VARCHAR(50)  NOT NULL,
    name            VARCHAR(100) NOT NULL,
    description     TEXT,
    mastery_level   DOUBLE       NOT NULL DEFAULT 0,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_knowledge_points_direction (direction),
    KEY idx_knowledge_points_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_dependencies (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    prerequisite_id   BIGINT       NOT NULL,
    dependent_id      BIGINT       NOT NULL,
    dependency_type   VARCHAR(50)           DEFAULT 'requires',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_dependencies_pair (prerequisite_id, dependent_id),
    KEY idx_knowledge_dependencies_prerequisite (prerequisite_id),
    KEY idx_knowledge_dependencies_dependent (dependent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS question_knowledge_map (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    question_id         BIGINT       NOT NULL,
    knowledge_point_id  BIGINT       NOT NULL,
    relevance_weight    DOUBLE       NOT NULL DEFAULT 1.0,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_question_knowledge_map (question_id, knowledge_point_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_knowledge_mastery (
    user_id             BIGINT       NOT NULL,
    knowledge_point_id  BIGINT       NOT NULL,
    mastery_level       DOUBLE       NOT NULL DEFAULT 0,
    attempt_count       INT          NOT NULL DEFAULT 0,
    correct_count       INT          NOT NULL DEFAULT 0,
    last_attempt_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, knowledge_point_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============ 11. 训练计划 ============

CREATE TABLE IF NOT EXISTS training_tasks (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    title           VARCHAR(255) NOT NULL,
    description     TEXT,
    task_type       VARCHAR(50)  NOT NULL,
    category        VARCHAR(50)           DEFAULT NULL,
    target_count    INT          NOT NULL DEFAULT 1,
    finished_count  INT          NOT NULL DEFAULT 0,
    status          VARCHAR(20)  NOT NULL DEFAULT 'TODO',
    source          VARCHAR(100)          DEFAULT NULL,
    due_date        DATE                  DEFAULT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_training_tasks_user_id (user_id),
    KEY idx_training_tasks_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 种子数据：4 位 AI 面试官（README 中提到）
-- 使用 INSERT IGNORE 避免重复启动时重复插入
-- ============================================================

INSERT IGNORE INTO interviewer_profiles (name, avatar, personality, style_desc, greeting, catchphrase, is_default) VALUES
('技术官老王', 'tech_wang', '严谨、专业、深度追问', '聚焦项目深度与底层原理，喜欢连环追问为什么，会针对你的项目细节深挖到实现层。', '你好，我是技术面试官老王。先做个简单的自我介绍吧，重点说一下你最近的项目。', '深挖到底', 1),
('HR 小李',   'hr_li',      '亲和、注重综合素质', '关注沟通能力、团队协作和职业规划，问题偏向行为面试，会用 STAR 法则评估你的回答。', '你好呀，我是 HR 面试官小李。先放松一下，跟我聊聊你最近一段工作里最有成就感的事？', '倾听你的故事', 0),
('压力官赵sir','pressure_zhao','犀利、挑战、施压', '故意制造压力，质疑你的回答，考察应变能力和抗压能力，会打断不清晰的回答。', '你好。我是压力面试官。直接开始：用一句话告诉我，你最不擅长的技术方向是什么？', '压力之下见真章', 0),
('引导官陈姐','guide_chen',  '温和、鼓励、循序渐进', '从基础概念切入，逐步加深难度，给正向反馈，让你逐步建立信心。适合初次模拟。', '你好呀，我是引导型面试官陈姐。第一次模拟的话，我们就从 Java 基础开始好不好？', '一步步来', 0);

-- ============================================================
-- 种子数据：少量示例题库，让题库页面不空
-- ============================================================

INSERT IGNORE INTO interview_questions (direction, category, difficulty, question_text, reference_answer, source_name, source_url) VALUES
('后端开发', 'Java 基础', '简单', '请说说 HashMap 在 JDK 8 中的底层实现。', 'HashMap 在 JDK 8 中基于 数组 + 链表 / 红黑树 实现。当链表长度超过 8 且数组长度达到 64 时，链表转为红黑树以提升查询效率。put 过程：计算 key 的 hash（高 16 位异或低 16 位扰动），定位桶；桶为空直接放入；冲突则按链表/树方式插入或更新。扩容因子 0.75，扩容时容量翻倍。', '内部示例', NULL),
('后端开发', 'Java 基础', '标准', 'synchronized 和 ReentrantLock 的区别？', '1. 实现：synchronized 是 JVM 层关键字，ReentrantLock 是 JDK API；2. 公平性：synchronized 非公平，ReentrantLock 可选公平；3. 中断：synchronized 不可中断，ReentrantLock 可中断；4. 超时：ReentrantLock 支持 tryLock 超时；5. 多 Condition：ReentrantLock 可绑定多个 Condition；6. 释放：synchronized 自动释放，ReentrantLock 必须手动 unlock。', '内部示例', NULL),
('后端开发', '项目深挖', '困难', '请讲一个你做过的最有挑战的并发场景，并说明你是如何解决竞态问题的。', '考察候选人对并发场景的真实理解。要点：1) 必须说出具体业务和并发量级；2) 必须指出竞态来源；3) 解决方案要有取舍（锁 vs 无锁 vs 队列）；4) 给出量化指标对比。', '内部示例', NULL),
('后端开发', '系统设计', '困难', '设计一个支持千万级日活的短链生成服务，说说你的方案。', '核心要点：1) 发号策略（自增 ID / 雪花算法 / Hash）；2) 存储（KV 数据库 vs MySQL 索引）；3) 缓存层（Redis 缓存热点短链）；4) 重定向 302 vs 301；5) 防止冲突（布隆过滤器）；6) 容量评估与分库分表。', '内部示例', NULL),
('前端开发', 'Vue 基础', '简单', 'Vue 3 相比 Vue 2 的主要改进有哪些？', '1. Composition API，逻辑复用更清晰；2. 响应式系统从 Object.defineProperty 改为 Proxy，支持数组索引和新增属性；3. 多根节点（Fragment）；4. Teleport/Suspense 内置组件；5. 性能：静态提升、PatchFlag、Tree-shaking；6. TypeScript 全量支持。', '内部示例', NULL)
ON DUPLICATE KEY UPDATE question_text = VALUES(question_text);

-- ============================================================
-- 种子数据：少量示例成就
-- ============================================================

INSERT IGNORE INTO achievements (code, name, description, icon, category, condition_type, condition_value, exp_reward, rarity) VALUES
('first_interview',     '初出茅庐', '完成第一次面试模拟',           '🎯', 'INTERVIEW', 'total_interviews', 1,   50,  'COMMON'),
('streak_7_days',       '坚持一周', '连续 7 天练习',                '🔥', 'STREAK',    'streak_days',      7,   100, 'RARE'),
('interviews_10',       '十战十胜', '完成 10 次面试模拟',           '🏅', 'INTERVIEW', 'total_interviews', 10,  200, 'RARE'),
('best_score_90',       '高分玩家', '单次面试得分超过 90',          '⭐', 'SCORE',     'best_score',       90,  150, 'EPIC'),
('questions_50',        '题海健将', '累计答题 50 道',                '📚', 'QUESTION',  'total_questions',  50,  100, 'COMMON')
ON DUPLICATE KEY UPDATE name = VALUES(name);
