# Local LLM (llama.cpp on the phone)

Default: http://127.0.0.1:8080/v1, model qwen2.5-1.5b-instruct, no API key. Only loopback hosts are accepted.
Start the server with tool calling enabled, e.g.:
    llama-server -m qwen2.5-1.5b-instruct-*.gguf --host 127.0.0.1 --port 8080 --jinja
(`--jinja` is needed for OpenAI-style tool calls.) If the server is down, JARVIS falls back to the offline brain
(and retries the server after 60 s). Overrides: SharedPreferences `jarvis_llm_config` or BuildConfig fields.
