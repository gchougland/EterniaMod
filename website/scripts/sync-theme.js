import fs from 'node:fs/promises';const tokens=JSON.parse(await fs.readFile(new URL('../../docs/planning/theme-tokens.json',import.meta.url),'utf8'));await fs.writeFile(new URL('../web/theme.css',import.meta.url),':root{'+Object.entries(tokens.colors).map(([k,v])=>'--et-'+k+':'+v).join(';')+'}\n');console.log('Citadel theme synchronized');

