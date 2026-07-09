// PM2 config — keeps Terminus alive forever on a computer/VPS:
//   pm2 start ecosystem.config.js
//   pm2 save && pm2 startup   (survive reboots)
module.exports = {
  apps: [
    {
      name: 'kortana-terminus',
      // Kortana runs in her own PM2 namespace so she never shares a node with
      // other apps (e.g. CampLoJack's EWS). Manage ONLY her with:
      //   pm2 restart kortana / pm2 stop kortana / pm2 logs kortana-terminus
      // EWS and anything else in the default namespace is untouched.
      namespace: 'kortana',
      script: 'index.js',
      cwd: __dirname,
      autorestart: true,
      max_restarts: 50,
      restart_delay: 5000,
      max_memory_restart: '512M',
      env: { NODE_ENV: 'production' },
      out_file: './logs/terminus-out.log',
      error_file: './logs/terminus-err.log',
      merge_logs: true,
      time: true,
    },
    {
      // Her local phi3 brain. Starting the stack with `pm2 start
      // ecosystem.config.js` now brings Ollama up too, so the app's tier-1
      // core is always reachable at http://127.0.0.1:11434 — even offline.
      // One-time model download (~2 GB, do it on Wi-Fi):  ollama pull phi3:mini
      // If ollama isn't installed on this machine this entry just errors out
      // harmlessly; Terminus keeps running.
      name: 'kortana-ollama',
      namespace: 'kortana',
      script: 'ollama',
      args: 'serve',
      interpreter: 'none',
      autorestart: true,
      max_restarts: 10,
      restart_delay: 10000,
      out_file: './logs/ollama-out.log',
      error_file: './logs/ollama-err.log',
      merge_logs: true,
      time: true,
    },
  ],
};
