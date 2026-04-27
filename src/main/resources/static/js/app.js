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
    const terminal = document.getElementById('terminal');
    const stdin = document.getElementById('stdin');
    const execInfo = document.getElementById('exec-info');
    const loadingOverlay = document.getElementById('loading-overlay');
    const themeToggle = document.getElementById('theme-toggle');
    const themeIcon = document.getElementById('theme-icon');
    const currentFileName = document.getElementById('current-file-name');

    let editorInstance = null;

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
        const input = stdin.value;

        if (isRun) appendToTerminal(`>>> Loading ${language.toUpperCase()} environment...`, 'info');
        else appendToTerminal(`>>> Saving code snippet...`, 'info');
        
        runBtn.disabled = true;
        // Show loading spinner
        loadingOverlay.classList.remove('opacity-0', 'pointer-events-none');
        loadingOverlay.classList.add('opacity-100');
        
        const start = performance.now();

        try {
            const endpoint = isRun ? '/api/execute' : '/api/save';
            const response = await fetch(endpoint, {
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
            execInfo.classList.remove('hidden');

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
            // Hide loading spinner
            loadingOverlay.classList.add('opacity-0', 'pointer-events-none');
            loadingOverlay.classList.remove('opacity-100');
        }
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
        terminal.innerHTML = '<div class="text-brand opacity-80 mb-2">Terminal cleared. Ready for execution.</div>';
        execInfo.classList.add('hidden');
    };

    copyBtn.onclick = async () => {
        try {
            await navigator.clipboard.writeText(terminal.innerText);
            
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
                    <strong class="text-[13px] text-gray-800 dark:text-gray-200 block mb-1">Code from ${new Date(sub.createdAt).toLocaleTimeString()}</strong>
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
                    
                    stdin.value = sub.input || '';
                    if (sub.output) {
                        appendToTerminal(sub.output, sub.exitCode === 0 ? 'success' : 'error');
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

    function appendToTerminal(text, type) {
        const div = document.createElement('div');
        div.className = 'mb-1 ' + (type === 'error' ? 'text-red-500' : type === 'success' ? 'text-gray-800 dark:text-gray-300' : 'text-brand');
        div.innerText = text;
        terminal.appendChild(div);
        terminal.scrollTop = terminal.scrollHeight;
    }

    // Init
    updateAuthUI();
});
