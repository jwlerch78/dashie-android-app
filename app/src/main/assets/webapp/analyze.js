const esbuild = require('esbuild');

esbuild.build({
  entryPoints: ['js/kiosk-settings.js'],
  bundle: true,
  minify: true,
  sourcemap: false,
  format: 'esm',
  metafile: true,
  outfile: '/tmp/analyze-out.js',
  external: ['node:fs', 'node:path'],
  target: ['chrome100'],
  alias: {
    '@dashie/ui': '../js/ui',
    '@dashie/utils': '../js/utils',
    '@dashie/config': '../config.js',
  },
  plugins: [require('./esbuild-kiosk-shims').kioskShimPlugin]
}).then(result => {
  const inputs = Object.entries(result.metafile.inputs)
    .map(([file, info]) => ({ file, bytes: info.bytes }))
    .sort((a, b) => b.bytes - a.bytes);
  
  // Sum by category
  const byCategory = {};
  inputs.forEach(item => {
    let category = 'other';
    if (item.file.includes('/pages/')) category = 'settings-pages';
    else if (item.file.includes('/handlers/')) category = 'handlers';
    else if (item.file.includes('/data/services/')) category = 'data-services';
    else if (item.file.includes('/node_modules/')) category = 'node_modules';
    else if (item.file.includes('/utils/')) category = 'utils';
    else if (item.file.includes('/ui/')) category = 'ui';
    else if (item.file.includes('/modules/Settings')) category = 'settings-core';
    
    if (!byCategory[category]) byCategory[category] = { count: 0, bytes: 0 };
    byCategory[category].count++;
    byCategory[category].bytes += item.bytes;
  });
  
  console.log('Bundle breakdown by category:');
  Object.entries(byCategory).sort((a, b) => b[1].bytes - a[1].bytes).forEach(([cat, data]) => {
    const mb = (data.bytes / 1024 / 1024).toFixed(2);
    console.log(`${cat}: ${mb} MB (${data.count} files)`);
  });
  
  console.log('\n\nTop 50 input files by size:');
  inputs.slice(0, 50).forEach((item, i) => {
    const sizeKB = (item.bytes / 1024).toFixed(1);
    console.log(`${sizeKB} KB - ${item.file}`);
  });
}).catch(err => {
  console.error('Error:', err.message);
  process.exit(1);
});
