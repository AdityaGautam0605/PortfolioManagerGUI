import { useEffect, useRef, useState } from 'react';
import { readStored, request, store } from './api.js';
import Brand from './Brand.jsx';
import HomePage from './HomePage.jsx';
import SignIn from './SignIn.jsx';

let workspaceModule;
function preloadWorkspace() {
  workspaceModule ??= import('./Workspace.jsx').catch(error => {
    workspaceModule = undefined;
    throw error;
  });
  return workspaceModule;
}
export default function App() {
  const [theme, setTheme] = useState(() => readStored('folio-theme', 'dark'));
  useEffect(() => {
    document.documentElement.dataset.theme = theme === 'light' ? 'light' : 'dark';
    store('folio-theme', theme);
  }, [theme]);
  const [requiresSignIn, setRequiresSignIn] = useState(false);
  const [accountVersion, setAccountVersion] = useState(0);
  const [hash, setHash] = useState(location.hash);
  const hadSession = useRef(false);
  const [workspace, setWorkspace] = useState(null);
  const [loadError, setLoadError] = useState('');
  const [slowConnection, setSlowConnection] = useState(false);
  useEffect(() => {
    setSlowConnection(false);
    if (workspace || requiresSignIn || loadError) return;
    const timer = setTimeout(() => setSlowConnection(true), 4000);
    return () => clearTimeout(timer);
  }, [workspace, requiresSignIn, loadError, accountVersion]);
  useEffect(() => {
    const onHash = () => setHash(location.hash);
    const listener = () => {
      if (hadSession.current || !['', '#/', '#/library', '#/signin', '#/signup'].includes(location.hash)) {
        location.hash = '/signin';
      }
      hadSession.current = false;
      setHash(location.hash);
      setWorkspace(null);
      setRequiresSignIn(true);
    };
    const accountChanged = (event) => {
      if (event.key === 'folio-account-change') {
        setWorkspace(null);
        setAccountVersion(value => value + 1);
        setRequiresSignIn(false);
      }
    };
    window.addEventListener('folio-auth-required', listener);
    window.addEventListener('storage', accountChanged);
    window.addEventListener('hashchange', onHash);
    return () => {
      window.removeEventListener('folio-auth-required', listener);
      window.removeEventListener('storage', accountChanged);
      window.removeEventListener('hashchange', onHash);
    };
  }, []);
  useEffect(() => {
    if (requiresSignIn) return;
    const controller = new AbortController();
    setLoadError('');
    async function openWorkspace() {
      try {
        const snapshot = await request('/snapshot', { signal: controller.signal });
        if (controller.signal.aborted) return;
        hadSession.current = Boolean(snapshot.account);
        const { default: Dashboard } = await preloadWorkspace();
        if (controller.signal.aborted) return;
        if (['#/signin', '#/signup'].includes(location.hash)) location.replace('#/');
        setWorkspace({ Dashboard, snapshot });
      } catch (error) {
        if (!controller.signal.aborted && error.status !== 401) setLoadError(error.message);
      }
    }
    openWorkspace();
    return () => controller.abort();
  }, [requiresSignIn, accountVersion]);

  useEffect(() => {
    if (requiresSignIn && ['#/signin', '#/signup'].includes(hash)) {
      // Download only UI code while the visitor completes authentication.
      preloadWorkspace().catch(() => {});
    }
  }, [requiresSignIn, hash]);

  if (requiresSignIn) {
    if (hash === '#/signin' || hash === '#/signup')
      return <SignIn key={hash} initialStep={hash === '#/signup' ? 'signup' : 'login'} onSignedIn={() => setRequiresSignIn(false)} />;
    return <HomePage libraryPage={hash === '#/library'} theme={theme} setTheme={setTheme} mark={<Brand />} />;
  }
  if (workspace) return <workspace.Dashboard initialSnapshot={workspace.snapshot} theme={theme} setTheme={setTheme} />;
  return <main className="auth-shell" aria-label="Opening Folio">
    <span className="brand"><Brand />folio.</span>
    {slowConnection && !loadError && <p role="status">Connecting to Folio. The server may take a moment to wake up…</p>}
    {loadError && <section className="empty"><h2>Your portfolio is out of reach</h2>
      <p role="alert">{loadError}</p>
      <button className="button primary" onClick={() => setAccountVersion(value => value + 1)}>Try loading again</button>
    </section>}
  </main>;
}

