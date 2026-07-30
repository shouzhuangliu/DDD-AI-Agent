<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue';

const defaultApiBase = import.meta.env.VITE_API_BASE || '/api/v1';
const apiBase = ref(localStorage.getItem('ai-agent-api-base') || defaultApiBase);
const showSettings = ref(false);
const tab = ref('dashboard');
const loading = ref(false);
const loadingVisible = ref(false);
let loadingCount = 0;
let loadingTimer = null;
const error = ref('');
const NO_FALLBACK = Symbol('no-fallback');

const agents = ref([]);
const selectedAgentId = ref('');
const stats = ref({});
const topCases = ref([]);
const cases = ref([]);
const agentProfile = ref(null);
const caseStatusFilter = ref('');
const selectedCase = ref(null);
const selectedCaseDetail = ref(null);
const selectedFeedback = ref(null);
const feedback = ref([]);
const signals = ref([]);
const memories = ref([]);
const sessions = ref([]);
const currentSessionId = ref('');
const sessionDetail = ref(null);
const chatMessagesRef = ref(null);
const chatInput = ref('');
const chatStream = ref([]);
const isStreaming = ref(false);
const chatStatus = ref('');
const currentExecutionId = ref('');
const subagentTasks = reactive({});
const todoItems = ref([]);
const chatModelId = ref('');
const models = ref([]);
const mcpServers = ref([]);
const selectedMcpServerId = ref('');
const mcpVersions = ref([]);
const skillPackages = ref([]);
const localSkills = ref([]);
const selectedSkillPackageId = ref('');
const selectedLocalSkillId = ref('');
const localSkillDetail = ref(null);
const skillVersions = ref([]);
const logs = ref([]);
const selectedLogAgent = ref('');
const logSessionQuery = ref('');
const selectedLogSessionKey = ref('');
const agentToolOptions = ref([]);
const agentSkillOptions = ref([]);
const agentMcpOptions = ref([]);
const agentBindingSummary = ref({});
const agentBindingDetail = ref(null);
let bindingOptionsPromise = null;
let bindingOptionsLoadedAt = 0;

const modal = reactive({
  open: false,
  kind: '',
  mode: 'create',
  title: '',
  saving: false,
  form: {},
  extra: {},
});

const selectedAgent = computed(() => agents.value.find(item => item.agentId === selectedAgentId.value) || null);
const selectedSession = computed(() => sessions.value.find(item => item.sessionId === currentSessionId.value) || null);
const currentAgentName = computed(() => selectedAgent.value?.agentName || selectedAgent.value?.agentId || '-');
const selectedMcpServer = computed(() => mcpServers.value.find(item => pick(item, 'id', 'ID') === selectedMcpServerId.value) || null);
const selectedSkillPackage = computed(() => skillPackages.value.find(item => String(pick(item, 'id', 'ID')) === String(selectedSkillPackageId.value)) || null);
const selectedLocalSkill = computed(() => localSkills.value.find(item => String(item.skillId) === String(selectedLocalSkillId.value)) || null);
const logAgentOptions = computed(() => agents.value.map(agent => ({
  id: agent.agentId,
  name: agent.agentName || agent.agentId,
})));
const logSessions = computed(() => logs.value
  .filter(group => !selectedLogAgent.value || group.agentId === selectedLogAgent.value)
  .flatMap(group => (group.sessions || []).map(session => ({
    ...session,
    agentId: group.agentId,
  })))
  .filter(session => !logSessionQuery.value.trim()
    || String(session.sessionId || '').toLowerCase().includes(logSessionQuery.value.trim().toLowerCase())));
const businessFeedbackItems = computed(() => feedback.value.filter(item => isBusinessFeedbackItem(item)));
const selectedLogSession = computed(() => logSessions.value.find(session => logSessionKey(session) === selectedLogSessionKey.value) || null);
const profileSections = computed(() => {
  const raw = agentProfile.value?.profileJson;
  if (!raw) return [];
  try {
    const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw;
    return Object.entries(parsed || {}).filter(([, values]) => Array.isArray(values) && values.length);
  } catch {
    return [];
  }
});

const CASE_ACTIONS = {
  CANDIDATE: [{ status: 'PENDING_REVIEW', label: '\u63d0\u4ea4\u5ba1\u6838' }],
  PENDING_REVIEW: [{ status: 'CONFIRMED', label: '\u786e\u8ba4\u6848\u4ef6' }, { status: 'IGNORED', label: '\u9a73\u56de\u95ee\u9898' }, { status: 'CANDIDATE', label: '\u9000\u56de\u521d\u6b65\u5019\u9009' }],
  CONFIRMED: [{ status: 'IN_PROGRESS', label: '\u5f00\u59cb\u5904\u7406' }, { status: 'PENDING_REVIEW', label: '\u9000\u56de\u5f85\u5ba1\u6838' }],
  IN_PROGRESS: [{ status: 'RESOLVED', label: '\u6807\u8bb0\u5df2\u89e3\u51b3' }, { status: 'CONFIRMED', label: '\u9000\u56de\u5df2\u786e\u8ba4' }],
  RESOLVED: [{ status: 'IN_PROGRESS', label: '\u91cd\u65b0\u5904\u7406' }, { status: 'ARCHIVED', label: '\u5f52\u6863' }],
  ARCHIVED: [{ status: 'CONFIRMED', label: '\u6062\u590d\u8ddf\u8e2a' }],
  IGNORED: [{ status: 'CANDIDATE', label: '\u91cd\u65b0\u63d0\u4ea4' }],
};

const FEEDBACK_ACTIONS = {
  OPEN: [
    { status: 'AI_EVALUATING', label: '提交 AI 评测' },
    { status: 'INVALID', label: '标记无效' },
  ],
  AI_EVALUATING: [
    { status: 'VALID', label: '确认可进入升级判断' },
    { status: 'NEED_MORE_INFO', label: '要求补充信息' },
    { status: 'INVALID', label: '标记无效' },
  ],
  NEED_MORE_INFO: [
    { status: 'AI_EVALUATING', label: '补充后重新评测' },
    { status: 'INVALID', label: '标记无效' },
  ],
  VALID: [
    { status: 'PROMOTED', label: '升级为 Case' },
    { status: 'CLUSTERED', label: '进入候选问题簇' },
    { status: 'INVALID', label: '判定无效' },
  ],
  CLUSTERED: [
    { status: 'PROMOTED', label: '升级为 Case' },
    { status: 'VALID', label: '退回升级判断' },
    { status: 'INVALID', label: '判定无效' },
  ],
  INVALID: [
    { status: 'OPEN', label: '重新打开' },
  ],
  PROMOTED: [
    { status: 'RESOLVED', label: '关闭反馈' },
  ],
  RESOLVED: [
    { status: 'OPEN', label: '重新打开' },
  ],
};

const CASE_STATUS_TEXT = {
  CANDIDATE: '\u5f85\u63d0\u4ea4\u5ba1\u6838',
  PENDING_REVIEW: '\u5f85\u7ba1\u7406\u5458\u5ba1\u6838',
  CONFIRMED: '\u5df2\u786e\u8ba4',
  IN_PROGRESS: '\u5904\u7406\u4e2d',
  RESOLVED: '\u5df2\u89e3\u51b3',
  ARCHIVED: '\u5df2\u5f52\u6863',
  IGNORED: '\u5df2\u9a73\u56de',
};

const CASE_STATUS_HINTS = {
  CANDIDATE: '\u6848\u4ef6\u5df2\u521d\u6b65\u5efa\u7acb\uff0c\u7b49\u5f85\u63d0\u4ea4\u7ba1\u7406\u5458\u5ba1\u6838',
  PENDING_REVIEW: '\u7ba1\u7406\u5458\u6838\u5b9e\u8bc1\u636e\u548c\u4e1a\u52a1\u4ef7\u503c',
  CONFIRMED: '\u5df2\u786e\u8ba4\u9700\u8981\u8ddf\u8e2a\uff0c\u4e0b\u4e00\u6b65\u6307\u5b9a\u8d1f\u8d23\u4eba',
  IN_PROGRESS: '\u8d1f\u8d23\u4eba\u6b63\u5728\u6392\u67e5\u548c\u5904\u7406',
  RESOLVED: '\u5df2\u5904\u7406\u5e76\u586b\u5199\u9a8c\u8bc1\u7ed3\u679c\uff0c\u53ef\u66f4\u65b0 Agent \u753b\u50cf',
  ARCHIVED: '\u5386\u53f2\u6848\u4ef6\uff0c\u9ed8\u8ba4\u4e0d\u518d\u8ddf\u8e2a',
  IGNORED: '\u5df2\u5224\u5b9a\u4e3a\u65e0\u6548\u6216\u65e0\u9700\u5904\u7406',
};

const CASE_TEXT = {
  caseWorkbench: '\u6848\u4ef6\u5de5\u4f5c\u53f0',
  allStatuses: '\u5168\u90e8\u72b6\u6001',
  refresh: '\u5237\u65b0',
  noCases: '\u6682\u65e0\u6848\u4ef6',
  profile: 'Agent \u957f\u671f\u753b\u50cf',
  noProfile: '\u6682\u65e0\u5df2\u89e3\u51b3\u6848\u4ef6\u753b\u50cf',
  flow: '\u53cd\u9988\u8fdb\u5165\u5f85\u5ba1\u6838\uff0c\u7ba1\u7406\u5458\u786e\u8ba4\u540e\u6307\u5b9a\u8d1f\u8d23\u4eba\uff0c\u5904\u7406\u5b8c\u6210\u540e\u586b\u5199\u89e3\u51b3\u65b9\u6848\uff0c\u6700\u540e\u66f4\u65b0 Agent \u957f\u671f\u753b\u50cf',
};

const dashboardCards = computed(() => ([
  { label: '今日反馈', value: stats.value.todayFeedback ?? 0 },
  { label: '业务反馈总数', value: stats.value.businessFeedback ?? stats.value.explicitFeedback ?? 0 },
  { label: '负面反馈', value: stats.value.negativeFeedback ?? 0 },
  { label: 'AI观察线索', value: stats.value.aiObservationCount ?? 0 },
  { label: '待升级反馈', value: stats.value.readyForCaseFeedback ?? 0 },
  { label: '候选Case', value: stats.value.candidateCases ?? 0 },
  { label: '待审核Case', value: stats.value.pendingCases ?? 0 },
  { label: '处理中Case', value: stats.value.inProgressCases ?? stats.value.highPriorityCases ?? 0 },
  { label: '已解决案例', value: stats.value.resolvedCases ?? 0 },
  { label: '满意度', value: `${stats.value.satisfactionRate ?? 0}%` },
]));

const STATUS_LABELS = {
  CANDIDATE: '状态',
  PENDING_REVIEW: '状态',
  CONFIRMED: '状态',
  IN_PROGRESS: '状态',
  RESOLVED: '状态',
  ARCHIVED: '状态',
  IGNORED: '状态',
  MERGED: '状态',
  OPEN: '状态',
  OBSERVED: '状态',
  AI_EVALUATING: '状态',
  NEED_MORE_INFO: '状态',
  VALID: '状态',
  VALIDATED: '状态',
  CLUSTERED: '状态',
  PROMOTED: '状态',
  INVALID: '状态',
  DRAFT: '状态',
  CONNECTIVITY_CHECKED: '状态',
  DISCOVERED: '状态',
  SCANNED: '状态',
  TESTED: '状态',
  IN_REVIEW: '状态',
  APPROVED: '状态',
  SIGNED: '状态',
  RELEASED: '状态',
  ACTIVE: '状态',
};

function pick(obj, ...keys) {
  for (const key of keys) {
    const value = obj?.[key];
    if (value !== undefined && value !== null && value !== '') return value;
  }
  return '';
}

function normalizeList(value) {
  return Array.isArray(value) ? value : [];
}

function normalizeBindingIds(selectedIds, options, ...idKeys) {
  const availableIds = normalizeList(options)
    .map(item => String(pick(item, ...idKeys) || '').trim())
    .filter(Boolean);
  const availableSet = new Set(availableIds);
  const prefixMap = new Map();
  for (const id of availableIds) {
    const dashIndex = id.lastIndexOf('-');
    if (dashIndex > 0) {
      const prefix = id.slice(0, dashIndex);
      if (!prefixMap.has(prefix)) prefixMap.set(prefix, id);
    }
  }

  const normalized = [];
  for (const rawId of normalizeList(selectedIds).map(value => String(value).trim()).filter(Boolean)) {
    if (availableSet.has(rawId)) {
      normalized.push(rawId);
      continue;
    }
    const mappedId = prefixMap.get(rawId);
    if (mappedId) normalized.push(mappedId);
  }
  return [...new Set(normalized)];
}

function joinUrl(base, path) {
  return `${String(base || '').replace(/\/$/, '')}/${String(path || '').replace(/^\//, '')}`;
}

function labelStatus(value) {
  const normalized = String(value || '').trim().toUpperCase();
  const overrides = {
    CANDIDATE: '候选案例',
    PENDING_REVIEW: '待人工审核',
    CONFIRMED: '已确认',
    IN_PROGRESS: '处理中',
    RESOLVED: '已解决',
    ARCHIVED: '已归档',
    IGNORED: '已忽略',
    MERGED: '已合并',
    OPEN: '新反馈',
    OBSERVED: '已观察',
    AI_EVALUATING: 'AI评测中',
    NEED_MORE_INFO: '需要补充信息',
    VALID: '待升级判断',
    VALIDATED: '已验证',
    CLUSTERED: '待升级 Case',
    PROMOTED: '已升级为 Case',
    INVALID: '无效反馈',
    DRAFT: '草稿',
    CONNECTIVITY_CHECKED: '连通性已验证',
    DISCOVERED: '已发现',
    SCANNED: '已扫描',
    TESTED: '已测试',
    IN_REVIEW: '审核中',
    APPROVED: '已批准',
    SIGNED: '已签名',
    RELEASED: '已发布',
    ACTIVE: '已启用',
    NEW: '新反馈',
    AI_INVALID: '无效反馈',
    PENDING_AI: '等待AI评测',
    WAITING_USER: '等待用户补充',
    READY_FOR_CASE: '可升级Case',
    NOT_PROMOTED: '未升级',
    NOT_ELIGIBLE: '不可升级',
    CLOSED: '已关闭',
  };
  return overrides[normalized] || STATUS_LABELS[normalized] || value || '';
}

function routeTypeText(value) {
  const normalized = String(value || '').trim().toLowerCase();
  return {
    chat: '普通对话',
    feedback: '反馈收集',
    react: '工具执行',
    auto: '自动编排',
    plan: '规划分析',
  }[normalized] || (value || '未识别');
}

function executionStatusLabel(value) {
  const normalized = String(value || '').trim().toUpperCase();
  return {
    RUNNING: '执行中',
    COMPLETED: '已完成',
    FAILED: '执行失败',
    CANCELLED: '已取消',
    CANCEL_REQUESTED: '取消中',
    TIMED_OUT: '已超时',
    PENDING: '排队中',
  }[normalized] || (value || '未知');
}

function feedbackSourceLabel(value) {
  const normalized = String(value || '').trim().toUpperCase();
  return {
    AI_INFERRED: '自动识别',
    EXPLICIT: '用户显式反馈',
    USER: '用户反馈',
    OPERATIONS: '运维反馈',
    TEST: '测试反馈',
  }[normalized] || (value || '反馈');
}

function isBusinessFeedbackItem(item) {
  const sourceType = String(item?.sourceType || '').trim().toUpperCase();
  return sourceType !== 'AI_INFERRED';
}

function logSessionKey(session) {
  return `${String(session?.agentId || '')}::${String(session?.sessionId || '')}`;
}

function setModal(kind, title, mode = 'create', form = {}, extra = {}) {
  modal.kind = kind;
  modal.title = title;
  modal.mode = mode;
  modal.saving = false;
  modal.open = true;
  modal.extra = { ...extra };
  Object.keys(modal.form).forEach(key => delete modal.form[key]);
  Object.assign(modal.form, form);
}

function closeModal() {
  modal.open = false;
  if (modal.kind === 'agent') {
    agentBindingDetail.value = null;
  }
}

function capHeaders(actor, role, json = true) {
  const headers = { 'X-Actor': actor, 'X-Role': role };
  if (json) headers['Content-Type'] = 'application/json';
  return headers;
}

async function readJson(response) {
  const text = await response.text();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

async function request(path, options = {}, fallback = NO_FALLBACK) {
  try {
    const headers = new Headers(options.headers || {});
    const body = options.body;
    if (body && !(body instanceof FormData) && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json');
    }
    const res = await fetch(joinUrl(apiBase.value, path), {
      ...options,
      headers,
      body: body && !(body instanceof FormData) && headers.get('Content-Type') === 'application/json'
        ? JSON.stringify(body)
        : body,
    });
    const data = await readJson(res);
    if (!res.ok) throw new Error((data && data.message) || `HTTP ${res.status}`);
      if (data && data.success === false) throw new Error(data.message || '请求失败');
    return data;
  } catch (err) {
    if (fallback !== NO_FALLBACK) return fallback;
    throw err;
  }
}

async function requestForm(path, formData, headers = {}) {
  const res = await fetch(joinUrl(apiBase.value, path), {
    method: 'POST',
    headers,
    body: formData,
  });
  const data = await readJson(res);
  if (!res.ok) throw new Error((data && data.message) || `HTTP ${res.status}`);
    if (data && data.success === false) throw new Error(data.message || '请求失败');
  return data;
}

async function withLoading(task) {
  loadingCount += 1;
  loading.value = true;
  // 防抖：仅当请求持续超过 150ms 才显示浮动加载药丸，快速请求不再闪烁。
  if (loadingCount === 1 && !loadingTimer) {
    loadingTimer = setTimeout(() => { loadingVisible.value = true; }, 150);
  }
  error.value = '';
  try {
    return await task();
  } catch (err) {
    error.value = err?.message || String(err);
    return null;
  } finally {
    loadingCount = Math.max(0, loadingCount - 1);
    if (loadingCount === 0) {
      if (loadingTimer) { clearTimeout(loadingTimer); loadingTimer = null; }
      loadingVisible.value = false;
      loading.value = false;
    } else {
      loading.value = loadingCount > 0;
    }
  }
}

async function loadAgents(withBindings = false) {
  const list = await request('/agents', {}, []);
  agents.value = normalizeList(list);
  if (!selectedAgentId.value && agents.value.length) selectedAgentId.value = agents.value[0].agentId;
  if (selectedAgentId.value && !agents.value.some(item => item.agentId === selectedAgentId.value)) {
    selectedAgentId.value = agents.value[0]?.agentId || '';
  }
  if (withBindings) {
    const summary = await Promise.all(agents.value.map(async agent => {
      try {
        const bindings = await request(`/agents/${encodeURIComponent(agent.agentId)}/bindings`, {}, {});
        return [agent.agentId, {
          toolCount: normalizeList(bindings?.toolIds).length,
          skillCount: normalizeList(bindings?.skillIds).length,
          mcpCount: normalizeList(bindings?.mcpIds).length,
        }];
      } catch {
        return [agent.agentId, { toolCount: 0, skillCount: 0, mcpCount: 0 }];
      }
    }));
    agentBindingSummary.value = Object.fromEntries(summary);
  }
}

async function loadDashboardData(agentId = selectedAgentId.value) {
  if (!agentId) return;
  const id = encodeURIComponent(agentId);
  const [statData, topData, feedbackData, signalData, memoryData, profileData] = await Promise.all([
    request(`/agents/${id}/workspace/stats`, {}, {}),
    request(`/agents/${id}/cases/top?limit=5`, {}, []),
    request(`/agents/${id}/feedback?limit=10`, {}, []),
    request(`/agents/${id}/signals?limit=10`, {}, []),
    request(`/agents/${id}/memory?limit=10`, {}, []),
    request(`/agents/${id}/memory/profile`, {}, null),
  ]);
  stats.value = statData || {};
  topCases.value = normalizeList(topData);
  feedback.value = normalizeList(feedbackData);
  signals.value = normalizeList(signalData);
  memories.value = normalizeList(memoryData);
  agentProfile.value = profileData?.profile || null;
}

async function loadDashboard() {
  return withLoading(async () => {
    await loadAgents();
    await loadDashboardData(selectedAgentId.value);
  });
}

async function loadFeedbackWorkspace() {
  return withLoading(async () => {
    await loadAgents();
    if (!selectedAgentId.value) return;
    const id = encodeURIComponent(selectedAgentId.value);
    const [feedbackData, signalData, memoryData, profileData] = await Promise.all([
      request(`/agents/${id}/feedback?limit=20`, {}, []),
      request(`/agents/${id}/signals?limit=20`, {}, []),
      request(`/agents/${id}/memory?limit=20`, {}, []),
      request(`/agents/${id}/memory/profile`, {}, null),
    ]);
    feedback.value = normalizeList(feedbackData);
    signals.value = normalizeList(signalData);
    memories.value = normalizeList(memoryData);
    agentProfile.value = profileData?.profile || null;
    if (!selectedFeedback.value || !businessFeedbackItems.value.some(item => item.id === selectedFeedback.value.id)) {
      selectedFeedback.value = businessFeedbackItems.value[0] || null;
    } else {
      selectedFeedback.value = businessFeedbackItems.value.find(item => item.id === selectedFeedback.value.id) || null;
    }
  });
}

async function loadCaseWorkspace() {
  return withLoading(async () => {
    await loadAgents();
    if (!selectedAgentId.value) return;
    const id = encodeURIComponent(selectedAgentId.value);
    const status = caseStatusFilter.value ? `?status=${encodeURIComponent(caseStatusFilter.value)}` : '';
    const [caseData, profileData] = await Promise.all([
      request(`/agents/${id}/cases${status}`, {}, []),
      request(`/agents/${id}/memory/profile`, {}, null),
    ]);
    cases.value = normalizeList(caseData);
    agentProfile.value = profileData?.profile || null;
    if (selectedCase.value && !cases.value.some(item => item.caseId === selectedCase.value.caseId)) {
      selectedCase.value = null;
      selectedCaseDetail.value = null;
    }
  });
}

function caseActions(status) {
  return CASE_ACTIONS[String(status || '').toUpperCase()] || [];
}

function caseActionList(item) {
  const serverActions = normalizeList(item?.availableActions);
  return serverActions.length ? serverActions : caseActions(item?.status);
}

function feedbackActions(status) {
  return FEEDBACK_ACTIONS[String(status || '').toUpperCase()] || [];
}

function feedbackActionList(item) {
  const serverActions = normalizeList(item?.availableActions);
  return serverActions.length ? serverActions : feedbackActions(item?.status);
}

function feedbackStatusHint(status) {
  const normalized = String(status || '').toUpperCase();
  return {
    OPEN: '新进入队列，等待人工提交 AI 评测或直接判定无效。',
    AI_EVALUATING: '正在确认这条反馈是否属于当前 Agent 负责的业务问题。',
    NEED_MORE_INFO: '证据不足，需要补充商品型号、页面入口、订单号或截图等上下文。',
    VALID: '已确认是有效业务反馈，下一步可以升级为 Case 或先归并同类问题。',
    CLUSTERED: '已进入待升级问题簇，适合和同类反馈合并后统一升级。',
    PROMOTED: '已升级为 Case，后续进入案例审核、处理和复盘流程。',
    RESOLVED: '反馈流程已经关闭，如后续再次出现可以重新打开。',
    INVALID: '已判定为无效反馈，或暂不属于当前 Agent 的业务范围。',
  }[normalized] || '当前反馈正在流转中。';
}

function feedbackNextStep(item) {
  const actions = feedbackActions(item?.status);
  if (!actions.length) return '当前阶段暂无可执行动作。';
  return `建议下一步：${actions.map(action => action.label).join(' / ')}`;
}

function feedbackEvidenceChips(item) {
  const text = String(item?.message || '').toLowerCase();
  const chips = [];
  if (/\d{2,}/.test(text)) chips.push('包含数字线索');
  if (/(sku|型号|model|ddr|显卡|内存|品牌|id)/i.test(text)) chips.push('包含商品或型号线索');
  if (/(页面|列表|详情|接口|api|下单|库存|支付|订单)/i.test(text)) chips.push('包含业务位置线索');
  if (/(截图|日志|报错|异常|不一致|失败|超时|缺货|补货|缺失)/i.test(text)) chips.push('包含问题证据线索');
  return chips;
}

function feedbackReasonText(item) {
  if (!item) return '暂无评测说明。';
  if (item.evaluationReason) return item.evaluationReason;
  const status = String(item.status || '').toUpperCase();
  const category = item.category ? `分类：${item.category}。` : '';
  const chips = feedbackEvidenceChips(item);
  const evidence = chips.length ? `已识别${chips.join('、')}。` : '暂未识别到足够证据线索。';
  if (status === 'NEED_MORE_INFO') return `${category}${evidence} 当前能判断这是业务相关反馈，但缺少足够上下文，暂时不能稳定升级为 Case。`;
  if (status === 'VALID') return `${category}${evidence} 已具备“问题描述 + 业务对象 + 基础证据”，可以进入待升级或直接转 Case。`;
  if (status === 'PROMOTED') return `${category}${evidence} 这条反馈已满足升级条件，已进入 Case 工作流。`;
  if (status === 'INVALID') return `${category}${evidence} 当前描述更像测试语句、问候或缺少明确业务问题，暂不纳入有效反馈。`;
  if (status === 'AI_EVALUATING') return `${category}${evidence} 系统正在确认它是否属于当前 Agent 的业务范围，以及是否达到升级阈值。`;
  return `${category}${evidence}`;
}

function feedbackActionAdvice(item) {
  if (!item) return '暂无处理建议。';
  if (item.nextAction) return item.nextAction;
  const status = String(item.status || '').toUpperCase();
  if (status === 'NEED_MORE_INFO') return '建议补充商品型号、页面位置、订单号、截图或稳定复现场景。';
  if (status === 'VALID') return '建议由运营或开发确认是否与历史同类反馈合并，再决定是否升级为 Case。';
  if (status === 'PROMOTED') return '建议进入 Case 审核、指派负责人并补充处理进展。';
  if (status === 'INVALID') return '如果这是真实业务问题，请补充具体业务对象和异常表现后重新提交。';
  return feedbackNextStep(item);
}

function caseStatusText(status) {
  return CASE_STATUS_TEXT[String(status || '').toUpperCase()] || status || '-';
}

function caseStatusHint(status) {
  return CASE_STATUS_HINTS[String(status || '').toUpperCase()] || '';
}

function openCaseTransition(item, action) {
  const operation = String(action.operation || '').toUpperCase();
  if (operation === 'MERGE' || String(action.status || '').toUpperCase() === 'MERGED') {
    setModal('caseMerge', `${action.label}: ${item.title || item.caseId}`, 'edit', {
      actionLabel: action.label,
      targetCaseId: '',
      reason: '',
    }, { caseId: item.caseId, fromStatus: item.status });
    return;
  }
  setModal('caseTransition', `${action.label}: ${item.title || item.caseId}`, 'edit', {
    toStatus: action.status,
    actionLabel: action.label,
    owner: item.owner || '',
    resolution: item.resolution || '',
    reason: '',
  }, { caseId: item.caseId, fromStatus: item.status });
}

async function saveCaseTransition() {
  const form = modal.form;
  const toStatus = String(form.toStatus || '').toUpperCase();
  if (['IN_PROGRESS'].includes(toStatus) && !String(form.owner || '').trim()) {
    error.value = '\u5f00\u59cb\u5904\u7406\u5fc5\u987b\u6307\u5b9a\u8d1f\u8d23\u4eba';
    return;
  }
  if (toStatus === 'RESOLVED' && !String(form.resolution || '').trim()) {
    error.value = '\u6807\u8bb0\u5df2\u89e3\u51b3\u5fc5\u987b\u586b\u5199\u89e3\u51b3\u65b9\u6848';
    return;
  }
  const rollback = (['PENDING_REVIEW', 'CONFIRMED', 'IN_PROGRESS', 'CANDIDATE'].includes(toStatus)
    && String(modal.extra.fromStatus || '') !== toStatus);
  if ((['IGNORED', 'ARCHIVED'].includes(toStatus) || rollback) && !String(form.reason || '').trim()) {
    error.value = '\u9a73\u56de\u3001\u5f52\u6863\u6216\u9000\u56de\u5fc5\u987b\u586b\u5199\u539f\u56e0';
    return;
  }
  modal.saving = true;
  await withLoading(async () => {
    await request(`/agents/${encodeURIComponent(selectedAgentId.value)}/cases/${encodeURIComponent(modal.extra.caseId)}/transition`, {
      method: 'POST',
      headers: capHeaders('local-admin', 'OPERATOR'),
      body: {
        toStatus,
        actor: 'local-admin',
        actorRole: 'OPERATOR',
        owner: String(form.owner || '').trim(),
        resolution: String(form.resolution || '').trim(),
        reason: String(form.reason || '').trim(),
      },
    });
    closeModal();
    await loadCaseWorkspace();
  });
  modal.saving = false;
}

async function saveCaseMerge() {
  const form = modal.form;
  const targetCaseId = String(form.targetCaseId || '').trim();
  if (!targetCaseId) {
    error.value = '合并 Case 必须填写目标 Case ID';
    return;
  }
  if (targetCaseId === String(modal.extra.caseId || '').trim()) {
    error.value = '不能把 Case 合并到自己';
    return;
  }
  modal.saving = true;
  await withLoading(async () => {
    await request(`/agents/${encodeURIComponent(selectedAgentId.value)}/cases/${encodeURIComponent(modal.extra.caseId)}/merge`, {
      method: 'POST',
      headers: capHeaders('local-admin', 'OPERATOR'),
      body: {
        targetCaseId,
        actor: 'local-admin',
        reason: String(form.reason || '').trim(),
      },
    });
    closeModal();
    await loadCaseWorkspace();
  });
  modal.saving = false;
}

function openFeedbackTransition(item, action) {
  setModal('feedbackTransition', `${action.label}: ${item.sourceLabel || item.feedbackType || item.id}`, 'edit', {
    toStatus: action.status,
    actionLabel: action.label,
    reason: action.status === 'PROMOTED'
      ? '该反馈已确认有效，升级为 Case 进入后续处理流程。'
      : action.status === 'INVALID'
        ? '信息不足或不属于当前 Agent 的业务处理范围。'
        : '',
    category: item.category || '',
    matchedCaseId: item.matchedCaseId || '',
  }, { feedbackId: item.id, fromStatus: item.status });
}

async function saveFeedbackTransition() {
  const form = modal.form;
  const toStatus = String(form.toStatus || '').toUpperCase();
  if (['INVALID', 'PROMOTED'].includes(toStatus) && !String(form.reason || '').trim()) {
    error.value = '标记无效或升级为Case时必须填写处理说明';
    return;
  }
  modal.saving = true;
  await withLoading(async () => {
    const result = await request(`/agents/${encodeURIComponent(selectedAgentId.value)}/feedback/${encodeURIComponent(modal.extra.feedbackId)}/transition`, {
      method: 'POST',
      headers: capHeaders('local-reviewer', 'OPERATOR'),
      body: {
        toStatus,
        actor: 'local-reviewer',
        reason: String(form.reason || '').trim(),
        category: String(form.category || '').trim(),
        matchedCaseId: String(form.matchedCaseId || '').trim(),
      },
    });
    closeModal();
    await loadFeedbackWorkspace();
    await loadDashboardData(selectedAgentId.value);
    await loadCaseWorkspace();
    if (toStatus === 'PROMOTED' && result?.caseId) {
      tab.value = 'cases';
      const promotedCase = cases.value.find(item => item.caseId === result.caseId);
      if (promotedCase) await openCase(promotedCase);
    }
  });
  modal.saving = false;
}

function openFeedback(item) {
  selectedFeedback.value = item;
}

async function openCase(item) {
  selectedCase.value = item;
  selectedCaseDetail.value = null;
  const detail = await request(`/agents/${encodeURIComponent(selectedAgentId.value)}/cases/${encodeURIComponent(item.caseId)}`, {}, null);
  if (detail?.case) selectedCase.value = detail.case;
  selectedCaseDetail.value = detail;
}

async function loadAgentConfigPage() {
  return withLoading(async () => {
    await loadAgents(true);
    await loadAgentBindingOptions();
  });
}

async function loadConversationPage(agentId = selectedAgentId.value) {
  if (!agentId) return;
  return withLoading(async () => {
    await loadAgents(true);
    await loadModelsPage();
    if (!chatModelId.value || !models.value.some(model => String(model.modelId) === String(chatModelId.value))) {
      chatModelId.value = selectedAgent.value?.modelId || models.value[0]?.modelId || '';
    }
    await loadSessionsForAgent(agentId);
    const sessionIds = new Set(sessions.value.map(item => item.sessionId));
    if (!currentSessionId.value || !sessionIds.has(currentSessionId.value)) {
      currentSessionId.value = sessions.value[0]?.sessionId || '';
    }
    if (currentSessionId.value) {
      await openSession(currentSessionId.value);
    } else {
      sessionDetail.value = null;
      chatStream.value = [];
    }
  });
}

async function loadSessionsForAgent(agentId = selectedAgentId.value) {
  if (!agentId) return;
  sessions.value = normalizeList(await request(`/agents/${encodeURIComponent(agentId)}/sessions`, {}, []));
  if (!currentSessionId.value || !sessions.value.some(item => item.sessionId === currentSessionId.value)) {
    currentSessionId.value = sessions.value[0]?.sessionId || '';
  }
}

async function openSession(sessionId) {
  if (!selectedAgentId.value || !sessionId) return;
  currentSessionId.value = sessionId;
  chatStream.value = [];
  sessionDetail.value = await request(
    `/agents/${encodeURIComponent(selectedAgentId.value)}/sessions/${encodeURIComponent(sessionId)}`,
    {},
    null,
  );
  if (!chatModelId.value) {
    chatModelId.value = sessionDetail.value?.session?.modelId || selectedAgent.value?.modelId || models.value[0]?.modelId || '';
  }
  await scrollChatToBottom();
}

async function scrollChatToBottom() {
  await nextTick();
  const element = chatMessagesRef.value;
  if (element) element.scrollTop = element.scrollHeight;
}

async function loadAgentWorkspace(agentId = selectedAgentId.value) {
  if (!agentId) return;
  return withLoading(async () => {
    await loadAgents(true);
    await loadDashboardData(agentId);
    await loadSessionsForAgent(agentId);
    if (currentSessionId.value) await openSession(currentSessionId.value);
  });
}

async function newChat() {
  if (!selectedAgentId.value) return;
  const created = await request(`/agents/${encodeURIComponent(selectedAgentId.value)}/sessions`, {
    method: 'POST',
    body: { title: '新对话', modelId: chatModelId.value || '' },
  });
  currentSessionId.value = created?.sessionId || created?.session?.sessionId || '';
  await loadConversationPage(selectedAgentId.value);
}

function sessionName(session) {
  const title = String(session?.title || '').trim();
  const defaultTitles = ['新对话', 'New chat', 'New Chat'];
  if (title && !defaultTitles.includes(title)) return title;
  const preview = String(session?.preview || '').replace(/\s+/g, ' ').trim();
  return preview.slice(0, 5) || '新对话';
}

function appendTrace(type, subType, step, content) {
  chatStream.value.push({
    id: `${Date.now()}-${Math.random().toString(16).slice(2)}`,
    type,
    subType,
    step,
    content,
  });
  scrollChatToBottom();
}

function handleStreamEvent(payload) {
  const type = payload?.type || 'trace';
  if (type === 'state_updated' && payload.executionId) {
    currentExecutionId.value = payload.executionId;
    return;
  }
  if (type === 'subagent_started') {
    subagentTasks[payload.taskId] = {
      taskId: payload.taskId,
      description: payload.description || '子任务',
      status: 'RUNNING',
      result: '',
      error: '',
    };
    chatStatus.value = '子任务执行中...';
    return;
  }
  if (type === 'subagent_completed' || type === 'subagent_failed'
    || type === 'subagent_cancelled' || type === 'subagent_timed_out') {
    const task = subagentTasks[payload.taskId] || { taskId: payload.taskId, description: '子任务' };
    task.status = payload.status || (type === 'subagent_completed' ? 'COMPLETED'
      : type === 'subagent_cancelled' ? 'CANCELLED'
        : type === 'subagent_timed_out' ? 'TIMED_OUT' : 'FAILED');
    task.result = payload.result || task.result || '';
    task.error = payload.error || task.error || '';
    subagentTasks[payload.taskId] = task;
    return;
  }
  if (type === 'subagent_trace') {
    const task = subagentTasks[payload.taskId] || {
      taskId: payload.taskId,
      description: '子任务',
      status: 'RUNNING',
      traces: [],
    };
    task.traces = task.traces || [];
    task.traces.push({
      type: payload.subType ? `${payload.type}/${payload.subType}` : payload.type,
      content: payload.content || '',
      step: payload.step || '',
    });
    subagentTasks[payload.taskId] = task;
    return;
  }
  if (type === 'todo_updated') {
    todoItems.value = Array.isArray(payload.todos) ? payload.todos : [];
    return;
  }
  appendTrace(type, payload.subType || '', payload.step || '', payload.content || '');
}

function clearExecutionProgress() {
  Object.keys(subagentTasks).forEach(taskId => delete subagentTasks[taskId]);
  todoItems.value = [];
  currentExecutionId.value = '';
}

function executionStatusText(status) {
  return {
    PENDING: '排队中',
    RUNNING: '执行中',
    CANCEL_REQUESTED: '正在停止',
    COMPLETED: '已完成',
    FAILED: '失败',
    CANCELLED: '已取消',
    TIMED_OUT: '已超时',
    IN_PROGRESS: '进行中',
  }[status] || status || '未知';
}

async function cancelSubagentTask(taskId) {
  const task = subagentTasks[taskId];
  if (!task || ['COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT'].includes(task.status)) return;
  task.status = 'CANCEL_REQUESTED';
  try {
    const result = await request(`/agent/subagents/${encodeURIComponent(taskId)}/cancel`, { method: 'POST' }, {});
    if (result?.status) task.status = result.status;
  } catch (err) {
    task.status = 'RUNNING';
    error.value = err?.message || String(err);
  }
}

async function cancelMainExecution() {
  if (!currentExecutionId.value || !isStreaming.value) return;
  chatStatus.value = '正在等待当前工具完成...';
  try {
    await request(`/agent/executions/${encodeURIComponent(currentExecutionId.value)}/cancel`, { method: 'POST' }, {});
  } catch (err) {
    error.value = err?.message || String(err);
  }
}

function sessionDisplayName(session) {
  const title = String(session?.title || '').trim();
  const defaultTitles = ['\u65b0\u5bf9\u8bdd', 'New chat', 'New Chat'];
  if (title && !defaultTitles.includes(title)) return title;
  const preview = String(session?.preview || '').replace(/\s+/g, ' ').trim();
  return preview.slice(0, 5) || 'New chat';
}

async function sendMessage() {
  if (!selectedAgentId.value || !chatInput.value.trim() || isStreaming.value) return;
  if (!currentSessionId.value) await newChat();

  const message = chatInput.value.trim();
  chatInput.value = '';
  clearExecutionProgress();
  appendTrace('chat', '', '', message);

  isStreaming.value = true;
  chatStatus.value = '处理中...';

  try {
    const response = await fetch(joinUrl(apiBase.value, '/agent/auto_agent'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({
        aiAgentId: selectedAgentId.value,
        message,
        sessionId: currentSessionId.value,
        mode: selectedAgent.value?.channel || 'auto',
        modelId: chatModelId.value || selectedAgent.value?.modelId || '',
      }),
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const reader = response.body?.getReader();
    if (!reader) throw new Error('stream unavailable');
    const decoder = new TextDecoder();
    let buffer = '';
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let cut = buffer.indexOf('\n\n');
      while (cut >= 0) {
        const chunk = buffer.slice(0, cut);
        buffer = buffer.slice(cut + 2);
        for (const line of chunk.split('\n')) {
          if (!line.startsWith('data:')) continue;
          const raw = line.slice(5).trim();
          if (!raw) continue;
          try {
            const payload = JSON.parse(raw);
            handleStreamEvent(payload);
          } catch {
            appendTrace('trace', '', '', raw);
          }
        }
        cut = buffer.indexOf('\n\n');
      }
    }
    await openSession(currentSessionId.value);
  } catch (err) {
    appendTrace('error', '', '', err?.message || String(err));
    error.value = err?.message || String(err);
  } finally {
    isStreaming.value = false;
    chatStatus.value = '';
  }
}

async function loadModelsPage() {
  return withLoading(async () => {
    const modelList = await request('/models', {}, []);
    models.value = normalizeList(modelList);
  });
}

function openModelCreate() {
  setModal('model', '模型配置', 'create', {
    modelId: '',
    modelName: '',
    modelStatus: 1,
    modelUrl: 'http://localhost:11434/v1',
    apiKey: '',
    completionsPath: '/v1/chat/completions',
    modelType: 'openai',
  });
}

async function openModelEdit(item) {
  const apiId = pick(item, 'apiId');
  const detail = apiId ? await request(`/client-apis/${encodeURIComponent(apiId)}`, {}, { api: null }) : { api: null };
  const api = detail?.api || {};
  setModal('model', '编辑模型 ' + pick(item, 'modelId'), 'edit', {
    modelId: pick(item, 'modelId'),
    modelName: pick(item, 'modelName'),
    modelStatus: Number(pick(item, 'status') ?? 1),
    modelUrl: pick(api, 'baseUrl'),
    apiKey: pick(api, 'apiKey'),
    completionsPath: pick(api, 'completionsPath') || '/v1/chat/completions',
    modelType: pick(item, 'modelType') || 'openai',
  }, { originalApiId: apiId, originalModelId: pick(item, 'modelId') });
}

async function saveModel() {
  modal.saving = true;
  try {
    await request('/client-model-bindings', {
      method: 'POST',
      body: {
        modelId: modal.mode === 'create' ? modal.form.modelId : modal.extra.originalModelId,
        modelName: modal.form.modelName,
        modelStatus: Number(modal.form.modelStatus) || 1,
        modelUrl: modal.form.modelUrl,
        apiKey: modal.form.apiKey,
        completionsPath: modal.form.completionsPath,
        modelType: modal.form.modelType || 'openai',
      },
    });
    closeModal();
    await loadModelsPage();
  } finally {
    modal.saving = false;
  }
}

async function deleteModel(modelId) {
  if (!confirm('Delete model ' + modelId + '?')) return;
  await request(`/client-models/${encodeURIComponent(modelId)}`, { method: 'DELETE' });
  await loadModelsPage();
}

async function loadAgentBindingOptions() {
  const now = Date.now();
  if (bindingOptionsPromise) return bindingOptionsPromise;
  if (now - bindingOptionsLoadedAt < 5000 && agentSkillOptions.value.length) return;
  bindingOptionsPromise = (async () => {
  if (!models.value.length) {
    await loadModelsPage();
  }
  const [tools, skills, releasedMcps] = await Promise.all([
    request('/agent-tools', {}, []),
    request('/skills', {}, []),
    request('/capabilities/mcp-bindings', {}, []),
  ]);
  agentToolOptions.value = normalizeList(tools);
  agentSkillOptions.value = normalizeList(skills);
  // Agent configuration exposes released MCP versions only.
  agentMcpOptions.value = normalizeList(releasedMcps).map(item => ({
    ...item,
    mcpId: item.mcp_id || item.mcpId,
    mcpName: item.mcp_name || item.mcpName,
    transportType: item.transport_type || item.transportType,
    releaseId: item.release_id || item.releaseId,
  }));
  bindingOptionsLoadedAt = Date.now();
  })();
  try {
    await bindingOptionsPromise;
  } finally {
    bindingOptionsPromise = null;
  }
}

function defaultAgentModelId() {
  return models.value.find(item => item.configured)?.modelId
    || models.value[0]?.modelId
    || '';
}

function effectiveToolSourceLabel(value) {
  const normalized = String(value || '').trim().toLowerCase();
  if (normalized === 'agent_binding') return '手动勾选';
  if (normalized === 'skill_binding') return '由 Skill 自动赋能';
  if (normalized === 'mcp_binding') return '由 MCP 自动赋能';
  if (normalized === 'task_cascade') return '由子任务能力级联';
  return '未知来源';
}

function runtimeAvailabilityClass(value) {
  return value ? 'success' : 'danger';
}

function runtimeAvailabilityText(item, fallbackAvailable, fallbackUnavailable) {
  if (item?.runtimeStatusText) return item.runtimeStatusText;
  return item?.runtimeAvailable ? fallbackAvailable : fallbackUnavailable;
}

function modelLabel(modelId) {
  if (!modelId) return '未绑定';
  const found = models.value.find(item => item.modelId === modelId);
  return found ? (found.modelName || found.modelId) : modelId;
}

async function openAgentCreate() {
  await loadAgentBindingOptions();
  setModal('agent', '配置智能体', 'create', {
    agentId: '',
    agentName: '',
    description: '',
    channel: 'auto',
    modelId: defaultAgentModelId(),
    workDir: '',
    systemPrompt: '',
    status: 1,
    boundToolIds: [],
    boundSkillIds: [],
    boundMcpIds: [],
  });
}

async function openAgentEdit(agentId) {
  await loadAgentBindingOptions();
  const agent = agents.value.find(item => item.agentId === agentId);
  if (!agent) return;
  const [bindings, detail] = await Promise.all([
    request(`/agents/${encodeURIComponent(agentId)}/bindings`, {}, {}),
    request(`/agents/${encodeURIComponent(agentId)}/bindings/detail`, {}, {}),
  ]);
  agentBindingDetail.value = detail || {
    workspace: '',
    effectiveToolIds: [],
    effectiveTools: [],
    skills: [],
    mcps: [],
    tools: [],
  };
  setModal('agent', '编辑智能体 ' + (agent.agentName || agent.agentId), 'edit', {
    agentId: agent.agentId,
    agentName: agent.agentName || '',
    description: agent.description || '',
    channel: agent.channel || 'auto',
    modelId: agent.modelId || defaultAgentModelId(),
    workDir: agent.workDir || '',
    systemPrompt: agent.systemPrompt || '',
    status: Number(agent.status) || 1,
    boundToolIds: normalizeList(bindings?.toolIds),
    boundSkillIds: normalizeList(bindings?.skillIds),
    boundMcpIds: normalizeList(bindings?.mcpIds),
  }, { originalAgentId: agentId });
}

async function saveAgent() {
  modal.saving = true;
  try {
    const normalizedSkillIds = normalizeBindingIds(modal.form.boundSkillIds, agentSkillOptions.value, 'skillId', 'SKILL_ID');
    const normalizedMcpIds = normalizeBindingIds(modal.form.boundMcpIds, agentMcpOptions.value, 'mcpId', 'MCP_ID');
    const normalizedToolIds = normalizeBindingIds(modal.form.boundToolIds, agentToolOptions.value, 'toolId', 'TOOL_ID');
    const payload = {
      agentId: modal.form.agentId,
      agentName: modal.form.agentName,
      description: modal.form.description,
      channel: modal.form.channel,
      modelId: modal.form.modelId,
      workDir: modal.form.workDir,
      systemPrompt: modal.form.systemPrompt,
      status: Number(modal.form.status) || 1,
    };
    const agentId = modal.mode === 'create' ? modal.form.agentId : modal.extra.originalAgentId;
    if (modal.mode === 'create') {
      await request('/agents', { method: 'POST', body: payload });
    } else {
      await request(`/agents/${encodeURIComponent(agentId)}`, { method: 'PUT', body: payload });
    }
    await request(`/agents/${encodeURIComponent(modal.mode === 'create' ? modal.form.agentId : agentId)}/bindings`, {
      method: 'PUT',
      body: {
        skillIds: normalizedSkillIds,
        mcpIds: normalizedMcpIds,
        toolIds: normalizedToolIds,
      },
    });
    selectedAgentId.value = modal.mode === 'create' ? modal.form.agentId : agentId;
    closeModal();
    await loadAgents();
    if (tab.value === 'agents') {
      await loadAgentConfigPage();
    } else if (tab.value === 'conversations') {
      await loadConversationPage(selectedAgentId.value);
    } else if (tab.value === 'dashboard') {
      await loadDashboard();
    }
  } finally {
    modal.saving = false;
  }
}

async function deleteAgent(agentId) {
  if (!confirm('Delete agent ' + agentId + '?')) return;
  await request(`/agents/${encodeURIComponent(agentId)}`, { method: 'DELETE' });
  if (selectedAgentId.value === agentId) {
    selectedAgentId.value = '';
    currentSessionId.value = '';
    sessionDetail.value = null;
  }
  await loadAgents();
  if (tab.value === 'agents') {
    await loadAgentConfigPage();
  } else if (tab.value === 'conversations') {
    await loadConversationPage(selectedAgentId.value);
  } else if (tab.value === 'dashboard') {
    await loadDashboard();
  }
}

async function loadMcpPage() {
  return withLoading(async () => {
    const legacyServers = await request('/capabilities/mcps', {}, []);
    mcpServers.value = normalizeList(legacyServers);
    if (!selectedMcpServerId.value || !mcpServers.value.some(item => String(pick(item, 'id', 'ID')) === String(selectedMcpServerId.value))) {
      selectedMcpServerId.value = String(pick(mcpServers.value[0], 'id', 'ID') || '');
    }
    if (selectedMcpServerId.value) {
      await loadMcpVersions(selectedMcpServerId.value);
    } else {
      mcpVersions.value = [];
    }
  });
}

async function loadMcpVersions(serverId) {
  if (!serverId) {
    mcpVersions.value = [];
    return;
  }
  selectedMcpServerId.value = String(serverId);
  mcpVersions.value = normalizeList(await request(`/capabilities/mcps/${encodeURIComponent(serverId)}/versions`, {}, []));
}

function openMcpCreate() {
  setModal('mcpServer', '新建 MCP', 'create', {
    mcpKey: '',
    name: '',
    description: '',
  });
}

function openMcpVersionCreate() {
  if (!selectedMcpServerId.value) return;
  setModal('mcpVersion', 'MCP 版本', 'create', {
    serverId: selectedMcpServerId.value,
    version: '1.0.0',
    transportType: 'sse',
    endpointConfig: '',
    credentialRef: '',
  });
}

async function saveMcpServer() {
  modal.saving = true;
  try {
    const created = await request('/capabilities/mcps', {
      method: 'POST',
      headers: capHeaders('local-developer', 'DEVELOPER'),
      body: {
        mcpKey: modal.form.mcpKey,
        name: modal.form.name,
        description: modal.form.description,
      },
    });
    closeModal();
    await loadMcpPage();
    if (created?.id !== undefined && created?.id !== null) {
      await loadMcpVersions(created.id);
    }
  } finally {
    modal.saving = false;
  }
}

async function seedLocalTestMcp() {
  await request('/capabilities/mcps/local-test', { method: 'POST' });
  await loadMcpPage();
}

async function saveMcpVersion() {
  modal.saving = true;
  try {
    await request(`/capabilities/mcps/${encodeURIComponent(modal.form.serverId)}/versions`, {
      method: 'POST',
      headers: capHeaders('local-developer', 'DEVELOPER'),
      body: {
        version: modal.form.version,
        transportType: modal.form.transportType,
        endpointConfig: modal.form.endpointConfig,
        credentialRef: modal.form.credentialRef,
      },
    });
    closeModal();
    await loadMcpVersions(modal.form.serverId);
  } finally {
    modal.saving = false;
  }
}

async function mcpAction(versionId, action) {
  const actor = action === 'scan' ? 'local-security'
    : action === 'connect' || action === 'sandbox' || action === 'discover' ? 'local-tester'
    : action === 'release' ? 'local-release-manager'
    : 'local-developer';
  const role = action === 'scan' ? 'SECURITY_REVIEWER'
    : action === 'connect' || action === 'sandbox' || action === 'discover' ? 'TESTER'
    : action === 'release' ? 'RELEASE_MANAGER'
    : 'DEVELOPER';
  if (action === 'discover') {
    const toolsJson = prompt('请输入 JSON 工具列表', '[]') || '[]';
    const tools = JSON.parse(toolsJson);
    await request(`/capabilities/mcp-versions/${versionId}/discovery`, {
      method: 'POST',
      headers: capHeaders(actor, role),
      body: { tools },
    });
  } else if (action === 'sandbox') {
    await request(`/capabilities/mcp-versions/${versionId}/sandbox-test`, {
      method: 'POST',
      headers: capHeaders(actor, role),
      body: { suite: 'default', passed: true },
    });
  } else if (action === 'reviewTest') {
    await request(`/capabilities/mcp-versions/${versionId}/reviews`, {
      method: 'POST',
      headers: capHeaders('mcp-test-reviewer', 'TEST_REVIEWER'),
      body: { reviewType: 'TEST', decision: 'APPROVED' },
    });
  } else if (action === 'reviewSecurity') {
    await request(`/capabilities/mcp-versions/${versionId}/reviews`, {
      method: 'POST',
      headers: capHeaders('mcp-security-reviewer', 'SECURITY_REVIEWER'),
      body: { reviewType: 'SECURITY', decision: 'APPROVED' },
    });
  } else if (action === 'release') {
    await request(`/capabilities/mcp-versions/${versionId}/releases`, {
      method: 'POST',
      headers: capHeaders(actor, role),
      body: { environment: 'DEV', rolloutPercent: 100 },
    });
  } else {
    const suffix = {
      connect: 'connectivity-test',
      scan: 'security-scan',
      submit: 'submit-review',
    }[action];
    await request(`/capabilities/mcp-versions/${versionId}/${suffix}`, {
      method: 'POST',
      headers: capHeaders(actor, role),
    });
  }
  await loadMcpVersions(selectedMcpServerId.value);
}

function capVersionActions(kind, status) {
  const s = String(status || '').toUpperCase();
  if (kind === 'mcp') {
    if (s === 'DRAFT') return ['connect'];
    if (s === 'CONNECTIVITY_CHECKED') return ['discover'];
    if (s === 'DISCOVERED') return ['scan'];
    if (s === 'SCANNED') return ['sandbox'];
    if (s === 'TESTED') return ['submit'];
    if (s === 'IN_REVIEW') return ['reviewTest', 'reviewSecurity'];
    if (s === 'APPROVED') return ['release'];
    if (s === 'RELEASED') return [];
  } else {
    if (s === 'VALID' || s === 'VALIDATED') return ['scan'];
    if (s === 'SCANNED') return ['test'];
    if (s === 'TESTED') return ['submit'];
    if (s === 'IN_REVIEW') return ['reviewTest', 'reviewSecurity'];
    if (s === 'APPROVED') return ['sign', 'release'];
    if (s === 'SIGNED') return ['release'];
    if (s === 'RELEASED') return [];
  }
  return [];
}

async function skillAction(versionId, action) {
  const actor = action === 'scan' ? 'local-security'
    : action === 'test' ? 'local-tester'
    : action === 'release' ? 'local-release-manager'
    : 'local-developer';
  const role = action === 'scan' ? 'SECURITY_REVIEWER'
    : action === 'test' ? 'TESTER'
    : action === 'release' ? 'RELEASE_MANAGER'
    : 'DEVELOPER';
  if (action === 'reviewTest') {
    await request(`/capabilities/skill-versions/${versionId}/reviews`, {
      method: 'POST',
      headers: capHeaders('skill-test-reviewer', 'TEST_REVIEWER'),
      body: { reviewType: 'TEST', decision: 'APPROVED' },
    });
  } else if (action === 'reviewSecurity') {
    await request(`/capabilities/skill-versions/${versionId}/reviews`, {
      method: 'POST',
      headers: capHeaders('skill-security-reviewer', 'SECURITY_REVIEWER'),
      body: { reviewType: 'SECURITY', decision: 'APPROVED' },
    });
  } else if (action === 'release') {
    await request(`/capabilities/skill-versions/${versionId}/releases`, {
      method: 'POST',
      headers: capHeaders(actor, role),
      body: { environment: 'DEV', rolloutPercent: 100 },
    });
  } else if (action === 'sign') {
    await request(`/capabilities/skill-versions/${versionId}/sign`, {
      method: 'POST',
      headers: capHeaders('local-release-manager', 'RELEASE_MANAGER'),
    });
  } else {
    const suffix = { scan: 'security-scan', test: 'sandbox-test', submit: 'submit-review' }[action];
    await request(`/capabilities/skill-versions/${versionId}/${suffix}`, {
      method: 'POST',
      headers: capHeaders(actor, role),
    });
  }
  await loadSkillVersions(selectedSkillPackageId.value);
}

async function loadSkillPage() {
  return withLoading(async () => {
    const [databaseSkills, filesystemSkills] = await Promise.all([
      request('/capabilities/skills', {}, []),
      request('/skills', {}, []),
    ]);
    skillPackages.value = normalizeList(databaseSkills);
    localSkills.value = normalizeList(filesystemSkills).map(skill => ({
      ...skill,
      skillId: pick(skill, 'skillId', 'SKILL_ID'),
      skillName: pick(skill, 'skillName', 'SKILL_NAME'),
      local: true,
    }));
    if (!selectedSkillPackageId.value || !skillPackages.value.some(item => String(pick(item, 'id', 'ID')) === String(selectedSkillPackageId.value))) {
      selectedSkillPackageId.value = String(pick(skillPackages.value[0], 'id', 'ID') || '');
    }
    if (selectedSkillPackageId.value) {
      await loadSkillVersions(selectedSkillPackageId.value);
    } else {
      skillVersions.value = [];
    }
    if (!selectedLocalSkillId.value || !localSkills.value.some(item => String(item.skillId) === String(selectedLocalSkillId.value))) {
      selectedLocalSkillId.value = String(localSkills.value[0]?.skillId || '');
    }
    if (selectedLocalSkillId.value) {
      await selectLocalSkill(selectedLocalSkillId.value);
    } else {
      localSkillDetail.value = null;
    }
  });
}

async function selectLocalSkill(skillId) {
  selectedLocalSkillId.value = String(skillId || '');
  selectedSkillPackageId.value = '';
  skillVersions.value = [];
  const query = selectedAgentId.value ? `?agentId=${encodeURIComponent(selectedAgentId.value)}` : '';
  localSkillDetail.value = await request(`/skills/${encodeURIComponent(selectedLocalSkillId.value)}${query}`, {}, null);
}

async function loadSkillVersions(packageId) {
  if (!packageId) {
    skillVersions.value = [];
    return;
  }
  selectedSkillPackageId.value = String(packageId);
  skillVersions.value = normalizeList(await request(`/capabilities/skills/${encodeURIComponent(packageId)}/versions`, {}, []));
}

function openSkillUpload() {
  setModal('skillUpload', '上传 Skill', 'create', {
    skillKey: '',
    name: '',
    description: '',
    version: '1.0.0',
    file: null,
  });
}

async function saveSkillUpload() {
  modal.saving = true;
  try {
    const fd = new FormData();
    fd.append('skillKey', modal.form.skillKey);
    fd.append('name', modal.form.name);
    fd.append('description', modal.form.description || '');
    fd.append('version', modal.form.version || '1.0.0');
    fd.append('file', modal.form.file);
    await requestForm('/capabilities/skills/upload', fd, capHeaders('local-developer', 'DEVELOPER', false));
    closeModal();
    await loadSkillPage();
  } finally {
    modal.saving = false;
  }
}

async function openCapabilityDetail(kind, versionId) {
  const detail = await request(`/capabilities/${kind === 'mcp' ? 'mcp-versions' : 'skill-versions'}/${versionId}`, {}, null);
  setModal('json', kind.toUpperCase(), 'view', { json: JSON.stringify(detail, null, 2) });
}

function openWorkspaceDetail(title, value) {
  setModal('json', title, 'view', { json: JSON.stringify(value, null, 2) });
}

function switchTab(nextTab, force = false) {
  if (!force && tab.value === nextTab) return;
  tab.value = nextTab;
  if (nextTab === 'dashboard') loadDashboard();
  if (nextTab === 'feedback') loadFeedbackWorkspace();
  if (nextTab === 'cases') loadCaseWorkspace();
  if (nextTab === 'agents') loadAgentConfigPage();
  if (nextTab === 'conversations') loadConversationPage();
  if (nextTab === 'models') loadModelsPage();
  if (nextTab === 'mcp') loadMcpPage();
  if (nextTab === 'skills') loadSkillPage();
  if (nextTab === 'logs') loadLogsPage();
}

function refreshCurrentTab() {
  switchTab(tab.value, true);
}

async function openConversationForAgent(agentId) {
  selectedAgentId.value = agentId;
  currentSessionId.value = '';
  sessionDetail.value = null;
  chatStream.value = [];
  tab.value = 'conversations';
  await loadConversationPage(agentId);
}

async function selectConversationAgent(agentId) {
  selectedAgentId.value = agentId;
  currentSessionId.value = '';
  sessionDetail.value = null;
  chatStream.value = [];
  await loadConversationPage(agentId);
}

function openSessionRename(session) {
  setModal('sessionTitle', '重命名对话', 'edit', {
    sessionTitle: session.title || session.sessionId,
  }, {
    sessionId: session.sessionId,
    agentId: selectedAgentId.value,
  });
}

async function saveSessionRename() {
  modal.saving = true;
  try {
    await request(`/agents/${encodeURIComponent(modal.extra.agentId)}/sessions/${encodeURIComponent(modal.extra.sessionId)}/title`, {
      method: 'PUT',
      body: { title: modal.form.sessionTitle },
    });
    closeModal();
    await loadConversationPage(modal.extra.agentId);
  } finally {
    modal.saving = false;
  }
}

async function deleteSession(sessionId) {
  if (!confirm('确认删除对话 ' + sessionId + ' 吗？')) return;
  await request(`/agents/${encodeURIComponent(selectedAgentId.value)}/sessions/${encodeURIComponent(sessionId)}`, {
    method: 'DELETE',
  });
  if (currentSessionId.value === sessionId) {
    currentSessionId.value = '';
    sessionDetail.value = null;
    chatStream.value = [];
  }
  await loadConversationPage(selectedAgentId.value);
}

async function loadLogsPage() {
  return withLoading(async () => {
    if (!agents.value.length) await loadAgents();
    logs.value = normalizeList(await request('/llm-logs/grouped?limit=200', {}, []));
    if (!selectedLogSessionKey.value || !logSessions.value.some(session => logSessionKey(session) === selectedLogSessionKey.value)) {
      selectedLogSessionKey.value = logSessions.value[0] ? logSessionKey(logSessions.value[0]) : '';
    }
  });
}

function selectLogSession(session) {
  selectedLogSessionKey.value = logSessionKey(session);
}

async function jumpToLogSession(agentId, sessionId) {
  if (!agentId || !sessionId) return;
  selectedLogAgent.value = agentId;
  tab.value = 'logs';
  await loadLogsPage();
  const matched = logSessions.value.find(session => session.agentId === agentId && session.sessionId === sessionId);
  selectedLogSessionKey.value = matched ? logSessionKey(matched) : `${agentId}::${sessionId}`;
}

async function jumpToConversationSession(agentId, sessionId) {
  if (!agentId || !sessionId) return;
  selectedAgentId.value = agentId;
  tab.value = 'conversations';
  await loadConversationPage(agentId);
  await openSession(sessionId);
}

function logAgentName(agentId) {
  return logAgentOptions.value.find(agent => agent.id === agentId)?.name || agentId || '未知 Agent';
}

function logRoleLabel(role) {
  const value = String(role || '').toLowerCase();
  if (value === 'user') return '用户';
  if (value === 'assistant') return 'AI';
  if (value === 'tool') return '工具';
  return role || '消息';
}

watch(apiBase, value => {
  localStorage.setItem('ai-agent-api-base', value);
});

onMounted(() => {
  loadDashboard();
});
</script>

<template>
  <div class="app">
    <header class="topbar">
      <div class="brand">
        <div class="brand-badge">AI</div>
        <div class="brand-text">
          <div class="brand-name">智能体工作台</div>
          <div class="brand-sub">AI Agent Station</div>
        </div>
      </div>
      <div class="topbar-right">
        <div class="settings-popover">
          <button class="icon-btn" title="接口地址设置" @click="showSettings = !showSettings">⚙</button>
          <div v-if="showSettings" class="popover-backdrop" @click="showSettings = false"></div>
          <div v-if="showSettings" class="popover-card" @click.stop>
            <label>API Base URL</label>
            <input v-model="apiBase" placeholder="/api/v1" />
            <div class="popover-hint">默认 /api/v1，一般无需修改</div>
          </div>
        </div>
        <button class="icon-btn" title="刷新当前页" @click="refreshCurrentTab">⟳</button>
      </div>
    </header>

    <div class="layout">
      <aside class="sidebar">
        <button class="nav-btn" :class="{ active: tab === 'dashboard' }" @click="switchTab('dashboard')">控制台</button>
        <button class="nav-btn" :class="{ active: tab === 'feedback' }" @click="switchTab('feedback')">反馈</button>
        <button class="nav-btn" :class="{ active: tab === 'cases' }" @click="switchTab('cases')">案例</button>
        <button class="nav-btn" :class="{ active: tab === 'agents' }" @click="switchTab('agents')">智能体</button>
        <button class="nav-btn" :class="{ active: tab === 'conversations' }" @click="switchTab('conversations')">对话</button>
        <button class="nav-btn" :class="{ active: tab === 'models' }" @click="switchTab('models')">模型</button>
        <button class="nav-btn" :class="{ active: tab === 'mcp' }" @click="switchTab('mcp')">MCP</button>
        <button class="nav-btn" :class="{ active: tab === 'skills' }" @click="switchTab('skills')">Skills</button>
        <button class="nav-btn" :class="{ active: tab === 'logs' }" @click="switchTab('logs')">日志</button>
        <div class="muted" style="margin-top:auto;font-size:12px">{{ currentAgentName }}</div>
      </aside>

      <main class="content">
        <div class="page">
          <div v-if="error" class="error section-gap">{{ error }}</div>
          <div v-if="loadingVisible" class="loading-indicator">
            <span class="loading-dot"></span>
            <span>加载中…</span>
          </div>

          <template v-if="tab === 'dashboard'">
            <section class="panel section-gap">
              <div class="panel-title">
                <span>数据概览</span>
                <div class="toolbar">
                  <select class="select" style="width:260px" v-model="selectedAgentId" @change="loadDashboard">
                    <option v-for="agent in agents" :key="agent.agentId" :value="agent.agentId">
                      {{ agent.agentName || agent.agentId }}
                    </option>
                  </select>
                  <button class="btn primary" @click="loadDashboard">刷新数据</button>
                </div>
              </div>
              <div class="stats">
                <div v-for="card in dashboardCards" :key="card.label" class="stat">
                  <div class="stat-value">{{ card.value }}</div>
                  <div class="stat-label">{{ card.label }}</div>
                </div>
              </div>
            </section>

            <div class="grid-2">
              <section class="panel">
                <div class="panel-title">重点案例</div>
                <div class="list">
                  <div v-if="!topCases.length" class="empty">信息</div>
                  <div v-for="item in topCases" :key="item.caseId" class="item clickable" @click="openWorkspaceDetail('Case 详情', item)">
                    <div class="item-row">
                      <div>
                        <div style="font-weight:600">{{ item.title || item.caseId }}</div>
                        <div class="muted" style="font-size:12px;margin-top:4px">{{ item.caseId }}</div>
                      </div>
                      <div class="actions">
                        <div class="pill brand">{{ item.totalScore ?? 0 }}</div>
                        <button class="btn" @click.stop="openWorkspaceDetail('Case 详情', item)">查看详情</button>
                      </div>
                    </div>
                  </div>
                </div>
              </section>

              <section class="panel">
                <div class="panel-title">反馈概览</div>
                <div class="list">
                  <div v-if="!businessFeedbackItems.length" class="empty">暂无业务反馈</div>
                  <div v-for="item in businessFeedbackItems" :key="item.id" class="item clickable" @click="openWorkspaceDetail('反馈详情', item)">
                    <div class="item-row">
                      <div>
                        <div style="font-weight:600">{{ item.feedbackType || 'COMMENT' }}</div>
                        <div class="muted" style="font-size:12px;margin-top:4px">{{ item.message || '-' }}</div>
                      </div>
                      <div class="actions">
                        <div class="pill">{{ labelStatus(item.status) }}</div>
                        <button class="btn" @click.stop="openWorkspaceDetail('反馈详情', item)">查看详情</button>
                      </div>
                    </div>
                  </div>
                </div>
              </section>
            </div>
            <div class="grid-2 section-gap">
              <section class="panel">
                <div class="panel-title">信号</div>
                <div class="list">
                  <div v-if="!signals.length" class="empty">暂无数据</div>
                  <div v-for="item in signals" :key="item.id" class="item clickable" @click="openWorkspaceDetail('信号详情', item)">
                    <div class="item-row">
                      <div>
                        <div style="font-weight:600">{{ item.signalType || item.sourceType || '-' }}</div>
                        <div class="muted" style="font-size:12px;margin-top:4px">{{ item.summary || item.rationale || '-' }}</div>
                      </div>
                      <div class="actions">
                        <span class="pill">{{ item.severity || '-' }}</span>
                        <span class="pill">{{ labelStatus(item.status) }}</span>
                        <button class="btn" @click.stop="openWorkspaceDetail('信号详情', item)">查看详情</button>
                      </div>
                    </div>
                  </div>
                </div>
              </section>
              <section class="panel">
                <div class="panel-title">记忆摘要</div>
                <div class="list">
                  <div v-if="!memories.length" class="empty">暂无数据</div>
                  <div v-for="item in memories" :key="item.id" class="item clickable" @click="openWorkspaceDetail('记忆详情', item)">
                    <div class="item-row">
                      <div>
                        <div style="font-weight:600">{{ item.sessionId || '-' }}</div>
                        <div class="muted" style="font-size:12px;margin-top:4px">{{ item.summary || '-' }}</div>
                      </div>
                      <div class="actions">
                        <span class="pill">v{{ item.version ?? '-' }}</span>
                        <span class="pill">{{ item.status || '-' }}</span>
                        <button class="btn" @click.stop="openWorkspaceDetail('记忆详情', item)">查看详情</button>
                      </div>
                    </div>
                  </div>
                </div>
              </section>
            </div>
            <section class="panel section-gap">
              <div class="panel-title">长期记忆画像</div>
              <div v-if="!profileSections.length" class="empty">当前 Agent 暂无长期画像，可通过已解决 Case 与沉淀记忆逐步形成</div>
              <div v-else class="grid-2">
                <div v-for="[section, values] in profileSections" :key="`dashboard-${section}`" class="profile-section">
                  <div class="profile-section-title">{{ section }}</div>
                  <div v-for="entry in values.slice(0, 3)" :key="`dashboard-${section}-${entry.caseId || entry.id || entry.summary}`" class="profile-entry">
                    <div>{{ entry.summary || entry.title || entry.memory || '-' }}</div>
                    <small class="muted">来源 {{ entry.caseId || entry.source || entry.sessionId || '-' }}</small>
                  </div>
                </div>
              </div>
            </section>
          </template>
          <template v-else-if="tab === 'feedback'">
            <section class="panel section-gap">
              <div class="panel-title">
                <span>反馈工作台</span>
                <div class="toolbar">
                  <select class="select" style="width:260px" v-model="selectedAgentId" @change="loadFeedbackWorkspace">
                    <option v-for="agent in agents" :key="agent.agentId" :value="agent.agentId">
                      {{ agent.agentName || agent.agentId }}
                    </option>
                  </select>
                  <button class="btn primary" @click="loadFeedbackWorkspace">刷新反馈</button>
                </div>
              </div>
              <div class="grid-2">
                <section class="panel">
                  <div class="panel-title">业务反馈</div>
                  <div class="list">
                    <div v-if="!businessFeedbackItems.length" class="empty">暂无业务反馈</div>
                    <div v-for="item in businessFeedbackItems" :key="item.id" class="item clickable" :class="{ active: selectedFeedback?.id === item.id }" @click="openFeedback(item)">
                      <div class="item-row">
                        <div>
                          <div style="font-weight:600">{{ item.sourceLabel || item.feedbackType || 'ISSUE_REPORT' }}</div>
                          <div class="muted" style="font-size:12px;margin-top:4px">{{ item.message || '-' }}</div>
                          <div class="actions" style="margin-top:8px">
                            <span class="pill">{{ item.statusLabel || labelStatus(item.status) }}</span>
                            <span class="pill">{{ item.aiStatusLabel || labelStatus(item.aiStatus) }}</span>
                            <span class="pill">{{ item.promotionStatusLabel || labelStatus(item.promotionStatus) }}</span>
                          </div>
                        </div>
                        <div class="actions">
                          <button class="btn" @click.stop="openWorkspaceDetail('反馈详情', item)">原始数据</button>
                        </div>
                      </div>
                      <div class="actions case-actions" @click.stop>
                        <button
                          v-for="action in feedbackActionList(item)"
                          :key="`${item.id}-${action.status}`"
                          class="btn"
                          :class="{ primary: action.status === 'PROMOTED' }"
                          @click="openFeedbackTransition(item, action)"
                        >
                          {{ action.label }}
                        </button>
                      </div>
                    </div>
                  </div>
                </section>
                <section class="panel">
                  <div class="panel-title">反馈详情</div>
                  <div v-if="!selectedFeedback" class="empty">请选择左侧一条反馈查看详情</div>
                  <template v-else>
                    <div class="profile-entry">
                      <div style="font-weight:700">{{ selectedFeedback.sourceLabel || selectedFeedback.feedbackType || `反馈 #${selectedFeedback.id}` }}</div>
                      <div class="muted" style="margin-top:6px">{{ selectedFeedback.message || '-' }}</div>
                      <div class="actions" style="margin-top:10px; flex-wrap:wrap">
                        <span class="pill">{{ selectedFeedback.statusLabel || labelStatus(selectedFeedback.status) }}</span>
                        <span class="pill">{{ selectedFeedback.aiStatusLabel || labelStatus(selectedFeedback.aiStatus) }}</span>
                        <span class="pill">{{ selectedFeedback.reviewStatusLabel || labelStatus(selectedFeedback.reviewStatus) }}</span>
                        <span class="pill">{{ selectedFeedback.promotionStatusLabel || labelStatus(selectedFeedback.promotionStatus) }}</span>
                        <span class="pill brand">{{ feedbackSourceLabel(selectedFeedback.sourceType) }}</span>
                      </div>
                    </div>
                    <div class="profile-section">
                      <div class="profile-section-title">当前判断</div>
                      <div class="profile-entry">
                        <div>{{ feedbackStatusHint(selectedFeedback.status) }}</div>
                        <small>{{ feedbackReasonText(selectedFeedback) }}</small>
                        <small>{{ feedbackActionAdvice(selectedFeedback) }}</small>
                      </div>
                    </div>
                    <div class="profile-section">
                      <div class="profile-section-title">升级 Case 资格</div>
                      <div class="profile-entry">
                        <div>{{ selectedFeedback.promotionReadinessLabel || (selectedFeedback.promotionEligible ? '满足升级条件' : '暂不满足升级条件') }}</div>
                        <small>{{ selectedFeedback.promotionReadinessReason || '当前暂无升级资格说明。' }}</small>
                      </div>
                    </div>
                    <div class="profile-section">
                      <div class="profile-section-title">业务上下文</div>
                      <div class="profile-entry">
                        <div>反馈分类：{{ selectedFeedback.category || '未分类' }}</div>
                        <small>提交来源：{{ feedbackSourceLabel(selectedFeedback.sourceType) }} / 提交人：{{ selectedFeedback.submittedBy || '-' }}</small>
                        <small v-if="selectedFeedback.matchedCaseId">已关联 Case：{{ selectedFeedback.matchedCaseId }}</small>
                        <small v-else>当前尚未关联 Case</small>
                      </div>
                    </div>
                    <div class="profile-section">
                      <div class="profile-section-title">AI 观察信号</div>
                      <div class="list">
                        <div v-if="!signals.length" class="empty">暂无 AI 观察信号</div>
                        <div v-for="item in signals" :key="item.id" class="item clickable" @click="openWorkspaceDetail('信号详情', item)">
                          <div class="item-row">
                            <div>
                              <div style="font-weight:600">{{ item.signalType || item.sourceType || '-' }}</div>
                              <div class="muted" style="font-size:12px;margin-top:4px">{{ item.summary || item.rationale || '-' }}</div>
                            </div>
                            <div class="actions">
                              <span class="pill">{{ item.severity || '-' }}</span>
                              <span class="pill">{{ labelStatus(item.status) }}</span>
                            </div>
                          </div>
                        </div>
                      </div>
                    </div>
                  </template>
                </section>
              </div>
              <section class="panel section-gap">
                <div class="panel-title">短期记忆摘要</div>
                <div class="list">
                  <div v-if="!memories.length" class="empty">暂无短期记忆摘要</div>
                  <div v-for="item in memories" :key="item.id" class="item clickable" @click="openWorkspaceDetail('记忆详情', item)">
                    <div class="item-row">
                      <div>
                        <div style="font-weight:600">{{ item.sessionId || '-' }}</div>
                        <div class="muted" style="font-size:12px;margin-top:4px">{{ item.summary || '-' }}</div>
                      </div>
                      <div class="actions">
                        <span class="pill">v{{ item.version ?? '-' }}</span>
                        <span class="pill">{{ item.status || '-' }}</span>
                      </div>
                    </div>
                  </div>
                </div>
              </section>
              <section class="panel section-gap">
                <div class="panel-title">长期记忆画像</div>
                <div v-if="!profileSections.length" class="empty">当前 Agent 暂无长期画像，真实业务反馈升级为 Case 后会逐步沉淀</div>
                <div v-else class="grid-2">
                  <div v-for="[section, values] in profileSections" :key="`feedback-${section}`" class="profile-section">
                    <div class="profile-section-title">{{ section }}</div>
                    <div v-for="entry in values.slice(0, 3)" :key="`feedback-${section}-${entry.caseId || entry.id || entry.summary}`" class="profile-entry">
                      <div>{{ entry.summary || entry.title || entry.memory || '-' }}</div>
                      <small class="muted">来源 {{ entry.caseId || entry.source || entry.sessionId || '-' }}</small>
                    </div>
                  </div>
                </div>
              </section>
            </section>
          </template>
          <template v-else-if="tab === 'cases'">
            <div class="case-workbench-title">{{ CASE_TEXT.caseWorkbench }}</div>
            <section class="panel section-gap">
              <div class="case-flow-guide">
                <strong>{{ CASE_TEXT.flow }}</strong>
                <span v-if="caseStatusFilter" class="muted">{{ caseStatusHint(caseStatusFilter) }}</span>
              </div>
              <div class="panel-title">
                <span>案件工作台</span>
                <div class="toolbar">
                  <select class="select" v-model="selectedAgentId" @change="loadCaseWorkspace">
                    <option v-for="agent in agents" :key="agent.agentId" :value="agent.agentId">{{ agent.agentName || agent.agentId }}</option>
                  </select>
                  <select class="select" v-model="caseStatusFilter" @change="loadCaseWorkspace">
                    <option value="">{{ CASE_TEXT.allStatuses }}</option>
                    <option value="PENDING_REVIEW">{{ caseStatusText('PENDING_REVIEW') }}</option>
                    <option value="CONFIRMED">{{ caseStatusText('CONFIRMED') }}</option>
                    <option value="IN_PROGRESS">{{ caseStatusText('IN_PROGRESS') }}</option>
                    <option value="RESOLVED">{{ caseStatusText('RESOLVED') }}</option>
                    <option value="ARCHIVED">{{ caseStatusText('ARCHIVED') }}</option>
                  </select>
                  <button class="btn" @click="loadCaseWorkspace">刷新</button>
                </div>
              </div>
              <div class="case-layout">
                <section class="case-list">
                  <div v-if="!cases.length" class="empty">暂无案件</div>
                  <article v-for="item in cases" :key="item.caseId" class="item case-item" :class="{ active: selectedCase?.caseId === item.caseId }" @click="openCase(item)">
                    <div class="item-row">
                      <div>
                        <strong>{{ item.title || item.caseId }}</strong>
                        <div class="muted case-meta">{{ item.caseId }} | {{ item.agentId }}</div>
                      </div>
                      <div class="actions">
                        <span class="pill">{{ item.statusLabel || caseStatusText(item.status) }}</span>
                        <span class="pill">{{ item.severity || 'MEDIUM' }}</span>
                      </div>
                    </div>
                    <div class="muted case-summary">{{ item.summary || '-' }}</div>
                    <div class="actions case-actions" @click.stop>
                      <button v-for="action in caseActionList(item)" :key="action.status" class="btn" :class="{ primary: action.status === 'RESOLVED' }" @click="openCaseTransition(item, action)">
                        {{ action.label }}
                      </button>
                    </div>
                  </article>
                </section>

                <aside class="panel profile-panel">
                  <div class="panel-title">
                    <span>智能体长期画像</span>
                    <span v-if="agentProfile" class="pill brand">v{{ agentProfile.version }}</span>
                  </div>
                  <div v-if="agentProfile" class="profile-source">来源案例：{{ agentProfile.sourceCaseIds || '-' }}</div>
                  <div v-if="!agentProfile" class="empty">暂无已解决案件画像</div>
                  <div v-for="[section, values] in profileSections" :key="section" class="profile-section">
                    <div class="profile-section-title">{{ section }}</div>
                    <div v-for="entry in values" :key="`${section}-${entry.caseId}`" class="profile-entry">
                      <div>{{ entry.text || '-' }}</div>
                      <small class="muted">案例 {{ entry.caseId }} | {{ caseStatusText(entry.status) }}</small>
                    </div>
                  </div>
                  <div v-if="selectedCase" class="profile-selected">
                    <div class="profile-section-title">当前选择案件</div>
                    <div>{{ selectedCase.title || selectedCase.caseId }}</div>
                    <div class="muted">{{ caseStatusText(selectedCase.status) }} | 负责人: {{ selectedCase.owner || '-' }}</div>
                  </div>
                  <div v-if="selectedCaseDetail?.evidence?.length" class="profile-section" style="margin-top:12px">
                    <div class="profile-section-title">来源证据</div>
                    <div v-for="entry in selectedCaseDetail.evidence" :key="`${selectedCase?.caseId || 'case'}-${entry.evidence_id || entry.id}`" class="profile-entry">
                      <div>{{ entry.excerpt || entry.preview || '-' }}</div>
                      <small class="muted">{{ entry.evidence_type || 'FEEDBACK' }} | {{ entry.session_id || entry.sessionId || '-' }}</small>
                    </div>
                  </div>
                  <div v-if="selectedCaseDetail?.reviews?.length" class="profile-section" style="margin-top:12px">
                    <div class="profile-section-title">审核记录</div>
                    <div v-for="entry in selectedCaseDetail.reviews" :key="`${selectedCase?.caseId || 'case'}-${entry.id || entry.createdAt}`" class="profile-entry">
                      <div>{{ entry.toStatus || entry.to_status || '-' }}</div>
                      <small class="muted">{{ entry.actor || entry.createdBy || '-' }} | {{ entry.reason || entry.notes || '-' }}</small>
                    </div>
                  </div>
                </aside>
              </div>
            </section>
          </template>
          <template v-else-if="tab === 'agents'">
            <section class="panel section-gap">
              <div class="panel-title">
                <span>智能体配置</span>
                <div class="actions">
                  <button class="btn primary" @click="openAgentCreate">新建智能体</button>
                  <button class="btn" @click="loadAgentConfigPage">操作</button>
                </div>
              </div>
              <div class="list">
                <div v-if="!agents.length" class="empty">暂无数据</div>
                <div
                  v-for="agent in agents"
                  :key="agent.agentId"
                  class="item clickable"
                  :class="{ active: selectedAgentId === agent.agentId }"
                  @click="openConversationForAgent(agent.agentId)"
                >
                  <div class="item-row">
                    <div>
                      <div style="font-weight:700">{{ agent.agentName || agent.agentId }}</div>
                      <div class="muted" style="font-size:12px;margin-top:4px">{{ agent.description || '-' }}</div>
                      <div class="actions" style="margin-top:8px">
                        <span class="pill brand">{{ modelLabel(agent.modelId) }}</span>
                        <span class="pill">{{ agentBindingSummary[agent.agentId]?.skillCount ?? 0 }}</span>
                        <span class="pill">MCP {{ agentBindingSummary[agent.agentId]?.mcpCount ?? 0 }}</span>
                        <span class="pill">{{ agentBindingSummary[agent.agentId]?.toolCount ?? 0 }}</span>
                      </div>
                    </div>
                    <div class="actions">
                      <span class="pill" :class="agent.channel === 'react' ? 'warn' : 'brand'">{{ agent.channel || 'auto' }}</span>
                      <button class="btn primary" @click.stop="openConversationForAgent(agent.agentId)">进入对话</button>
                      <button class="btn" @click.stop="openAgentEdit(agent.agentId)">编辑</button>
                      <button class="btn danger" @click.stop="deleteAgent(agent.agentId)">删除</button>
                    </div>
                  </div>
                </div>
              </div>
            </section>
          </template>

          <template v-else-if="tab === 'conversations'">
            <section class="panel section-gap conversation-panel">
              <div class="conversation-controls">
                <div class="panel-title">
                  <span>对话记录</span>
                  <div class="actions">
                    <button class="btn" :disabled="!selectedAgentId" @click="newChat">新建对话</button>
                    <button class="btn primary" :disabled="!selectedAgentId" @click="loadConversationPage(selectedAgentId)">刷新记录</button>
                  </div>
                </div>

                <div class="toolbar conversation-toolbar">
                  <select class="select" v-model="selectedAgentId" @change="selectConversationAgent(selectedAgentId)">
                    <option v-for="agent in agents" :key="agent.agentId" :value="agent.agentId">
                      {{ agent.agentName || agent.agentId }}
                    </option>
                  </select>
                  <select class="select" v-model="chatModelId">
                    <option value="">请选择对话模型</option>
                    <option v-for="model in models" :key="model.modelId" :value="model.modelId">
                      {{ model.modelName || model.modelId }}
                    </option>
                  </select>
                </div>
              </div>

              <div class="conversation-page">
                <div class="conversation-list">
                  <div class="panel-title" style="margin-bottom:8px">对话列表</div>
                  <div class="list">
                    <div v-if="!sessions.length" class="empty">暂无对话</div>
                    <div
                      v-for="session in sessions"
                      :key="session.sessionId"
                      class="item clickable session-item"
                      :class="{ active: currentSessionId === session.sessionId }"
                      @click="openSession(session.sessionId)"
                    >
                      <div class="item-row">
                        <div>
                          <div class="session-name">{{ sessionDisplayName(session) }}</div>
                        </div>
                        <div class="actions session-actions">
                          <button class="btn primary" @click.stop="openSession(session.sessionId)">继续对话</button>
                          <button class="btn" @click.stop="openSessionRename(session)">重命名</button>
                          <button class="btn danger" @click.stop="deleteSession(session.sessionId)">删除</button>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>

                <div class="conversation-detail">
                  <div class="panel-title" style="margin-bottom:8px">当前对话</div>
                  <div v-if="currentSessionId && sessionDetail?.overview" class="conversation-overview">
                    <div class="kvs conversation-summary-grid">
                      <div class="kv"><div class="k">路由类型</div><div class="v">{{ routeTypeText(sessionDetail.overview.latestRouteType) }}</div></div>
                      <div class="kv"><div class="k">执行状态</div><div class="v">{{ executionStatusLabel(sessionDetail.overview.latestExecutionStatus) }}</div></div>
                      <div class="kv"><div class="k">反馈数量</div><div class="v">{{ sessionDetail.overview.feedbackCount || 0 }}</div></div>
                      <div class="kv"><div class="k">业务反馈</div><div class="v">{{ sessionDetail.overview.businessFeedbackCount || 0 }}</div></div>
                      <div class="kv"><div class="k">AI观察</div><div class="v">{{ sessionDetail.overview.aiObservationCount || 0 }}</div></div>
                      <div class="kv"><div class="k">关联 Case</div><div class="v">{{ sessionDetail.overview.caseCount || 0 }}</div></div>
                      <div class="kv"><div class="k">待升级反馈</div><div class="v">{{ sessionDetail.overview.readyForCaseFeedbackCount || 0 }}</div></div>
                      <div class="kv"><div class="k">工具消息</div><div class="v">{{ sessionDetail.overview.toolMessageCount || 0 }}</div></div>
                      <div class="kv"><div class="k">短期记忆</div><div class="v">{{ sessionDetail.overview.hasMemorySummary ? '已生成' : '未生成' }}</div></div>
                    </div>
                    <section class="panel subtle" style="margin-top:12px">
                      <div class="panel-title" style="margin-bottom:8px">运营链路时间线</div>
                      <div v-if="!(sessionDetail.timeline || []).length" class="empty">当前会话暂无运营链路记录</div>
                      <div v-for="entry in (sessionDetail.timeline || [])" :key="`${entry.type}-${entry.refId}-${entry.time}`" class="profile-entry">
                        <div class="item-row">
                          <div style="font-weight:600">{{ entry.title || entry.type }}</div>
                          <span class="pill">{{ entry.status || '-' }}</span>
                        </div>
                        <div class="muted" style="font-size:12px;margin-top:6px">{{ entry.summary || '-' }}</div>
                        <div class="actions" style="margin-top:8px">
                          <button class="btn" @click="jumpToLogSession(selectedAgentId, currentSessionId)">查看日志链路</button>
                        </div>
                        <small class="muted">{{ entry.time || '-' }}</small>
                      </div>
                    </section>
                    <div class="conversation-side-grid">
                      <section class="panel subtle">
                        <div class="panel-title" style="margin-bottom:8px">关联业务反馈</div>
                        <div v-if="!(sessionDetail.feedback || []).length" class="empty">当前会话暂无反馈</div>
                        <div v-for="item in (sessionDetail.feedback || [])" :key="`fb-${item.id}`" class="item">
                          <div class="item-row">
                            <div style="font-weight:600">{{ item.message || item.category || `反馈 #${item.id}` }}</div>
                            <span class="pill">{{ labelStatus(item.status) }}</span>
                          </div>
                          <div class="muted" style="font-size:12px;margin-top:6px">{{ feedbackSourceLabel(item.sourceType) }} / {{ item.category || '未分类' }}</div>
                          <div v-if="item.sourcePreview" class="muted" style="font-size:12px;margin-top:4px">来源摘录：{{ item.sourcePreview }}</div>
                          <div v-if="item.qualificationHint" class="muted" style="font-size:12px;margin-top:4px">{{ item.qualificationHint }}</div>
                          <div class="muted" style="font-size:12px;margin-top:4px">{{ feedbackReasonText(item) }}</div>
                          <div class="actions" style="margin-top:8px">
                            <button class="btn" @click="jumpToLogSession(item.agentId || selectedAgentId, item.sessionId || currentSessionId)">查看日志</button>
                          </div>
                          <div v-if="item.matchedCaseId" class="muted" style="font-size:12px;margin-top:4px">已关联 Case：{{ item.matchedCaseId }}</div>
                        </div>
                      </section>
                      <section class="panel subtle">
                        <div class="panel-title" style="margin-bottom:8px">最近一次 Subagent 执行</div>
                        <div v-if="!(sessionDetail.subagents || []).length" class="empty">当前会话暂无已落库的子任务执行记录</div>
                        <div
                          v-for="item in (sessionDetail.subagents || [])"
                          :key="item.taskId || item.id"
                          class="item"
                        >
                          <div class="item-row">
                            <div style="font-weight:600">{{ item.description || item.taskId || '未命名子任务' }}</div>
                            <span class="pill">{{ executionStatusText(item.status) }}</span>
                          </div>
                          <div v-if="item.result" class="muted" style="font-size:12px;margin-top:6px">{{ item.result }}</div>
                          <div v-else-if="item.errorMessage" class="muted danger-text" style="font-size:12px;margin-top:6px">{{ item.errorMessage }}</div>
                          <div class="muted" style="font-size:12px;margin-top:4px">
                            taskId：{{ item.taskId || '-' }} / 执行批次：{{ item.executionId || '-' }}
                          </div>
                          <div class="muted" style="font-size:12px;margin-top:4px">
                            开始：{{ item.startedAt || '-' }} / 完成：{{ item.completedAt || item.updatedAt || '-' }}
                          </div>
                        </div>
                      </section>
                      <section class="panel subtle">
                        <div class="panel-title" style="margin-bottom:8px">关联 Case</div>
                        <div v-if="!(sessionDetail.cases || []).length" class="empty">当前会话暂无 Case</div>
                        <div v-for="item in (sessionDetail.cases || [])" :key="item.caseId" class="item clickable" @click="openCase(item)">
                          <div class="item-row">
                            <div style="font-weight:600">{{ item.title || item.caseId }}</div>
                            <span class="pill">{{ caseStatusText(item.status) }}</span>
                          </div>
                          <div class="muted" style="font-size:12px;margin-top:6px">{{ item.caseId }}</div>
                          <div v-if="item.summary" class="muted" style="font-size:12px;margin-top:4px">{{ item.summary }}</div>
                          <div class="actions" style="margin-top:8px">
                            <button class="btn" @click.stop="jumpToLogSession(item.agentId || selectedAgentId, currentSessionId)">查看日志</button>
                          </div>
                        </div>
                      </section>
                    </div>
                  </div>
                  <div v-if="Object.keys(subagentTasks).length || todoItems.length" class="execution-progress">
                    <div v-if="todoItems.length" class="todo-strip">
                      <span class="progress-label">执行计划</span>
                      <span v-for="todo in todoItems" :key="todo.todoId" class="progress-chip" :class="String(todo.status || '').toLowerCase()">
                        {{ todo.content }} · {{ executionStatusText(todo.status) }}
                      </span>
                    </div>
                    <div v-if="Object.keys(subagentTasks).length" class="subagent-strip">
                      <div v-for="task in Object.values(subagentTasks)" :key="task.taskId" class="subagent-row">
                        <span class="progress-dot" :class="String(task.status || '').toLowerCase()"></span>
                        <span class="subagent-description">{{ task.description }}</span>
                        <span class="muted">{{ executionStatusText(task.status) }}</span>
                        <button
                          v-if="['RUNNING', 'PENDING'].includes(task.status)"
                          class="btn danger compact"
                          @click="cancelSubagentTask(task.taskId)"
                        >停止</button>
                        <span v-if="task.traces?.length" class="subagent-trace">
                          {{ task.traces[task.traces.length - 1].content }}
                        </span>
                      </div>
                    </div>
                  </div>
                  <div ref="chatMessagesRef" class="chat-list conversation-messages" style="padding-right:2px">
                    <div v-if="!currentSessionId" class="empty">暂无对话</div>
                    <template v-else>
                      <div v-if="sessionDetail?.messages?.length">
                        <div
                          v-for="msg in sessionDetail.messages"
                          :key="msg.id"
                          class="chat-box"
                          :class="msg.role === 'user' ? 'chat-user' : 'chat-ai'"
                          style="margin-bottom:10px"
                        >
                          <div class="muted" style="font-size:11px;margin-bottom:6px">{{ msg.role }}</div>
                          <div>{{ msg.content }}</div>
                        </div>
                      </div>
                      <div v-else-if="!chatStream.length" class="empty">暂无消息</div>
                      <div
                        v-for="entry in chatStream"
                        :key="entry.id"
                        class="chat-box"
                        :class="entry.type === 'chat' ? 'chat-user' : 'chat-ai'"
                        style="margin-bottom:10px"
                      >
                        <div class="muted" style="font-size:11px;margin-bottom:6px">
                          {{ entry.type }} {{ entry.step ? `#${entry.step}` : '' }}
                        </div>
                        <div>{{ entry.content }}</div>
                      </div>
                    </template>
                  </div>

                  <div class="toolbar section-gap" style="margin-top:12px">
                    <textarea
                      class="textarea"
                      v-model="chatInput"
                      placeholder="输入消息，Enter 发送"
                      @keydown.enter.exact.prevent="sendMessage"
                    ></textarea>
                    <button class="btn primary" :disabled="isStreaming" @click="sendMessage">发送</button>
                    <button v-if="isStreaming" class="btn danger" @click="cancelMainExecution">停止本轮</button>
                  </div>
                  <div class="muted" style="font-size:12px">{{ chatStatus }}</div>
                </div>
              </div>
            </section>
          </template>

          <template v-else-if="tab === 'models'">
            <div class="grid-2">
              <section class="panel">
                <div class="panel-title">
                  <span>模型配置</span>
                  <div class="actions">
                    <button class="btn primary" @click="openModelCreate">新建模型</button>
                    <button class="btn" @click="loadModelsPage">操作</button>
                  </div>
                </div>
                <div class="list">
                  <div v-if="!models.length" class="empty">暂无数据</div>
                  <div v-for="model in models" :key="model.modelId" class="item">
                    <div class="item-row">
                      <div>
                        <div style="font-weight:700">{{ model.modelName || model.modelId }}</div>
                        <div class="muted" style="font-size:12px;margin-top:4px">
                           {{ model.providerName || 'OpenAI 兼容接口' }}
                        </div>
                      </div>
                      <div class="actions">
                        <span class="pill" :class="Number(model.status) === 1 ? 'ok' : 'bad'">
                           {{ Number(model.status) === 1 ? '启用' : '停用' }}
                        </span>
                        <button class="btn" @click="openModelEdit(model)">编辑</button>
                        <button class="btn danger" @click="deleteModel(model.modelId)">删除</button>
                      </div>
                    </div>
                    <div class="kvs" style="margin-top:10px">
                      <div class="kv"><div class="k">配置状态</div><div class="v">{{ model.configured ? '可用' : '未配置或不可用' }}</div></div>
                      <div class="kv"><div class="k">类型</div><div class="v">{{ model.modelType || 'openai' }}</div></div>
                    </div>
                  </div>
                </div>
              </section>

            </div>
          </template>

          <template v-else-if="tab === 'mcp'">
            <div class="grid-2">
              <section class="panel">
                <div class="panel-title">
                  <span>MCP</span>
                  <div class="actions">
                    <button class="btn primary" @click="openMcpCreate">新建 MCP</button>
                    <button class="btn" @click="seedLocalTestMcp">导入本地测试 MCP</button>
                    <button class="btn" @click="loadMcpPage">操作</button>
                  </div>
                </div>
                <div class="list">
                  <div v-if="!mcpServers.length" class="empty">
                    <div>暂无 MCP 服务</div>
                    <button class="btn primary" @click="seedLocalTestMcp">导入本地测试 MCP</button>
                  </div>
                  <div
                    v-for="item in mcpServers"
                    :key="pick(item, 'id', 'ID')"
                    class="item clickable"
                    :class="{ active: String(pick(item, 'id', 'ID')) === String(selectedMcpServerId) }"
                    @click="loadMcpVersions(pick(item, 'id', 'ID'))"
                  >
                    <div class="item-row">
                      <div>
                        <div style="font-weight:700">{{ pick(item, 'name', 'NAME') || pick(item, 'mcpName', 'MCP_NAME') }}</div>
                        <div class="muted" style="font-size:12px;margin-top:4px">{{ pick(item, 'mcpKey', 'MCP_KEY') || pick(item, 'key', 'KEY') }}</div>
                      </div>
                      <div class="actions">
                        <span class="pill">{{ pick(item, 'latestVersion', 'LATEST_VERSION') || '-' }}</span>
                        <button class="btn" @click.stop="loadMcpVersions(pick(item, 'id', 'ID'))">操作</button>
                      </div>
                    </div>
                  </div>
                </div>
              </section>

              <section class="panel">
                <div class="panel-title">
                  <span>MCP 版本</span>
                  <div class="actions">
                    <button class="btn primary" :disabled="!selectedMcpServerId" @click="openMcpVersionCreate">新建版本</button>
                    <button class="btn" :disabled="!selectedMcpServerId" @click="loadMcpVersions(selectedMcpServerId)">操作</button>
                  </div>
                </div>
                <div class="muted" style="font-size:12px;margin-bottom:10px">信息</div>
                <div class="list">
                  <div v-if="!mcpVersions.length" class="empty">
                    <div>这个 MCP 还没有版本配置</div>
                    <button class="btn primary" :disabled="!selectedMcpServerId" @click="openMcpVersionCreate">创建第一个版本</button>
                  </div>
                  <div v-for="version in mcpVersions" :key="pick(version, 'id', 'ID')" class="item">
                    <div class="item-row">
                      <div>
                        <div style="font-weight:700">v{{ pick(version, 'version', 'VERSION') || '-' }}</div>
                        <div class="muted" style="font-size:12px;margin-top:4px">{{ pick(version, 'transportType', 'TRANSPORT_TYPE') || '-' }}</div>
                      </div>
                      <div class="actions">
                        <span class="pill">{{ labelStatus(pick(version, 'status', 'STATUS')) }}</span>
                        <button class="btn" @click="openCapabilityDetail('mcp', pick(version, 'id', 'ID'))">查看详情</button>
                      </div>
                    </div>
                    <div class="actions" style="margin-top:10px;gap:6px">
                      <button
                        v-for="action in capVersionActions('mcp', pick(version, 'status', 'STATUS'))"
                        :key="action"
                        class="btn"
                        @click="mcpAction(pick(version, 'id', 'ID'), action)"
                      >
                         {{ action }}
                      </button>
                    </div>
                  </div>
                </div>
              </section>
            </div>
          </template>

          <template v-else-if="tab === 'skills'">
            <div class="grid-2">
              <section class="panel">
                <div class="panel-title">
                  <span>Skills</span>
                  <div class="actions">
                    <button class="btn primary" @click="openSkillUpload">上传 Skill</button>
                    <button class="btn" @click="loadSkillPage">操作</button>
                  </div>
                </div>
                <div class="list">
                  <div v-if="!skillPackages.length && !localSkills.length" class="empty">暂无 Skill</div>
                  <div
                    v-for="item in skillPackages"
                    :key="pick(item, 'id', 'ID')"
                    class="item clickable"
                    :class="{ active: String(pick(item, 'id', 'ID')) === String(selectedSkillPackageId) }"
                    @click="loadSkillVersions(pick(item, 'id', 'ID'))"
                  >
                    <div class="item-row">
                      <div>
                        <div style="font-weight:700">{{ pick(item, 'name', 'NAME') }}</div>
                        <div class="muted" style="font-size:12px;margin-top:4px">{{ pick(item, 'skillKey', 'SKILL_KEY') || pick(item, 'skill_key', 'SKILL_KEY') }}</div>
                      </div>
                      <div class="actions">
                        <span class="pill">{{ labelStatus(pick(item, 'status', 'STATUS', 'lifecycleStatus', 'LIFECYCLE_STATUS')) }}</span>
                        <button class="btn" @click.stop="loadSkillVersions(pick(item, 'id', 'ID'))">操作</button>
                      </div>
                    </div>
                    <div class="muted" style="font-size:12px;margin-top:8px">{{ pick(item, 'description', 'DESCRIPTION') || '-' }}</div>
                  </div>
                  <div v-for="item in localSkills" :key="`local-${item.skillId}`" class="item clickable" :class="{ active: String(item.skillId) === String(selectedLocalSkillId) }" @click="selectLocalSkill(item.skillId)">
                    <div class="item-row">
                      <div>
                        <div style="font-weight:700">{{ item.skillName || item.skillId }}</div>
                        <div class="muted" style="font-size:12px;margin-top:4px">{{ item.skillId }}</div>
                      </div>
                      <span class="pill brand">本地</span>
                    </div>
                    <div class="muted" style="font-size:12px;margin-top:8px">{{ item.description || '-' }}</div>
                  </div>
                </div>
              </section>

              <section class="panel">
                <template v-if="selectedLocalSkill">
                  <div class="panel-title">
                    <span>{{ selectedLocalSkill.skillName || selectedLocalSkill.skillId }}</span>
                    <span class="pill brand">本地 Skill</span>
                  </div>
                  <div class="muted" style="font-size:12px;margin-bottom:10px">{{ selectedLocalSkill.skillId }}</div>
                  <div class="muted" style="font-size:12px;margin-bottom:10px">
                    {{ selectedAgentId ? `当前按 Agent「${currentAgentName}」的运行时技能视角读取（.ma/skills）` : '当前未选择 Agent，正在读取全局本地 Skill 视图' }}
                  </div>
                  <div v-if="localSkillDetail?.skill" class="json-box" style="white-space:pre-wrap;max-height:520px;overflow:auto">{{ localSkillDetail.skill.content }}</div>
                  <div v-else class="empty">无法读取本地 SKILL.md</div>
                </template>
                <template v-else>
                <div class="panel-title">
                  <span>Skill 版本</span>
                  <button class="btn" :disabled="!selectedSkillPackageId" @click="loadSkillVersions(selectedSkillPackageId)">操作</button>
                </div>
                <div class="muted" style="font-size:12px;margin-bottom:10px">信息</div>
                <div class="list">
                   <div v-if="!skillVersions.length" class="empty">暂无数据</div>
                  <div v-for="version in skillVersions" :key="pick(version, 'id', 'ID')" class="item">
                    <div class="item-row">
                      <div>
                        <div style="font-weight:700">v{{ pick(version, 'version', 'VERSION') || '-' }}</div>
                        <div class="muted" style="font-size:12px;margin-top:4px">{{ pick(version, 'status', 'STATUS') || '-' }}</div>
                      </div>
                      <div class="actions">
                        <span class="pill">{{ labelStatus(pick(version, 'status', 'STATUS')) }}</span>
                        <button class="btn" @click="openCapabilityDetail('skill', pick(version, 'id', 'ID'))">查看详情</button>
                      </div>
                    </div>
                    <div class="actions" style="margin-top:10px;gap:6px">
                      <button
                        v-for="action in capVersionActions('skill', pick(version, 'status', 'STATUS'))"
                        :key="action"
                        class="btn"
                        @click="skillAction(pick(version, 'id', 'ID'), action)"
                      >
                         {{ action }}
                       </button>
                     </div>
                   </div>
                 </div>
                </template>
              </section>
            </div>
          </template>

          <template v-else-if="tab === 'logs'">
            <section class="panel log-panel">
              <div class="log-toolbar">
                <div>
                  <div class="page-title">对话日志</div>
                  <div class="muted log-subtitle">按 Agent 和会话查看完整问答记录</div>
                </div>
                <div class="log-filters">
                  <select class="select log-agent-filter" v-model="selectedLogAgent">
                    <option value="">全部 Agent</option>
                    <option v-for="agent in logAgentOptions" :key="agent.id" :value="agent.id">{{ agent.name }}</option>
                  </select>
                  <input v-model="logSessionQuery" class="api-input log-search" placeholder="搜索会话 ID" />
                  <button class="btn" @click="loadLogsPage">刷新</button>
                </div>
              </div>
              <div class="log-layout">
                <aside class="log-sessions">
                  <div class="log-section-head"><span>会话</span><span class="pill">{{ logSessions.length }}</span></div>
                  <div v-if="!logSessions.length" class="empty">暂无匹配的会话</div>
                  <button v-for="session in logSessions" :key="logSessionKey(session)" class="log-session-row" :class="{ active: logSessionKey(session) === selectedLogSessionKey }" @click="selectLogSession(session)">
                    <span class="log-session-icon">◌</span>
                    <span class="log-session-main"><strong>{{ session.sessionId }}</strong><small>{{ logAgentName(session.agentId) }}</small></span>
                    <span class="log-session-count">{{ session.messages?.length ?? 0 }}</span>
                  </button>
                </aside>
                <section class="log-conversation">
                  <template v-if="selectedLogSession">
                    <div class="log-conversation-head">
                      <div><div class="log-session-title">{{ selectedLogSession.sessionId }}</div><div class="muted">{{ logAgentName(selectedLogSession.agentId) }} · {{ selectedLogSession.lastSeenAt || '-' }}</div></div>
                      <div class="actions"><span class="pill">{{ selectedLogSession.callCount ?? 0 }} 次调用</span><span class="pill">{{ selectedLogSession.totalTokens ?? 0 }} tokens</span></div>
                    </div>
                    <div class="profile-section" style="margin-bottom:12px">
                      <div class="profile-section-title">LLM 调用摘要</div>
                      <div v-if="!selectedLogSession.logs?.length" class="empty">当前会话暂无模型调用记录</div>
                      <div v-else class="list">
                        <div v-for="(entry, index) in selectedLogSession.logs" :key="`llm-${selectedLogSession.sessionId}-${entry.id || index}`" class="item">
                          <div class="item-row">
                            <div>
                              <div style="font-weight:600">{{ entry.modelName || entry.modelId || '未知模型' }}</div>
                              <div class="muted" style="font-size:12px;margin-top:4px">
                                {{ entry.mode || '-' }} / 历史 {{ entry.historyMsgCount ?? 0 }} / 折叠后 {{ entry.foldedMsgCount ?? 0 }}
                              </div>
                            </div>
                            <div class="actions">
                              <span class="pill">{{ entry.status || '-' }}</span>
                              <span class="pill">{{ entry.durationMs ?? 0 }} ms</span>
                              <span class="pill">{{ entry.totalTokens ?? 0 }} tokens</span>
                            </div>
                          </div>
                          <div class="muted" style="font-size:12px;margin-top:8px">
                            系统提示 {{ entry.systemPromptLen ?? 0 }} 字 / 用户 {{ entry.userMessageLen ?? 0 }} 字 / 回复 {{ entry.assistantResponseLen ?? 0 }} 字
                          </div>
                          <div v-if="entry.errorMessage" class="error section-gap" style="margin-top:8px">{{ entry.errorMessage }}</div>
                        </div>
                      </div>
                    </div>
                    <div class="log-messages">
                      <div v-if="!selectedLogSession.messages?.length" class="empty">该会话暂无消息</div>
                      <div v-for="msg in selectedLogSession.messages" :key="msg.id" class="log-message" :class="`log-message-${String(msg.role || '').toLowerCase()}`">
                        <div class="log-avatar">{{ String(msg.role || '').toLowerCase() === 'user' ? 'U' : String(msg.role || '').toLowerCase() === 'tool' ? 'T' : 'AI' }}</div>
                        <div class="log-message-body"><div class="log-message-meta">{{ logRoleLabel(msg.role) }} <span v-if="msg.turn != null">轮次 {{ msg.turn }}</span><span v-if="msg.step != null">步骤 {{ msg.step }}</span><span v-if="msg.toolName">工具 {{ msg.toolName }}</span><span>{{ msg.createdAt || '' }}</span></div><div class="log-message-content">{{ msg.content || msg.toolArguments || msg.toolCallsJson || '(无内容)' }}</div></div>
                      </div>
                    </div>
                  </template>
                  <div v-else class="log-empty-state"><div class="log-empty-icon">↗</div><strong>选择一个会话</strong><span class="muted">左侧选择会话后查看完整对话记录</span></div>
                </section>
              </div>
            </section>
          </template>

          <template v-else-if="tab === 'legacy-logs'">
            <section class="panel">
              <div class="panel-title">模型调用日志</div>
              <div class="list">
                <div v-if="!logs.length" class="empty">暂无数据</div>
                <div v-for="agent in logs" :key="agent.agentId" class="item">
                  <div class="item-row">
                    <div>
                      <div style="font-weight:700">{{ agent.agentId }}</div>
                      <div class="muted" style="font-size:12px;margin-top:4px">
                         Sessions: {{ agent.sessionCount ?? 0 }} | Calls: {{ agent.totalCalls ?? 0 }} | Token: {{ agent.totalTokens ?? 0 }}
                      </div>
                    </div>
                    <button class="btn" @click="selectedLogAgent = agent.agentId">查看详情</button>
                  </div>

                  <div v-if="selectedLogAgent === agent.agentId" style="margin-top:12px;display:flex;flex-direction:column;gap:10px">
                    <div v-for="session in agent.sessions || []" :key="session.sessionId" class="item">
                      <div class="item-row">
                        <div>
                          <div style="font-weight:600">{{ session.sessionId }}</div>
                          <div class="muted" style="font-size:12px;margin-top:4px">{{ session.lastSeenAt || '-' }}</div>
                        </div>
                        <div class="actions">
                          <span class="pill">LLM {{ session.callCount ?? 0 }}</span>
                          <span class="pill">{{ session.toolCalls ?? 0 }}</span>
                          <span class="pill">{{ session.messages?.length ?? 0 }}</span>
                        </div>
                      </div>
                      <div v-if="session.messages?.length" class="trace-list" style="margin-top:10px">
                        <div v-for="msg in session.messages" :key="msg.id" class="trace-stage">
                          <div class="trace-title">{{ msg.role }} {{ msg.step ? `#${msg.step}` : '' }}</div>
                          <div class="trace-content">{{ msg.content }}</div>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </section>
          </template>
        </div>
      </main>
    </div>

    <div v-if="modal.open" class="modal-overlay" @click.self="closeModal">
      <div class="modal-content">
        <div class="modal-head">
          <h2>{{ modal.title }}</h2>
          <button class="btn" @click="closeModal">取消</button>
        </div>

        <template v-if="modal.kind === 'agent'">
          <div class="field-grid">
            <div class="field">
              <label>agentId</label>
              <input v-model="modal.form.agentId" :disabled="modal.mode === 'edit'" placeholder="agent-001" />
            </div>
            <div class="field">
              <label>agentName</label>
               <input v-model="modal.form.agentName" placeholder="Agent name" />
            </div>
            <div class="field">
              <label>channel</label>
              <select v-model="modal.form.channel">
                <option value="auto">auto</option>
                <option value="react">react</option>
              </select>
            </div>
            <div class="field">
              <label>modelId</label>
              <select v-model="modal.form.modelId">
                <option value="">请选择模型</option>
                <option v-for="model in models" :key="model.modelId" :value="model.modelId">
                  {{ model.modelName || model.modelId }}
                </option>
              </select>
            </div>
            <div class="field">
              <label>status</label>
              <select v-model="modal.form.status">
                <option :value="1">启用</option>
                <option :value="0">停用</option>
              </select>
            </div>
            <div class="field">
              <label>沙箱父目录</label>
               <input v-model="modal.form.workDir" placeholder="留空则使用项目 .ma/workspaces 目录" />
               <div class="field-hint">每个 Agent 会在此目录下自动创建独立的 .ma/workspaces/agentId 沙箱。</div>
            </div>
            <div class="field field-full">
              <label>description</label>
               <input v-model="modal.form.description" placeholder="智能体描述" />
            </div>
            <div class="field field-full">
              <label>systemPrompt</label>
               <textarea v-model="modal.form.systemPrompt" rows="4" placeholder="系统提示词"></textarea>
            </div>

            <div class="field field-full">
              <label>名称</label>
              <div class="bind-list">
                <label
                  v-for="tool in agentToolOptions.filter(item => !['call_mcp_tool'].includes(pick(item, 'toolId', 'tool_id')))"
                  :key="pick(tool, 'toolId', 'tool_id')"
                  class="bind-item"
                >
                  <input v-model="modal.form.boundToolIds" type="checkbox" :value="String(pick(tool, 'toolId', 'tool_id'))" />
                  <div class="bind-main">
                    <div class="bind-title">{{ pick(tool, 'name', 'NAME') || pick(tool, 'toolId', 'tool_id') }}</div>
                    <div class="bind-sub">{{ pick(tool, 'description', 'DESCRIPTION') || '-' }}</div>
                  </div>
                  <div class="pill">{{ pick(tool, 'riskLevel', 'RISK_LEVEL') || '-' }}</div>
                </label>
              </div>
            </div>

            <div class="field field-full">
              <label>Skills</label>
              <div class="bind-list">
                <label
                  v-for="skill in agentSkillOptions"
                  :key="pick(skill, 'skillId', 'SKILL_ID')"
                  class="bind-item"
                >
                  <input v-model="modal.form.boundSkillIds" type="checkbox" :value="String(pick(skill, 'skillId', 'SKILL_ID'))" />
                  <div class="bind-main">
                    <div class="bind-title">{{ pick(skill, 'skillName', 'SKILL_NAME') || pick(skill, 'name', 'NAME') }}</div>
                    <div class="bind-sub">{{ pick(skill, 'description', 'DESCRIPTION') || '-' }}</div>
                  </div>
                  <div class="pill">{{ pick(skill, 'skillId', 'SKILL_ID') }}</div>
                </label>
              </div>
            </div>

            <div class="field field-full">
              <label>MCP</label>
              <div class="bind-list">
                <label
                  v-for="mcp in agentMcpOptions"
                  :key="pick(mcp, 'mcpId', 'MCP_ID')"
                  class="bind-item"
                >
                  <input v-model="modal.form.boundMcpIds" type="checkbox" :value="String(pick(mcp, 'mcpId', 'MCP_ID'))" />
                  <div class="bind-main">
                    <div class="bind-title">{{ pick(mcp, 'mcpName', 'MCP_NAME') || pick(mcp, 'name', 'NAME') }}</div>
                    <div class="bind-sub">{{ pick(mcp, 'baseUrl', 'BASE_URL') || pick(mcp, 'transportType', 'TRANSPORT_TYPE') || '-' }}</div>
                  </div>
                  <div class="pill">{{ pick(mcp, 'mcpId', 'MCP_ID') }}</div>
                </label>
              </div>
            </div>

            <div class="field field-full">
              <label>运行时能力预览</label>
              <div class="bind-runtime-panel">
                <div class="field-hint">
                  当前工作目录：{{ agentBindingDetail?.workspace || modal.form.workDir || '将使用默认 Agent 工作目录' }}
                </div>
                <div v-if="(agentBindingDetail?.skills || []).length" class="bind-runtime-section">
                  <div class="bind-runtime-section-title">Skill 装配状态</div>
                  <div class="bind-list">
                    <div
                      v-for="skill in (agentBindingDetail?.skills || [])"
                      :key="skill.skillId"
                      class="bind-item bind-item-static"
                    >
                      <div class="bind-main">
                        <div class="bind-title">{{ skill.skillName || skill.skillId }}</div>
                        <div class="bind-sub">{{ runtimeAvailabilityText(skill, '已同步到运行时', '运行时未发现该 Skill') }}</div>
                      </div>
                      <div class="bind-runtime-tags">
                        <span class="pill">{{ skill.skillId }}</span>
                        <span class="pill" :class="runtimeAvailabilityClass(skill.runtimeAvailable)">{{ skill.runtimeStatusText || (skill.runtimeAvailable ? '已装配' : '未装配') }}</span>
                      </div>
                    </div>
                  </div>
                </div>
                <div v-if="(agentBindingDetail?.mcps || []).length" class="bind-runtime-section">
                  <div class="bind-runtime-section-title">MCP 装配状态</div>
                  <div class="bind-list">
                    <div
                      v-for="mcp in (agentBindingDetail?.mcps || [])"
                      :key="mcp.mcpId"
                      class="bind-item bind-item-static"
                    >
                      <div class="bind-main">
                        <div class="bind-title">{{ mcp.mcpName || mcp.mcpId }}</div>
                        <div class="bind-sub">{{ runtimeAvailabilityText(mcp, '已同步到运行时', '运行时未发现该 MCP') }}</div>
                      </div>
                      <div class="bind-runtime-tags">
                        <span class="pill">{{ mcp.mcpId }}</span>
                        <span class="pill" :class="runtimeAvailabilityClass(mcp.runtimeAvailable)">{{ mcp.runtimeStatusText || (mcp.runtimeAvailable ? '已装配' : '未装配') }}</span>
                      </div>
                    </div>
                  </div>
                </div>
                <div v-if="(agentBindingDetail?.effectiveTools || []).length" class="bind-list">
                  <div
                    v-for="tool in (agentBindingDetail?.effectiveTools || [])"
                    :key="tool.toolId"
                    class="bind-item bind-item-static"
                  >
                    <div class="bind-main">
                      <div class="bind-title">{{ tool.name || tool.toolId }}</div>
                      <div class="bind-sub">{{ tool.description || tool.toolId }}</div>
                    </div>
                    <div class="bind-runtime-tags">
                      <span class="pill">{{ tool.toolId }}</span>
                      <span class="pill brand">{{ effectiveToolSourceLabel(tool.source) }}</span>
                    </div>
                  </div>
                </div>
                <div v-else class="empty-inline">
                  当前还没有可展示的运行时生效工具；保存绑定后会自动计算。
                </div>
              </div>
            </div>

          </div>
          <div class="field-actions">
            <button class="btn" @click="closeModal">取消</button>
            <button class="btn primary" :disabled="modal.saving || !modal.form.agentId || !modal.form.modelId" @click="saveAgent">保存智能体</button>
          </div>
        </template>

        <template v-else-if="modal.kind === 'sessionTitle'">
          <div class="field-grid">
            <div class="field field-full">
              <label>对话标题</label>
              <input v-model="modal.form.sessionTitle" placeholder="对话标题" />
            </div>
          </div>
          <div class="field-actions">
            <button class="btn" @click="closeModal">取消</button>
            <button class="btn primary" :disabled="modal.saving" @click="saveSessionRename">保存</button>
          </div>
        </template>
        <template v-else-if="modal.kind === 'model'">
          <div class="field-grid">
            <div class="field">
              <label>模型名称</label>
              <input v-model="modal.form.modelName" placeholder="deepseek-chat" />
            </div>
            <div class="field">
              <label>接口地址</label>
              <input v-model="modal.form.modelUrl" placeholder="https://api.deepseek.com/v1" />
            </div>
            <div class="field">
              <label>API Key</label>
              <input v-model="modal.form.apiKey" type="password" placeholder="sk-... 或 ${DEEPSEEK_API_KEY}" />
            </div>
            <div class="field">
              <label>对话补全路径</label>
              <input v-model="modal.form.completionsPath" placeholder="/v1/chat/completions" />
            </div>
            <div class="field">
              <label>模型类型</label>
              <input v-model="modal.form.modelType" placeholder="openai" />
            </div>
            <div class="field">
              <label>状态</label>
              <select v-model="modal.form.modelStatus">
                <option :value="1">启用</option>
                <option :value="0">停用</option>
              </select>
            </div>
          </div>
          <div class="field-actions">
             <button class="btn" @click="closeModal">取消</button>
             <button class="btn primary" :disabled="modal.saving" @click="saveModel">保存模型</button>
          </div>
        </template>

        <template v-else-if="modal.kind === 'mcpServer'">
          <div class="field-grid">
            <div class="field">
              <label>mcpKey</label>
              <input v-model="modal.form.mcpKey" placeholder="demo-mcp" />
            </div>
            <div class="field">
              <label>name</label>
              <input v-model="modal.form.name" placeholder="MCP 名称" />
            </div>
            <div class="field field-full">
              <label>description</label>
              <textarea v-model="modal.form.description" rows="4" placeholder="MCP 描述"></textarea>
            </div>
          </div>
          <div class="field-actions">
            <button class="btn" @click="closeModal">取消</button>
            <button class="btn primary" :disabled="modal.saving || !modal.form.mcpKey || !modal.form.name" @click="saveMcpServer">保存 MCP</button>
          </div>
        </template>

         <template v-else-if="modal.kind === 'mcpVersion'">
           <div class="field-grid">
            <div class="field">
              <label>version</label>
              <input v-model="modal.form.version" />
            </div>
            <div class="field">
              <label>transportType</label>
              <select v-model="modal.form.transportType">
                <option value="sse">sse</option>
                <option value="stdio">stdio</option>
                <option value="streamable-http">streamable-http</option>
              </select>
            </div>
            <div class="field">
              <label>credentialRef</label>
              <input v-model="modal.form.credentialRef" />
            </div>
            <div class="field field-full">
              <label>endpointConfig</label>
              <textarea v-model="modal.form.endpointConfig" rows="5" placeholder='HTTP: {"baseUri":"http://localhost:3000"}；STDIO: {"command":"node","args":["server.js"]}'></textarea>
            </div>
          </div>
          <div class="field-actions">
            <button class="btn" @click="closeModal">取消</button>
            <button class="btn primary" :disabled="modal.saving || !modal.form.version || !modal.form.transportType || !modal.form.endpointConfig" @click="saveMcpVersion">保存版本</button>
          </div>
        </template>

        <template v-else-if="modal.kind === 'skillUpload'">
          <div class="field-grid">
            <div class="field">
              <label>skillKey</label>
              <input v-model="modal.form.skillKey" />
            </div>
            <div class="field">
              <label>name</label>
              <input v-model="modal.form.name" />
            </div>
            <div class="field">
              <label>version</label>
              <input v-model="modal.form.version" />
            </div>
            <div class="field">
              <label>file</label>
              <input type="file" accept=".zip" @change="modal.form.file = $event.target.files?.[0] || null" />
            </div>
            <div class="field field-full">
              <label>description</label>
              <textarea v-model="modal.form.description" rows="4"></textarea>
            </div>
          </div>
          <div class="field-actions">
            <button class="btn" @click="closeModal">取消</button>
            <button class="btn primary" :disabled="modal.saving || !modal.form.file" @click="saveSkillUpload">保存</button>
          </div>
        </template>

        <template v-else-if="modal.kind === 'caseMerge'">
          <div class="transition-context">
            <div class="transition-target">{{ modal.form.actionLabel }}</div>
            <div class="muted">当前 Case：{{ modal.extra.caseId }}</div>
          </div>
          <div class="field-grid">
            <div class="field field-full">
              <label>目标 Case ID</label>
              <input v-model="modal.form.targetCaseId" placeholder="填写要合并到的目标 Case ID" />
            </div>
            <div class="field field-full">
              <label>合并原因</label>
              <textarea v-model="modal.form.reason" rows="3" placeholder="说明两个 Case 为什么属于同一类业务问题"></textarea>
            </div>
          </div>
          <div class="field-actions">
            <button class="btn" @click="closeModal">取消</button>
            <button class="btn primary" :disabled="modal.saving || !modal.form.targetCaseId" @click="saveCaseMerge">提交合并</button>
          </div>
        </template>

        <template v-else-if="modal.kind === 'caseTransition'">
          <div class="transition-context">
            <div class="transition-target">{{ modal.form.actionLabel }}</div>
            <div class="muted">当前状态: {{ caseStatusText(modal.extra.fromStatus || '') }} -> {{ caseStatusText(modal.form.toStatus) }}</div>
          </div>
          <div class="field-grid">
            <div v-if="modal.form.toStatus === 'IN_PROGRESS'" class="field field-full">
              <label>负责人</label>
              <input v-model="modal.form.owner" placeholder="填写实际负责处理的人" />
            </div>
            <div v-if="modal.form.toStatus === 'RESOLVED'" class="field field-full">
              <label>解决方案</label>
              <textarea v-model="modal.form.resolution" rows="4" placeholder="填写修复内容、验证结果和后续措施"></textarea>
            </div>
            <div class="field field-full">
              <label>审核/处理理由</label>
              <textarea v-model="modal.form.reason" rows="3" placeholder="说明为什么进行这次状态变更"></textarea>
            </div>
          </div>
          <div class="field-actions">
            <button class="btn" @click="closeModal">取消</button>
            <button class="btn primary" :disabled="modal.saving" @click="saveCaseTransition">提交状态变更</button>
          </div>
        </template>

        <template v-else-if="modal.kind === 'feedbackTransition'">
          <div class="transition-context">
            <div class="transition-target">{{ modal.form.actionLabel }}</div>
            <div class="muted">当前状态：{{ labelStatus(modal.extra.fromStatus || '') }} -> {{ labelStatus(modal.form.toStatus) }}</div>
          </div>
          <div class="field-grid">
            <div class="field field-full">
              <label>反馈分类</label>
              <input v-model="modal.form.category" placeholder="例如：库存异常 / 商品空缺 / 支付问题" />
            </div>
            <div v-if="modal.form.toStatus === 'PROMOTED'" class="field field-full">
              <label>Case ID（可选）</label>
              <input v-model="modal.form.matchedCaseId" placeholder="留空则自动创建 Case" />
            </div>
            <div class="field field-full">
              <label>处理说明</label>
              <textarea v-model="modal.form.reason" rows="3" placeholder="说明为什么执行这次反馈流转"></textarea>
            </div>
          </div>
          <div class="field-actions">
            <button class="btn" @click="closeModal">取消</button>
            <button class="btn primary" :disabled="modal.saving" @click="saveFeedbackTransition">提交反馈动作</button>
          </div>
        </template>

        <template v-else-if="modal.kind === 'json'">
          <pre class="json-box">{{ modal.form.json }}</pre>
          <div class="field-actions">
            <button class="btn primary" @click="closeModal">取消</button>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>
