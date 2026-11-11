import { useEffect, useRef, useState } from 'react';
import { announceAccountChange, request } from './api.js';

const titles = {
  signup: 'Create your account', login: 'Welcome back.', email: 'Sign in with email',
  reset: 'Reset your password', verify: 'Check your email', username: 'Choose your username',
};
const blankProfile = { email: '', firstName: '', middleName: '', lastName: '', password: '', username: '', code: '' };
const post = (path, body) => request('/auth/' + path, { method: 'POST', body });

export default function SignIn({ onSignedIn }) {
  const [step, setStep] = useState('login');
  const [purpose, setPurpose] = useState('');
  const [fields, setFields] = useState(blankProfile);
  const [ticket, setTicket] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [options, setOptions] = useState(null);
  const [googleReady, setGoogleReady] = useState(false);
  const heading = useRef(null);
  const firstRender = useRef(true);
  useEffect(() => {
    const controller = new AbortController();
    request('/auth/options', { signal: controller.signal }).then(setOptions).catch(() => {
      if (!controller.signal.aborted) setNotice('Sign-in options could not be loaded. Refresh to try again.');
    });
    return () => controller.abort();
  }, []);
  useEffect(() => {
    if (!options?.googleClientId) return;
    if (window.google?.accounts?.oauth2) { setGoogleReady(true); return; }
    const script = document.createElement('script');
    script.src = 'https://accounts.google.com/gsi/client';
    script.async = true;
    script.onload = () => setGoogleReady(true);
    script.onerror = () => setNotice('Google Sign-In could not load. Try another sign-in method.');
    document.head.appendChild(script);
    return () => script.remove();
  }, [options]);
  useEffect(() => {
    if (!firstRender.current) heading.current?.focus();
    firstRender.current = false;
  }, [step]);
  function move(next) {
    setStep(next); setError(''); setNotice(''); setTicket('');
    setFields({ ...blankProfile, email: fields.email }); setPurpose('');
  }
  function signedIn() {
    setFields(blankProfile); setTicket('');
    announceAccountChange(); window.location.hash = '/'; onSignedIn();
  }
  function accept(result) {
    if (result.ticket) {
      setTicket(result.ticket); setStep('username');
      setFields(current => ({ ...current, email: result.email, code: '' }));
    } else if (result.reset) {
      move('login'); setNotice('Password updated. Sign in with your new password.');
    } else if (result.signedIn) signedIn();
  }
  function googleSignIn() {
    setPending(true); setError('');
    const failed = () => { setPending(false); setError('Google sign-in did not finish. Please try again.'); };
    try {
      window.google.accounts.oauth2.initCodeClient({
        client_id: options.googleClientId, scope: 'openid email', ux_mode: 'popup',
        error_callback: failed,
        callback: async ({ code }) => {
          if (!code) { failed(); return; }
          try { accept(await post('google', { code })); }
          catch (e) { setError(e.message); }
          finally { setPending(false); }
        },
      }).requestCode();
    } catch { failed(); }
  }
  async function submit(event) {
    event.preventDefault();
    if (pending) return;
    setPending(true); setError(''); setNotice('');
    try {
      if (step === 'login') {
        await post('login', { username: fields.email, password: fields.password }); signedIn();
      } else if (step === 'username') {
        await post('register', { ...fields, ticket }); signedIn();
      } else if (step === 'verify') {
        accept(await post('verify', { purpose, code: fields.code, password: purpose === 'reset' ? fields.password : undefined }));
      } else {
        if (step === 'signup' && (!fields.firstName.trim() || !fields.lastName.trim())) {
          throw new Error('Enter your first and last name.');
        }
        await post('send-code', { email: fields.email, purpose: step, ...(step === 'signup' ? { password: fields.password } : {}) });
        setPurpose(step); setStep('verify');
      }
    } catch (e) { setError(e.message); }
    finally { setPending(false); }
  }
  function field(key, label, props = {}) {
    return <label className="auth-field" htmlFor={'auth-' + key}>{label}
      <input id={'auth-' + key} required disabled={pending} value={fields[key]}
        onChange={e => setFields({ ...fields, [key]: e.target.value })} {...props} />
    </label>;
  }
  const passwordSetup = () => <>
    {field('password', 'Password', { type: 'password', autoComplete: 'new-password', 'aria-describedby': 'password-guide' })}
    <small id="password-guide">Use 15–128 characters. A few unrelated words work well. Spaces are welcome.</small>
  </>;
  const entry = step === 'signup' || step === 'login';
  const action = { signup: 'Verify email', login: 'Sign in', email: 'Send sign-in code', reset: 'Send reset code',
    verify: purpose === 'reset' ? 'Set new password' : 'Continue', username: 'Create account' }[step];
  return <main className="auth-shell"><section className="panel auth-panel" aria-labelledby="auth-heading">
    <span className="auth-brand">folio.</span>
    <h1 ref={heading} tabIndex={-1} id="auth-heading">{titles[step]}</h1>
    {step === 'verify' && <p>Enter the code sent to <strong>{fields.email}</strong>. It expires in 10 minutes.</p>}
    {step === 'reset' && <p>Enter the verified email on your account to receive a reset code.</p>}
    <form onSubmit={submit}>
      {['signup','reset','email','login'].includes(step) && field('email', step === 'login' ? 'Email or username' : 'Email address', {
        type: step === 'login' ? 'text' : 'email', maxLength: 254, autoComplete: step === 'login' ? 'username' : 'email',
        autoCapitalize: 'none', spellCheck: false,
      })}
      {step === 'signup' && <>
        <div className="auth-names">
          {field('firstName','First name',{ autoComplete: 'given-name', maxLength: 100 })}
          {field('middleName','Middle name (optional)',{ autoComplete: 'additional-name', maxLength: 100, required: false })}
          {field('lastName','Last name',{ autoComplete: 'family-name', maxLength: 100 })}
        </div>{passwordSetup()}
      </>}
      {step === 'login' && <>
        {field('password','Password',{ type: 'password', autoComplete: 'current-password' })}
        <div className="auth-account-links">
          <button type="button" className="auth-link" disabled={pending} onClick={() => move('reset')}>Forgot password?</button>
          <button type="button" className="auth-link" disabled={pending} onClick={() => move('signup')}>Create an account</button>
        </div>
      </>}
      {step === 'verify' && <>
        {field('code','Email code',{ autoComplete: 'one-time-code', autoCapitalize: 'none', spellCheck: false })}
        {purpose === 'reset' && passwordSetup()}
      </>}
      {step === 'username' && field('username','Username',{ autoComplete: 'username', pattern: '[A-Za-z0-9_]{3,40}', title: '3–40 letters, numbers or underscores', maxLength: 40 })}
      {error && <p role="alert" className="negative">{error}</p>}
      {notice && <p role="status">{notice}</p>}
      <button className="button primary" disabled={pending}>{pending ? 'Please wait…' : action}</button>
    </form>
    {entry && <div className="auth-methods">
      {options?.googleClientId && <button className="button" disabled={pending || !googleReady} onClick={googleSignIn}>Continue with Google</button>}
      <button className="button" disabled={pending} onClick={() => move('email')}>Sign in with an email code</button>
    </div>}
    {step !== 'login' && <button className="auth-switch auth-link" disabled={pending} onClick={() => move('login')}>
      {step === 'signup' ? 'Already have an account? Sign in' : 'Back to sign in'}
    </button>}
    {step === 'verify' && <button className="auth-link" disabled={pending} onClick={() => {
      setStep(purpose); setError(''); setFields({ ...fields, code: '' });
    }}>Change email or request another code</button>}
  </section></main>;
}
