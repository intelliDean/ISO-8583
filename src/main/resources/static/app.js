let fieldCatalog = {};

document.addEventListener('DOMContentLoaded', () => {
    fetchSpecCatalog();
    initBitmapGrid();
    onMtiChange();
    loadPreset('financial');
});

function switchTab(tabId) {
    document.querySelectorAll('.tab-btn').forEach(btn => btn.classList.remove('active'));
    document.querySelectorAll('.tab-content').forEach(content => content.classList.remove('active'));

    const activeBtn = Array.from(document.querySelectorAll('.tab-btn')).find(btn => btn.getAttribute('onclick').includes(tabId));
    if (activeBtn) activeBtn.classList.add('active');

    const targetTab = document.getElementById(`tab-${tabId}`);
    if (targetTab) targetTab.classList.add('active');
}

async function fetchSpecCatalog() {
    try {
        const res = await fetch('/api/iso/spec');
        fieldCatalog = await res.json();
    } catch (e) {
        console.error('Failed to load ISO field catalog', e);
    }
}

function loadPreset(type) {
    const rawInput = document.getElementById('rawInput');
    const hasHeader = document.getElementById('hasHeader');
    hasHeader.checked = true;

    if (type === 'financial') {
        // 0200 Financial Request: DE 2 PAN (LLVAR), DE 3 Processing Code (FIXED 6), DE 4 Amount (FIXED 12),
        // DE 11 STAN (FIXED 6), DE 41 Terminal ID (FIXED 8), DE 42 Merchant ID (FIXED 15), DE 49 Currency (FIXED 3)
        // Bitmap: 7020000000C08000
        rawInput.value =
            "600000000002007020000000C0800016453201558899123400000000000000255000012 3TERM0001MERCHANT1234567840".replace(
                /\s/g, '');
    } else if (type === 'echo') {
        hasHeader.checked = false;
        // 0800 Network Management: DE 11 STAN (FIXED 6), DE 70 NMIC (FIXED 3)
        // Secondary Bitmap present (Bit 1 set), DE 70 is in secondary (bit 70)
        rawInput.value = "080080200000000000000400000000000000000999301";
    } else if (type === 'reversal') {
        // 0400 Reversal: same fields as financial request
        rawInput.value =
            "600000000004007020000000C0800016453201558899123400000000000000255000012 3TERM0001MERCHANT1234567840"
            .replace(/\s/g, '');
    }
    unpackPayload();
}

async function unpackPayload() {
    const payload = document.getElementById('rawInput').value.trim();
    const hasHeader = document.getElementById('hasHeader').checked;

    if (!payload) return;

    try {
        const res = await fetch('/api/iso/unpack', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ payload, hasHeader })
        });

        if (!res.ok) throw new Error('Unpack failed');

        const data = await res.json();
        renderInspectionResult(data);
        highlightBitmapGrid(data.activeFields || []);
    } catch (e) {
        alert('Error unpacking ISO message: ' + e.message);
    }
}

function renderInspectionResult(data) {
    document.getElementById('inspectionResult').classList.remove('hidden');

    document.getElementById('resHeader').textContent = data.header || 'None';
    document.getElementById('resMti').textContent = data.mti || '-';
    document.getElementById('resMtiDesc').textContent = data.mtiDescription || '-';
    document.getElementById('resPrimaryBitmap').textContent = data.primaryBitmapHex || '-';
    document.getElementById('resSecondaryBitmap').textContent = data.secondaryBitmapHex || 'None';

    const tbody = document.getElementById('fieldsTableBody');
    tbody.innerHTML = '';

    if (data.fields && data.fields.length > 0) {
        data.fields.forEach(f => {
            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td><strong class="code-font highlight">DE ${f.fieldId}</strong></td>
                <td>${f.name}</td>
                <td><code>${f.type}</code></td>
                <td class="code-font">${escapeHtml(f.value)}</td>
            `;
            tbody.appendChild(tr);
        });
    } else {
        tbody.innerHTML = '<tr><td colspan="4" style="text-align:center;">No Data Elements present</td></tr>';
    }
}

function initBitmapGrid() {
    const gridPrimary = document.getElementById('gridPrimary');
    const gridSecondary = document.getElementById('gridSecondary');

    gridPrimary.innerHTML = '';
    gridSecondary.innerHTML = '';

    for (let bit = 1; bit <= 64; bit++) {
        const cell = createBitCell(bit);
        gridPrimary.appendChild(cell);
    }

    for (let bit = 65; bit <= 128; bit++) {
        const cell = createBitCell(bit);
        gridSecondary.appendChild(cell);
    }
}

function createBitCell(bitId) {
    const div = document.createElement('div');
    div.className = 'bit-cell';
    div.id = `bit-${bitId}`;
    div.textContent = bitId;

    div.addEventListener('mouseenter', () => inspectBit(bitId));
    div.addEventListener('click', () => inspectBit(bitId));

    return div;
}

function highlightBitmapGrid(activeFields) {
    document.querySelectorAll('.bit-cell').forEach(cell => {
        cell.classList.remove('active', 'secondary-present');
    });

    activeFields.forEach(bitId => {
        const cell = document.getElementById(`bit-${bitId}`);
        if (cell) {
            if (bitId === 1) {
                cell.classList.add('secondary-present');
            } else {
                cell.classList.add('active');
            }
        }
    });

    // Update hex display
    const primaryHex = document.getElementById('resPrimaryBitmap').textContent;
    const secondaryHex = document.getElementById('resSecondaryBitmap').textContent;
    document.getElementById('primaryHexVal').textContent = primaryHex;
    document.getElementById('secondaryHexVal').textContent = secondaryHex;
}

function inspectBit(bitId) {
    const hoverCard = document.getElementById('bitHoverInfo');
    const def = fieldCatalog[bitId];

    if (bitId === 1) {
        hoverCard.innerHTML = `<strong>Bit 1: Secondary Bitmap</strong> - Indicates presence of Data Elements 65 to 128.`;
        return;
    }

    if (def) {
        hoverCard.innerHTML = `
            <strong>Bit ${bitId}: ${def.name}</strong><br>
            Format: <code>${def.type}</code> | Max Length: <strong>${def.maxLength}</strong><br>
            <span style="color: var(--text-muted);">${def.description}</span>
        `;
    } else {
        hoverCard.innerHTML = `<strong>Bit ${bitId}</strong>: Reserved / Custom Data Element`;
    }
}

function onMtiChange() {
    const mti = document.getElementById('bMti').value;
    const fieldsDiv = document.getElementById('builderFields');

    if (mti === '0200' || mti === '0100' || mti === '0400') {
        fieldsDiv.innerHTML = `
            <div class="form-group">
                <label for="f2">DE 2 - Primary Account Number (PAN):</label>
                <input type="text" id="f2" value="4532015588991234">
            </div>
            <div class="form-row">
                <div class="form-group">
                    <label for="f3">DE 3 - Processing Code:</label>
                    <input type="text" id="f3" value="000000">
                </div>
                <div class="form-group">
                    <label for="f4">DE 4 - Transaction Amount ($):</label>
                    <input type="text" id="f4" value="000000002550">
                </div>
            </div>
            <div class="form-row">
                <div class="form-group">
                    <label for="f11">DE 11 - STAN (Sequence #):</label>
                    <input type="text" id="f11" value="000123">
                </div>
                <div class="form-group">
                    <label for="f49">DE 49 - Currency Code:</label>
                    <input type="text" id="f49" value="840">
                </div>
            </div>
            <div class="form-row">
                <div class="form-group">
                    <label for="f41">DE 41 - Terminal ID:</label>
                    <input type="text" id="f41" value="TERM0001">
                </div>
                <div class="form-group">
                    <label for="f42">DE 42 - Merchant ID:</label>
                    <input type="text" id="f42" value="MERCHANT1234567">
                </div>
            </div>
        `;
    } else if (mti === '0800') {
        fieldsDiv.innerHTML = `
            <div class="form-group">
                <label for="f11">DE 11 - STAN (Sequence #):</label>
                <input type="text" id="f11" value="000999">
            </div>
            <div class="form-group">
                <label for="f70">DE 70 - Network Management Code:</label>
                <select id="f70">
                    <option value="301">301 - Echo Test</option>
                    <option value="001">001 - Logon Request</option>
                    <option value="002">002 - Logoff Request</option>
                </select>
            </div>
        `;
    }
}

function collectBuilderFields() {
    const fields = {};
    ['2', '3', '4', '11', '41', '42', '49', '70'].forEach(id => {
        const input = document.getElementById(`f${id}`);
        if (input && input.value.trim() !== '') {
            fields[id] = input.value.trim();
        }
    });
    return fields;
}

async function generatePackedMessage() {
    const header = document.getElementById('bHeader').value.trim();
    const mti = document.getElementById('bMti').value;
    const fields = collectBuilderFields();

    try {
        const res = await fetch('/api/iso/pack', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ header, mti, fields })
        });

        if (!res.ok) throw new Error('Packing failed');

        const data = await res.json();
        document.getElementById('packedOutput').textContent = data.rawPayload;
        document.getElementById('bitmapInfo').innerHTML = `
            Primary Bitmap: <strong class="code-font highlight">${data.primaryBitmapHex}</strong> 
            ${data.secondaryBitmapHex ? `| Secondary: <strong class="code-font">${data.secondaryBitmapHex}</strong>` : ''}
            | Total Length: <strong>${data.length} chars</strong>
        `;
        return data.rawPayload;
    } catch (e) {
        alert('Error packing message: ' + e.message);
        return null;
    }
}

async function packAndSimulate() {
    const rawPayload = await generatePackedMessage();
    if (!rawPayload) return;

    const simBox = document.getElementById('simResult');
    simBox.className = 'sim-box';
    simBox.textContent = 'Sending TCP request to localhost:8583...';

    try {
        const res = await fetch('/api/iso/simulate', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ rawPayload })
        });

        const data = await res.json();

        if (data.success) {
            simBox.className = 'sim-box success';
            simBox.innerHTML = `
                <div><strong>Status:</strong> <span style="color: var(--accent-color);">CONNECTED & APPROVED</span> (${data.roundtripMs} ms)</div>
                <div style="margin-top: 0.5rem;"><strong>TCP Sent Payload:</strong></div>
                <div style="color: var(--text-muted); font-size: 0.8rem; word-break: break-all;">${escapeHtml(data.requestPayload)}</div>
                <div style="margin-top: 0.5rem;"><strong>TCP Received Response (${data.responseMti}):</strong></div>
                <div style="color: var(--accent-color); font-size: 0.85rem; word-break: break-all;">${escapeHtml(data.responsePayload)}</div>
                <div style="margin-top: 0.5rem;"><strong>Result:</strong> DE 39 = <code>${data.responseCode}</code> (${data.responseCodeDescription})</div>
            `;
        } else {
            simBox.className = 'sim-box error';
            simBox.innerHTML = `
                <div><strong>Status:</strong> <span style="color: var(--danger-color);">TCP SIMULATOR ERROR</span></div>
                <div>${escapeHtml(data.message)}</div>
            `;
        }
    } catch (e) {
        simBox.className = 'sim-box error';
        simBox.textContent = 'TCP Socket error: ' + e.message;
    }
}

function escapeHtml(str) {
    if (!str) return '';
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
