document.addEventListener('DOMContentLoaded', () => {
    // State
    let avatarUrl = localStorage.getItem('avatarUrl') || '';
    let selectedAvatar = '';

    // UI Elements
    const authOverlay = document.getElementById('auth-overlay');
    const loginForm = document.getElementById('login-form');
    const signupForm = document.getElementById('signup-form');
    const authError = document.getElementById('auth-error');
    const displayUsername = document.getElementById('display-username');
    const userAvatar = document.getElementById('user-avatar');
    const logoutBtn = document.getElementById('logout-btn');
    const historyList = document.getElementById('history-list');
    const historyFilter = document.getElementById('history-lang-filter');

    const editorTextArea = document.getElementById('editor');
    const languageSelect = document.getElementById('language-select');
    const runBtn = document.getElementById('run-btn');
    const saveBtn = document.getElementById('save-btn');
    const clearBtn = document.getElementById('clear-btn');
    const terminal = document.getElementById('terminal');
    const stdin = document.getElementById('stdin');
    const execInfo = document.getElementById('exec-info');

    // Initialize CodeMirror
    const editor = CodeMirror.fromTextArea(editorTextArea, {
        lineNumbers: true,
        theme: 'dracula',
        mode: 'text/x-java',
        indentUnit: 4,
        matchBrackets: true,
        lineWrapping: true
    });

    const snippets = {
        java: "// Write your Java code here\npublic class Main {\n    public static void main(String[] args) {\n        System.out.println(\"Hello, Java!\");\n    }\n}",
        python: "# Write your Python code here\nprint(\"Hello, Python!\")",
        c: "// Write your C code here\n#include <stdio.h>\n\nint main() {\n    printf(\"Hello, C!\\n\");\n    return 0;\n}",
        cpp: "// Write your C++ code here\n#include <iostream>\n\nint main() {\n    std::cout << \"Hello, C++!\" << std::endl;\n    return 0;\n}"
    };

    const modes = {
        java: 'text/x-java',
        python: 'text/x-python',
        javascript: 'text/javascript',
        c: 'text/x-csrc',
        cpp: 'text/x-c++src'
    };

    // --- Authentication Logic ---
    function updateAuthUI() {
        if (token) {
            authOverlay.style.display = 'none';
            displayUsername.innerText = username;
            
            // Set User Avatar
            if (avatarUrl) {
                userAvatar.style.backgroundImage = `url('https://api.dicebear.com/7.x/avataaars/svg?seed=${avatarUrl}&backgroundColor=b6e3f4')`;
                userAvatar.style.backgroundPosition = 'center';
            }
            
            loadHistory();
        } else {
            authOverlay.style.display = 'flex';
        }
    }

    // Avatar Selection Logic
    document.querySelectorAll('.avatar-opt').forEach(opt => {
        opt.onclick = () => {
            document.querySelectorAll('.avatar-opt').forEach(o => o.classList.remove('selected'));
            opt.classList.add('selected');
            selectedAvatar = opt.dataset.avatar;
        };
    });

    document.getElementById('show-signup').onclick = (e) => {
        e.preventDefault();
        loginForm.style.display = 'none';
        signupForm.style.display = 'block';
    };

    document.getElementById('show-login').onclick = (e) => {
        e.preventDefault();
        signupForm.style.display = 'none';
        loginForm.style.display = 'block';
    };

    async function authAction(endpoint, body) {
        authError.style.display = 'none';
        try {
            const response = await fetch(`/api/auth/${endpoint}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
            const data = await response.json();
            if (response.ok) {
                token = data.token;
                username = data.username;
                avatarUrl = data.avatarUrl;
                localStorage.setItem('token', token);
                localStorage.setItem('username', username);
                localStorage.setItem('avatarUrl', avatarUrl);
                updateAuthUI();
            } else {
                authError.innerText = data.error || 'Authentication failed';
                authError.style.display = 'block';
            }
        } catch (err) {
            authError.innerText = 'Server connection error';
            authError.style.display = 'block';
        }
    }

    document.getElementById('login-btn').onclick = () => {
        const u = document.getElementById('login-username').value;
        const p = document.getElementById('login-password').value;
        authAction('login', { username: u, password: p });
    };

    document.getElementById('signup-submit-btn').onclick = () => {
        const u = document.getElementById('signup-username').value;
        const p = document.getElementById('signup-password').value;
        if (!selectedAvatar) {
            authError.innerText = 'Please select an avatar';
            authError.style.display = 'block';
            return;
        }
        authAction('signup', { username: u, password: p, avatarUrl: selectedAvatar });
    };

    logoutBtn.onclick = () => {
        localStorage.clear();
        location.reload();
    };

    // --- Execution Logic ---
    languageSelect.addEventListener('change', () => {
        const lang = languageSelect.value;
        const mode = modes[lang] || 'text/x-java';
        editor.setOption('mode', mode);
        editor.setValue(snippets[lang]);
    });

    async function performSave(isRun = true) {
        if (!token) return updateAuthUI();

        const code = editor.getValue();
        const language = languageSelect.value;
        const input = stdin.value;

        if (isRun) appendToTerminal(`>>> Loading ${language.toUpperCase()} environment...`, 'info');
        else appendToTerminal(`>>> Saving code snippet...`, 'info');
        
        runBtn.disabled = true;
        saveBtn.disabled = true;
        const start = performance.now();

        try {
            const response = await fetch('/api/execute', {
                method: 'POST',
                headers: { 
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${token}`
                },
                body: JSON.stringify({ code, language, input })
            });

            if (response.status === 403 || response.status === 401) {
                logoutBtn.click(); 
                return;
            }

            if (!response.ok) {
                const errData = await response.text();
                appendToTerminal(`Server Error (${response.status}): ${errData}`, 'error');
                return;
            }

            const result = await response.json();
            const end = performance.now();
            execInfo.innerText = `${Math.round(end - start)}ms`;

            if (isRun) {
                if (result.error) appendToTerminal(result.error, 'error');
                if (result.output) appendToTerminal(result.output, 'success');
            } else {
                appendToTerminal('Snippet saved successfully!', 'success');
            }
            
            loadHistory(); // Refresh history
        } catch (err) {
            appendToTerminal(`Operation failed: ${err.message}`, 'error');
        } finally {
            runBtn.disabled = false;
            saveBtn.disabled = false;
        }
    }

    runBtn.onclick = () => performSave(true);
    saveBtn.onclick = () => performSave(false);

    clearBtn.onclick = () => {
        terminal.innerHTML = '<div class="output-line">Terminal cleared.</div>';
    };

    // --- History Logic ---
    historyFilter.onchange = loadHistory;

    async function loadHistory() {
        if (!token) return;
        const lang = historyFilter.value;
        try {
            const response = await fetch(`/api/history${lang ? '?language=' + lang : ''}`, {
                headers: { 'Authorization': `Bearer ${token}` }
            });
            const data = await response.json();
            historyList.innerHTML = '';
            data.forEach(sub => {
                const item = document.createElement('div');
                item.className = `history-item ${sub.exitCode === 0 ? 'success' : 'error'}`;
                item.innerHTML = `
                    <div class="lang-badge">${sub.language}</div>
                    <strong>Code from ${new Date(sub.createdAt).toLocaleTimeString()}</strong>
                    <div class="meta">
                        <span>Time: ${sub.executionTimeMs}ms</span>
                        <span>Code: ${sub.code.substring(0, 15)}...</span>
                    </div>
                `;
                item.onclick = () => {
                    editor.setValue(sub.code);
                    languageSelect.value = sub.language;
                    stdin.value = sub.input || '';
                    if (sub.output) {
                        terminal.innerHTML = `<div class="output-line success-text">${sub.output}</div>`;
                    }
                };
                historyList.appendChild(item);
            });
        } catch (err) {
            console.error('History load failed', err);
        }
    }

    function appendToTerminal(text, type) {
        const div = document.createElement('div');
        div.className = 'output-line ' + (type === 'error' ? 'error-text' : type === 'success' ? 'success-text' : '');
        div.innerText = text;
        terminal.appendChild(div);
        terminal.scrollTop = terminal.scrollHeight;
    }

    // Init
    editor.setValue(snippets.java);
    updateAuthUI();
});
