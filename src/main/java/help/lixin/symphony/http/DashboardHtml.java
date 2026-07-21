package help.lixin.symphony.http;

/**
 * Dashboard HTML with embedded CSS, matching the Elixir LiveView dashboard.
 */
public class DashboardHtml {

    private static final String DASHBOARD_CSS = """
:root {
  color-scheme: light;
  --page: #f7f7f8;
  --page-soft: #fbfbfc;
  --page-deep: #ececf1;
  --card: rgba(255, 255, 255, 0.94);
  --card-muted: #f3f4f6;
  --ink: #202123;
  --muted: #6e6e80;
  --line: #ececf1;
  --line-strong: #d9d9e3;
  --accent: #10a37f;
  --accent-ink: #0f513f;
  --accent-soft: #e8faf4;
  --danger: #b42318;
  --danger-soft: #fef3f2;
  --shadow-sm: 0 1px 2px rgba(16, 24, 40, 0.05);
  --shadow-lg: 0 20px 50px rgba(15, 23, 42, 0.08);
}

* { box-sizing: border-box; }

html { background: var(--page); }

body {
  margin: 0;
  min-height: 100vh;
  background:
    radial-gradient(circle at top, rgba(16, 163, 127, 0.12) 0%, rgba(16, 163, 127, 0) 30%),
    linear-gradient(180deg, var(--page-soft) 0%, var(--page) 24%, #f3f4f6 100%);
  color: var(--ink);
  font-family: "SF Pro Text", "Helvetica Neue", "Segoe UI", sans-serif;
  line-height: 1.5;
}

a {
  color: var(--ink);
  text-decoration: none;
  transition: color 140ms ease;
}

a:hover { color: var(--accent); }

button {
  appearance: none;
  border: 1px solid var(--accent);
  background: var(--accent);
  color: white;
  border-radius: 999px;
  padding: 0.72rem 1.08rem;
  cursor: pointer;
  font: inherit;
  font-weight: 600;
  letter-spacing: -0.01em;
  box-shadow: 0 8px 20px rgba(16, 163, 127, 0.18);
  transition: transform 140ms ease, box-shadow 140ms ease, background 140ms ease, border-color 140ms ease;
}

button:hover {
  transform: translateY(-1px);
  box-shadow: 0 12px 24px rgba(16, 163, 127, 0.22);
}

button.secondary {
  background: var(--card);
  color: var(--ink);
  border-color: var(--line-strong);
  box-shadow: var(--shadow-sm);
}

button.secondary:hover { box-shadow: 0 6px 16px rgba(15, 23, 42, 0.08); }

.subtle-button {
  appearance: none;
  border: 1px solid var(--line-strong);
  background: rgba(255, 255, 255, 0.72);
  color: var(--muted);
  border-radius: 999px;
  padding: 0.34rem 0.72rem;
  cursor: pointer;
  font: inherit;
  font-size: 0.82rem;
  font-weight: 600;
  letter-spacing: 0.01em;
  box-shadow: none;
  transition: background 140ms ease, border-color 140ms ease, color 140ms ease;
}

.subtle-button:hover {
  transform: none;
  box-shadow: none;
  background: white;
  border-color: var(--muted);
  color: var(--ink);
}

pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

code, pre, .mono {
  font-family: "SFMono-Regular", "SF Mono", Consolas, "Liberation Mono", monospace;
}

.mono, .numeric {
  font-variant-numeric: tabular-nums slashed-zero;
  font-feature-settings: "tnum" 1, "zero" 1;
}

.app-shell {
  max-width: 1280px;
  margin: 0 auto;
  padding: 2rem 1rem 3.5rem;
}

.dashboard-shell {
  display: grid;
  gap: 1rem;
}

.hero-card, .section-card, .metric-card, .error-card {
  background: var(--card);
  border: 1px solid rgba(217, 217, 227, 0.82);
  box-shadow: var(--shadow-sm);
  backdrop-filter: blur(18px);
}

.hero-card {
  border-radius: 28px;
  padding: clamp(1.25rem, 3vw, 2rem);
  box-shadow: var(--shadow-lg);
}

.hero-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 1.25rem;
  align-items: start;
}

.eyebrow {
  margin: 0;
  color: var(--muted);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  font-size: 0.76rem;
  font-weight: 600;
}

.hero-title {
  margin: 0.35rem 0 0;
  font-size: clamp(2rem, 4vw, 3.3rem);
  line-height: 0.98;
  letter-spacing: -0.04em;
}

.hero-copy {
  margin: 0.75rem 0 0;
  max-width: 46rem;
  color: var(--muted);
  font-size: 1rem;
}

.status-stack {
  display: grid;
  justify-items: end;
  align-content: start;
  min-width: min(100%, 9rem);
}

.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 0.45rem;
  min-height: 2rem;
  padding: 0.35rem 0.78rem;
  border-radius: 999px;
  border: 1px solid var(--line);
  background: var(--card-muted);
  color: var(--muted);
  font-size: 0.82rem;
  font-weight: 700;
  letter-spacing: 0.01em;
}

.status-badge-dot {
  width: 0.52rem;
  height: 0.52rem;
  border-radius: 999px;
  background: currentColor;
  opacity: 0.9;
}

.status-badge-live {
  display: none;
  background: var(--accent-soft);
  border-color: rgba(16, 163, 127, 0.18);
  color: var(--accent-ink);
}

.status-badge-offline {
  background: #f5f5f7;
  border-color: var(--line-strong);
  color: var(--muted);
}

.metric-grid {
  display: grid;
  gap: 0.85rem;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
}

.metric-card {
  border-radius: 22px;
  padding: 1rem 1.05rem 1.1rem;
}

.metric-label {
  margin: 0;
  color: var(--muted);
  font-size: 0.82rem;
  font-weight: 600;
  letter-spacing: 0.01em;
}

.metric-value {
  margin: 0.35rem 0 0;
  font-size: clamp(1.6rem, 2vw, 2.1rem);
  line-height: 1.05;
  letter-spacing: -0.03em;
}

.metric-detail {
  margin: 0.45rem 0 0;
  color: var(--muted);
  font-size: 0.88rem;
}

.section-card {
  border-radius: 24px;
  padding: 1.15rem;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 1rem;
  flex-wrap: wrap;
}

.section-title {
  margin: 0;
  font-size: 1.08rem;
  line-height: 1.2;
  letter-spacing: -0.02em;
}

.section-copy {
  margin: 0.35rem 0 0;
  color: var(--muted);
  font-size: 0.94rem;
}

.table-wrap {
  overflow-x: auto;
  margin-top: 1rem;
}

.data-table {
  width: 100%;
  min-width: 720px;
  border-collapse: collapse;
}

.data-table-running {
  table-layout: fixed;
  min-width: 980px;
}

.data-table th {
  padding: 0 0.5rem 0.75rem 0;
  text-align: left;
  color: var(--muted);
  font-size: 0.78rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.data-table td {
  padding: 0.9rem 0.5rem 0.9rem 0;
  border-top: 1px solid var(--line);
  vertical-align: top;
  font-size: 0.94rem;
}

.issue-stack, .session-stack, .detail-stack, .token-stack {
  display: grid;
  gap: 0.24rem;
  min-width: 0;
}

.event-text {
  font-weight: 500;
  line-height: 1.45;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.event-meta {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.state-badge {
  display: inline-flex;
  align-items: center;
  min-height: 1.85rem;
  padding: 0.3rem 0.68rem;
  border-radius: 999px;
  border: 1px solid var(--line);
  background: var(--card-muted);
  color: var(--ink);
  font-size: 0.8rem;
  font-weight: 600;
  line-height: 1;
}

.state-badge-active {
  background: var(--accent-soft);
  border-color: rgba(16, 163, 127, 0.18);
  color: var(--accent-ink);
}

.state-badge-warning {
  background: #fff7e8;
  border-color: #f1d8a6;
  color: #8a5a00;
}

.state-badge-danger {
  background: var(--danger-soft);
  border-color: #f6d3cf;
  color: var(--danger);
}

.issue-id {
  font-weight: 600;
  letter-spacing: -0.01em;
}

.issue-id-link {
  color: inherit;
  text-decoration: underline;
  text-decoration-color: currentColor;
  text-decoration-thickness: 1px;
  text-underline-offset: 0.18em;
}

.issue-link {
  color: var(--muted);
  font-size: 0.86rem;
}

.muted { color: var(--muted); }

.code-panel {
  margin-top: 1rem;
  padding: 1rem;
  border-radius: 18px;
  background: #f5f5f7;
  border: 1px solid var(--line);
  color: #353740;
  font-size: 0.9rem;
}

.empty-state {
  margin: 1rem 0 0;
  color: var(--muted);
}

.error-card {
  border-radius: 24px;
  padding: 1.25rem;
  background: linear-gradient(180deg, #fff8f7 0%, var(--danger-soft) 100%);
  border-color: #f6d3cf;
}

.error-title {
  margin: 0;
  color: var(--danger);
  font-size: 1.15rem;
  letter-spacing: -0.02em;
}

.error-copy {
  margin: 0.45rem 0 0;
  color: var(--danger);
}

@media (max-width: 860px) {
  .app-shell { padding: 1rem 0.85rem 2rem; }
  .hero-grid { grid-template-columns: 1fr; }
  .status-stack { justify-items: start; }
  .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 560px) {
  .metric-grid { grid-template-columns: 1fr; }
  .section-card, .hero-card, .error-card {
    border-radius: 20px;
    padding: 1rem;
  }
}
""";

    public static final byte[] CSS = DASHBOARD_CSS.getBytes();

    public static String render() {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Symphony Operations Dashboard</title>
    <link rel="stylesheet" href="/dashboard.css">
    <script>
        const POLL_INTERVAL = 1000;
        let currentPayload = null;
        let now = new Date();

        async function loadPayload() {
            try {
                const resp = await fetch('/api/v1/state');
                currentPayload = await resp.json();
                now = new Date();
                render();
            } catch (e) {
                console.error('Failed to load payload:', e);
            }
        }

        function render() {
            const d = document;
            d.getElementById('generated-at').textContent = currentPayload.generated_at || '';

            if (currentPayload.error) {
                d.getElementById('error-section').style.display = 'block';
                d.getElementById('error-code').textContent = currentPayload.error.code;
                d.getElementById('error-message').textContent = currentPayload.error.message;
                d.getElementById('main-content').style.display = 'none';
                return;
            }

            d.getElementById('error-section').style.display = 'none';
            d.getElementById('main-content').style.display = 'block';

            // Counts
            const counts = currentPayload.counts || {};
            d.getElementById('running-count').textContent = counts.running || 0;
            d.getElementById('retrying-count').textContent = counts.retrying || 0;
            d.getElementById('blocked-count').textContent = counts.blocked || 0;

            // Codex totals
            const totals = currentPayload.codex_totals || {};
            d.getElementById('total-tokens').textContent = formatInt(totals.total_tokens || 0);
            d.getElementById('input-tokens').textContent = formatInt(totals.input_tokens || 0);
            d.getElementById('output-tokens').textContent = formatInt(totals.output_tokens || 0);
            d.getElementById('runtime-seconds').textContent = formatRuntime(totals.seconds_running || 0);

            // Rate limits
            const rateLimitsEl = d.getElementById('rate-limits');
            rateLimitsEl.textContent = currentPayload.rate_limits ? JSON.stringify(currentPayload.rate_limits, null, 2) : 'n/a';

            // Running sessions
            renderRunningSessions(d, currentPayload.running || []);

            // Blocked sessions
            renderBlockedSessions(d, currentPayload.blocked || []);

            // Retry queue
            renderRetryQueue(d, currentPayload.retrying || []);
        }

        function renderRunningSessions(d, running) {
            const tbody = d.getElementById('running-tbody');
            if (running.length === 0) {
                d.getElementById('running-empty').style.display = 'block';
                d.getElementById('running-table').style.display = 'none';
                return;
            }
            d.getElementById('running-empty').style.display = 'none';
            d.getElementById('running-table').style.display = 'table';

            tbody.innerHTML = running.map(entry => {
                const runtimeAndTurns = formatRuntimeAndTurns(entry.started_at, entry.turn_count);
                return `
                <tr>
                    <td>
                        <div class="issue-stack">
                            <span class="issue-id">${entry.issue_identifier || 'n/a'}</span>
                            <a class="issue-link" href="/api/v1/${entry.issue_identifier}">JSON details</a>
                        </div>
                    </td>
                    <td><span class="${stateBadgeClass(entry.state)}">${entry.state || 'n/a'}</span></td>
                    <td>
                        ${entry.session_id
                            ? `<button class="subtle-button" onclick="copyToClipboard(this, '${entry.session_id}')">Copy ID</button>`
                            : '<span class="muted">n/a</span>'}
                    </td>
                    <td class="numeric">${runtimeAndTurns}</td>
                    <td>
                        <div class="detail-stack">
                            <span class="event-text">${entry.last_event || 'n/a'}</span>
                            <span class="muted event-meta">
                                ${entry.last_event || 'n/a'}
                                ${entry.last_event_at ? ' · ' + entry.last_event_at : ''}
                            </span>
                        </div>
                    </td>
                    <td>
                        <div class="token-stack numeric">
                            <span>Total: ${formatInt(entry.tokens?.total_tokens || 0)}</span>
                            <span class="muted">In ${formatInt(entry.tokens?.input_tokens || 0)} / Out ${formatInt(entry.tokens?.output_tokens || 0)}</span>
                        </div>
                    </td>
                </tr>`;
            }).join('');
        }

        function renderBlockedSessions(d, blocked) {
            const tbody = d.getElementById('blocked-tbody');
            if (blocked.length === 0) {
                d.getElementById('blocked-empty').style.display = 'block';
                d.getElementById('blocked-table').style.display = 'none';
                return;
            }
            d.getElementById('blocked-empty').style.display = 'none';
            d.getElementById('blocked-table').style.display = 'table';

            tbody.innerHTML = blocked.map(entry => {
                return `
                <tr>
                    <td>
                        <div class="issue-stack">
                            <span class="issue-id">${entry.issue_identifier || 'n/a'}</span>
                            <a class="issue-link" href="/api/v1/${entry.issue_identifier}">JSON details</a>
                        </div>
                    </td>
                    <td><span class="${stateBadgeClass(entry.state || 'blocked')}">${entry.state || 'Blocked'}</span></td>
                    <td>
                        ${entry.session_id
                            ? `<button class="subtle-button" onclick="copyToClipboard(this, '${entry.session_id}')">Copy ID</button>`
                            : '<span class="muted">n/a</span>'}
                    </td>
                    <td class="mono">${entry.blocked_at || 'n/a'}</td>
                    <td>
                        <div class="detail-stack">
                            <span class="event-text">${entry.last_event || 'n/a'}</span>
                            <span class="muted event-meta">
                                ${entry.last_event || 'n/a'}
                                ${entry.last_event_at ? ' · ' + entry.last_event_at : ''}
                            </span>
                        </div>
                    </td>
                    <td>${entry.error || 'n/a'}</td>
                </tr>`;
            }).join('');
        }

        function renderRetryQueue(d, retrying) {
            const tbody = d.getElementById('retry-tbody');
            if (retrying.length === 0) {
                d.getElementById('retry-empty').style.display = 'block';
                d.getElementById('retry-table').style.display = 'none';
                return;
            }
            d.getElementById('retry-empty').style.display = 'none';
            d.getElementById('retry-table').style.display = 'table';

            tbody.innerHTML = retrying.map(entry => {
                return `
                <tr>
                    <td>
                        <div class="issue-stack">
                            <span class="issue-id">${entry.issue_identifier || 'n/a'}</span>
                            <a class="issue-link" href="/api/v1/${entry.issue_identifier}">JSON details</a>
                        </div>
                    </td>
                    <td>${entry.attempt || 0}</td>
                    <td class="mono">${entry.due_at || 'n/a'}</td>
                    <td>${entry.error || 'n/a'}</td>
                </tr>`;
            }).join('');
        }

        function formatInt(value) {
            if (typeof value !== 'number') return 'n/a';
            return value.toString().replace(/\\B(?=(\\d{3})+(?!\\d))/g, ',');
        }

        function formatRuntime(seconds) {
            const wholeSeconds = Math.max(Math.floor(seconds), 0);
            const mins = Math.floor(wholeSeconds / 60);
            const secs = wholeSeconds % 60;
            return `${mins}m ${secs}s`;
        }

        function formatRuntimeAndTurns(startedAt, turnCount) {
            if (!startedAt) return 'n/a';
            const start = new Date(startedAt);
            const diffSeconds = Math.floor((now - start) / 1000);
            const runtime = formatRuntime(diffSeconds);
            if (turnCount && turnCount > 0) {
                return `${runtime} / ${turnCount}`;
            }
            return runtime;
        }

        function stateBadgeClass(state) {
            const base = 'state-badge';
            const normalized = (state || '').toLowerCase();
            if (normalized.includes('progress') || normalized.includes('running') || normalized.includes('active')) {
                return base + ' state-badge-active';
            }
            if (normalized.includes('blocked') || normalized.includes('error') || normalized.includes('failed')) {
                return base + ' state-badge-danger';
            }
            if (normalized.includes('todo') || normalized.includes('queued') || normalized.includes('pending') || normalized.includes('retry')) {
                return base + ' state-badge-warning';
            }
            return base;
        }

        function copyToClipboard(button, text) {
            navigator.clipboard.writeText(text).then(() => {
                const original = button.textContent;
                button.textContent = 'Copied';
                setTimeout(() => { button.textContent = original; }, 1200);
            });
        }

        // Initial load and periodic refresh
        loadPayload();
        setInterval(loadPayload, POLL_INTERVAL);
    </script>
</head>
<body>
    <div class="app-shell">
        <section class="dashboard-shell">
            <header class="hero-card">
                <div class="hero-grid">
                    <div>
                        <p class="eyebrow">Symphony Observability</p>
                        <h1 class="hero-title">Operations Dashboard</h1>
                        <p class="hero-copy">Current state, retry pressure, token usage, and orchestration health for the active Symphony runtime.</p>
                        <p class="hero-copy" id="generated-at"></p>
                    </div>
                    <div class="status-stack">
                        <span class="status-badge status-badge-live">
                            <span class="status-badge-dot"></span>
                            Live
                        </span>
                        <span class="status-badge status-badge-offline">
                            <span class="status-badge-dot"></span>
                            Offline
                        </span>
                    </div>
                </div>
            </header>

            <section id="error-section" class="error-card" style="display: none;">
                <h2 class="error-title">Snapshot unavailable</h2>
                <p class="error-copy"><strong id="error-code"></strong>: <span id="error-message"></span></p>
            </section>

            <div id="main-content">
                <section class="metric-grid">
                    <article class="metric-card">
                        <p class="metric-label">Running</p>
                        <p class="metric-value numeric" id="running-count">0</p>
                        <p class="metric-detail">Active issue sessions in the current runtime.</p>
                    </article>

                    <article class="metric-card">
                        <p class="metric-label">Retrying</p>
                        <p class="metric-value numeric" id="retrying-count">0</p>
                        <p class="metric-detail">Issues waiting for the next retry window.</p>
                    </article>

                    <article class="metric-card">
                        <p class="metric-label">Blocked</p>
                        <p class="metric-value numeric" id="blocked-count">0</p>
                        <p class="metric-detail">Issues paused for operator input or approval.</p>
                    </article>

                    <article class="metric-card">
                        <p class="metric-label">Total tokens</p>
                        <p class="metric-value numeric" id="total-tokens">0</p>
                        <p class="metric-detail numeric">
                            In <span id="input-tokens">0</span> / Out <span id="output-tokens">0</span>
                        </p>
                    </article>

                    <article class="metric-card">
                        <p class="metric-label">Runtime</p>
                        <p class="metric-value numeric" id="runtime-seconds">0m 0s</p>
                        <p class="metric-detail">Total Codex runtime across completed and active sessions.</p>
                    </article>
                </section>

                <section class="section-card">
                    <div class="section-header">
                        <div>
                            <h2 class="section-title">Rate limits</h2>
                            <p class="section-copy">Latest upstream rate-limit snapshot, when available.</p>
                        </div>
                    </div>
                    <pre class="code-panel" id="rate-limits">n/a</pre>
                </section>

                <section class="section-card">
                    <div class="section-header">
                        <div>
                            <h2 class="section-title">Running sessions</h2>
                            <p class="section-copy">Active issues, last known agent activity, and token usage.</p>
                        </div>
                    </div>
                    <p class="empty-state" id="running-empty">No active sessions.</p>
                    <div class="table-wrap">
                        <table class="data-table data-table-running" id="running-table" style="display: none;">
                            <colgroup>
                                <col style="width: 12rem;">
                                <col style="width: 8rem;">
                                <col style="width: 7.5rem;">
                                <col style="width: 8.5rem;">
                                <col>
                                <col style="width: 10rem;">
                            </colgroup>
                            <thead>
                                <tr>
                                    <th>Issue</th>
                                    <th>State</th>
                                    <th>Session</th>
                                    <th>Runtime / turns</th>
                                    <th>Codex update</th>
                                    <th>Tokens</th>
                                </tr>
                            </thead>
                            <tbody id="running-tbody"></tbody>
                        </table>
                    </div>
                </section>

                <section class="section-card">
                    <div class="section-header">
                        <div>
                            <h2 class="section-title">Blocked sessions</h2>
                            <p class="section-copy">Issues paused because Codex requested operator input or approval.</p>
                        </div>
                    </div>
                    <p class="empty-state" id="blocked-empty">No blocked sessions.</p>
                    <div class="table-wrap">
                        <table class="data-table" id="blocked-table" style="min-width: 760px; display: none;">
                            <thead>
                                <tr>
                                    <th>Issue</th>
                                    <th>State</th>
                                    <th>Session</th>
                                    <th>Blocked at</th>
                                    <th>Last update</th>
                                    <th>Error</th>
                                </tr>
                            </thead>
                            <tbody id="blocked-tbody"></tbody>
                        </table>
                    </div>
                </section>

                <section class="section-card">
                    <div class="section-header">
                        <div>
                            <h2 class="section-title">Retry queue</h2>
                            <p class="section-copy">Issues waiting for the next retry window.</p>
                        </div>
                    </div>
                    <p class="empty-state" id="retry-empty">No issues are currently backing off.</p>
                    <div class="table-wrap">
                        <table class="data-table" id="retry-table" style="min-width: 680px; display: none;">
                            <thead>
                                <tr>
                                    <th>Issue</th>
                                    <th>Attempt</th>
                                    <th>Due at</th>
                                    <th>Error</th>
                                </tr>
                            </thead>
                            <tbody id="retry-tbody"></tbody>
                        </table>
                    </div>
                </section>
            </div>
        </section>
    </div>
</body>
</html>
""";
    }
}
