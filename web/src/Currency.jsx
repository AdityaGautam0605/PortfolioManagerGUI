import { createContext, useContext, useEffect, useState } from 'react';
import { readStored, request, store } from './api.js';

const codes = ['USD','INR','EUR','GBP','CAD','JPY'];
const Context = createContext(null);
export const useCurrency = () => useContext(Context);
export function CurrencyProvider({ children }) {
  const [choice,setChoice] = useState(() => {
    const saved = readStored('folio-currency','USD');
    return codes.includes(saved) ? saved : 'USD';
  });
  const [fx,setFx] = useState(null);
  const [error,setError] = useState('');
  const [retry,setRetry] = useState(0);
  useEffect(() => {
    if (choice === 'USD') return;
    let active = true;
    const controller = new AbortController();
    async function load() {
      try {
        const data = await request('/exchange-rates',{ signal: controller.signal });
        if (!codes.every(code => Number.isFinite(data.rates?.[code]) && data.rates[code]>0)) throw new Error('Currency rates unavailable.');
        if (active) { setFx(data); setError(''); }
      } catch { if (active) setError('Rate update unavailable.'); }
    }
    load();
    const timer = setInterval(load,3600000);
    return () => { active=false; controller.abort(); clearInterval(timer); };
  },[choice,retry]);
  const currency = fx ? choice : 'USD';
  const rate = fx?.rates[currency] || 1;
  const formatter = new Intl.NumberFormat(currency==='INR' ? 'en-IN' : 'en-US',{ style:'currency', currency, currencyDisplay:'code' });
  const money = value => currency==='USD' ? new Intl.NumberFormat('en-US',{style:'currency',currency}).format(value) : formatter.format(value*rate);
  function select(value) { setError(''); setChoice(value); store('folio-currency',value); }
  return <Context.Provider value={{ currency,choice,select,money,rate,fx,error,reload:()=>setRetry(v=>v+1),
    signedMoney:value=>(value>=0 ? '+' : '−')+money(Math.abs(value)) }}>{children}</Context.Provider>;
}
export function CurrencyPicker() {
  const { choice,currency,select,fx,error,reload } = useCurrency();
  return <div className="currency-picker"><select aria-label="Display currency" value={choice} onChange={e=>select(e.target.value)}>
    {codes.map(code=><option key={code}>{code}</option>)}
  </select>
    {choice!=='USD' && <small>{currency==='USD' ? 'Showing USD' : 'FX · '+fx.date}
      {error ? <> · {error} <button className="text-button" onClick={reload}>Retry</button></> : currency==='USD' ? ' · Loading rate…' : ''}</small>}
  </div>;
}
