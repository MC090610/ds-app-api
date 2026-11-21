import { createHash } from 'node:crypto'
import { cp, mkdir, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { zipSync } from 'fflate'

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)))
const { version } = JSON.parse(await readFile(path.join(root, 'package.json'), 'utf8'))
const distDirectory = path.join(root, 'dist')
const releaseDirectory = path.join(root, 'release')
const packageName = `DS-API-Web-${version}`
const packageDirectory = path.join(releaseDirectory, packageName)
const archivePath = path.join(releaseDirectory, `${packageName}.zip`)
const checksumPath = `${archivePath}.sha256`

const assertInsideRelease = (target) => {
  const resolvedTarget = path.resolve(target)
  const resolvedRelease = path.resolve(releaseDirectory)
  if (!resolvedTarget.startsWith(`${resolvedRelease}${path.sep}`)) {
    throw new Error(`Refusing to modify path outside release directory: ${resolvedTarget}`)
  }
}

const collectFiles = async (directory, prefix = '') => {
  const entries = {}
  const items = await readdir(directory, { withFileTypes: true })
  for (const item of items) {
    const absolutePath = path.join(directory, item.name)
    const archivePath = path.posix.join(prefix, item.name)
    if (item.isDirectory()) {
      Object.assign(entries, await collectFiles(absolutePath, archivePath))
    } else if (item.isFile()) {
      entries[archivePath] = new Uint8Array(await readFile(absolutePath))
    }
  }
  return entries
}

const indexFile = path.join(distDirectory, 'index.html')
try {
  const info = await stat(indexFile)
  if (!info.isFile()) throw new Error()
} catch {
  throw new Error('dist/index.html is missing. Run `pnpm build` first.')
}

await mkdir(releaseDirectory, { recursive: true })
assertInsideRelease(packageDirectory)
assertInsideRelease(archivePath)
await rm(packageDirectory, { recursive: true, force: true })
await rm(archivePath, { force: true })
await rm(checksumPath, { force: true })
await cp(distDirectory, packageDirectory, { recursive: true })

const files = await collectFiles(packageDirectory)
const archive = zipSync(
  Object.fromEntries(
    Object.entries(files).map(([filePath, bytes]) => [
      path.posix.join(packageName, filePath),
      bytes,
    ])
  ),
  { level: 9 }
)
await writeFile(archivePath, archive)
const digest = createHash('sha256').update(archive).digest('hex').toUpperCase()
await writeFile(checksumPath, `${digest}  ${path.basename(archivePath)}\n`, 'utf8')

console.log(`HTML directory: ${packageDirectory}`)
console.log(`HTML archive:   ${archivePath}`)
console.log(`SHA-256:        ${digest}`)
