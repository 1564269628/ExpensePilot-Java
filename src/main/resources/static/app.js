/* ExpensePilot local workbench. Zero frontend dependencies, served by Spring Boot. */
(function () {
  'use strict';

  const apiBase = '/api/v1';
  const state = { tab: 'overview', tasks: [], documents: [], trips: [],
    policies: [], submissions: [], notifications: [], overview: null,
    selectedTaskId: null, selectedDetails: null, loadingTask: false };
  const names = {
    overview: '工作台概览', tasks: 'Agent 任务', documents: '费用材料',
    trips: '差旅行程', policies: '费用政策', results: '报销与通知'
  };
  const $ = (id) => document.getElementById(id);
  const safe = (value) => String(value == null ? '' : value).replace(/[&<>"']/g, (c) => {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
  });
  const price = (n) => {
    const num = Number(n || 0);
    return Number.isFinite(num) ? '¥ ' + num.toLocaleString('zh-CN', {
      minimumFractionDigits: 2, maximumFractionDigits: 2 }) : '—';
  };
  const when = (text) => text ? safe(String(text).replace('T', ' ').slice(0, 19)) : '—';
  const idempotency = () => {
    if (globalThis.crypto && crypto.randomUUID) return crypto.randomUUID();
    return Date.now() + '-' + Math.random().toString(36).slice(2) + '-expense';
  };

  async function api(path, options) {
    const res = await fetch(apiBase + path, Object.assign({ cache: 'no-store' }, options || {}));
    const body = await res.text();
    let parsed;
    try { parsed = body ? JSON.parse(body) : {}; }
    catch (_) { parsed = { message: body.slice(0, 300) }; }
    if (!res.ok) {
      const msg = parsed.message || parsed.detail || ('HTTP ' + res.status);
      throw new Error(msg);
    }
    return parsed;
  }

  function post(path, data) {
    return api(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data || {})
    });
  }

  let flashTimer;
  function notify(message, error) {
    const el = $('flash');
    el.textContent = message;
    el.className = error ? 'error' : '';
    el.hidden = false;
    clearTimeout(flashTimer);
    flashTimer = setTimeout(() => { el.hidden = true; }, 6500);
  }

  async function action(button, fn) {
    if (button) button.disabled = true;
    try {
      await fn();
    } catch (err) {
      notify(err.message || String(err), true);
    } finally {
      if (button) button.disabled = false;
    }
  }

  function showTab(tab) {
    if (!names[tab]) return;
    state.tab = tab;
    document.querySelectorAll('[data-tab]').forEach((el) =>
      el.classList.toggle('active', el.dataset.tab === tab));
    document.querySelectorAll('[data-panel]').forEach((el) =>
      { el.hidden = el.dataset.panel !== tab; });
    $('pageTitle').textContent = names[tab];
    window.scrollTo({ top: 0, behavior: 'smooth' });
    refresh();
  }

  function table(columns, items) {
    if (!items || !items.length) return '<div class="empty">暂无数据</div>';
    let head = columns.map((col) => '<th>' + safe(col[0]) + '</th>').join('');
    let body = items.map((row) => '<tr>' + columns.map((col) =>
      '<td>' + (col[1](row) || '—') + '</td>').join('') + '</tr>').join('');
    return '<table><thead><tr>' + head + '</tr></thead><tbody>' + body + '</tbody></table>';
  }
  function badge(value) {
    const s = String(value || 'UNKNOWN');
    return '<span class="status ' + safe(s) + '">' + safe(s) + '</span>';
  }

  async function refresh() {
    $('lastSync').textContent = '正在同步…';
    const loaders = [
      async () => { state.overview = await api('/local/overview'); drawOverview(); },
      async () => { state.tasks = await api('/local/tasks'); drawTasks(); },
      async () => { state.documents = await api('/local/documents'); drawDocuments(); },
      async () => { state.trips = await api('/local/trips'); drawTrips(); },
      async () => { state.policies = await api('/local/policies'); drawPolicies(); },
      async () => {
        const pair = await Promise.all([
          api('/local/submissions'), api('/local/notifications')]);
        state.submissions = pair[0]; state.notifications = pair[1]; drawResults();
      }
    ];
    const results = await Promise.allSettled(loaders.map((load) => load()));
    const firstFailure = results.find((r) => r.status === 'rejected');
    $('lastSync').textContent = firstFailure ? '部分接口不可用' : '刚刚已同步';
    if (firstFailure) notify(firstFailure.reason.message || String(firstFailure.reason), true);
    if (state.selectedTaskId) await loadTask(false);
  }

  function drawOverview() {
    const o = state.overview || {};
    const taskCount = (o.statusCounts || []).reduce(
      (n, r) => n + Number(r.total || 0), 0);
    const cells = [
      ['Agent 任务', taskCount, '累计任务'],
      ['费用材料', o.documents || 0, '本地录入'],
      ['已提交报销', o.submissions || 0, '幂等记录'],
      ['已处理通知', o.notifications || 0, 'Outbox / RocketMQ']
    ];
    $('metrics').innerHTML = cells.map((m) =>
      '<div class="metric"><small>' + safe(m[0]) + '</small><strong>'
      + safe(m[1]) + '</strong><span>' + safe(m[2]) + '</span></div>').join('');
  }

  function drawTasks() {
    const list = $('taskList');
    if (!state.tasks.length) {
      list.innerHTML = '<div class="empty">尚无任务，提交自然语言需求后即可查看</div>';
      return;
    }
    list.innerHTML = state.tasks.map((t) =>
      '<button type="button" class="task-item '
      + (Number(t.taskId) === state.selectedTaskId ? 'selected' : '')
      + '" data-task-id="' + safe(t.taskId) + '"><span><strong>任务 #'
      + safe(t.taskId) + '</strong><small>' + when(t.updatedAt)
      + ' · ' + safe(t.currentNode || 'waiting') + '</small></span>'
      + badge(t.status) + '</button>').join('');
    list.querySelectorAll('[data-task-id]').forEach((button) =>
      button.addEventListener('click', () =>
        { state.selectedTaskId = Number(button.dataset.taskId);
          loadTask(true); drawTasks(); }));
  }

  async function loadTask(scroll) {
    if (!state.selectedTaskId || state.loadingTask) return;
    state.loadingTask = true;
    try {
      const data = await api('/local/tasks/' + state.selectedTaskId + '/details');
      state.selectedDetails = data;
      drawDetails();
      if (scroll) $('taskDetail').scrollIntoView({ behavior: 'smooth', block: 'start' });
    } catch (error) { notify(error.message, true); }
    finally { state.loadingTask = false; }
  }

  function drawDetails() {
    const d = state.selectedDetails;
    if (!d || !d.task) return;
    const t = d.task;
    $('taskDetail').hidden = false;
    $('selectedTaskId').textContent = '#' + t.taskId;
    $('selectedTaskStatus').outerHTML = '<span id="selectedTaskStatus" class="status '
      + safe(t.status) + '">' + safe(t.status) + '</span>';
    $('taskSummary').innerHTML = '<div class="detail-summary">'
      + '<div><small>当前节点</small><b>' + safe(t.currentNode || '—') + '</b></div>'
      + '<div><small>目标 / Agent 规划摘要</small><b>' + safe(t.goal || t.planSummary || '等待 Groq 规划') + '</b></div>'
      + '<div><small>失败重试 / 最后错误</small><b>' + safe(t.lastError || ('重试次数：' + t.retryCount)) + '</b></div></div>';

    const steps = d.steps || [];
    $('taskSteps').innerHTML = steps.length ? steps.map((s) =>
      '<div class="step"><strong>' + safe(s.stepType) + '</strong><small>'
      + safe(s.stepKey) + '</small>' + badge(s.status) + '</div>').join('')
      : '<div class="empty">等待 Planner 生成 DAG</div>';

    const approvals = (d.approvals || []).map((a) =>
      '<div class="audit-record"><strong>' + safe(a.operationType)
      + '</strong>' + badge(a.status) + '<span>' + safe(a.approver || '等待审批人')
      + '</span></div>').join('');
    const tools = (d.toolExecutions || []).map((a) =>
      '<div class="audit-record"><strong>' + safe(a.toolName) + '</strong>'
      + badge(a.status) + '<span>' + safe(a.businessNo || '') + '</span></div>').join('');
    $('taskAudit').innerHTML = approvals + tools || '<div class="empty">暂无审批或副作用记录</div>';
    drawTaskActions(t);
  }

  function drawTaskActions(task) {
    const status = task.status;
    const parts = [];
    if (status === 'WAITING_APPROVAL') {
      const kind = task.currentNode === 'humanApproval'
        ? 'POLICY_EXCEPTION' : 'SUBMIT_REPORT';
      parts.push('<span class="muted">待审批：' + safe(kind)
        + '</span><button type="button" class="btn primary" data-decision="approve" data-op="'
        + kind + '">同意审批</button>'
        + '<button type="button" class="btn danger" data-decision="reject" data-op="'
        + kind + '">拒绝审批</button>');
    } else if (status === 'WAITING_INPUT') {
      parts.push('<div class="form" style="width:100%"><p class="muted">补充确定的差旅范围，然后恢复工作流。</p>'
        + '<div class="fields"><label>城市<input id="clarifyCity" value="上海"></label>'
        + '<label>开始日期<input id="clarifyStart" type="date" value="2026-10-01"></label>'
        + '<label>结束日期<input id="clarifyEnd" type="date" value="2026-10-03"></label></div>'
        + '<button type="button" class="btn primary" id="submitClarification">提交澄清信息</button></div>');
    } else if (status === 'WAITING_MATERIAL') {
      const checks = state.documents.map((doc) =>
        '<label style="display:block;font-size:12px;margin:8px 0">'
        + '<input type="checkbox" name="materialRef" value="' + safe(doc.documentRef)
        + '"> ' + safe(doc.fileName) + ' · ' + price(doc.amount) + '</label>').join('');
      parts.push('<div style="width:100%"><p class="muted">若缺少材料，请先到「费用材料」上传。还需要确保当前工作区存在对应差旅行程。</p>'
        + checks + '<button type="button" class="btn primary" id="submitSupplement">提交补件并继续</button></div>');
    } else if (['RETRYING', 'UNKNOWN', 'CREATED'].includes(status)) {
      parts.push('<button type="button" class="btn outline" id="resumeTask">从 Checkpoint 恢复</button>');
    } else if (status === 'SUCCEEDED') {
      parts.push('<span class="status SUCCEEDED">报销已提交，Outbox 通知会异步处理</span>');
    } else if (status === 'REJECTED') {
      parts.push('<span class="status REJECTED">任务已被审批拒绝</span>');
    }
    $('taskActions').innerHTML = parts.join('');
    $('taskActions').querySelectorAll('[data-decision]').forEach((b) =>
      b.addEventListener('click', () => action(b, async () => {
        const op = encodeURIComponent(b.dataset.op);
        const decision = b.dataset.decision;
        await post('/expense-tasks/' + state.selectedTaskId
          + '/approval/' + decision + '?operationType=' + op + '&comment=local-workspace');
        notify('审批决定已提交'); await loadTask(false);
      })));
    const clarify = $('submitClarification');
    if (clarify) clarify.addEventListener('click', () => action(clarify, async () => {
      await post('/expense-tasks/' + state.selectedTaskId + '/clarification', {
        city: $('clarifyCity').value,
        startDate: $('clarifyStart').value,
        endDate: $('clarifyEnd').value
      });
      notify('已提交澄清信息'); await loadTask(false);
    }));
    const supplement = $('submitSupplement');
    if (supplement) supplement.addEventListener('click', () => action(supplement, async () => {
      const refs = Array.from(document.querySelectorAll('input[name="materialRef"]:checked'))
        .map((c) => c.value);
      if (!refs.length) throw new Error('请先勾选至少一项已录入材料');
      await post('/expense-tasks/' + state.selectedTaskId + '/supplement',
        { materialRefs: refs });
      notify('补充材料已提交'); await loadTask(false);
    }));
    const resume = $('resumeTask');
    if (resume) resume.addEventListener('click', () => action(resume, async () => {
      await post('/expense-tasks/' + state.selectedTaskId + '/resume');
      notify('恢复请求已提交'); await loadTask(false);
    }));
  }

  function drawDocuments() {
    $('documentList').innerHTML = table([
      ['名称', (d) => '<b>' + safe(d.fileName) + '</b>'
        + (d.sample ? ' <span class="tag">DEMO</span>' : '')],
      ['来源', (d) => safe(d.sourceType)],
      ['类别', (d) => safe(d.category)],
      ['城市 / 日期', (d) => safe(d.city) + ' · ' + when(d.issuedOn)],
      ['金额', (d) => '<b>' + price(d.amount) + '</b>'],
      ['引用', (d) => '<code>' + safe(d.documentRef) + '</code>']
    ], state.documents);
  }

  function drawTrips() {
    $('tripList').innerHTML = table([
      ['城市', (t) => '<b>' + safe(t.city) + '</b>' + (t.sample ? ' <span class="tag">DEMO</span>' : '')],
      ['起止日期', (t) => when(t.startDate) + ' — ' + when(t.endDate)],
      ['用途', (t) => safe(t.purpose)]
    ], state.trips);
  }

  function drawPolicies() {
    $('policyList').innerHTML = table([
      ['城市', (p) => safe(p.city)],
      ['类别', (p) => '<b>' + safe(p.category) + '</b>'],
      ['上限', (p) => price(p.maxAmount)],
      ['说明', (p) => safe(p.description)]
    ], state.policies);
  }

  function drawResults() {
    $('submissionList').innerHTML = table([
      ['本地业务号', (s) => '<b>' + safe(s.businessNo) + '</b>'],
      ['提交时间', (s) => when(s.submittedAt)],
      ['来源', () => '<span class="tag">LOCAL DB</span>']
    ], state.submissions);
    $('notificationList').innerHTML = table([
      ['报销号', (s) => safe(s.businessNo)],
      ['事件 ID', (s) => '<code>' + safe(s.eventId) + '</code>'],
      ['记录时间', (s) => when(s.sentAt)]
    ], state.notifications);
  }

  async function setup() {
    document.querySelectorAll('[data-tab]').forEach((b) =>
      b.addEventListener('click', () => showTab(b.dataset.tab)));
    document.querySelectorAll('[data-refresh]').forEach((b) =>
      b.addEventListener('click', () => action(b, refresh)));
    $('heroCreate').addEventListener('click', () => showTab('tasks'));
    $('seedDemo').addEventListener('click', (e) => action(e.currentTarget, async () => {
      const seed = await post('/local/demo-seed');
      $('requestText').value = seed.suggestedRequest;
      notify('明确标记为 DEMO 的测试材料和行程已加载');
      await refresh(); showTab('tasks');
    }));
    $('testGroq').addEventListener('click', (e) => action(e.currentTarget, async () => {
      $('groqResult').textContent = '正在调用 Groq 真实 API…';
      try {
        const data = await post('/local/groq-check');
        $('groqResult').textContent = data.response + '\n\n' + data.provider + ' / ' + data.model;
        notify('真实 Groq 模型调用已返回');
      } catch (error) {
        $('groqResult').textContent = '连接失败：' + error.message;
        throw error;
      }
    }));
    $('taskForm').addEventListener('submit', (e) => {
      e.preventDefault(); const btn = e.target.querySelector('[type="submit"]');
      action(btn, async () => {
        const text = $('requestText').value.trim();
        if (!text) throw new Error('请输入报销需求');
        const result = await api('/expense-tasks', {
          method: 'POST', headers: {
            'Content-Type': 'application/json', 'Idempotency-Key': idempotency()
          }, body: JSON.stringify({ requestText: text })
        });
        state.selectedTaskId = Number(result.taskId);
        notify('Agent 任务已创建，正在异步规划');
        await refresh(); await loadTask(true);
      });
    });
    $('uploadForm').addEventListener('submit', (e) => {
      e.preventDefault(); const form = e.target, btn = form.querySelector('[type="submit"]');
      action(btn, async () => {
        const result = await api('/local/documents/upload', {
          method: 'POST', body: new FormData(form)
        });
        notify(result.message || '文件已保存'); form.reset(); await refresh();
      });
    });
    $('documentForm').addEventListener('submit', (e) => {
      e.preventDefault(); const form = e.target, btn = form.querySelector('[type="submit"]');
      action(btn, async () => {
        await post('/local/documents', Object.fromEntries(new FormData(form)));
        notify('材料引用已录入'); form.reset(); await refresh();
      });
    });
    $('tripForm').addEventListener('submit', (e) => {
      e.preventDefault(); const form = e.target, btn = form.querySelector('[type="submit"]');
      action(btn, async () => {
        await post('/local/trips', Object.fromEntries(new FormData(form)));
        notify('差旅行程已保存'); form.reset(); await refresh();
      });
    });
    $('policyForm').addEventListener('submit', (e) => {
      e.preventDefault(); const form = e.target, btn = form.querySelector('[type="submit"]');
      action(btn, async () => {
        await post('/local/policies', Object.fromEntries(new FormData(form)));
        notify('本地费用政策已更新'); await refresh();
      });
    });
    document.querySelectorAll('input[type="date"]').forEach((field) => {
      if (!field.value) field.value = new Date().toLocaleDateString('en-CA');
    });
    await refresh();
    setInterval(async () => {
      if (state.tab === 'tasks' && state.selectedTaskId) {
        await loadTask(false);
        try { state.tasks = await api('/local/tasks'); drawTasks(); } catch (_) {}
      }
    }, 2500);
  }

  setup().catch((error) => notify(error.message, true));
})();