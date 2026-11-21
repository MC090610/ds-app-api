import { spawnSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)))
const run = (command, args, cwd, options = {}) => {
  const result = spawnSync(command, args, {
    cwd,
    stdio: 'inherit',
    ...options,
  })
  if (result.status !== 0) {
    process.exit(result.status ?? 1)
  }
}

run(process.execPath, [path.join(root, 'scripts', 'build.mjs')], root)

const wrapper = path.join(
  root,
  'android',
  process.platform === 'win32' ? 'gradlew.bat' : 'gradlew'
)
if (process.platform === 'win32') {
  run(
    process.env.ComSpec ?? 'cmd.exe',
    ['/d', '/s', '/c', `${wrapper} assembleDebug`],
    path.join(root, 'android')
  )
} else {
  run(wrapper, ['assembleDebug'], path.join(root, 'android'))
}
