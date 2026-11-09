// shared/api.ts (types + endpoints shared with the website) lives outside this folder.
const path = require('path')
const { getDefaultConfig } = require('expo/metro-config')

const config = getDefaultConfig(__dirname)
config.watchFolders = [path.resolve(__dirname, '../shared')]
// keep resolution inside mobile/: C:\Users\Shiva has a stray package.json + node_modules
config.resolver.nodeModulesPaths = [path.resolve(__dirname, 'node_modules')]
config.resolver.disableHierarchicalLookup = true
module.exports = config
