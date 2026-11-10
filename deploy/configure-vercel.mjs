import { writeFile } from 'node:fs/promises';

const input = process.argv[2];
let backend;
try { backend = new URL(input); } catch { throw new Error('Pass your Render URL: node deploy/configure-vercel.mjs https://your-service.onrender.com'); }
if (backend.protocol !== 'https:' || !backend.hostname.endsWith('.onrender.com') || backend.username || backend.password || backend.port || backend.search || backend.hash || backend.pathname !== '/') {
  throw new Error('Use the HTTPS origin of your Render service, without a path or query.');
}
const common = {
  rewrites: [{ source: '/api/:path*', destination: `${backend.origin}/api/:path*` }],
  headers: [{ source: '/api/:path*', headers: [{ key: 'Cache-Control', value: 'private, no-store' }] }],
};
const root = new URL('../', import.meta.url);
await writeFile(new URL('vercel.json', root), JSON.stringify({
  ...common, framework: null, installCommand: 'npm ci --prefix web', buildCommand: 'npm run build --prefix web', outputDirectory: 'web/dist',
}, null, 2) + '\n');
await writeFile(new URL('web/vercel.json', root), JSON.stringify({ ...common, framework: 'vite' }, null, 2) + '\n');
console.log(`Vercel API requests will use ${backend.origin}. Commit the generated configs and redeploy Vercel.`);
