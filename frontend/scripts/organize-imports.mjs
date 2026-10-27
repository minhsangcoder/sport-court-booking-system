import fs from 'node:fs'
import path from 'node:path'
import ts from 'typescript'

const root = process.cwd()
const configPath = ts.findConfigFile(root, ts.sys.fileExists, 'tsconfig.json')
const config = ts.readConfigFile(configPath, ts.sys.readFile)
const parsed = ts.parseJsonConfigFileContent(config.config, ts.sys, path.dirname(configPath))
const host = {
  getScriptFileNames: () => parsed.fileNames,
  getScriptVersion: () => '0',
  getScriptSnapshot: (file) =>
    fs.existsSync(file) ? ts.ScriptSnapshot.fromString(fs.readFileSync(file, 'utf8')) : undefined,
  getCurrentDirectory: () => path.dirname(configPath),
  getCompilationSettings: () => parsed.options,
  getDefaultLibFileName: (options) => ts.getDefaultLibFilePath(options),
  fileExists: ts.sys.fileExists,
  readFile: ts.sys.readFile,
  readDirectory: ts.sys.readDirectory,
}
const service = ts.createLanguageService(host)
for (const argument of process.argv.slice(2)) {
  const file = path.resolve(root, argument)
  if (
    !file.startsWith(root + path.sep) ||
    file.includes(path.sep + 'api' + path.sep + 'generated' + path.sep)
  )
    throw new Error('Outside refactor scope')
  let source = fs.readFileSync(file, 'utf8')
  const edits = service.organizeImports(
    { type: 'file', fileName: file },
    {},
    { organizeImportsIgnoreCase: false },
  )
  for (const change of edits)
    for (const edit of [...change.textChanges].sort((a, b) => b.span.start - a.span.start)) {
      source =
        source.slice(0, edit.span.start) +
        edit.newText +
        source.slice(edit.span.start + edit.span.length)
    }
  fs.writeFileSync(file, source)
}
service.dispose()
