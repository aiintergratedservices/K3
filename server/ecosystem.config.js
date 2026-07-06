// PM2 config — keeps Terminus alive forever on a computer/VPS:
//   pm2 start ecosystem.config.js
//   pm2 save && pm2 startup   (survive reboots)
module.exports = {
  apps: [
    {
      name: 'kortana-terminus',
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
  ],
};
