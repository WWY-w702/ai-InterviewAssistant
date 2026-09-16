# AI-InterviewAssistant 分批修复计划

> 原则：分批、隔离、小 PR、先安全后功能、强制最小改动、每批自测。
> 本文件本身受 `.gitignore:36`（`*.md`）影响，入库需 `git add -f`，或先做 Batch 0-C。

---

## 0. 先决条件（不解决则所有批次无法验证）

| # | 阻塞项 | 现状（已实测） | 需要谁做 |
|---|---|---|---|
| 0-A | **无推送权限** | `git push --dry-run origin main` → `403 Permission to fcary-love/AI-InterviewAssistant.git denied to WWY-w702` | 你 |
| 0-B | **无提交身份** | `user.name` / `user.email` 在仓库级和 `--global` 均为空 | 你 |
| 0-C | **`schema.sql` 未入库** | `git check-ignore` 命中 `.gitignore:39`；`git ls-files` 无此文件。全新克隆后 26 张表全缺，而 `continue-on-error: true` 会吞掉初始化失败 | 我（Batch 0） |
| 0-D | **启动脚本不加载 `.env`** | `scripts/start-backend.ps1` 只跑 `mvn spring-boot:run`，Spring Boot 不读 `.env` | 我（Batch 0） |
| 0-E | **无 Docker** | `docker` 命令不存在 → `docker-compose.yml` / Dockerfile 无法验证，RAG 无法端到端自测 | 你（装 Docker Desktop） |
| 0-F | **Redis 无 RediSearch** | 本机 Redis 5.0.14.1，`FT._LIST` → `unknown command`；`MODULE LIST` 为空。RAG/向量库功能跑不了 | 你（Docker 后跑 redis-stack） |

### 0-A / 0-B 解除步骤（你的动作）

```bash
# 1) 配置提交身份（换成你自己的）
git config --global user.name  "你的名字"
git config --global user.email "你的邮箱"

# 2) 换掉缓存的 GitHub 凭据（当前是 WWY-w702，无权写）
#    Windows: 控制面板 → 凭据管理器 → Windows 凭据 → 删除 git:https://github.com
#    或用 GCM 重新登录 fcary-love 账号：
git credential-manager github logout fcary-love
git credential-manager github login  fcary-love
```

替代方案：把 `WWY-w702` 加为 `fcary-love/AI-InterviewAssistant` 的 Collaborator（Settings → Collaborators），则无需换凭据。

确认通过：`git push --dry-run origin main` 不再 403。

---

## 1. 铁律（每批强制遵守）

1. **一批一支一 PR**：分支名 `{sec|fix|chore}/<短横线主题>`，从最新 `main` 切出，不叠罗汉。
2. **最小改动**：只动与该批问题直接相关的行。**禁止**顺手改名、重排 import、改缩进、换格式、升级无关依赖。
3. **不加新依赖**，除非该批不引入就无法修复（需在 PR 描述里单独说明理由）。
4. **不混关注点**：安全分支里不夹带功能改动，反之亦然。
5. **每批必须留自测证据**：PR 描述里贴「改动前的失败输出」+「改动后的通过输出」。
6. **红灯先行**：先写能复现问题的命令并确认它失败（红），再改代码，再确认它通过（绿）。
7. **不许批量提交**：一个 PR 里 `git log` 超过 3 个提交就要考虑拆分。
8. **改不动就停**：若某批发现改动会外溢到 3 个以上无关文件，停下来报告并拆批，不要硬做。

---

## 2. 每批自测协议

每批按下表执行，缺一项视为未完成。

### 2.1 通用门禁（所有批次）

```bash
# G1 后端编译
cd backend && mvn -o -q -DskipTests compile

# G2 后端单测（55 个用例。注意：main 上现有 7 个失败，见第 7 节）
# 首次运行需联网拉 surefire 插件，之后可加 -o
cd backend && mvn test

# G3 前端构建（涉及 frontend/ 时）
cd frontend && npm run build
```

### 2.2 专属复现（红→绿）

每批在 `scripts/verify/<batch>.sh` 下放一个可执行脚本，形态固定：

```
改动前执行 → 必须失败（复现问题）
改动后执行 → 必须通过
```

脚本提交进 PR，作为证据。

### 2.3 回归冒烟（后端批次）

```bash
# 启动（Batch 0 之后会有一个加载 .env 的脚本）
./scripts/start-backend.sh &        # 或 .ps1

curl -s -o /dev/null -w "health=%{http_code}\n" http://localhost:8002/api/health          # 期望 200
U="smoke_$(date +%s)"
curl -s -X POST http://localhost:8002/api/auth/register -H "Content-Type: application/json" \
  -d "{\"username\":\"$U\",\"password\":\"Probe123456\",\"displayName\":\"smoke\"}"         # 期望 code 200
curl -s -X POST http://localhost:8002/api/auth/login -H "Content-Type: application/json" \
  -d "{\"username\":\"$U\",\"password\":\"Probe123456\"}"                                  # 期望 200 + token
curl -s -o /dev/null -w "noauth=%{http_code}\n" http://localhost:8002/api/interviews/reports # 期望 401
```

**基线已实测确认**（2026-09-16）：后端 3.3 秒启动成功；`/api/health` 无 token → 401、带 token → 200；`/api/interviews/*` 无 token → 401；用户 id 自增，首个注册用户 id=2。

### 2.4 证据留档格式

PR 描述里附：

```
## 自测证据
### 改动前（红）
$ <命令>
<输出：显示问题存在>

### 改动后（绿）
$ <命令>
<输出：显示问题消失>

### 回归
$ <G1/G2/G3 + 冒烟>
<输出>
```

---

## 3. 批次总表

| 波 | 批次 | 分支 | 类型 | 预估改动量 |
|---|---|---|---|---|
| 0 | 基线可运行性 | `chore/baseline-runnable` | 阻塞 | 小 |
| 0.5 | 测试绿基线（见第 7.1 节） | `chore/green-test-baseline` | 阻塞 | 中 |
| 1 | JWT 密钥加固 | `sec/jwt-secret-hardening` | 安全 | 小 |
| 1 | 静态资源越权读 | `sec/files-access-control` | 安全 | 中 |
| 1 | 错误响应信息泄漏 | `sec/error-handler-hygiene` | 安全 | 小 |
| 1 | 路径穿越 | `sec/path-traversal-image-url` | 安全 | 小 |
| 1 | 回放接口越权 | `sec/replay-ownership` | 安全 | 小 |
| 1 | 错题标记越权 | `sec/question-review-ownership` | 安全 | 小 |
| 1 | 前端存储型 XSS | `sec/frontend-report-xss` | 安全 | 小 |
| 2 | PDF 接口越权 + 上传落库 | `sec/pdf-endpoint-ownership` | 安全 | 大 |
| 2 | 认证面加固 | `sec/auth-surface-hardening` | 安全 | 小 |
| 3 | SSE 流式全空 | `fix/sse-stream-decode` | 功能 | 小 |
| 3 | compare 恒失败 | `fix/compare-interviews-null-user` | 功能 | 小 |
| 3 | 关键词表缺失 | `fix/missing-keyword-annotations-table` | 功能 | 小 |
| 3 | RAG 缺 Qualifier | `fix/rag-vectorstore-qualifier` | 功能 | 小 |
| 3 | Agent NPE | `fix/agent-map-of-npe` | 功能 | 小 |
| 3 | 报告未落库 | `fix/report-not-saved` | 功能 | 小 |
| 4 | 评分/JSON 解析健壮性 | `fix/scoring-robustness` | 功能 | 中 |
| 4 | 后台重复评分 | `fix/duplicate-background-scoring` | 功能 | 中 |
| 4 | 知识图谱死代码 | `fix/knowledge-graph-question-id` | 功能 | 中 |
| 4 | 出题去重失效 | `fix/rag-dedup-question-id` | 功能 | 中 |
| 5 | 事务与原子更新 | `fix/transactional-atomic-updates` | 数据 | 大 |
| 5 | 前端语音重复 | `fix/speech-transcript-duplication` | 功能 | 小 |
| 5 | 登录 401 误报 | `fix/login-401-message` | 功能 | 小 |
| 5 | 报告历史死路径 | `fix/report-history-deadpath` | 功能 | 小 |
| 6 | 图表/动画泄漏 | `fix/frontend-resource-leaks` | 功能 | 中 |
| 6 | 依赖 CVE 升级 | `chore/dependency-upgrade` | 安全 | 中 |
| 7 | 部署配置 | `chore/deploy-hardening` | 部署 | 中 |
| 8 | 清理死代码 | `chore/remove-dead-code` | 清理 | 中 |
| 9 | 架构重构 | `refactor/*` | 重构 | 大 |

---

## 4. 各批详细

### Batch 0 — `chore/baseline-runnable`

**为什么必须最先**：后续每批都要"启动后端验证"，而当前仓库克隆下来跑不起来，且启动脚本不加载 `.env`。

**改动**
- 提交现有 3 个未提交改动（`application.yml` 的 `encoding: UTF-8`、两个 `.ps1` 的路径改成相对路径）——它们已存在，先入库让后续 diff 干净
- `.gitignore`：移除 `backend/src/main/resources/schema.sql`（第 39 行）；`*.md`（第 36 行）改为不忽略源码树内的文档
- `git add -f backend/src/main/resources/schema.sql` 并入库
- 新增 `scripts/start-backend.sh`：`set -a; . ./.env; set +a` 后启动（本地开发用；`.ps1` 同步改）
- 新增 `scripts/verify/smoke.sh`：第 2.3 节的冒烟脚本

**自测**
- 红：`git ls-files backend/src/main/resources/` 只有 `application.yml`
- 绿：同上命令出现 `schema.sql`
- 红：不加载 `.env` 直接 `mvn spring-boot:run` → 连接数据库失败
- 绿：`./scripts/start-backend.sh` → `Started PdfReaderBackendApplication in ~3.3s`
- 冒烟：`scripts/verify/smoke.sh` 全绿

**风险**：`schema.sql` 进 public 仓库会公开表结构（26 张表）。若你不想公开，改为把 DDL 移至 `db/migration/` 并同样入库——**但必须入库**，否则新克隆永远跑不起来。`continue-on-error: true` 建议同批改为 `false`（让 schema 失败变成启动失败）。

---

### Batch 1.1 — `sec/jwt-secret-hardening`

**问题**：`application.yml:71` 默认密钥 `your-jwt-secret-must-be-at-least-24-characters` 长 46，而 `JwtService.java:77` 只校验 `length >= 24` → 校验通过、服务正常启动并用公开密钥签名 → 可伪造任意 `uid` 的 token。`docker-compose.yml:53` 另有兜底 `face-ai-docker-secret-change-me-2026`。

**最小改法**
- `application.yml:71`：改成 `${JWT_SECRET}`（去掉默认值）
- `JwtService.java:77`：校验改为「非空 且 不在占位串黑名单 且 长度 ≥ 32」，不满足直接抛异常让启动失败
- 占位串黑名单常量：上述两个串
- **不动** token TTL、不动 `parseToken` 结构（避免外溢）

**自测**
- 红：`JWT_SECRET=your-jwt-secret-must-be-at-least-24-characters mvn spring-boot:run` → 当前会正常启动
- 绿：同命令 → 启动失败，报错明确指出密钥为占位值
- 绿：不设 `JWT_SECRET` → 启动失败
- 绿：`JWT_SECRET=<32+ 随机串>` → 正常启动，冒烟通过

**依赖**：Batch 0（否则 `.env` 不生效）。

---

### Batch 1.2 — `sec/files-access-control`

**问题（已实测复现）**：`WebConfig.java:44-52` 把存储根目录映射到 `/files/**`，`AuthInterceptor.java:49-53` 显式放行。实测 `curl http://localhost:8002/files/uploads/probe.txt` **无任何认证头**返回 `HTTP 200` + 文件内容，同一时刻 `/api/documents` 返回 401。

**最小改法**（二选一，PR 里说明选择）
- **方案 A（推荐）**：拦截器路径从 `/api/**` 扩展为 `/api/**` + `/files/**`，前端下载改走带 token 的路径
- **方案 B（改动更小，兼容性更好）**：保留静态映射，但在 `AuthInterceptor` 里对 `/files/` 校验「签名 query 参数 + 过期时间」

**注意**：A 会影响前端所有 `fileUrl` 用法（`img src`、下载链接无法带 `Authorization` 头），需要前端同批改为 `fetch` + blob 或签名 URL。**这是本批会外溢的地方，若前端改动超过 3 个文件，拆成 `sec/files-access-control`（后端）+ `fix/frontend-file-download`（前端）两批。**

**自测**
- 红：`curl -s -o /dev/null -w "%{http_code}" http://localhost:8002/files/uploads/probe.txt` → 200（无认证）
- 绿：同命令 → 401
- 绿：带合法 token 的前端下载流程正常（手动点一次简历下载）

---

### Batch 1.3 — `sec/error-handler-hygiene`

**问题**：`GlobalExceptionHandler.java:23-27` 把 `ex.getMessage()` 原样回显（泄漏 SQL/Redis/上游返回体），且**整个文件没有任何日志**，500 堆栈根本不落盘。

**最小改法**
- 加 SLF4J logger，`handleGeneric` 里 `log.error("未处理异常", ex)`
- 响应体改为固定文案（如「服务异常，请稍后重试」），不带 `ex.getMessage()`
- **不动**其他 handler 的语义

**自测**
- 红：制造一个 500（如 `AgentService.java:26` 对 null message 的 NPE：`POST /api/agent/chat` body `{"sessionId":null,"message":null}`）→ 响应体含内部细节，日志文件里没有堆栈
- 绿：同请求 → 响应体是固定文案；日志里出现完整堆栈

---

### Batch 1.4 — `sec/path-traversal-image-url`

**问题**：`ImageDescriptionRequest.java:5-9` 的 `imageUrl` 只有 `@NotBlank`；`PdfService.java:155-161` 的 `resolveStoredFileFromUrl` 归一化后不校验是否越出根目录。`POST /api/pdf/{fileId}/images/describe {"imageUrl":"/files/../../../.env"}` 可读任意文件并 Base64 发给 DashScope。

**最小改法**
- `PdfService.resolveStoredFileFromUrl`：`normalize()` 后加 `resolved.startsWith(root)` 断言，否则抛异常
- 顺带给 `imageUrl` 加 `@Pattern(regexp = "^/files/.*")` 前缀白名单
- **不动**其他路径解析逻辑（`PdfService.java:58/151-153`、`ImageService`、`OcrService` 的 `resolve(fileId)` 同族问题留下一批）

**自测**
- 红：`curl -X POST .../images/describe -d '{"imageUrl":"/files/../../../.env"}'` → 能读到文件（或触发 AI 外传）
- 绿：同请求 → 400/403，且日志显示路径越界被拒

---

### Batch 1.5 — `sec/replay-ownership`

**问题**：`InterviewRepository.java:115-126` 的 `findTurnsBySessionId` SQL 只有 `WHERE session_id = ?`，缺 `user_id`（对比 `findBySessionId:53-63` 是带的）。`ReplayController.java:36-58` 三个接口全部直接透传 path 里的 `sessionId`。且 `InterviewReplayService.java:62-69` 会以任意 sessionId **写** `turn_keyword_annotations`。

**最小改法**
- `findTurnsBySessionId` 加 `s.user_id = ?` 关联，签名加 userId 参数
- 调用方（`InterviewReplayService.java:53-133` 三处）传 `AuthContext.currentUserId()`
- **不动** `compareInterviews`（那是 Batch 3.2）

**自测**
- 红：用户 B 用用户 A 的 sessionId 调 `GET /api/interviews/{A的sessionId}/replay` → 200 且返回 A 的数据
- 绿：同请求 → 404/403

---

### Batch 1.6 — `sec/question-review-ownership`

**问题**：`InterviewQuestionRepository.java:170-175` 的 `UPDATE interview_turns SET reviewed = TRUE WHERE session_id = ? AND question_no = ?` 没有 user_id 关联（同类查询 `listWrongQuestions:143-168` 是有的）。

**最小改法**：UPDATE 加 `AND EXISTS (SELECT 1 FROM interview_sessions s WHERE s.session_id = interview_turns.session_id AND s.user_id = ?)`，调用方传当前用户。

**自测**
- 红：用户 B 调 `POST /api/questions/wrong/{A的sessionId}/{题号}/review` → 能改 A 的状态
- 绿：同请求 → 404/403

---

### Batch 1.7 — `sec/frontend-report-xss`

**问题**：`useInterviewConfig.js:405` 把 AI 生成的 `reportContent` / `summary` 未经转义拼进 `container.innerHTML`。内容由简历 + JD + 回答驱动，可被 prompt 注入污染。配合 token 存 localStorage，可直接窃取 token。

**最小改法**
- 引入一个转义函数（`& < > " '`），对插值的每个字段套用
- 或改用 `textContent` 分节点赋值
- **不引入** DOMPurify 之类新依赖（保持最小改动；若你偏好库方案，PR 里说明）

**自测**
- 红：构造一份含 `<img src=x onerror=alert(1)>` 的报告内容（可临时 mock 后端响应），点「下载报告」→ 弹窗
- 绿：同操作 → 页面显示原始文本，无脚本执行

---

### Batch 2.1 — `sec/pdf-endpoint-ownership`（最大的一批，注意拆）

**问题**：
- `PdfController.java:59-62` 的 `upload` **从不调 `DocumentRepository.save`** → 文件与用户无关联记录
- `PdfService.java:40-55 / 57-73`、`ImageService.java:26-45 / 47-66` 只按 `fileId` 定位，签名里没有 userId
- 受影响：`/api/pdf/{fileId}`、`/image/{fileId}`、`/{fileId}/ocr`、`/summary`、`/qa`、`/images/describe`、`/rag/*`
- `PdfRagIndexService.java:45-66` 的向量 metadata 也没有 userId（对比 `KnowledgeRagIndexService.java:55` 是有的）→ RAG 检索可跨用户

**最小改法**
1. `upload` 落库（复用 `DocumentRepository.save`，与 `/api/documents/upload` 同构）
2. `PdfService` / `ImageService` 所有按 fileId 的读方法加 userId 参数，内部 `findByFileId(userId, fileId)` 校验
3. `PdfRagIndexService` metadata 加 `userId`，检索 filter 加 `userId == '...'`

**建议拆成 3 个小 PR**：`sec/pdf-upload-persist` → `sec/pdf-read-ownership` → `sec/pdf-rag-ownership`。**第 1 个不做完，第 2 个无从校验。**

**自测**：用户 A 上传 → 用户 B 用 A 的 fileId 调各接口 → 期望全部 404/403。

---

### Batch 2.2 — `sec/auth-surface-hardening`

**问题**：Swagger 未鉴权（实测 `/v3/api-docs` → 200）；登录无失败节流（`AuthService.java:41-49`）；注册返回「账号已存在」可枚举用户名（`:30-32`）；密码下限仅 6 位（`AuthRegisterRequest.java:13`）；token 无吊销机制且 TTL 168 小时。

**最小改法**：关掉生产 profile 的 springdoc；加一个基于内存的登录失败计数（不做持久化，避免引入 Redis 依赖）；注册错误文案统一。**TTL 和吊销机制不在这批**（会外溢到 token 结构）。

**自测**：`/v3/api-docs` → 404/403；连续 6 次错密码 → 第 6 次被拒。

---

### Batch 3.x — 功能修复（每批都是几行代码）

| 批次 | 问题位置 | 最小改法 | 自测（红→绿） |
|---|---|---|---|
| `fix/sse-stream-decode` | `AiClient.java:173` | `bodyToFlux(String.class)` 走 Spring SSE 解码器时 `data:` 前缀已被剥离，`startsWith("data:")` 恒 false → 所有 chunk 被丢弃。改为不依赖前缀（或改用 `ServerSentEvent` 类型接收） | 调 `POST /api/interviews/{id}/answer/stream` → 红：只有元数据无正文；绿：逐字返回 |
| `fix/compare-interviews-null-user` | `InterviewReplayService.java:139-140` | 传 `null` userId，而 SQL 是 `WHERE user_id = ?`，`= NULL` 恒不成立 → 必然抛「报告不存在」。改为传 `AuthContext.currentUserId()` | `POST /api/interviews/compare?sessionId1=..&sessionId2=..` → 红：必然 400；绿：返回对比结果 |
| `fix/missing-keyword-annotations-table` | `schema.sql` 无 `turn_keyword_annotations`（26 张表里没有），`InterviewReplayService.java:62-69` 引用它 | 补建表 DDL | `POST /api/interviews/{id}/keywords/annotate` → 红：200 + 空数组（异常被 `:72` 吞掉）；绿：正常返回关键词；`GET .../keywords` 红：500 → 绿：200 |
| `fix/rag-vectorstore-qualifier` | `PdfRagIndexService.java:36`、`SinglePdfRagQaSkill.java:24` 缺 `@Qualifier`，容器有 3 个 VectorStore 无 `@Primary` | 加 `@Qualifier("vectorStore")`（与该文件其他类写法对齐） | `POST /api/pdf/{fileId}/rag/index` → 红：报「Redis 向量库不可用」；绿：正常索引（需 RediSearch） |
| `fix/agent-map-of-npe` | `AgentEngine.java:65-77 / 98-105` 用 `Map.of` 装 `arguments`/`id`，模型不返回该字段时 NPE 逃出 try/catch | try/catch 范围扩到回填消息；或用允许 null 的 map | `POST /api/agent/chat` 触发无参工具调用 → 红：500「服务异常: null」；绿：正常继续 |
| `fix/report-not-saved` | `InterviewService.java:315-321` 构造了 response 但没调 `saveReport`（`refineReport:363` 有） | 补一次 `saveReport` | 生成报告后 `GET /api/interviews/{id}/report` → 红：404「请先生成报告」；绿：200 |

---

### Batch 4.x — 健壮性

| 批次 | 问题 | 最小改法 |
|---|---|---|
| `fix/scoring-robustness` | ① `ScoringService.java:148` `subList(1, size-1)` 在 size==2 时为空 → `.orElse(50)`，两次成功评分算成 50 分；② `ScoringService.java:29`、`InterviewService.java:38`、`InterviewReplayService.java:32` 用贪婪正则 `\{[\s\S]*\}`，多个 JSON 对象时必错 | ① 对 size<=2 直接取平均；② 换成非贪婪 + 括号配平扫描 |
| `fix/duplicate-background-scoring` | `InterviewService.java:191`、`:266` 后台线程重算已算过的分并覆写 DB → 返回值与后续读取不一致，每答一题最多 9 次 LLM 调用；且 lambda 异常完全静默 | 删掉 `:191`/`:266` 的后台重算；若确需异步，抽 `ThreadPoolTaskExecutor` 并挂 `exceptionally` 打日志 |
| `fix/knowledge-graph-question-id` | `InterviewService.java:168`、`:249` 传 `updateMastery(userId, null, ...)` → questionId 恒 null → `user_knowledge_mastery` 永远空，技能树/学习建议/两个 Agent 工具全部失效 | 传入真实 questionId（需要题目记录的 id 可用） |
| `fix/rag-dedup-question-id` | `InterviewService.java:453` 传题目**文本**，`InterviewRagService.java:102` 比对**数字 ID**，永匹配不上 → 出题去重 0% 生效 | 统一为 ID 比对 |

---

### Batch 5.1 — `fix/transactional-atomic-updates`（数据正确性）

**问题**：全项目 `@Transactional` 只有 1 处（`AuthRepository.java:64`）。ELO 和游戏化积分是「读绝对值 → Java 计算 → 写绝对值」（`GamificationRepository.java:42-53` 全是 `= ?`），并发必丢更新；`claimDailyTask` 先查后写非原子，双击可重复领奖；`settleInterview` 5 次写无事务。

**最小改法**
- 把积分/ELO 改成 SQL 内原子自增（`SET exp_points = exp_points + ?`，ELO 用 `LEAST/GREATEST` 在 SQL 内完成）——**比加 `@Transactional` 更有效**，因为丢更新来自「读-算-写」而非缺事务
- `claimDailyTask` 的 UPDATE 加 `AND claimed = FALSE` 并校验受影响行数
- `settleInterview` 加 `@Transactional`

**自测**：并发脚本对同一用户并发提交 N 次结算，断言最终经验值 == 期望值（红：小于期望；绿：相等）。

---

### Batch 5.2-5.4 — 前端功能修复

| 批次 | 问题位置 | 最小改法 |
|---|---|---|
| `fix/speech-transcript-duplication` | `InterviewSetupPanel.vue:305-316` watcher 每次把**累积值**整体追加 → 文字层层重复 | 记录已消费 offset，只取增量 |
| `fix/login-401-message` | `api/auth.js:4` 也用带全局拦截器的实例，密码错 401 → 清登录态 + 弹「登录已过期」 | 登录/注册请求跳过全局 401 拦截 |
| `fix/report-history-deadpath` | `useInterviewDesk.js:45` 的 `activeMode` 全项目无赋值点（死代码），报告历史列表首次进入永远为空 | 在 `ReportsPage` 挂载时直接调 `handleLoadReportHistory()` |

---

### Batch 6.1 — `fix/frontend-resource-leaks`

**问题**：5 个 ECharts 组件（`ScoreRadar.vue:37`、`ScoreTrend.vue:28`、`TimeDistributionChart.vue:47`、`DifficultyTrajectoryChart.vue:25`、`SkillTreePanel.vue:129`）不 `dispose`，resize 监听用匿名箭头函数无法移除；`useGsapMotion.js:12-17` 的 `gsap.context` 传的是空函数，`revert()` 清理不了任何动画。

**最小改法**：按 `GrowthDashboardPanel.vue:87-97` 的正确写法（命名 handler + `removeEventListener` + `dispose()`）逐个对齐；`gsap.context` 的箭头函数改为在里面创建动画。

---

### Batch 6.2 — `chore/dependency-upgrade`

**CVE**：Tomcat 10.1.31（CVE-2025-24813，修复于 10.1.35）；Spring Framework 6.1.14（CVE-2024-38819）；`poi-ooxml 4.1.2`（2019 年，XXE + commons-compress 1.19 的多个 CVE），而它正解析用户上传的 .docx。

**最小改法**：先只升 patch（Spring Boot 3.3.5 → 3.3.8+ 解决 Tomcat/Spring Framework），`poi-ooxml` 4.1.2 → 5.4.x 单独一批（可能改 API）。Spring Boot 3.3.x OSS 支持已于 2025-06 结束，升 3.5.x 是更大动作，**单独一批**。

**自测**：G1/G2 编译测试通过 + 完整冒烟 + docx 上传解析一次。

---

### Batch 7 — `chore/deploy-hardening`

- `.dockerignore`（当前不存在，`frontend/Dockerfile:5` 的 `COPY . .` 会覆盖成 Windows 版 node_modules → 构建失败）
- `nginx.conf` 加 `client_max_body_size 20m`（当前默认 1m，而后端配 20MB → 所有 >1MB 上传 413）
- Redis 加 `--requirepass` 并去掉 6379/8001 对外端口
- compose 里 `latest` 标签钉版本；去掉 `container_name`
- backend/frontend 加 healthcheck（注意：`/api/health` 当前**需要鉴权**，需先放行或改用其他探针）
- 容器改非 root

**自测**：需 Docker Desktop。`docker compose build` 成功 + `docker compose up` 后从 80 端口上传一个 >1MB 的 PDF 成功。

---

### Batch 8-9 — 清理与重构（最后）

- Batch 8：删 8 个死组件（1261 行）+ `LightWorkbench.vue`（1093 行，含 `startInterview(){}` 空函数和编造的「击败 87.4%」文案）；或给它接真实接口
- Batch 9：引入 Pinia（当前模块级单例已导致 `jdText` 两个 ref 互相覆盖）；13 个 axios 实例合一；错误提示模板（复制 44 次）抽公共函数；中文枚举从接口契约里拆出；按需引入 element-plus / echarts；移除为 4 个动效背上的 6.4MB GSAP

---

## 5. 执行顺序与节奏

```
Batch 0（阻塞，必须先做）
   ↓
Wave 1  安全低成本高收益：1.1 → 1.7    （每批一个小 PR，互不依赖，可并行审查）
   ↓
Wave 2  安全较大改动：2.1 → 2.2        （2.1 需拆成 3 个小 PR 串行）
   ↓
Wave 3  功能确定性 bug：SSE / compare / 缺表 / Qualifier / NPE / 报告落库
   ↓
Wave 4  健壮性：评分 / 重复评分 / 知识图谱 / 去重
   ↓
Wave 5  数据正确性（事务与原子更新）+ 前端小修
   ↓
Wave 6  资源泄漏 + 依赖 CVE 升级
   ↓
Wave 7  部署加固（需 Docker）
   ↓
Wave 8-9 清理与重构
```

**每批的退出条件**：G1/G2/G3 通过 + 专属复现红→绿 + 冒烟通过 + PR 描述含证据 + 已合并到 main。任一项不满足，不进入下一批。

---

## 6. 需要你决定的点

1. **`schema.sql` 进 public 仓库**：会公开 26 张表结构。接受 / 改放 `db/migration/` / 其他
2. **`/files/**` 改法**：方案 A（移除静态映射 + 前端改 fetch）还是方案 B（签名 URL）
3. **前端 XSS 修复**：手写转义（零依赖）还是引入 DOMPurify
4. **Batch 6.2 依赖升级范围**：只升 patch，还是顺势升到 Spring Boot 3.5.x（风险更大但能拿到后续安全补丁）
5. **推送到哪个分支**：`main` 直推，还是走 `fix/*` 分支 + PR（推荐后者，符合「小 PR」要求）

---

## 7. Batch 0 执行期间实测发现的计划外问题

### 7.1 【阻塞 G2】main 上测试套件本来就是红的

`mvn test` 实测：**Tests run: 55, Failures: 2, Errors: 5**。这直接推翻了「每批 G2 必须全绿」这条门禁——绿基线不存在，后续每批都无法判断「是不是我改坏的」。

| 失败项 | 性质 | 根因 |
|---|---|---|
| `JwtServiceTest.parseToken_throwsForExpiredToken` | **确定性失败**（连跑 5 次全失败） | 测试用 `TTL=0` 造过期 token（`exp == now`），而 `JwtService.java:56` 判断是 `Instant.now() > expiresAt`（严格大于）→ 同秒内不抛异常 |
| `ScoringServiceTest.evaluateWithConfidence_returnsScores_whenAiReturnsValidJson` | 断言不符 | `expected: <75> but was: <74>` —— 正是第 4 波 `fix/scoring-robustness` 要修的取平均逻辑 |
| `PdfReaderBackendApplicationTests.contextLoads` | 环境依赖 | `@SpringBootTest` 零断言，但需 MySQL + Redis（含 RediSearch），无 CI 也跑不到 |
| `EloRatingServiceTest` ×2 | Mockito 严格桩漂移 | `UnnecessaryStubbing` |
| `InterviewServiceTest` ×2 | Mockito 严格桩漂移 | `PotentialStubbingProblem` |

**新增 Batch 0.5 — `chore/green-test-baseline`**：把这 7 个失败处理掉，产出真正可用的绿基线。按关注点再拆：
- `fix/jwt-expiry-boundary`：确认代码用 `>` 是否有意为之（1 秒宽限），还是测试该换成真正的过期 token
- `fix/scoring-trim-average`：并入第 4 波
- `chore/quarantine-context-loads-test`：`@SpringBootTest` 加 `@Disabled` + 注明原因，或补 `src/test/resources/application.yml` + Testcontainers
- `chore/fix-strict-stub-drift`：修 EloRatingServiceTest / InterviewServiceTest 的桩

在 Batch 0.5 完成前，门禁降级为**「本批不得新增失败」**（与 7.1 表格逐项比对）。

### 7.2 其他实测发现

- **404 被转成 500**：`curl /api/documents` → `{"code":500,"message":"服务异常: No static resource api/documents."}`。`GlobalExceptionHandler` 缺 `NoResourceFoundException` handler。归入 Batch 1.3。
- **`/api/health` 需要鉴权**：无 token → 401，带 token → 200。部署批次想用它做 Docker healthcheck 必须先放行，否则容器永远 unhealthy。
- **`/api/documents` 只有 POST，没有 GET**（`/upload`、`/{fileId}/summary`、`/{fileId}/qa`、`/{fileId}/qa/stream`）。写冒烟脚本时容易假设错。
- **`user_profiles` 没有 `user_id` 列**：`app_users.id` 无 `AUTO_INCREMENT`，靠复用 `user_profiles` 的自增 id。写数据清理/迁移脚本时按 `id` 对齐，不要按 `user_id`。
- **测试门禁首次运行需联网**：本地 `~/.m2` 没缓存 surefire 插件（此前只跑过 `compile`），`mvn -o test` 会失败。

### 7.3 Batch 0 已完成内容与证据

**红灯**
```
$ git ls-files backend/src/main/resources/
backend/src/main/resources/application.yml          # schema.sql 不在其中
$ cd backend && mvn -o spring-boot:run
Caused by: java.sql.SQLException: Access denied for user 'root'@'localhost' (using password: YES)
BUILD FAILURE                                        # 不加载 .env 根本起不来
```

**绿灯**
```
$ ./scripts/start-backend.sh
已加载 /d/.../.env
Started PdfReaderBackendApplication in 2.743 seconds

$ ./scripts/verify/smoke.sh
冒烟测试 → http://localhost:8002
[1] 未认证访问受保护接口应被拒
  ✅ GET /api/interviews/reports 无 token (期望 401，实际 401)
  ✅ GET /api/profile/overview 无 token (期望 401，实际 401)
  ✅ GET /api/pdf/anything 无 token (期望 401，实际 401)
[2] 注册与登录
  ✅ 账号已存在，改用登录
[3] 携带 token 的访问
  ✅ GET /api/health (期望 200，实际 200)
  ✅ GET /api/profile/overview (期望 200，实际 200)
  ✅ GET /api/interviews/reports (期望 200，实际 200)
  ✅ GET /api/agent/sessions (期望 200，实际 200)
通过 8 项，失败 0 项
```

**改动清单**
- `.gitignore`：移除 `*.md` 与 `backend/src/main/resources/schema.sql` 两条规则
- `backend/src/main/resources/schema.sql`：入库（423 行、26 张表）
- `BATCH-PLAN.md`：入库
- `scripts/start-backend.sh`（新增）：加载 `.env` 后启动
- `scripts/verify/smoke.sh`（新增）：8 项冒烟
- `scripts/start-backend.ps1`：补 `.env` 加载

