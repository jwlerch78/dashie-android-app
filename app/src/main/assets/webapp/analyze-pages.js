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
  
  console.log('Settings Pages in bundle:');
  inputs.filter(i => i.file.includes('/pages/')).forEach((item, i) => {
    const sizeKB = (item.bytes / 1024).toFixed(1);
    console.log(`${sizeKB} KB - ${item.file.split('pages/')[1]}`);
  });
  
  console.log('\n\nData Services in bundle:');
  inputs.filter(i => i.file.includes('/data/services/')).forEach((item, i) => {
    const sizeKB = (item.bytes / 1024).toFixed(1);
    console.log(`${sizeKB} KB - ${item.file.split('services/')[1]}`);
  });
}).catch(err => {
  console.error('Error:', err.message);
  process.exit(1);
});
