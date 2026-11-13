const companies = [
  ['AAPL', 'Apple', 'Technology'], ['MSFT', 'Microsoft', 'Technology'],
  ['NVDA', 'NVIDIA', 'Technology'], ['AMD', 'Advanced Micro Devices', 'Technology'],
  ['AVGO', 'Broadcom', 'Technology'], ['ORCL', 'Oracle', 'Technology'],
  ['GOOGL', 'Alphabet', 'Communication services'], ['META', 'Meta Platforms', 'Communication services'],
  ['NFLX', 'Netflix', 'Communication services'], ['DIS', 'Walt Disney', 'Communication services'],
  ['AMZN', 'Amazon', 'Consumer discretionary'], ['TSLA', 'Tesla', 'Consumer discretionary'],
  ['HD', 'Home Depot', 'Consumer discretionary'], ['NKE', 'Nike', 'Consumer discretionary'],
  ['JPM', 'JPMorgan Chase', 'Financials'], ['V', 'Visa', 'Financials'],
  ['MA', 'Mastercard', 'Financials'], ['BAC', 'Bank of America', 'Financials'],
  ['LLY', 'Eli Lilly', 'Health care'], ['JNJ', 'Johnson & Johnson', 'Health care'],
  ['UNH', 'UnitedHealth Group', 'Health care'], ['PFE', 'Pfizer', 'Health care'],
  ['KO', 'Coca-Cola', 'Consumer staples'], ['PG', 'Procter & Gamble', 'Consumer staples'],
  ['COST', 'Costco Wholesale', 'Consumer staples'], ['WMT', 'Walmart', 'Consumer staples'],
  ['XOM', 'Exxon Mobil', 'Energy'], ['CVX', 'Chevron', 'Energy'],
  ['CAT', 'Caterpillar', 'Industrials'], ['UPS', 'United Parcel Service', 'Industrials'],
  ['NEE', 'NextEra Energy', 'Utilities'], ['PLD', 'Prologis', 'Real estate'],
  ['TSX:SHOP', 'Shopify', 'Technology'], ['TSX:RY', 'Royal Bank of Canada', 'Financials'],
  ['TSX:CNQ', 'Canadian Natural Resources', 'Energy'], ['BSE:RELIANCE', 'Reliance Industries', 'Energy'],
  ['BSE:TCS', 'Tata Consultancy Services', 'Technology'], ['BSE:HDFCBANK', 'HDFC Bank', 'Financials'],
];

export function marketOf(symbol) {
  return symbol.startsWith('TSX:') ? 'Toronto' : symbol.startsWith('BSE:') ? 'Bombay' : 'US';
}
export function quoteCurrency(symbol = '') {
  return symbol.startsWith('TSX:') ? 'CAD' : symbol.startsWith('BSE:') ? 'INR' : 'USD';
}

export function stockLibrary(saved, watched, demo) {
  const library = new Map();
  if (!demo) {
    for (const [symbol, name, sector] of companies) library.set(symbol, { symbol, name, sector });
    for (const symbol of watched) {
      if (!library.has(symbol)) library.set(symbol, { symbol, name: symbol });
    }
  }
  for (const stock of saved) {
    const listed = library.get(stock.symbol);
    library.set(stock.symbol, { ...listed, ...stock, name: stock.name === stock.symbol && listed ? listed.name : stock.name });
  }
  return [...library.values()].sort((a,b) => a.symbol.localeCompare(b.symbol));
}
