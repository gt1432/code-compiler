document.addEventListener('DOMContentLoaded', () => {
    // State
    let token = localStorage.getItem('token') || '';
    let username = localStorage.getItem('username') || '';
    let avatarUrl = localStorage.getItem('avatarUrl') || '';
    let selectedAvatar = '';
    let isDarkMode = localStorage.getItem('theme') !== 'light';

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

    const languageSelect = document.getElementById('language-select');
    const runBtn = document.getElementById('run-btn');
    const saveBtn = document.getElementById('save-btn');
    const clearBtn = document.getElementById('clear-btn');
    const copyBtn = document.getElementById('copy-btn');
    const terminalSection = document.getElementById('terminal-section');
    const execInfo = document.getElementById('exec-info');
    const loadingOverlay = document.getElementById('loading-overlay');
    const themeToggle = document.getElementById('theme-toggle');
    const themeIcon = document.getElementById('theme-icon');
    const currentFileName = document.getElementById('current-file-name');

    let editorInstance = null;
    let term = null;
    let fitAddon = null;

    const snippets = {
        java: "// Write your Java code here\npublic class Main {\n    public static void main(String[] args) {\n        System.out.println(\"Hello, Lumina!\");\n    }\n}",
        python: "# Write your Python code here\nprint(\"Hello, Lumina!\")",
        c: "// Write your C code here\n#include <stdio.h>\n\nint main() {\n    printf(\"Hello, Lumina!\\n\");\n    return 0;\n}",
        cpp: "// Write your C++ code here\n#include <iostream>\n\nint main() {\n    std::cout << \"Hello, Lumina!\" << std::endl;\n    return 0;\n}"
    };

    const monacoModes = {
        java: 'java',
        python: 'python',
        c: 'c',
        cpp: 'cpp'
    };

    const fileNames = {
        java: 'Main.java',
        python: 'script.py',
        c: 'main.c',
        cpp: 'main.cpp'
    };

    // --- Theme Logic ---
    function updateThemeIcon() {
        if (isDarkMode) {
            themeIcon.setAttribute('data-lucide', 'sun');
            themeIcon.classList.add('text-gray-400');
            themeIcon.classList.remove('text-gray-500');
        } else {
            themeIcon.setAttribute('data-lucide', 'moon');
            themeIcon.classList.remove('text-gray-400');
            themeIcon.classList.add('text-gray-500');
        }
        if (typeof lucide !== 'undefined') lucide.createIcons();
    }

    // Apply initial theme
    if (isDarkMode) {
        document.documentElement.classList.add('dark');
    } else {
        document.documentElement.classList.remove('dark');
    }
    updateThemeIcon();

    themeToggle.onclick = () => {
        isDarkMode = !isDarkMode;
        localStorage.setItem('theme', isDarkMode ? 'dark' : 'light');
        if (isDarkMode) {
            document.documentElement.classList.add('dark');
            if(editorInstance) monaco.editor.setTheme('vs-dark');
        } else {
            document.documentElement.classList.remove('dark');
            if(editorInstance) monaco.editor.setTheme('vs');
        }
        updateThemeIcon();
    };

    // --- Monaco Initialization ---
    require.config({ paths: { 'vs': 'https://cdnjs.cloudflare.com/ajax/libs/monaco-editor/0.39.0/min/vs' }});
    require(['vs/editor/editor.main'], function() {
        const themeStr = isDarkMode ? 'vs-dark' : 'vs';
        editorInstance = monaco.editor.create(document.getElementById('editor-container'), {
            value: snippets.java,
            language: 'java',
            theme: themeStr,
            automaticLayout: true,
            fontSize: 14,
            fontFamily: "'JetBrains Mono', monospace",
            minimap: { enabled: false },
            scrollbar: {
                vertical: 'visible',
                horizontal: 'visible'
            },
            padding: { top: 16 }
        });

        // Add Ctrl+Enter Shortcut
        editorInstance.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, function() {
            performSave(true);
        });
    });

    // --- xterm.js Initialization ---
    term = new Terminal({
        fontFamily: "'JetBrains Mono', monospace",
        fontSize: 13,
        theme: {
            background: '#000000',
            foreground: '#d4d4d4',
            cursor: '#7c4dff'
        },
        cursorBlink: true
    });
    fitAddon = new FitAddon.FitAddon();
    term.loadAddon(fitAddon);
    term.open(document.getElementById('xterm-container'));
    fitAddon.fit();
    term.writeln('\x1b[35mWelcome to Lumina Code v2.0 Interactive Terminal.\x1b[0m');
    term.writeln('Ready to compile and run your code.');

    // --- Authentication Logic ---
    function updateAuthUI() {
        if (token) {
            authOverlay.style.display = 'none';
            displayUsername.innerText = username;
            
            // Set User Avatar using DiceBear
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
            document.querySelectorAll('.avatar-opt').forEach(o => o.classList.remove('selected', 'border-brand'));
            opt.classList.add('selected', 'border-brand');
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
        const mode = monacoModes[lang] || 'java';
        currentFileName.innerText = fileNames[lang] || 'script';
        
        if (editorInstance) {
            monaco.editor.setModelLanguage(editorInstance.getModel(), mode);
            editorInstance.setValue(snippets[lang] || '');
        }
    });

    async function performSave(isRun = true) {
        if (!token) return updateAuthUI();
        if (!editorInstance) return;

        const code = editorInstance.getValue();
        const language = languageSelect.value;

        if (!isRun) {
            // Save logic
            loadingOverlay.classList.remove('opacity-0', 'pointer-events-none');
            loadingOverlay.classList.add('opacity-100');
            try {
                const response = await fetch('/api/save', {
                    method: 'POST',
                    headers: { 
                        'Content-Type': 'application/json',
                        'Authorization': `Bearer ${token}`
                    },
                    body: JSON.stringify({ code, language, input: "" })
                });

                if (response.status === 403 || response.status === 401) {
                    logoutBtn.click(); 
                    return;
                }

                if (!response.ok) {
                    term.writeln(`\r\n\x1b[31mServer Error (${response.status})\x1b[0m`);
                    return;
                }

                term.writeln('\r\n\x1b[32mSnippet saved successfully!\x1b[0m');
                loadHistory(); 
            } catch (err) {
                term.writeln(`\r\n\x1b[31mOperation failed: ${err.message}\x1b[0m`);
            } finally {
                loadingOverlay.classList.add('opacity-0', 'pointer-events-none');
                loadingOverlay.classList.remove('opacity-100');
            }
            return;
        }

        // Run logic via WebSocket
        runBtn.disabled = true;
        term.clear();
        term.writeln(`\x1b[34m>>> Loading ${language.toUpperCase()} environment...\x1b[0m`);

        // Track history silently
        fetch('/api/save', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
            body: JSON.stringify({ code, language, input: "" })
        }).then(() => loadHistory());

        if (window.activeSocket) {
            window.activeSocket.close();
        }

        const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const ws = new WebSocket(`${wsProtocol}//${window.location.host}/ws/execute`);
        window.activeSocket = ws;

        ws.onopen = () => {
            term.writeln('\x1b[32mConnected to server. Compiling...\x1b[0m');
            ws.send(JSON.stringify({ code, language }));
            
            if (window.termDataListener) window.termDataListener.dispose();
            window.termDataListener = term.onData(data => {
                let sendData = data;
                if (data === '\r') {
                    sendData = '\n';
                    term.write('\r\n');
                } else if (data === '\x7F') {
                    term.write('\b \b');
                } else {
                    term.write(data);
                }
                ws.send(sendData);
            });
        };

        ws.onmessage = (event) => {
            term.write(event.data);
        };

        ws.onclose = () => {
            if (window.termDataListener) window.termDataListener.dispose();
            term.writeln('\r\n\x1b[33m[Disconnected from server]\x1b[0m');
            runBtn.disabled = false;
        };

        ws.onerror = (err) => {
            term.writeln('\r\n\x1b[31m[WebSocket Error Occurred]\x1b[0m');
            runBtn.disabled = false;
        };
    }

    runBtn.onclick = () => performSave(true);
    if(saveBtn) saveBtn.onclick = () => performSave(false);

    // Global Keyboard Shortcuts
    document.addEventListener('keydown', (e) => {
        if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
            e.preventDefault();
            performSave(true);
        }
        if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
            e.preventDefault();
            performSave(false);
        }
    });

    clearBtn.onclick = () => {
        term.clear();
        term.writeln('\x1b[35mTerminal cleared. Ready for execution.\x1b[0m');
        execInfo.classList.add('hidden');
    };

    copyBtn.onclick = async () => {
        try {
            if (term.hasSelection()) {
                await navigator.clipboard.writeText(term.getSelection());
            } else {
                term.selectAll();
                await navigator.clipboard.writeText(term.getSelection());
                term.clearSelection();
            }
            
            // Temporary icon change for feedback
            const originalIcon = copyBtn.innerHTML;
            copyBtn.innerHTML = '<i data-lucide="check" class="w-4 h-4 text-green-500"></i>';
            if (typeof lucide !== 'undefined') lucide.createIcons();
            
            setTimeout(() => {
                copyBtn.innerHTML = originalIcon;
                if (typeof lucide !== 'undefined') lucide.createIcons();
            }, 2000);
        } catch (err) {
            console.error('Failed to copy text: ', err);
        }
    };

    // --- History Logic ---
    historyFilter.onchange = loadHistory;

    let isFirstLoad = true;

    async function loadHistory() {
        if (!token) return;
        const lang = historyFilter.value;
        try {
            const response = await fetch(`/api/history${lang ? '?language=' + lang : ''}`, {
                headers: { 'Authorization': `Bearer ${token}` }
            });
            const data = await response.json();
            historyList.innerHTML = '';
            
            if (data.length === 0) {
                historyList.innerHTML = '<div class="text-center text-sm text-gray-400 mt-10">No execution history yet.</div>';
                return;
            }
            
            data.forEach((sub, index) => {
                const item = document.createElement('div');
                item.className = `history-item ${sub.exitCode === 0 ? 'success' : 'error'}`;
                item.innerHTML = `
                    <div class="lang-badge">${sub.language}</div>
                    <strong class="text-[13px] text-gray-800 dark:text-gray-200 block mb-1">Code from ${sub.createdAt ? new Date(sub.createdAt).toLocaleString() : 'Just now'}</strong>
                    <div class="meta">
                        <span class="flex items-center gap-1"><i data-lucide="clock" class="w-3 h-3"></i> ${sub.executionTimeMs}ms</span>
                        <span class="font-mono bg-lightBg dark:bg-darkBg px-1.5 py-0.5 rounded opacity-70">${sub.code.substring(0, 10).replace(/\n/g, ' ')}...</span>
                    </div>
                `;
                item.onclick = () => {
                    if (editorInstance) editorInstance.setValue(sub.code);
                    languageSelect.value = sub.language;
                    currentFileName.innerText = fileNames[sub.language] || 'script';
                    if (editorInstance) monaco.editor.setModelLanguage(editorInstance.getModel(), monacoModes[sub.language]);
                    
                    if (sub.output) {
                        term.writeln(`\r\n\x1b[34m--- History Output ---\x1b[0m\r\n`);
                        term.write(sub.output.replace(/\n/g, '\r\n'));
                    }
                };
                historyList.appendChild(item);
                if (typeof lucide !== 'undefined') lucide.createIcons({ root: item });

                // Auto-load most recent snippet on login
                if (isFirstLoad && index === 0) {
                    setTimeout(() => item.click(), 500); // Small delay to ensure Monaco is ready
                    isFirstLoad = false;
                }
            });
        } catch (err) {
            console.error('History load failed', err);
        }
    }

    // --- Resizable Panels Logic ---
    const sidebarSection = document.getElementById('sidebar-section');
    const resizerSidebar = document.getElementById('resizer-sidebar');
    
    const editorSection = document.getElementById('editor-section');
    const resizerVertical = document.getElementById('resizer-vertical');

    // 1. Sidebar Resizer
    if (resizerSidebar && sidebarSection) {
        let isResizing = false;
        resizerSidebar.addEventListener('mousedown', (e) => {
            isResizing = true;
            document.body.style.cursor = 'col-resize';
            e.preventDefault();
        });
        document.addEventListener('mousemove', (e) => {
            if (!isResizing) return;
            const newWidth = e.clientX;
            if (newWidth > 200 && newWidth < 600) {
                sidebarSection.style.width = `${newWidth}px`;
                if(editorInstance) editorInstance.layout();
            }
        });
        document.addEventListener('mouseup', () => {
            if (isResizing) {
                isResizing = false;
                document.body.style.cursor = '';
            }
        });
    }

    // 2. Vertical Resizer
    if (resizerVertical && editorSection && terminalSection) {
        let isResizing = false;
        resizerVertical.addEventListener('mousedown', (e) => {
            isResizing = true;
            document.body.style.cursor = 'row-resize';
            e.preventDefault();
        });
        document.addEventListener('mousemove', (e) => {
            if (!isResizing) return;
            const containerHeight = editorSection.parentElement.getBoundingClientRect().height;
            const containerTop = editorSection.parentElement.getBoundingClientRect().top;
            const newHeight = e.clientY - containerTop;
            
            if (newHeight > 100 && newHeight < containerHeight - 100) {
                editorSection.style.flex = 'none';
                editorSection.style.height = `${newHeight}px`;
                terminalSection.style.flex = '1';
                terminalSection.style.height = 'auto';
                if(editorInstance) editorInstance.layout();
            }
        });
        document.addEventListener('mouseup', () => {
            if (isResizing) {
                isResizing = false;
                document.body.style.cursor = '';
                if (fitAddon) fitAddon.fit();
            }
        });
    }

    // Auto-layout Monaco on window resize
    window.addEventListener('resize', () => {
        if (editorInstance) editorInstance.layout();
        if (fitAddon) fitAddon.fit();
    });

    // Init
    updateAuthUI();
});
