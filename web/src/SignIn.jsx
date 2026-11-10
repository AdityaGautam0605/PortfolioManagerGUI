import { useState } from 'react';
import { announceAccountChange, request } from './api.js';

export default function SignIn({ onSignedIn }) {
  const [register, setRegister] = useState(false);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  async function submit(event) {
    event.preventDefault();
    if (pending) return;
    setPending(true); setError('');
    try {
      await request(register ? '/auth/register' : '/auth/login', { method: 'POST', body: { username, password } });
      setPassword('');
      announceAccountChange();
      window.location.hash = '/';
      onSignedIn();
    } catch (e) { setError(e.message); }
    finally { setPending(false); }
  }
  return <main className="auth-shell">
    <section className="panel auth-panel">
      <span className="eyebrow">FOLIO · YOUR PERSONAL WORKSPACE</span>
      <h1>{register ? 'A home for your portfolio.' : 'Welcome back.'}</h1>
      <p>{register ? 'Create an account to track your own holdings. Your portfolio is private to you.' : 'Sign in to pick up where you left off.'}</p>
      <form onSubmit={submit}>
        <label htmlFor="auth-username">Username</label>
        <input id="auth-username" autoComplete="username" required minLength={3} maxLength={40} pattern="[A-Za-z0-9_]{3,40}" value={username} onChange={e => setUsername(e.target.value)} disabled={pending} />
        <label htmlFor="auth-password">Password</label>
        <input id="auth-password" type="password" autoComplete={register ? 'new-password' : 'current-password'} required minLength={12} maxLength={128} value={password} onChange={e => setPassword(e.target.value)} disabled={pending} />
        {register && <small>Use 12–128 characters. Save your password securely; password recovery is not available yet.</small>}
        {error && <p role="alert" className="negative">{error}</p>}
        <button type="submit" className="button primary" disabled={pending}>{pending ? 'Please wait…' : register ? 'Create account' : 'Sign in'}</button>
      </form>
      <button className="button ghost" disabled={pending} onClick={() => { setRegister(!register); setError(''); setPassword(''); }}>
        {register ? 'Already have an account? Sign in' : 'New to Folio? Create an account'}
      </button>
    </section>
  </main>;
}
