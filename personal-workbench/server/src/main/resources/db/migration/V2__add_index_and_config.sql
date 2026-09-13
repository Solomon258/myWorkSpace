CREATE INDEX idx_task_deleted ON task(deleted);
CREATE INDEX idx_memo_deleted ON memo(deleted);
CREATE INDEX idx_inbox_deleted ON inbox_item(deleted);
CREATE INDEX idx_ai_job_status ON ai_job(status, created_at);

INSERT OR IGNORE INTO app_config(config_key, config_value) VALUES
    ('app.initialized',   'false'),
    ('app.username',      ''),
    ('app.password_hash', ''),
    ('app.timezone',      'Asia/Shanghai'),
    ('ai.enabled',        'false'),
    ('ai.base_url',       ''),
    ('ai.api_key',        ''),
    ('ai.model',          ''),
    ('obsidian.enabled',  'false'),
    ('obsidian.vault_path',''),
    ('obsidian.vault_name',''),
    ('pomo.work',         '25'),
    ('pomo.short',        '5'),
    ('pomo.long',         '15'),
    ('pomo.auto',         'false');
