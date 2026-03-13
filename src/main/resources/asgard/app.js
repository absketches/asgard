'use strict';

const POLL_MS    = 3000;
const BUCKET_CAP = 2000;

// ── Store ─────────────────────────────────────────────────────────────────────
const store = {
    lastSeen: null,
    buckets: {
        NORMAL:            [],
        TRACKING:          [],
        SUSPICIOUS:        [],
        EXFILTRATION_RISK: []
    },
    userBlocks: []
};

let activeTab = 'ALL';

// ── DOM ───────────────────────────────────────────────────────────────────────
const statusDot    = document.getElementById('statusDot');
const statusLabel  = document.getElementById('statusLabel');
const statPill     = document.getElementById('statPill');
const tableBody    = document.getElementById('tableBody');
const blocksBody   = document.getElementById('blocksBody');
const trafficView  = document.getElementById('trafficView');
const blocksView   = document.getElementById('blocksView');
const modal        = document.getElementById('modal');
const modalHost    = document.getElementById('modalHost');
const modalHostInline = document.getElementById('modalHostInline');
const modalNote    = document.getElementById('modalNote');
const clockEl      = document.getElementById('clock');

// ── Clock ─────────────────────────────────────────────────────────────────────
function updateClock() {
    const now = new Date();
    clockEl.textContent = now.toLocaleTimeString('en-GB', { hour12: false });
}
updateClock();
setInterval(updateClock, 1000);

// ── Store helpers ─────────────────────────────────────────────────────────────
function addRows(rows) {
    if (!rows.length) return;
    rows.forEach(r => {
        const bucket = store.buckets[r.classification];
        if (!bucket) return;
        bucket.unshift(r);
        if (bucket.length > BUCKET_CAP) bucket.length = BUCKET_CAP;
    });
    if (!store.lastSeen || rows[0].timestamp > store.lastSeen) {
        store.lastSeen = rows[0].timestamp;
    }
    updateCounts();
    renderActiveTabIncremental(rows);
}

function visibleRows() {
    if (activeTab === 'ALL') {
        return Object.values(store.buckets)
            .flat()
            .sort((a, b) => b.timestamp.localeCompare(a.timestamp));
    }
    return store.buckets[activeTab] || [];
}

// ── Counts ────────────────────────────────────────────────────────────────────
function updateCounts() {
    let total = 0;
    Object.entries(store.buckets).forEach(([cls, rows]) => {
        const el = document.getElementById('count-' + cls);
        if (el) el.textContent = fmt(rows.length);
        total += rows.length;
    });
    const allEl = document.getElementById('count-ALL');
    if (allEl) allEl.textContent = fmt(total);
    const blEl = document.getElementById('count-USER_BLOCKS');
    if (blEl) blEl.textContent = fmt(store.userBlocks.length);
}

// ── Tabs ──────────────────────────────────────────────────────────────────────
document.querySelectorAll('.tab').forEach(tab => {
    tab.addEventListener('click', () => {
        document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
        tab.classList.add('active');
        activeTab = tab.dataset.cls;
        const clearBtn = document.getElementById('clearBtn');
        clearBtn.style.display = activeTab === 'USER_BLOCKS' ? 'none' : '';
        if (activeTab === 'USER_BLOCKS') {
            trafficView.classList.add('hidden');
            blocksView.classList.remove('hidden');
            renderBlocksTable();
        } else {
            blocksView.classList.add('hidden');
            trafficView.classList.remove('hidden');
            renderFull();
        }
    });
});

// ── Clear button ──────────────────────────────────────────────────────────────
document.getElementById('clearBtn').addEventListener('click', async () => {
    if (activeTab === 'USER_BLOCKS') return; // clearing blocks not done here
    const label = activeTab === 'ALL' ? 'ALL records' : activeTab + ' records';
    if (!confirm(`Clear ${label} from the database?`)) return;
    const cls = activeTab === 'ALL' ? 'ALL' : activeTab;
    await fetch(`/asgard/api/requests?cls=${cls}`, { method: 'DELETE' });
    if (activeTab === 'ALL') {
        Object.keys(store.buckets).forEach(k => { store.buckets[k] = []; });
    } else {
        store.buckets[activeTab] = [];
    }
    updateCounts();
    renderFull();
});


function renderFull() {
    const rows = visibleRows();
    if (!rows.length) {
        tableBody.innerHTML = `
            <tr class="empty-row">
                <td colspan="6">
                    <div class="empty-state">
                        <div class="empty-icon">◈</div>
                        <div>No traffic yet</div>
                        <div class="empty-hint">Set your proxy to this host:8888</div>
                    </div>
                </td>
            </tr>`;
        return;
    }
    tableBody.innerHTML = '';
    const frag = document.createDocumentFragment();
    rows.forEach(r => frag.appendChild(buildTrafficRow(r, false)));
    tableBody.appendChild(frag);
}

function renderActiveTabIncremental(newRows) {
    if (activeTab === 'USER_BLOCKS') return;
    const relevant = activeTab === 'ALL'
        ? newRows
        : newRows.filter(r => r.classification === activeTab);
    if (!relevant.length) return;
    const empty = tableBody.querySelector('.empty-row');
    if (empty) empty.remove();
    const frag = document.createDocumentFragment();
    relevant.forEach(r => frag.appendChild(buildTrafficRow(r, true)));
    tableBody.insertBefore(frag, tableBody.firstChild);
    while (tableBody.children.length > BUCKET_CAP) tableBody.removeChild(tableBody.lastChild);
}

function buildTrafficRow(r, isNew) {
    const tr = document.createElement('tr');
    tr.className = 'row-' + r.classification + (isNew ? ' new-row' : '');

    const time  = new Date(r.timestamp).toLocaleTimeString('en-GB', { hour12: false });
    const size  = r.dataSize > 0 ? fmtBytes(r.dataSize) : '—';
    const label = r.classification.replace('_', ' ');
    const isBlocked = store.userBlocks.some(b => hostMatchesBlock(r.destination, b.host));

    tr.innerHTML =
        `<td class="col-time">${esc(time)}</td>` +
        `<td class="col-ip">${esc(r.sourceIp || '—')}</td>` +
        `<td class="col-dest" title="${esc(r.destination)}">${esc(r.destination)}${isBlocked ? ' <span style="color:var(--red);font-size:9px">⊘</span>' : ''}</td>` +
        `<td class="col-method">${esc(r.method)}</td>` +
        `<td class="col-size">${size}</td>` +
        `<td class="col-cls"><span class="badge badge-${r.classification}">${label}</span></td>`;

    tr.addEventListener('click', () => openBlockModal(r.destination));
    return tr;
}

// ── Blocks table ──────────────────────────────────────────────────────────────
function renderBlocksTable() {
    if (!store.userBlocks.length) {
        blocksBody.innerHTML = `
            <tr class="empty-row">
                <td colspan="4">
                    <div class="empty-state">
                        <div class="empty-icon">◈</div>
                        <div>No blocked domains</div>
                    </div>
                </td>
            </tr>`;
        return;
    }
    blocksBody.innerHTML = '';
    const frag = document.createDocumentFragment();
    store.userBlocks.forEach(b => frag.appendChild(buildBlockRow(b)));
    blocksBody.appendChild(frag);
}

function buildBlockRow(b) {
    const tr = document.createElement('tr');
    const time = new Date(b.createdAt).toLocaleString('en-GB');
    tr.innerHTML =
        `<td class="col-dest"><strong>${esc(b.host)}</strong><span class="subdomain-hint">+subdomains</span></td>` +
        `<td class="col-time">${esc(time)}</td>` +
        `<td class="col-note">${esc(b.note || '—')}</td>` +
        `<td class="col-action"><button class="btn-unblock" data-host="${esc(b.host)}">UNBLOCK</button></td>`;
    tr.querySelector('.btn-unblock').addEventListener('click', e => {
        e.stopPropagation();
        unblock(b.host);
    });
    return tr;
}

// ── Block modal ───────────────────────────────────────────────────────────────
function openBlockModal(destination) {
    if (store.userBlocks.some(b => hostMatchesBlock(destination, b.host))) {
        if (confirm(`${destination} is already blocked. Unblock it?`)) unblock(destination);
        return;
    }
    modalHost.textContent = destination;
    modalHostInline.textContent = destination;
    modalNote.value = '';
    modal.classList.remove('hidden');
    setTimeout(() => modalNote.focus(), 50);
}

document.getElementById('modalCancel').addEventListener('click', closeModal);
modal.addEventListener('click', e => { if (e.target === modal) closeModal(); });
document.addEventListener('keydown', e => { if (e.key === 'Escape') closeModal(); });

document.getElementById('modalConfirm').addEventListener('click', () => {
    const host = modalHost.textContent.trim();
    const note = modalNote.value.trim();
    closeModal();
    fetch('/asgard/api/block', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ host, note })
    })
    .then(r => r.json())
    .then(() => loadBlocks())
    .catch(err => console.error('Block failed:', err));
});

function unblock(host) {
    fetch('/asgard/api/block?host=' + encodeURIComponent(host), { method: 'DELETE' })
        .then(r => r.json())
        .then(() => loadBlocks())
        .catch(err => console.error('Unblock failed:', err));
}

function closeModal() {
    modal.classList.add('hidden');
    modalNote.value = '';
}

// ── Network ───────────────────────────────────────────────────────────────────
function setStatus(ok, msg) {
    statPill.classList.toggle('error', !ok);
    statusLabel.textContent = (msg || (ok ? 'LIVE' : 'OFFLINE')).toUpperCase();
}

function loadBlocks() {
    fetch('/asgard/api/blocks')
        .then(r => r.json())
        .then(blocks => {
            store.userBlocks = blocks;
            updateCounts();
            if (activeTab === 'USER_BLOCKS') renderBlocksTable();
        })
        .catch(() => {});
}

// ── Boot ──────────────────────────────────────────────────────────────────────
Promise.all([
    fetch('/asgard/api/init').then(r => r.json()),
    fetch('/asgard/api/blocks').then(r => r.json())
])
.then(([rows, blocks]) => {
    store.userBlocks = blocks;
    setStatus(true, 'LIVE');
    addRows(rows);
    if (!rows.length) renderFull();
})
.catch(() => {
    setStatus(false, 'OFFLINE');
    tableBody.innerHTML = `
        <tr class="empty-row">
            <td colspan="6">
                <div class="empty-state">
                    <div class="empty-icon">◈</div>
                    <div>Could not reach server</div>
                </div>
            </td>
        </tr>`;
});

// Poll
setInterval(() => {
    const since = store.lastSeen || new Date(0).toISOString();
    fetch('/asgard/api/poll?since=' + encodeURIComponent(since))
        .then(r => r.json())
        .then(rows => {
            setStatus(true, 'LIVE');
            if (rows.length) addRows(rows);
        })
        .catch(() => setStatus(false, 'OFFLINE'));
}, POLL_MS);

// ── Utils ─────────────────────────────────────────────────────────────────────
function hostMatchesBlock(host, blockHost) {
    if (!host || !blockHost) return false;
    const h = host.toLowerCase(), b = blockHost.toLowerCase();
    return h === b || h.endsWith('.' + b);
}

function fmtBytes(b) {
    if (b < 1024)      return b + ' B';
    if (b < 1_048_576) return (b / 1024).toFixed(1) + ' KB';
    return (b / 1_048_576).toFixed(1) + ' MB';
}

function fmt(n) {
    return n >= 1000 ? (n / 1000).toFixed(1) + 'k' : String(n);
}

function esc(str) {
    const d = document.createElement('div');
    d.textContent = str || '';
    return d.innerHTML;
}
